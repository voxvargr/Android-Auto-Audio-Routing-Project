# AAARP device migration and rollback gate

Use this procedure before uninstalling `dev.voxvargr.aaarp`. The backup captures AAARP and its
device access state for the **currently active Android user**. It is not a full-phone image and it
does not back up music, accounts, or unrelated applications.

The backup script force-stops AAARP only after it has captured the live state, installed APKs, and
candidate signer. It never uninstalls or installs anything. A failed or interrupted run is not a
usable migration backup.

## Manual prerequisites

Complete these while the existing installation is still intact:

1. Unlock the phone, switch to the Android user that owns the AAARP setup, and keep that user
   active for the whole capture.
2. In AAARP, tap **Backup Settings** and create a fresh `AAARP-settings-backup.json`. Copy that
   export to a private host path. The script requires it and, by default, rejects an export older
   than 24 hours. A current version 2 export must contain its creation time and access snapshot.
3. Build and test the replacement APK off-device. Keep its exact APK available to pass as
   `-CandidateApkPath`; without it, the tool can make a complete backup but will explicitly mark it
   unsafe to uninstall. If its signer differs from the installed app, compare both recorded
   certificate SHA-256 digests. That transition cannot update in place and must be explicitly
   acknowledged with `-AllowCandidateSignerChangeForCleanInstall`.
4. In Magisk or the installed root manager, record whether AAARP is allowed root access and any
   per-app policy choices. Write a short non-secret note and, if practical, take a screenshot.
5. In LSPosed, record whether **AAARP AA Trust Hook** is enabled and confirm its scope includes
   Android Auto (`com.google.android.projection.gearhead`). Write a short note and take screenshots
   of the module and scope when practical.
6. Record every OEM battery, background-run, and autostart choice for AAARP. On OEM settings pages,
   capture screenshots when practical. The generic Android doze/app-op state is collected
   automatically, but OEM controls often have no reliable command-line interface.
7. Select two private backup roots outside the repository. For independent protection they must be
   on different physical volumes, for example an encrypted internal volume and an encrypted USB
   volume. Two folders on `C:` are not independent copies.
8. Confirm `adb`, host `tar`, Android SDK `apksigner`/`aapt2`, and the Java runtime needed by
   `apksigner` are installed. The script can discover Android Studio's standard JBR location;
   otherwise pass `-JavaHome '<path-to-jbr>'`. Root must already work on the phone. Confirm no
   other task or person will open AAARP or use the shared phone during the capture.

Do not put passwords, owner codes, private locations, or other secrets in the three note
parameters. Put screenshots or supporting text files in private storage and pass their paths with
`-ManualEvidencePaths`.

## Run the gated backup

Run from the repository root, replacing every placeholder. The exact ADB serial is mandatory and
every device command is pinned to it.

```powershell
.\tools\Backup-AAARPDevice.ps1 `
  -Serial '<exact-adb-serial>' `
  -SettingsBackupPath '<private-host-path\AAARP-settings-backup.json>' `
  -CandidateApkPath '<path-to-tested-AAARP-candidate.apk>' `
  -OutputRoot '<private-primary-volume\AAARP-backups>' `
  -SecondCopyRoot '<private-second-volume\AAARP-backups>' `
  -RootAuthorizationNote '<root manager and AAARP policy observed>' `
  -LsposedScopeNote '<module enabled state and exact observed scope>' `
  -OemBatteryAutostartNote '<battery/background/autostart state observed>' `
  -ManualEvidencePaths @('<root-screenshot>', '<lsposed-screenshot>', '<oem-screenshot>')
```

If `apksigner` is not discoverable automatically, add
`-ApkSignerExecutable '<Android-SDK\build-tools\...\apksigner.bat>'`. If Java is not discoverable,
also add `-JavaHome '<Android-Studio\jbr>'`. `aapt2` is normally resolved beside `apksigner`; use
`-Aapt2Executable '<Android-SDK\build-tools\...\aapt2.exe>'` only if it is elsewhere.

If and only if the candidate certificate is intentionally different from the installed app, add
`-AllowCandidateSignerChangeForCleanInstall`. The script otherwise fails before force-stop. With
the flag, the completion marker records `candidateSignerMatch: false`,
`candidateSignerChangeAccepted: true`, and `cleanInstallRequired: true`. This is authority for the
recorded candidate only; it is not a general signature-check bypass.

Only when a second physical volume is genuinely unavailable may the operator add
`-AllowSameVolumeDuplicate`. The manifest and completion marker will then say
`same-volume-explicit-duplicate`; this protects against an accidental file change, not loss of that
volume. Never describe it as two independent backups.

The capture is considered successful only when **both** backup directories contain
`BACKUP-COMPLETE.json`, both markers name the same `manifestSha256`, and neither directory contains
`BACKUP-FAILED.txt`. The completion marker must also show:

- `safeToUninstall: true`;
- either `candidateSignerMatch: true`, or both `candidateSignerChangeAccepted: true` and
  `cleanInstallRequired: true`; and
- `copyMode: distinct-volume-copies`, unless the reduced same-volume protection was knowingly
  accepted.

The manifest records the current Android user and app UID, exact pre-stop monitor preference,
portable-export validation, media volume/route baseline, installed package versions and signer,
generic access state, root/LSPosed evidence, OEM notes, and SHA-256 for every captured file. The
credential-protected archive and the installed main APK are mandatory. Optional paths are labeled
missing rather than silently treated as captured.

AAARP remains force-stopped after either a successful run or any failure that occurs after the
stop. If the migration is postponed, open AAARP and manually return the monitor to the value in
`device-state/exact-pre-stop-preferences.json`. On any failure, do not uninstall; keep the directory
with `BACKUP-FAILED.txt` only as diagnostic evidence and perform a new full capture.

## Clean reinstall and normal restore

1. Review both completion markers and verify the manifest hash in each copy. Keep one backup volume
   disconnected from the PC during the actual reinstall.
2. Read `androidUser` from `manifest.json` and confirm `am get-current-user` still reports that
   user. Stop if it differs.
3. If `candidateSignerMatch` is true, attempt an in-place update first; a clean reinstall is not
   justified merely because it was anticipated. If `cleanInstallRequired` and
   `candidateSignerChangeAccepted` are both true, uninstall only `dev.voxvargr.aaarp` for the
   recorded user. Do not remove helper packages or data from another Android user. Do not proceed
   if the completion marker says `safeToUninstall` is false.
4. Install the backed-up candidate APK for that same user. Verify its host SHA-256 and certificate
   again against `installed-apks/candidate-verification.json` before installing it.
5. Launch AAARP once and leave the monitor off. Import
   `portable/AAARP-settings-backup.json`; the app performs its supported settings migrations during
   import.
6. Compare profiles, mappings, Bluetooth targets, notification choices, and loudness settings with
   the portable backup and manifest. Confirm the music-volume index and active audio route were not
   changed by restoration.
7. Restore only the access that was actually recorded: runtime permissions, notification access,
   doze/OEM exemptions, root authorization, and LSPosed module/scope. Re-check these in their native
   settings screens; a command snapshot is evidence, not proof that an OEM UI accepted a restore.
8. Enable the monitor last, and only if
   `device-state/exact-pre-stop-preferences.json` says it was enabled before capture.
9. Validate a normal song transition, a quiet-to-loud transition, manual earbud volume control, and
   Android Auto routing on the real device before declaring the migration complete.

Keep historical logs in the backup. Do not inject logs, caches, `code_cache`, compiled code, or a
stale app UID into the clean installation.

## Safe rollback to the captured APK

Use this if the new build fails but AAARP can still import its portable settings:

1. Stop AAARP and leave its monitor off. Preserve a separate diagnostic backup of the failed new
   installation before changing it.
2. Verify `BACKUP-COMPLETE.json`, its `manifestSha256`, and every relevant file hash in
   `manifest.json` from the offline copy.
3. Verify every APK under `installed-apks/dev_voxvargr_aaarp` with host `apksigner`. All captured
   base/split APKs must retain the signer certificate recorded in `package-artifacts.json`.
4. Remove AAARP only for the recorded Android user, then reinstall the full captured APK set for
   that same user. Use `adb install` when exactly one APK was captured and `adb install-multiple`
   with **all** captured base/split APKs when more than one was captured. Pin every command to the
   exact serial and pass the recorded user explicitly.
5. Launch once with the monitor off, import the portable JSON, restore the recorded access state,
   and enable the monitor last only if it was previously enabled.

This portable rollback is the default. It avoids copying files owned by an obsolete Android app
UID.

## Emergency raw-data rollback

Use the raw credential archive only if portable restore cannot recover required state. This is a
last-resort, rooted maintenance procedure and must use the APK signer and data layout that produced
the archive. Do not perform it across an unknown schema downgrade.

1. Work from a verified offline backup copy. Recalculate the SHA-256 of
   `archives/credential-protected-data.tar.gz` and require an exact match with `manifest.json` and
   its device hash evidence. Confirm its listing contains
   `dev.voxvargr.aaarp/shared_prefs/aaarp_prefs.xml`.
2. Install the captured old APK set for the recorded user and launch it once with the monitor off.
   Re-check its signer. Query the **new** owner UID of
   `/data/user/<current-user>/dev.voxvargr.aaarp`; never reuse `appUidAtBackup` as an ownership
   target.
3. Force-stop `dev.voxvargr.aaarp` for that user and prove no process named
   `dev.voxvargr.aaarp` or `dev.voxvargr.aaarp:*` remains.
4. Push the verified archive to a uniquely named file under `/data/local/tmp`, immediately change
   it to root ownership and mode `0600`, and verify its on-device SHA-256 before extraction. Create
   a unique root-only staging directory; extract there, never directly over `/data/user`.
5. Inspect the staged tree and require the single expected package directory. Inside the live app
   data directory, **move** existing `shared_prefs`, `databases`, and `files` into a timestamped
   rollback-hold directory on the same filesystem. Do not delete them. Copy only those selected
   directories from staging. Do not copy `cache`, `code_cache`, compiled artifacts, or the archived
   top-level ownership metadata.
6. Recursively change only the copied directories to the newly queried UID/GID, then run
   `restorecon -RF` on the exact live package directory. Verify owner, mode, and SELinux label on
   representative files. Before launch, set the restored `monitor_enabled` Boolean to false while
   preserving the prior value recorded by the backup.
7. Remove the sensitive `/data/local/tmp` archive and staging tree immediately, and verify they are
   gone. Keep the rollback-hold directory until AAARP launches and the restored data is verified;
   it is the recovery path if the raw copy is rejected.
8. Launch AAARP, validate data before granting root or enabling LSPosed, restore access one item at
   a time, and enable the monitor last. If launch or validation fails, force-stop AAARP and move the
   held fresh-install directories back rather than repeatedly modifying the failed restore.

The raw archive is evidence and an emergency recovery source, not permission to overwrite another
user, another package, or an unverified installation.
