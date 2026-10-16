[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$OutputRoot,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$SecondCopyRoot,

    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$SettingsBackupPath,

    [Parameter(Mandatory = $true)]
    [ValidateScript({ -not [string]::IsNullOrWhiteSpace($_) })]
    [string]$RootAuthorizationNote,

    [Parameter(Mandatory = $true)]
    [ValidateScript({ -not [string]::IsNullOrWhiteSpace($_) })]
    [string]$LsposedScopeNote,

    [Parameter(Mandatory = $true)]
    [ValidateScript({ -not [string]::IsNullOrWhiteSpace($_) })]
    [string]$OemBatteryAutostartNote,

    [string]$CandidateApkPath,
    [string]$ApkSignerExecutable,
    [string]$Aapt2Executable,
    [string]$JavaHome,
    [string[]]$ManualEvidencePaths = @(),

    [ValidateRange(1, 168)]
    [int]$MaxSettingsBackupAgeHours = 24,

    [switch]$AllowSameVolumeDuplicate,
    [switch]$AllowCandidateSignerChangeForCleanInstall,
    [string]$AdbExecutable = "adb"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$packageName = "dev.voxvargr.aaarp"
$relatedPackages = @(
    $packageName,
    "dev.voxvargr.aaarp.volumeup",
    "dev.voxvargr.aaarp.volumedown",
    "dev.voxvargr.aaarp.aatrusthook"
)
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$snapshotStatuses = New-Object 'System.Collections.Generic.List[object]'
$archiveStatuses = New-Object 'System.Collections.Generic.List[object]'
$forceStopAttempted = $false
$forceStopped = $false
$monitorSnapshot = $null
$backupDirectory = $null
$secondBackupDirectory = $null
$stateDirectory = $null
$apkDirectory = $null
$archiveDirectory = $null
$portableDirectory = $null
$evidenceDirectory = $null

function Get-FullPath([string]$Path) {
    $full = [System.IO.Path]::GetFullPath($Path)
    $root = [System.IO.Path]::GetPathRoot($full)
    if ($full.Equals($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $root
    }
    return $full.TrimEnd([char[]]@('\', '/'))
}

function Test-PathWithin([string]$Candidate, [string]$Parent) {
    $candidateFull = Get-FullPath $Candidate
    $parentFull = Get-FullPath $Parent
    $parentPrefix = if ($parentFull.EndsWith('\') -or $parentFull.EndsWith('/')) {
        $parentFull
    } else {
        $parentFull + [System.IO.Path]::DirectorySeparatorChar
    }
    return $candidateFull.Equals($parentFull, [System.StringComparison]::OrdinalIgnoreCase) -or
        $candidateFull.StartsWith(
            $parentPrefix,
            [System.StringComparison]::OrdinalIgnoreCase
        )
}

function Assert-OutsideRepository([string]$Path, [string]$Label) {
    if (Test-PathWithin -Candidate $Path -Parent $repositoryRoot) {
        throw "$Label must be outside the Git repository: $(Get-FullPath $repositoryRoot)"
    }
}

function Resolve-Executable([string]$Value, [string]$Label) {
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "$Label was not specified."
    }
    if (Test-Path -LiteralPath $Value -PathType Leaf) {
        return (Get-Item -LiteralPath $Value).FullName
    }
    $command = Get-Command -Name $Value -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $command) {
        throw "$Label was not found: $Value"
    }
    if (-not [string]::IsNullOrWhiteSpace($command.Source)) {
        return $command.Source
    }
    return $command.Path
}

function Resolve-ApkSigner([string]$RequestedPath) {
    if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
        return Resolve-Executable -Value $RequestedPath -Label "apksigner"
    }

    $onPath = Get-Command -Name "apksigner" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $onPath) {
        if (-not [string]::IsNullOrWhiteSpace($onPath.Source)) {
            return $onPath.Source
        }
        return $onPath.Path
    }

    $sdkRoots = @()
    foreach ($candidateRoot in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($candidateRoot)) {
            $sdkRoots += $candidateRoot
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $sdkRoots += (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    }

    foreach ($sdkRoot in @($sdkRoots | Select-Object -Unique)) {
        $buildToolsRoot = Join-Path $sdkRoot "build-tools"
        if (-not (Test-Path -LiteralPath $buildToolsRoot -PathType Container)) {
            continue
        }
        foreach ($directory in Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
                Sort-Object Name -Descending) {
            foreach ($fileName in @("apksigner.bat", "apksigner")) {
                $candidate = Join-Path $directory.FullName $fileName
                if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                    return $candidate
                }
            }
        }
    }
    throw "apksigner is required to verify the installed APK certificate. Pass -ApkSignerExecutable or install Android SDK build-tools."
}

function Initialize-JavaForApkSigner([string]$RequestedJavaHome) {
    if (-not [string]::IsNullOrWhiteSpace($RequestedJavaHome)) {
        $requestedJava = Join-Path $RequestedJavaHome "bin\java.exe"
        if (-not (Test-Path -LiteralPath $requestedJava -PathType Leaf)) {
            throw "JavaHome does not contain bin\java.exe: $RequestedJavaHome"
        }
        $env:JAVA_HOME = (Get-Item -LiteralPath $RequestedJavaHome).FullName
        return
    }
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME) -and
        (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe") -PathType Leaf)) {
        return
    }
    if ($null -ne (Get-Command -Name "java" -ErrorAction SilentlyContinue | Select-Object -First 1)) {
        return
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ProgramFiles)) {
        $androidStudioJbr = Join-Path $env:ProgramFiles "Android\Android Studio\jbr"
        if (Test-Path -LiteralPath (Join-Path $androidStudioJbr "bin\java.exe") -PathType Leaf) {
            $env:JAVA_HOME = $androidStudioJbr
            return
        }
    }
    throw "Java is required by apksigner. Pass -JavaHome, set JAVA_HOME, or put java on PATH."
}

function Resolve-Aapt2([string]$RequestedPath, [string]$ResolvedApkSigner) {
    if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
        return Resolve-Executable -Value $RequestedPath -Label "aapt2"
    }
    $apksignerDirectory = Split-Path -Parent $ResolvedApkSigner
    foreach ($fileName in @("aapt2.exe", "aapt2")) {
        $sibling = Join-Path $apksignerDirectory $fileName
        if (Test-Path -LiteralPath $sibling -PathType Leaf) {
            return $sibling
        }
    }
    return Resolve-Executable -Value "aapt2" -Label "aapt2"
}

function Invoke-AdbText {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$AllowFailure
    )

    $output = & $script:AdbPath @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | ForEach-Object { $_.ToString() }) -join "`n"
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw "ADB failed ($exitCode): $text"
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Text = $text
    }
}

function Invoke-DeviceShell([string]$Command, [switch]$AllowFailure) {
    return Invoke-AdbText -Arguments @("-s", $Serial, "shell", $Command) -AllowFailure:$AllowFailure
}

function Invoke-RootShell([string]$Command, [switch]$AllowFailure) {
    # `adb shell` joins its remaining arguments into one remote shell command. Passing a complex
    # command directly after `su -c` lets that outer shell expand variables and operators before
    # root sees them. Carry the exact UTF-8 command as base64 and quote the decoder pipeline as
    # the single `su -c` operand; the inner root shell then owns every expansion and exit status.
    $encodedCommand = [Convert]::ToBase64String(
        [System.Text.Encoding]::UTF8.GetBytes($Command)
    )
    $rootCommand = "'echo $encodedCommand | /system/bin/base64 -d | /system/bin/sh'"
    return Invoke-AdbText -Arguments @(
        "-s", $Serial, "shell", "su", "-c", $rootCommand
    ) -AllowFailure:$AllowFailure
}

function Quote-AndroidShell([string]$Value) {
    if ($Value.Contains("'")) {
        throw "Android shell value contains an unsupported apostrophe."
    }
    return "'" + $Value + "'"
}

function Write-Utf8File([string]$Path, [string]$Content) {
    [System.IO.File]::WriteAllText(
        $Path,
        $Content + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Test-JsonObject([object]$Value) {
    return $Value -is [System.Management.Automation.PSCustomObject]
}

function Get-RequiredJsonProperty([object]$Object, [string]$Name, [string]$Context) {
    if (-not (Test-JsonObject $Object)) {
        throw "$Context must be a JSON object."
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) {
        throw "$Context does not contain a non-null $Name field."
    }
    if ($property.Value -is [System.Array]) {
        Write-Output -NoEnumerate $property.Value
        return
    }
    return $property.Value
}

function Get-OptionalJsonProperty([object]$Object, [string]$Name) {
    if (-not (Test-JsonObject $Object)) {
        return $null
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    if ($property.Value -is [System.Array]) {
        Write-Output -NoEnumerate $property.Value
        return
    }
    return $property.Value
}

function Test-JsonNumber([object]$Value) {
    return $Value -is [byte] -or $Value -is [sbyte] -or
        $Value -is [int16] -or $Value -is [uint16] -or
        $Value -is [int32] -or $Value -is [uint32] -or
        $Value -is [int64] -or $Value -is [uint64] -or
        $Value -is [single] -or $Value -is [double] -or $Value -is [decimal]
}

function Convert-ToExactInt64([object]$Value, [string]$Context) {
    if (-not (Test-JsonNumber $Value)) {
        throw "$Context must be a JSON number."
    }
    try {
        $number = [decimal]$Value
    } catch {
        throw "$Context is outside the supported integer range."
    }
    if ([decimal]::Truncate($number) -ne $number -or
        $number -lt [decimal][int64]::MinValue -or
        $number -gt [decimal][int64]::MaxValue) {
        throw "$Context must be an exact 64-bit whole number."
    }
    return [int64]$number
}

function Assert-PortablePreferenceValue([string]$Key, [object]$Entry) {
    if (-not (Test-JsonObject $Entry)) {
        throw "Preference '$Key' must be a JSON object."
    }
    $type = Get-RequiredJsonProperty -Object $Entry -Name "type" -Context "Preference '$Key'"
    $value = Get-RequiredJsonProperty -Object $Entry -Name "value" -Context "Preference '$Key'"
    if ($type -isnot [string]) {
        throw "Preference '$Key' has a non-string type."
    }

    switch ($type) {
        "string" {
            if ($value -isnot [string]) { throw "Preference '$Key' does not contain a string." }
        }
        "boolean" {
            if ($value -isnot [bool]) { throw "Preference '$Key' does not contain a Boolean." }
        }
        "stringSet" {
            if ($value -isnot [System.Array]) {
                throw "Preference '$Key' does not contain a JSON array."
            }
            foreach ($item in $value) {
                if ($item -isnot [string]) {
                    throw "Preference '$Key' contains a non-string string-set item."
                }
            }
        }
        "integer" {
            $integer = Convert-ToExactInt64 -Value $value -Context "Preference '$Key'"
            if ($integer -lt [int32]::MinValue -or $integer -gt [int32]::MaxValue) {
                throw "Preference '$Key' is outside the 32-bit integer range."
            }
        }
        "long" {
            Convert-ToExactInt64 -Value $value -Context "Preference '$Key'" | Out-Null
        }
        "float" {
            if (-not (Test-JsonNumber $value)) {
                throw "Preference '$Key' does not contain a number."
            }
            $doubleValue = [double]$value
            $floatValue = [single]$doubleValue
            if ([double]::IsNaN($doubleValue) -or [double]::IsInfinity($doubleValue) -or
                [single]::IsNaN($floatValue) -or [single]::IsInfinity($floatValue)) {
                throw "Preference '$Key' is not a finite 32-bit float."
            }
        }
        default {
            throw "Preference '$Key' uses unsupported type '$type'."
        }
    }
}

function Assert-OptionalBooleanField([object]$Object, [string]$Name, [string]$Context) {
    $property = $Object.PSObject.Properties[$Name]
    if ($null -ne $property -and $property.Value -isnot [bool]) {
        throw "$Context field '$Name' must be Boolean."
    }
}

function Test-PortableSettingsBackup([string]$Path) {
    try {
        $document = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "SettingsBackupPath is not valid JSON: $($_.Exception.Message)"
    }
    if (-not (Test-JsonObject $document)) {
        throw "SettingsBackupPath must contain one JSON object."
    }

    $format = Get-RequiredJsonProperty -Object $document -Name "format" -Context "Settings backup"
    if ($format -isnot [string] -or $format -ne "dev.voxvargr.aaarp.settings") {
        throw "SettingsBackupPath is not an AAARP settings export."
    }
    $versionValue = Get-RequiredJsonProperty -Object $document -Name "version" -Context "Settings backup"
    $version = Convert-ToExactInt64 -Value $versionValue -Context "Settings backup version"
    if ($version -notin @(1, 2)) {
        throw "AAARP settings export version $version is unsupported."
    }

    $preferences = Get-RequiredJsonProperty -Object $document -Name "preferences" -Context "Settings backup"
    if (-not (Test-JsonObject $preferences)) {
        throw "Settings backup preferences must be a JSON object."
    }
    $preferenceCount = 0
    foreach ($property in $preferences.PSObject.Properties) {
        Assert-PortablePreferenceValue -Key $property.Name -Entry $property.Value
        $preferenceCount++
    }

    $createdAtProperty = $document.PSObject.Properties["createdAtMillis"]
    $createdAtSource = "file-last-write-time"
    $createdAtUtc = (Get-Item -LiteralPath $Path).LastWriteTimeUtc
    if ($null -ne $createdAtProperty) {
        if ($null -eq $createdAtProperty.Value) {
            throw "Settings backup creation time is null."
        }
        $createdAtMillis = Convert-ToExactInt64 -Value $createdAtProperty.Value -Context "Settings backup creation time"
        if ($createdAtMillis -lt 0) {
            throw "Settings backup creation time is negative."
        }
        try {
            $createdAtUtc = [DateTimeOffset]::FromUnixTimeMilliseconds($createdAtMillis).UtcDateTime
        } catch {
            throw "Settings backup creation time is outside the supported range."
        }
        $createdAtSource = "createdAtMillis"
    } elseif ($version -eq 2) {
        throw "A version 2 settings backup must contain createdAtMillis. Export a fresh copy from AAARP."
    }

    $accessProperty = $document.PSObject.Properties["access"]
    if ($null -ne $accessProperty) {
        $access = $accessProperty.Value
        if (-not (Test-JsonObject $access)) {
            throw "Settings backup access must be a JSON object."
        }
        Assert-OptionalBooleanField -Object $access -Name "rootDiagnosticsEnabled" -Context "Access snapshot"
        Assert-OptionalBooleanField -Object $access -Name "batteryOptimizationExempt" -Context "Access snapshot"
        $permissionsProperty = $access.PSObject.Properties["runtimePermissions"]
        if ($null -ne $permissionsProperty) {
            if (-not (Test-JsonObject $permissionsProperty.Value)) {
                throw "Access snapshot runtimePermissions must be a JSON object."
            }
            foreach ($permission in $permissionsProperty.Value.PSObject.Properties) {
                if ($permission.Value -isnot [bool]) {
                    throw "Runtime permission '$($permission.Name)' must contain a Boolean."
                }
            }
        }
    } elseif ($version -eq 2) {
        throw "A version 2 settings backup must contain its access snapshot. Export a fresh copy from AAARP."
    }

    $ageHours = ((Get-Date).ToUniversalTime() - $createdAtUtc).TotalHours
    if ($ageHours -lt -0.25) {
        throw "Settings backup creation time is more than 15 minutes in the future. Check the phone and PC clocks."
    }
    if ($ageHours -gt $MaxSettingsBackupAgeHours) {
        throw ("Settings backup is {0:N1} hours old; the allowed maximum is {1} hours. Export it again immediately before migration." -f $ageHours, $MaxSettingsBackupAgeHours)
    }

    return [pscustomobject]@{
        Document = $document
        Version = [int]$version
        PreferenceCount = $preferenceCount
        CreatedAtUtc = $createdAtUtc.ToString("o")
        CreatedAtSource = $createdAtSource
        AgeHoursAtValidation = [math]::Round($ageHours, 3)
    }
}

function Write-Snapshot {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Command,
        [switch]$Root,
        [switch]$Required,
        [switch]$AllowEmpty
    )

    $result = if ($Root) {
        Invoke-RootShell -Command $Command -AllowFailure
    } else {
        Invoke-DeviceShell -Command $Command -AllowFailure
    }
    $hasContent = -not [string]::IsNullOrWhiteSpace($result.Text)
    $success = $result.ExitCode -eq 0 -and ($AllowEmpty -or $hasContent)
    $header = "required=$([bool]$Required)`nsuccess=$success`nexitCode=$($result.ExitCode)`ncommand=$Command`n"
    Write-Utf8File -Path (Join-Path $script:stateDirectory $Name) -Content ($header + $result.Text)
    $status = [pscustomobject]@{
        name = $Name
        required = [bool]$Required
        root = [bool]$Root
        success = $success
        exitCode = $result.ExitCode
        hasContent = $hasContent
    }
    $script:snapshotStatuses.Add($status)
    if ($Required -and -not $success) {
        throw "Required device-state snapshot '$Name' failed or was empty."
    }
    return $status
}

function Get-BooleanPreferenceSnapshot([string]$PreferencesPath, [string]$PreferenceName) {
    $readResult = Invoke-RootShell -Command ("cat " + (Quote-AndroidShell $PreferencesPath)) -AllowFailure
    if ($readResult.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($readResult.Text)) {
        throw "Could not read required preferences file $PreferencesPath."
    }
    try {
        [xml]$xml = $readResult.Text
    } catch {
        throw "Required preferences file $PreferencesPath is not valid XML."
    }
    $nodes = @($xml.SelectNodes("/map/*[@name='$PreferenceName']"))
    if ($nodes.Count -gt 1) {
        throw "Preference '$PreferenceName' appears more than once in $PreferencesPath."
    }
    if ($nodes.Count -eq 0) {
        return [pscustomobject]@{
            name = $PreferenceName
            present = $false
            value = $false
            source = "SharedPreferences default (key absent)"
            path = $PreferencesPath
        }
    }
    $node = $nodes[0]
    # PowerShell's XML adapter exposes the element's `name` attribute through `.Name`, hiding
    # XmlNode.Name. Use LocalName/GetAttribute so a normal Android entry such as
    # <boolean name="monitor_enabled" value="true" /> is interpreted unambiguously.
    $booleanValue = $node.GetAttribute("value")
    if ($node.LocalName -ne "boolean" -or $node.HasAttribute("value") -eq $false -or
        $booleanValue -notin @("true", "false")) {
        throw "Preference '$PreferenceName' does not contain a strict SharedPreferences Boolean."
    }
    return [pscustomobject]@{
        name = $PreferenceName
        present = $true
        value = $booleanValue -eq "true"
        source = "SharedPreferences XML"
        path = $PreferencesPath
    }
}

function Get-ApkSignerInfo([string]$ApkPath, [string]$EvidencePath) {
    $output = & $script:ApkSignerPath verify --verbose --print-certs $ApkPath 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | ForEach-Object { $_.ToString() }) -join "`n"
    Write-Utf8File -Path $EvidencePath -Content ("exitCode=$exitCode`napk=$ApkPath`n" + $text)
    if ($exitCode -ne 0) {
        throw "apksigner rejected $ApkPath. See $EvidencePath."
    }
    $certificates = @()
    $certificatePattern = '(?im)^(?!Source Stamp)(?:Signer #\d+|V[0-9.]+ Signer):?\s+certificate SHA-256 digest:\s*([0-9a-f:]{32,})'
    foreach ($match in [regex]::Matches($text, $certificatePattern)) {
        $digest = $match.Groups[1].Value.Replace(":", "").ToLowerInvariant()
        if ($digest.Length -eq 64 -and $digest -notin $certificates) {
            $certificates += $digest
        }
    }
    if ($certificates.Count -eq 0) {
        throw "apksigner did not report a signing-certificate SHA-256 for $ApkPath."
    }
    return [pscustomobject]@{
        Certificates = @($certificates | Sort-Object)
        Sha256 = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

function Get-ApkIdentity([string]$ApkPath, [string]$EvidencePath) {
    $output = & $script:Aapt2Path dump badging $ApkPath 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | ForEach-Object { $_.ToString() }) -join "`n"
    Write-Utf8File -Path $EvidencePath -Content ("exitCode=$exitCode`napk=$ApkPath`n" + $text)
    if ($exitCode -ne 0 -or
        $text -notmatch "(?m)^package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'") {
        throw "aapt2 could not verify the package identity of $ApkPath."
    }
    return [pscustomobject]@{
        PackageName = $Matches[1]
        VersionCode = $Matches[2]
        VersionName = $Matches[3]
    }
}

function Save-PackageArtifacts {
    param(
        [Parameter(Mandatory = $true)][string]$Package,
        [Parameter(Mandatory = $true)][int]$AndroidUser,
        [switch]$Required
    )

    $safePackage = $Package.Replace('.', '_')
    $packageDirectory = Join-Path $script:apkDirectory $safePackage
    New-Item -ItemType Directory -Path $packageDirectory | Out-Null
    $pathsCommand = "pm path --user $AndroidUser " + (Quote-AndroidShell $Package)
    $pathsResult = Invoke-DeviceShell -Command $pathsCommand -AllowFailure
    Write-Utf8File -Path (Join-Path $packageDirectory "package-paths.txt") -Content ("exitCode=$($pathsResult.ExitCode)`ncommand=$pathsCommand`n" + $pathsResult.Text)

    $remotePaths = @()
    if ($pathsResult.ExitCode -eq 0) {
        foreach ($line in ($pathsResult.Text -split "`r?`n")) {
            if ($line.StartsWith("package:")) {
                $remotePath = $line.Substring("package:".Length).Trim()
                if (-not [string]::IsNullOrWhiteSpace($remotePath)) {
                    $remotePaths += $remotePath
                }
            }
        }
    }
    if ($remotePaths.Count -eq 0) {
        if ($Required) {
            throw "Required package $Package has no APK path for Android user $AndroidUser."
        }
        return [pscustomobject]@{
            packageName = $Package
            required = [bool]$Required
            installedForCurrentUser = $false
            artifacts = @()
        }
    }

    $artifacts = @()
    $index = 0
    foreach ($remotePath in $remotePaths) {
        $index++
        $lastSlash = $remotePath.LastIndexOf('/')
        $baseName = if ($lastSlash -ge 0) { $remotePath.Substring($lastSlash + 1) } else { "package.apk" }
        $localName = "{0:D2}-{1}" -f $index, $baseName
        $localPath = Join-Path $packageDirectory $localName
        $pull = Invoke-AdbText -Arguments @("-s", $Serial, "pull", $remotePath, $localPath)
        Write-Utf8File -Path ($localPath + ".pull.txt") -Content $pull.Text

        $deviceHash = Invoke-DeviceShell -Command ("sha256sum " + (Quote-AndroidShell $remotePath)) -AllowFailure
        Write-Utf8File -Path ($localPath + ".device-sha256.txt") -Content $deviceHash.Text
        if ($deviceHash.ExitCode -ne 0 -or $deviceHash.Text -notmatch '(?im)^([0-9a-f]{64})\s+') {
            throw "The device did not return a SHA-256 for $remotePath."
        }
        $deviceHashValue = $Matches[1].ToLowerInvariant()
        $hostHashValue = (Get-FileHash -LiteralPath $localPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hostHashValue -ne $deviceHashValue) {
            throw "Device-to-host SHA-256 mismatch for $remotePath."
        }

        $signerInfo = Get-ApkSignerInfo -ApkPath $localPath -EvidencePath ($localPath + ".apksigner.txt")
        $apkIdentity = Get-ApkIdentity -ApkPath $localPath -EvidencePath ($localPath + ".aapt2.txt")
        if ($apkIdentity.PackageName -ne $Package) {
            throw "Pulled APK for $Package identifies itself as $($apkIdentity.PackageName)."
        }
        $artifacts += [pscustomobject]@{
            remotePath = $remotePath
            localName = $localName
            bytes = (Get-Item -LiteralPath $localPath).Length
            sha256 = $hostHashValue
            packageName = $apkIdentity.PackageName
            versionCode = $apkIdentity.VersionCode
            versionName = $apkIdentity.VersionName
            signerCertificateSha256 = @($signerInfo.Certificates)
        }
    }

    $expectedCertificates = @($artifacts[0].signerCertificateSha256)
    foreach ($artifact in $artifacts) {
        if (@(Compare-Object -ReferenceObject $expectedCertificates -DifferenceObject @($artifact.signerCertificateSha256)).Count -ne 0) {
            throw "Installed APK splits for $Package do not report one consistent signer set."
        }
    }
    return [pscustomobject]@{
        packageName = $Package
        required = [bool]$Required
        installedForCurrentUser = $true
        artifacts = @($artifacts)
    }
}

function Assert-AppQuiescent([int]$AndroidUser, [string]$Stage) {
    $lastResult = $null
    $matches = @()
    for ($attempt = 1; $attempt -le 8; $attempt++) {
        $lastResult = Invoke-DeviceShell -Command "ps -A" -AllowFailure
        if ($lastResult.ExitCode -ne 0) {
            break
        }
        $matches = @($lastResult.Text -split "`r?`n" | Where-Object {
            $_ -match ("\s" + [regex]::Escape($packageName) + "(?::\S+)?\s*$")
        })
        if ($matches.Count -eq 0) {
            break
        }
        Start-Sleep -Milliseconds 500
    }
    $content = "stage=$Stage`nandroidUser=$AndroidUser`nexitCode=$($lastResult.ExitCode)`nmatchingProcesses=$($matches.Count)`n" + ($matches -join "`n")
    Write-Utf8File -Path (Join-Path $script:stateDirectory ("quiescence-$Stage.txt")) -Content $content
    if ($null -eq $lastResult -or $lastResult.ExitCode -ne 0 -or
        [string]::IsNullOrWhiteSpace($lastResult.Text) -or $matches.Count -ne 0) {
        throw "AAARP was not proven quiescent at stage '$Stage'."
    }
}

function Remove-StaleRemoteArchives([switch]$AllowFailure) {
    $command = 'status=0; for f in /data/local/tmp/aaarp-*.tar.gz; do if [ -f "$f" ]; then rm -f -- "$f" || status=1; fi; done; exit "$status"'
    return Invoke-RootShell -Command $command -AllowFailure:$AllowFailure
}

function Save-RemoteArchive {
    param(
        [Parameter(Mandatory = $true)][string]$SourcePath,
        [Parameter(Mandatory = $true)][string]$ArchiveName,
        [string[]]$RequiredEntries = @(),
        [switch]$Required
    )

    $exists = Invoke-RootShell -Command ("test -e " + (Quote-AndroidShell $SourcePath)) -AllowFailure
    if ($exists.ExitCode -ne 0) {
        $status = [pscustomobject]@{
            name = $ArchiveName
            sourcePath = $SourcePath
            required = [bool]$Required
            present = $false
            verified = $false
        }
        $script:archiveStatuses.Add($status)
        Write-Utf8File -Path (Join-Path $script:archiveDirectory ($ArchiveName + ".missing.txt")) -Content "Device path was not present: $SourcePath"
        if ($Required) {
            throw "Required device data path is missing: $SourcePath"
        }
        return $status
    }

    $lastSlash = $SourcePath.LastIndexOf('/')
    if ($lastSlash -lt 0 -or $lastSlash -eq ($SourcePath.Length - 1)) {
        throw "SourcePath must be an absolute Android path ending in a file or directory name."
    }
    $sourceParent = if ($lastSlash -eq 0) { "/" } else { $SourcePath.Substring(0, $lastSlash) }
    $sourceLeaf = $SourcePath.Substring($lastSlash + 1)
    $remoteArchive = "/data/local/tmp/aaarp-$script:timestamp-$ArchiveName.tar.gz"
    $createCommand = "umask 077 && tar -czf " + (Quote-AndroidShell $remoteArchive) +
        " -C " + (Quote-AndroidShell $sourceParent) + " " + (Quote-AndroidShell $sourceLeaf) +
        " && chown 2000:2000 " + (Quote-AndroidShell $remoteArchive) +
        " && chmod 0600 " + (Quote-AndroidShell $remoteArchive) +
        " && stat -c '%u:%g %a' " + (Quote-AndroidShell $remoteArchive) +
        " && sha256sum " + (Quote-AndroidShell $remoteArchive)

    try {
        $deviceHash = Invoke-RootShell -Command $createCommand
        Write-Utf8File -Path (Join-Path $script:archiveDirectory ($ArchiveName + ".device-sha256.txt")) -Content $deviceHash.Text
        if ($deviceHash.Text -notmatch '(?im)^([0-9a-f]{64})\s+') {
            throw "The device did not return a SHA-256 for $SourcePath."
        }
        $expectedHash = $Matches[1].ToLowerInvariant()
        if ($deviceHash.Text -notmatch '(?m)^2000:2000 600\s*$') {
            throw "The device staging archive was not owned by shell with mode 0600."
        }

        $localArchive = Join-Path $script:archiveDirectory ($ArchiveName + ".tar.gz")
        $pull = Invoke-AdbText -Arguments @("-s", $Serial, "pull", $remoteArchive, $localArchive)
        Write-Utf8File -Path (Join-Path $script:archiveDirectory ($ArchiveName + ".pull.txt")) -Content $pull.Text
        $hostHash = (Get-FileHash -LiteralPath $localArchive -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hostHash -ne $expectedHash) {
            throw "Device-to-host SHA-256 mismatch for $SourcePath."
        }

        $listingOutput = & $script:TarPath -tzf $localArchive 2>&1
        $listingExitCode = $LASTEXITCODE
        $listing = ($listingOutput | ForEach-Object { $_.ToString() }) -join "`n"
        if ($listingExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($listing)) {
            throw "Could not list $localArchive`: $listing"
        }
        Write-Utf8File -Path (Join-Path $script:archiveDirectory ($ArchiveName + ".listing.txt")) -Content $listing
        foreach ($requiredEntry in $RequiredEntries) {
            if ($listing -notmatch ("(?m)^" + [regex]::Escape($requiredEntry) + "(?:/)?$")) {
                throw "Archive $ArchiveName is missing required entry $requiredEntry."
            }
        }

        $verboseOutput = & $script:TarPath -tvzf $localArchive 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Could not produce an ownership listing for $localArchive."
        }
        Write-Utf8File -Path (Join-Path $script:archiveDirectory ($ArchiveName + ".ownership-listing.txt")) -Content (($verboseOutput | ForEach-Object { $_.ToString() }) -join "`n")

        $status = [pscustomobject]@{
            name = $ArchiveName
            sourcePath = $SourcePath
            required = [bool]$Required
            present = $true
            verified = $true
            bytes = (Get-Item -LiteralPath $localArchive).Length
            sha256 = $hostHash
        }
        $script:archiveStatuses.Add($status)
        return $status
    } finally {
        Invoke-RootShell -Command ("rm -f -- " + (Quote-AndroidShell $remoteArchive)) | Out-Null
    }
}

function Copy-ManualEvidence([string[]]$Paths) {
    $entries = @()
    $index = 0
    foreach ($path in $Paths) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Manual evidence file was not found: $path"
        }
        $index++
        $leaf = (Split-Path -Leaf $path) -replace '[^A-Za-z0-9._-]', '_'
        $destinationName = "{0:D2}-{1}" -f $index, $leaf
        $destination = Join-Path $script:evidenceDirectory $destinationName
        Copy-Item -LiteralPath $path -Destination $destination
        $entries += [pscustomobject]@{
            storedAs = $destinationName
            originalLeafName = Split-Path -Leaf $path
            bytes = (Get-Item -LiteralPath $destination).Length
            sha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    return @($entries)
}

function Get-ManifestEntries([string]$Root) {
    $entries = @()
    $prefix = $Root.TrimEnd([char[]]@('\', '/')) + [System.IO.Path]::DirectorySeparatorChar
    foreach ($file in Get-ChildItem -LiteralPath $Root -File -Recurse | Sort-Object FullName) {
        $relativePath = $file.FullName.Substring($prefix.Length).Replace('\', '/')
        if ($relativePath -in @("manifest.json", "BACKUP-COMPLETE.json", "BACKUP-FAILED.txt")) {
            continue
        }
        $entries += [pscustomobject]@{
            path = $relativePath
            bytes = $file.Length
            sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    return @($entries)
}

function Assert-ManifestFiles([string]$Root, [object[]]$Entries, [string]$Label) {
    foreach ($entry in $Entries) {
        $localPath = Join-Path $Root ($entry.path.Replace('/', [System.IO.Path]::DirectorySeparatorChar))
        if (-not (Test-Path -LiteralPath $localPath -PathType Leaf)) {
            throw "$Label is missing $($entry.path)."
        }
        $file = Get-Item -LiteralPath $localPath
        if ($file.Length -ne $entry.bytes) {
            throw "$Label size mismatch for $($entry.path)."
        }
        $hash = (Get-FileHash -LiteralPath $localPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hash -ne $entry.sha256) {
            throw "$Label SHA-256 mismatch for $($entry.path)."
        }
    }
}

function Write-FailureState([System.Management.Automation.ErrorRecord]$Failure) {
    foreach ($completePath in @(
            $(if ($script:backupDirectory) { Join-Path $script:backupDirectory "BACKUP-COMPLETE.json" }),
            $(if ($script:secondBackupDirectory) { Join-Path $script:secondBackupDirectory "BACKUP-COMPLETE.json" })
        )) {
        if (-not [string]::IsNullOrWhiteSpace($completePath) -and (Test-Path -LiteralPath $completePath)) {
            [System.IO.File]::Delete($completePath)
        }
    }

    $message = @(
        "AAARP DEVICE BACKUP FAILED",
        "timeUtc=$((Get-Date).ToUniversalTime().ToString('o'))",
        "serial=$Serial",
        "forceStopAttempted=$script:forceStopAttempted",
        "forceStopped=$script:forceStopped",
        "error=$($Failure.Exception.Message)",
        "",
        "DO NOT UNINSTALL AAARP.",
        "This directory is incomplete and must not be used as a migration gate."
    )
    if ($script:forceStopAttempted) {
        $priorMonitor = if ($null -eq $script:monitorSnapshot) { "unknown" } else { [string]$script:monitorSnapshot.value }
        $stopState = if ($script:forceStopped) { "is" } else { "may be" }
        $message += "AAARP $stopState force-stopped. The captured monitor setting was $priorMonitor. If migration is postponed, open AAARP and manually restore the prior monitor state."
    }
    foreach ($directory in @($script:backupDirectory, $script:secondBackupDirectory)) {
        if (-not [string]::IsNullOrWhiteSpace($directory) -and (Test-Path -LiteralPath $directory -PathType Container)) {
            Write-Utf8File -Path (Join-Path $directory "BACKUP-FAILED.txt") -Content ($message -join "`n")
        }
    }
}

Assert-OutsideRepository -Path $OutputRoot -Label "OutputRoot"
Assert-OutsideRepository -Path $SecondCopyRoot -Label "SecondCopyRoot"
$outputRootFull = Get-FullPath $OutputRoot
$secondCopyRootFull = Get-FullPath $SecondCopyRoot
if ((Test-PathWithin -Candidate $outputRootFull -Parent $secondCopyRootFull) -or
    (Test-PathWithin -Candidate $secondCopyRootFull -Parent $outputRootFull)) {
    throw "OutputRoot and SecondCopyRoot must be separate, non-nested directories."
}
$primaryVolume = [System.IO.Path]::GetPathRoot($outputRootFull)
$secondVolume = [System.IO.Path]::GetPathRoot($secondCopyRootFull)
if ([string]::IsNullOrWhiteSpace($primaryVolume) -or [string]::IsNullOrWhiteSpace($secondVolume)) {
    throw "Could not determine both backup volume roots."
}
$sameVolume = $primaryVolume.Equals($secondVolume, [System.StringComparison]::OrdinalIgnoreCase)
if ($sameVolume -and -not $AllowSameVolumeDuplicate) {
    throw "The two backup roots are on the same volume ($primaryVolume). Use two distinct volumes, or explicitly pass -AllowSameVolumeDuplicate and accept that the second copy is not independent."
}

Initialize-JavaForApkSigner -RequestedJavaHome $JavaHome
$script:AdbPath = Resolve-Executable -Value $AdbExecutable -Label "adb"
$script:ApkSignerPath = Resolve-ApkSigner -RequestedPath $ApkSignerExecutable
$script:Aapt2Path = Resolve-Aapt2 -RequestedPath $Aapt2Executable -ResolvedApkSigner $script:ApkSignerPath
$script:TarPath = Resolve-Executable -Value "tar" -Label "host tar"
$portableValidation = Test-PortableSettingsBackup -Path $SettingsBackupPath

if (-not [string]::IsNullOrWhiteSpace($CandidateApkPath) -and
    -not (Test-Path -LiteralPath $CandidateApkPath -PathType Leaf)) {
    throw "CandidateApkPath was not found: $CandidateApkPath"
}

$serialSafe = $Serial -replace '[^A-Za-z0-9._-]', '_'
$script:timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$script:backupDirectory = Join-Path $outputRootFull "AAARP-$script:timestamp-$serialSafe"
$script:secondBackupDirectory = Join-Path $secondCopyRootFull (Split-Path -Leaf $script:backupDirectory)
if ((Test-Path -LiteralPath $script:backupDirectory) -or (Test-Path -LiteralPath $script:secondBackupDirectory)) {
    throw "A backup directory for this timestamp already exists."
}

New-Item -ItemType Directory -Path $script:backupDirectory -Force | Out-Null
$script:stateDirectory = (New-Item -ItemType Directory -Path (Join-Path $script:backupDirectory "device-state")).FullName
$script:apkDirectory = (New-Item -ItemType Directory -Path (Join-Path $script:backupDirectory "installed-apks")).FullName
$script:archiveDirectory = (New-Item -ItemType Directory -Path (Join-Path $script:backupDirectory "archives")).FullName
$script:portableDirectory = (New-Item -ItemType Directory -Path (Join-Path $script:backupDirectory "portable")).FullName
$script:evidenceDirectory = (New-Item -ItemType Directory -Path (Join-Path $script:backupDirectory "manual-evidence")).FullName

try {
    $manualEvidence = Copy-ManualEvidence -Paths $ManualEvidencePaths
    $operatorEvidence = [ordered]@{
        capturedUtc = (Get-Date).ToUniversalTime().ToString("o")
        rootAuthorization = $RootAuthorizationNote
        lsposedModuleAndScope = $LsposedScopeNote
        oemBatteryAndAutostart = $OemBatteryAutostartNote
        files = $manualEvidence
    }
    Write-Utf8File -Path (Join-Path $script:evidenceDirectory "operator-evidence.json") -Content ($operatorEvidence | ConvertTo-Json -Depth 6)

    $portableSourceHash = (Get-FileHash -LiteralPath $SettingsBackupPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $portableCopyPath = Join-Path $script:portableDirectory "AAARP-settings-backup.json"
    Copy-Item -LiteralPath $SettingsBackupPath -Destination $portableCopyPath
    $portableCopyHash = (Get-FileHash -LiteralPath $portableCopyPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($portableCopyHash -ne $portableSourceHash) {
        throw "Settings backup changed or was corrupted while copying it into the device backup."
    }
    $portableRecord = [ordered]@{
        version = $portableValidation.Version
        preferenceCount = $portableValidation.PreferenceCount
        createdAtUtc = $portableValidation.CreatedAtUtc
        createdAtSource = $portableValidation.CreatedAtSource
        ageHoursAtValidation = $portableValidation.AgeHoursAtValidation
        sha256 = $portableCopyHash
    }
    Write-Utf8File -Path (Join-Path $script:portableDirectory "validation.json") -Content ($portableRecord | ConvertTo-Json -Depth 4)

    $adbVersionOutput = & $script:AdbPath version 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Could not read the host adb version." }
    $apksignerVersionOutput = & $script:ApkSignerPath version 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Could not read the host apksigner version." }
    $aapt2VersionOutput = & $script:Aapt2Path version 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Could not read the host aapt2 version." }
    $tarVersionOutput = & $script:TarPath --version 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Could not read the host tar version." }
    Write-Utf8File -Path (Join-Path $script:stateDirectory "host-tool-versions.txt") -Content (
        "adb=$script:AdbPath`n" + (($adbVersionOutput | ForEach-Object { $_.ToString() }) -join "`n") +
        "`n`napksigner=$script:ApkSignerPath`nJAVA_HOME=$env:JAVA_HOME`n" + (($apksignerVersionOutput | ForEach-Object { $_.ToString() }) -join "`n") +
        "`n`naapt2=$script:Aapt2Path`n" + (($aapt2VersionOutput | ForEach-Object { $_.ToString() }) -join "`n") +
        "`n`ntar=$script:TarPath`n" + (($tarVersionOutput | ForEach-Object { $_.ToString() }) -join "`n")
    )

    $connectedState = Invoke-AdbText -Arguments @("-s", $Serial, "get-state")
    if ($connectedState.Text.Trim() -ne "device") {
        throw "ADB serial $Serial is not in the device state."
    }
    $reportedSerial = (Invoke-AdbText -Arguments @("-s", $Serial, "get-serialno")).Text.Trim()
    if ($reportedSerial -ne $Serial) {
        throw "ADB targeted $reportedSerial instead of the required serial $Serial."
    }

    $userResult = Invoke-DeviceShell -Command "am get-current-user"
    if ($userResult.Text.Trim() -notmatch '^\d+$') {
        throw "Could not determine the current Android user: $($userResult.Text)"
    }
    $currentUser = [int]$userResult.Text.Trim()
    Write-Utf8File -Path (Join-Path $script:stateDirectory "current-android-user.txt") -Content "androidUser=$currentUser"

    $packageCheck = Invoke-DeviceShell -Command ("pm path --user $currentUser " + (Quote-AndroidShell $packageName)) -AllowFailure
    if ($packageCheck.ExitCode -ne 0 -or -not $packageCheck.Text.Contains("package:")) {
        throw "$packageName is not installed for current Android user $currentUser on $Serial."
    }
    $rootIdentity = Invoke-RootShell -Command "id" -AllowFailure
    if ($rootIdentity.ExitCode -ne 0 -or $rootIdentity.Text -notmatch 'uid=0(?:\D|$)') {
        throw "Root access is required for a complete AAARP backup."
    }
    $rootTools = Invoke-RootShell -Command "command -v tar && command -v sha256sum && command -v stat" -AllowFailure
    if ($rootTools.ExitCode -ne 0) {
        throw "Root tar, sha256sum, and stat are required for a complete AAARP backup."
    }

    $staleCleanup = Remove-StaleRemoteArchives -AllowFailure
    if ($staleCleanup.ExitCode -ne 0) {
        throw "Could not remove stale AAARP archives from /data/local/tmp."
    }

    $credentialDataPath = "/data/user/$currentUser/$packageName"
    $deviceDataPath = "/data/user_de/$currentUser/$packageName"
    $externalDataPath = "/storage/emulated/$currentUser/Android/data/$packageName"
    $downloadDataPath = "/storage/emulated/$currentUser/Download/AAARP"
    $preferencesPath = "$credentialDataPath/shared_prefs/aaarp_prefs.xml"
    $credentialCheck = Invoke-RootShell -Command ("test -d " + (Quote-AndroidShell $credentialDataPath)) -AllowFailure
    if ($credentialCheck.ExitCode -ne 0) {
        throw "Required credential-protected AAARP data is missing at $credentialDataPath."
    }
    $uidResult = Invoke-RootShell -Command ("stat -c '%u' " + (Quote-AndroidShell $credentialDataPath)) -AllowFailure
    if ($uidResult.ExitCode -ne 0 -or $uidResult.Text.Trim() -notmatch '^\d+$') {
        throw "Could not determine AAARP's current app UID from $credentialDataPath."
    }
    $appUid = [int64]$uidResult.Text.Trim()
    Write-Utf8File -Path (Join-Path $script:stateDirectory "device-data-paths.json") -Content (([ordered]@{
        androidUser = $currentUser
        appUid = $appUid
        credentialProtected = $credentialDataPath
        deviceProtected = $deviceDataPath
        appExternal = $externalDataPath
        sharedDiagnostics = $downloadDataPath
        preferences = $preferencesPath
    }) | ConvertTo-Json -Depth 3)

    $script:monitorSnapshot = Get-BooleanPreferenceSnapshot -PreferencesPath $preferencesPath -PreferenceName "monitor_enabled"
    $rootPreferenceSnapshot = Get-BooleanPreferenceSnapshot -PreferencesPath $preferencesPath -PreferenceName "use_root"
    Write-Utf8File -Path (Join-Path $script:stateDirectory "exact-pre-stop-preferences.json") -Content (([ordered]@{
        capturedUtc = (Get-Date).ToUniversalTime().ToString("o")
        monitorEnabled = $script:monitorSnapshot
        rootDiagnosticsEnabled = $rootPreferenceSnapshot
    }) | ConvertTo-Json -Depth 5)

    Write-Snapshot -Name "device-identity.txt" -Command "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.fingerprint; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.serialno" -Required | Out-Null
    Write-Snapshot -Name "root-identity.txt" -Command "id" -Root -Required | Out-Null
    Write-Snapshot -Name "root-su-version.txt" -Command "su -v" -Root | Out-Null
    Write-Snapshot -Name "root-su-version-code.txt" -Command "su -V" -Root | Out-Null
    Write-Snapshot -Name "magisk-version.txt" -Command "magisk -V" -Root | Out-Null
    Write-Snapshot -Name "magisk-policy-for-aaarp.txt" -Command ("magisk --sqlite " + (Quote-AndroidShell "SELECT uid,policy,until,logging,notification FROM policies WHERE uid=$appUid;")) -Root | Out-Null
    Write-Snapshot -Name "lsposed-module-properties.txt" -Command "cat /data/adb/modules/zygisk_lsposed/module.prop" -Root | Out-Null
    Write-Snapshot -Name "lsposed-config-metadata.txt" -Command "ls -laZ /data/adb/lspd/config" -Root | Out-Null
    Write-Snapshot -Name "lsposed-config-db-sha256.txt" -Command "sha256sum /data/adb/lspd/config/modules_config.db" -Root | Out-Null
    Write-Snapshot -Name "lsposed-target-markers.txt" -Command "strings /data/adb/lspd/config/modules_config.db | grep -E 'dev.voxvargr.aaarp.aatrusthook|com.google.android.projection.gearhead'" -Root | Out-Null
    Write-Snapshot -Name "lsposed-manager-package.txt" -Command "dumpsys package org.lsposed.manager" | Out-Null

    Write-Snapshot -Name "main-package.txt" -Command ("dumpsys package " + (Quote-AndroidShell $packageName)) -Required | Out-Null
    Write-Snapshot -Name "main-package-version.txt" -Command ("dumpsys package " + (Quote-AndroidShell $packageName) + " | grep -E 'versionCode=|versionName=|firstInstallTime=|lastUpdateTime='") -Required | Out-Null
    Write-Snapshot -Name "main-appops.txt" -Command ("cmd appops get " + (Quote-AndroidShell $packageName)) -Required | Out-Null
    Write-Snapshot -Name "deviceidle-state.txt" -Command "dumpsys deviceidle" -Required | Out-Null
    Write-Snapshot -Name "deviceidle-whitelist.txt" -Command "cmd deviceidle whitelist" -Required | Out-Null
    Write-Snapshot -Name "main-standby-bucket.txt" -Command ("am get-standby-bucket --user $currentUser " + (Quote-AndroidShell $packageName)) | Out-Null
    Write-Snapshot -Name "main-service-before-stop.txt" -Command ("dumpsys activity services " + (Quote-AndroidShell $packageName)) -Required | Out-Null
    Write-Snapshot -Name "main-processes-before-stop.txt" -Command "ps -A" -Required | Out-Null
    Write-Snapshot -Name "notification-state.txt" -Command ("dumpsys notification --noredact | grep -A 100 -B 20 " + (Quote-AndroidShell $packageName)) | Out-Null
    Write-Snapshot -Name "media-audio-baseline.txt" -Command "dumpsys audio" -Required | Out-Null
    Write-Snapshot -Name "media-audio-policy-baseline.txt" -Command "dumpsys media.audio_policy" | Out-Null
    Write-Snapshot -Name "media-audio-flinger-baseline.txt" -Command "dumpsys media.audio_flinger" | Out-Null
    Write-Snapshot -Name "media-session-baseline.txt" -Command "cmd media_session list-sessions" | Out-Null

    $packageStatuses = @()
    foreach ($relatedPackage in $relatedPackages) {
        $snapshotName = "package-" + $relatedPackage.Replace('.', '_') + ".txt"
        if ($relatedPackage -ne $packageName) {
            Write-Snapshot -Name $snapshotName -Command ("dumpsys package " + (Quote-AndroidShell $relatedPackage)) | Out-Null
        }
        $packageStatuses += Save-PackageArtifacts -Package $relatedPackage -AndroidUser $currentUser -Required:($relatedPackage -eq $packageName)
    }
    $mainPackageStatus = @($packageStatuses | Where-Object { $_.packageName -eq $packageName })[0]
    if ($null -eq $mainPackageStatus -or -not $mainPackageStatus.installedForCurrentUser -or
        @($mainPackageStatus.artifacts).Count -eq 0) {
        throw "The required main AAARP APK was not captured."
    }

    $candidateStatus = [ordered]@{
        supplied = $false
        signerMatchesInstalled = $false
        signerChangeAccepted = $false
        cleanInstallRequired = $false
    }
    if (-not [string]::IsNullOrWhiteSpace($CandidateApkPath)) {
        $candidateDirectory = New-Item -ItemType Directory -Path (Join-Path $script:apkDirectory "candidate")
        $candidateLocalPath = Join-Path $candidateDirectory.FullName "AAARP-candidate.apk"
        Copy-Item -LiteralPath $CandidateApkPath -Destination $candidateLocalPath
        $candidateSigner = Get-ApkSignerInfo -ApkPath $candidateLocalPath -EvidencePath ($candidateLocalPath + ".apksigner.txt")
        $candidateIdentity = Get-ApkIdentity -ApkPath $candidateLocalPath -EvidencePath ($candidateLocalPath + ".aapt2.txt")
        if ($candidateIdentity.PackageName -ne $packageName) {
            throw "Candidate APK package is $($candidateIdentity.PackageName), not $packageName. Do not uninstall."
        }
        $baseArtifact = @($mainPackageStatus.artifacts | Where-Object { $_.remotePath -match '/base\.apk$' } | Select-Object -First 1)
        if ($baseArtifact.Count -eq 0) {
            $baseArtifact = @($mainPackageStatus.artifacts | Select-Object -First 1)
        }
        $installedCertificates = @($baseArtifact[0].signerCertificateSha256)
        $signerMatch = @(Compare-Object -ReferenceObject $installedCertificates -DifferenceObject @($candidateSigner.Certificates)).Count -eq 0
        $signerChangeAccepted = -not $signerMatch -and
            [bool]$AllowCandidateSignerChangeForCleanInstall
        $candidateStatus = [ordered]@{
            supplied = $true
            sourceLeafName = Split-Path -Leaf $CandidateApkPath
            storedAs = "installed-apks/candidate/AAARP-candidate.apk"
            bytes = (Get-Item -LiteralPath $candidateLocalPath).Length
            sha256 = $candidateSigner.Sha256
            packageName = $candidateIdentity.PackageName
            versionCode = $candidateIdentity.VersionCode
            versionName = $candidateIdentity.VersionName
            signerCertificateSha256 = @($candidateSigner.Certificates)
            installedSignerCertificateSha256 = $installedCertificates
            signerMatchesInstalled = $signerMatch
            signerChangeAccepted = $signerChangeAccepted
            cleanInstallRequired = -not $signerMatch
        }
        if (-not $signerMatch -and -not $signerChangeAccepted) {
            throw "Candidate APK signer does not match the installed AAARP signer. A clean install is required; rerun only with -AllowCandidateSignerChangeForCleanInstall after reviewing both certificate digests. Do not uninstall."
        }
    }
    Write-Utf8File -Path (Join-Path $script:apkDirectory "package-artifacts.json") -Content ($packageStatuses | ConvertTo-Json -Depth 8)
    Write-Utf8File -Path (Join-Path $script:apkDirectory "candidate-verification.json") -Content ($candidateStatus | ConvertTo-Json -Depth 6)

    # Record all runtime state first, then quiesce only AAARP so its private archive is coherent.
    Write-Snapshot -Name "media-audio-immediately-before-stop.txt" -Command "dumpsys audio" -Required | Out-Null
    $script:monitorSnapshot = Get-BooleanPreferenceSnapshot -PreferencesPath $preferencesPath -PreferenceName "monitor_enabled"
    Write-Utf8File -Path (Join-Path $script:stateDirectory "exact-pre-stop-preferences.json") -Content (([ordered]@{
        capturedUtc = (Get-Date).ToUniversalTime().ToString("o")
        monitorEnabled = $script:monitorSnapshot
        rootDiagnosticsEnabled = $rootPreferenceSnapshot
    }) | ConvertTo-Json -Depth 5)
    $stopCommand = "am force-stop --user $currentUser " + (Quote-AndroidShell $packageName)
    # Mark the attempt before invoking ADB. The device can execute force-stop and then
    # disconnect before the host receives a successful result, leaving state uncertain.
    $script:forceStopAttempted = $true
    $stopResult = Invoke-DeviceShell -Command $stopCommand
    $script:forceStopped = $true
    Write-Utf8File -Path (Join-Path $script:stateDirectory "main-force-stop.txt") -Content ("exitCode=$($stopResult.ExitCode)`ncommand=$stopCommand`n" + $stopResult.Text)
    Assert-AppQuiescent -AndroidUser $currentUser -Stage "after-force-stop"
    Write-Snapshot -Name "main-service-after-stop.txt" -Command ("dumpsys activity services " + (Quote-AndroidShell $packageName)) -Required -AllowEmpty | Out-Null

    Save-RemoteArchive -SourcePath $credentialDataPath -ArchiveName "credential-protected-data" -Required -RequiredEntries @("$packageName/shared_prefs/aaarp_prefs.xml") | Out-Null
    Assert-AppQuiescent -AndroidUser $currentUser -Stage "after-private-archive"
    Save-RemoteArchive -SourcePath $deviceDataPath -ArchiveName "device-protected-data" | Out-Null
    Save-RemoteArchive -SourcePath $externalDataPath -ArchiveName "app-external-data" | Out-Null
    Save-RemoteArchive -SourcePath $downloadDataPath -ArchiveName "shared-download-diagnostics" | Out-Null

    $cleanupResult = Remove-StaleRemoteArchives -AllowFailure
    if ($cleanupResult.ExitCode -ne 0) {
        throw "Could not prove that sensitive AAARP staging archives were removed from /data/local/tmp."
    }

    $failedRequiredSnapshots = @($snapshotStatuses | Where-Object { $_.required -and -not $_.success })
    if ($failedRequiredSnapshots.Count -ne 0) {
        throw "One or more required device-state snapshots failed."
    }
    $failedRequiredArchives = @($archiveStatuses | Where-Object { $_.required -and -not $_.verified })
    if ($failedRequiredArchives.Count -ne 0) {
        throw "One or more required private-data archives failed verification."
    }
    Write-Utf8File -Path (Join-Path $script:stateDirectory "snapshot-status.json") -Content ($snapshotStatuses | ConvertTo-Json -Depth 5)
    Write-Utf8File -Path (Join-Path $script:archiveDirectory "archive-status.json") -Content ($archiveStatuses | ConvertTo-Json -Depth 5)

    $manifestEntries = Get-ManifestEntries -Root $script:backupDirectory
    $copyMode = if ($sameVolume) { "same-volume-explicit-duplicate" } else { "distinct-volume-copies" }
    $manifest = [ordered]@{
        format = "dev.voxvargr.aaarp.device-backup"
        version = 2
        createdUtc = (Get-Date).ToUniversalTime().ToString("o")
        serial = $Serial
        androidUser = $currentUser
        appUidAtBackup = $appUid
        packageName = $packageName
        monitorEnabledBeforeStop = [bool]$script:monitorSnapshot.value
        portableSettings = $portableRecord
        candidateApk = $candidateStatus
        rootAuthorizationNote = $RootAuthorizationNote
        lsposedScopeNote = $LsposedScopeNote
        oemBatteryAutostartNote = $OemBatteryAutostartNote
        copyMode = $copyMode
        primaryVolume = $primaryVolume
        secondVolume = $secondVolume
        requiredSnapshotsPassed = $true
        requiredArchivesPassed = $true
        forceStopped = $true
        note = if (-not $candidateStatus.supplied) {
            "AAARP is still force-stopped. No candidate APK was supplied, so this backup is not an uninstall authorization."
        } elseif ($candidateStatus.cleanInstallRequired) {
            "AAARP is still force-stopped. The explicitly accepted signer transition requires a clean uninstall/install; preserve both verified backups and the captured old APK set for rollback."
        } else {
            "AAARP is still force-stopped. The candidate signer matches the installed app, so an in-place update should be attempted before any clean reinstall."
        }
        files = $manifestEntries
    }
    $manifestPath = Join-Path $script:backupDirectory "manifest.json"
    Write-Utf8File -Path $manifestPath -Content ($manifest | ConvertTo-Json -Depth 10)
    Assert-ManifestFiles -Root $script:backupDirectory -Entries $manifestEntries -Label "Primary backup"

    New-Item -ItemType Directory -Path $secondCopyRootFull -Force | Out-Null
    if (Test-Path -LiteralPath $script:secondBackupDirectory) {
        throw "Second backup path appeared during capture; refusing to merge with it: $script:secondBackupDirectory"
    }
    Copy-Item -LiteralPath $script:backupDirectory -Destination $script:secondBackupDirectory -Recurse
    Assert-ManifestFiles -Root $script:secondBackupDirectory -Entries $manifestEntries -Label "Second backup"
    $secondManifestPath = Join-Path $script:secondBackupDirectory "manifest.json"
    if (-not (Test-Path -LiteralPath $secondManifestPath -PathType Leaf)) {
        throw "Second backup is missing manifest.json."
    }
    $primaryManifestHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $secondManifestHash = (Get-FileHash -LiteralPath $secondManifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($primaryManifestHash -ne $secondManifestHash) {
        throw "Second-copy SHA-256 mismatch for manifest.json."
    }

    # A completion marker is written only after every primary and second-copy hash has passed.
    $completion = [ordered]@{
        format = "dev.voxvargr.aaarp.device-backup-complete"
        version = 1
        completedUtc = (Get-Date).ToUniversalTime().ToString("o")
        manifestSha256 = $primaryManifestHash
        copyMode = $copyMode
        candidateSignerMatch = [bool]$candidateStatus.signerMatchesInstalled
        candidateSignerChangeAccepted = [bool]$candidateStatus.signerChangeAccepted
        cleanInstallRequired = [bool]$candidateStatus.cleanInstallRequired
        safeToUninstall = [bool]($candidateStatus.supplied -and (
                $candidateStatus.signerMatchesInstalled -or
                $candidateStatus.signerChangeAccepted
            ))
    }
    $completionText = $completion | ConvertTo-Json -Depth 4
    $secondCompletePath = Join-Path $script:secondBackupDirectory "BACKUP-COMPLETE.json"
    $primaryCompletePath = Join-Path $script:backupDirectory "BACKUP-COMPLETE.json"
    Write-Utf8File -Path $secondCompletePath -Content $completionText
    Write-Utf8File -Path $primaryCompletePath -Content $completionText
    $primaryCompleteHash = (Get-FileHash -LiteralPath $primaryCompletePath -Algorithm SHA256).Hash
    $secondCompleteHash = (Get-FileHash -LiteralPath $secondCompletePath -Algorithm SHA256).Hash
    if ($primaryCompleteHash -ne $secondCompleteHash) {
        throw "Completion markers did not verify identically."
    }

    Write-Host "AAARP backup complete and both copies verified." -ForegroundColor Green
    Write-Host "Primary: $script:backupDirectory"
    Write-Host "Second copy: $script:secondBackupDirectory"
    if ($sameVolume) {
        Write-Warning "Both copies are on $primaryVolume. This is an explicitly requested duplicate, not protection from volume loss."
    }
    if (-not $candidateStatus.supplied) {
        Write-Warning "No candidate APK was supplied. The backup is complete, but BACKUP-COMPLETE.json marks safeToUninstall=false."
    } elseif ($candidateStatus.cleanInstallRequired) {
        Write-Warning "The candidate signer differs from the installed app. This explicitly accepted transition requires a clean uninstall/install; keep the captured old APK and both verified backup copies for rollback."
    }
    Write-Warning "AAARP remains force-stopped. Review BACKUP-COMPLETE.json before uninstalling."
} catch {
    $failure = $_
    if ($null -ne $script:stateDirectory -and (Test-Path -LiteralPath $script:stateDirectory -PathType Container)) {
        if ($snapshotStatuses.Count -gt 0) {
            Write-Utf8File -Path (Join-Path $script:stateDirectory "snapshot-status.json") -Content ($snapshotStatuses | ConvertTo-Json -Depth 5)
        }
        if ($archiveStatuses.Count -gt 0) {
            Write-Utf8File -Path (Join-Path $script:archiveDirectory "archive-status.json") -Content ($archiveStatuses | ConvertTo-Json -Depth 5)
        }
    }
    if ($script:forceStopAttempted) {
        Remove-StaleRemoteArchives -AllowFailure | Out-Null
    }
    Write-FailureState -Failure $failure
    Write-Host "AAARP BACKUP FAILED - DO NOT UNINSTALL." -ForegroundColor Red
    if ($script:forceStopAttempted) {
        $stopWarning = if ($script:forceStopped) { "remains" } else { "may be" }
        Write-Host "AAARP $stopWarning force-stopped; reopen it and restore the captured monitor state if migration is postponed." -ForegroundColor Yellow
    }
    throw
}
