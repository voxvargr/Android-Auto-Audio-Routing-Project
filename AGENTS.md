# AAARP continuation instructions

Read AAARP-MACHINE-HANDOFF.txt first, then migration/CURRENT-ARCHITECTURE.txt and migration/DATA-INVENTORY.txt. The two dated historical handoffs explain past investigations; verify their claims against current source and the actual phone before treating them as current bugs.

The active source comes from GitHub main commit 7a60777503ffcf94e48011dfa6f3d5da1eb01ad2, with migration documentation/data added. migration/local-prototype-snapshot is an older preserved prototype, never a replacement for the active app. Do not merge its source over the current implementation.

Preserve all five Gradle modules and existing call/ringtone/Assistant protections. Keep the real music app's Android Auto session intact. The helpers intentionally do not become media players. Hook support is version-specific and must fail closed outside its supported Android Auto internals.

Use the checked-in Gradle wrapper. Match source/build/installed APK with hashes: app version 0.1.0/code 1 was reused across different builds. A passing unit test or build does not prove physical audio, microphone, calls, Assistant voice replies, or dock behavior.

New-machine debug signing can differ. Main and volume helpers must use one certificate. Export current phone settings before any uninstall, preserve installed APKs separately, restore permissions/root/battery/LSPosed state, and never uninstall as an automatic response to an install signature mismatch.

Keep secrets, private signing keys, local.properties, SDKs, caches, and generated APKs out of commits. Diagnostic files are intentionally preserved raw, including device/network identifiers. Do not publish/push without the user's instruction. Verify both preservation SHA256 manifests with tools/Verify-PreservedData.ps1 before and after transfer.

Prefer FOSS tools. Give concise status updates and double-check results. Preserve unrelated user changes.
