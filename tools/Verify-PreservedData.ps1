param([string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($RepositoryRoot)
$manifests = @('migration/PRESERVED-FILES.sha256', 'migration/PRIOR-PREPARATION.sha256')
$count = 0
foreach ($manifestRelative in $manifests) {
$manifest = Join-Path $root $manifestRelative
foreach ($line in Get-Content -LiteralPath $manifest) {
    if (-not $line.Trim() -or $line.StartsWith('#')) { continue }
    if ($line -notmatch '^([A-Fa-f0-9]{64})  (.+)$') { throw "Malformed manifest entry: $line" }
    $expected = $Matches[1]
    $relative = $Matches[2]
    $target = [IO.Path]::GetFullPath((Join-Path $root $relative))
    if (-not $target.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw "Path outside repository: $relative" }
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { throw "Missing preserved file: $relative" }
    $actual = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
    if ($actual -ne $expected) { throw "SHA256 mismatch: $relative" }
    $count++
}
}
if ($count -eq 0) { throw 'Manifest contains no files.' }
Write-Output "Verified $count preserved files against SHA256 manifest."
