# Desktop APK recovery, 2026-10-16

The four APKs supplied after the desktop wipe are the recovery reference. Existing source and
the Pixel routing fixes were backed up before comparing or replacing source files.

## Reference APKs

| File | SHA-256 |
| --- | --- |
| AAARP 0.2.0 | `341a76cfc950ec2d2d7897fb4495cd2645a9ae048015b2e50117364ee1cf1d6c` |
| Android Auto Trust Hook 0.6.0 | `2003a1d66c1df361d152eea61bae165b773fd5e1fa911fb3a0bbf37088fcab0f` |
| Volume down 0.1.0 | `11dd712c6386cb0de9889c065414fdd32966e01b205d87cf39629ea6f89869e9` |
| Volume up 0.1.0 | `0c7ea320234dbfa37139a7e1234b9a8279616a52f85d8553614cadc43071b5b5` |

## Comparison and recovered source

The supplied main APK matches the saved pre-fix local APK byte for byte. An isolated rebuild of
all pre-fix Java sources using the matching D8 version reproduces all 862 app methods instruction
for instruction after normalizing reference-pool offsets and generated synthetic class names;
there are no missing or additional methods. Comparing its decompiled
application classes with the fixed build produces 22 identical Java files; differences are
confined to `AudioRouteController`, `RootShell`, `RouteDevice`, and `RoutingMonitorService`, the
four classes changed for this review. All 17 compared non-code ZIP entries match exactly after
excluding DEX files, the versioned manifest, and META-INF signing/build metadata. This preserves
the installed settings, profile, backup/restore, leveler, interface, and volume-control code.

Both supplied volume helpers also match their local build outputs byte for byte. Their custom
provider/service classes, nested car-app code, manifests, icons, labels, and automotive metadata
were checked against the existing source. They require no source recovery changes.

The Trust Hook source was older, at 0.5.0. Its recovered 0.6.0 source now includes:

- Adaptive `RailHotseatItemView` binding and exact `hotseat_three`/`hotseat_four` volume slots.
- Helper icons, accessibility labels, and direct main-app provider calls that retain the car screen.
- Android Auto 17.4 and 17.3 profiles, with their different factory signatures.
- Structural checks, enum role lookup, and exact helper-service matching before interception.
- The original Gearhead-only scope, trust allow-list, module metadata, and resources.

Decompiler-generated synthetic captures were reconstructed as normal Java lambdas. Raw DEX
exception ranges confirmed the ambiguous fixed-dock callback boundary rather than relying on
the decompiler's nested catch rendering. No third-party runtime sources were imported: the
decompiler's errors were confined to unchanged bundled Kotlin library code.

## Pixel fixes retained in AAARP 0.2.1

- Hold the Bluetooth media guard through 15-second song gaps and release AAARP-owned SCO promptly
  when a media-start callback arrives, respecting the active connection's profile and call guards.
- Release an owned SCO request after a call even if the monitor's local route-active flag reset.
- Yield automatic routing for all non-normal audio modes, including screening and redirected calls.
- Correct native LE Audio headset/speaker/broadcast output identifiers.
- Remove a deprecated A2DP method call that has no effect on the Android implementation examined.
- Prioritize Bluetooth/communication evidence in diagnostics, bound verbose sections, reuse audio
  snapshots, retain the actual Pixel focus stack, and always mark overall output overflow.

## Signing and installation

The main app and both helpers use certificate SHA-256
`30f2b9d78da9ceda52152550df4af164404f545d21bfca6a519825e5fbb1c739`.
The new main app retains that certificate and increments its version to 0.2.1, code 3.

The supplied Trust Hook uses a different certificate:
`015fb2c9528859464613f0373f89614eb0659a1e183d8a5241f3a5db8932064b`.
The local recovery build verifies the hook source but uses this machine's debug certificate;
it cannot update that desktop-signed hook in place. The recovery bundle therefore contains the
original, untouched signed Trust Hook 0.6.0, not the locally re-signed verification build.

For the existing four-app installation, update only the main AAARP app. Keep the installed
Trust Hook 0.6.0 and both Volume 0.1.0 helpers. No uninstall or settings import is required by
this update. No signing private keys are included in the source backup.

## Verification and limits

`testDebugUnitTest assembleDebug lintDebug` succeeds across all modules: 110 tests pass with no
failures or skips, and lint reports no errors. The four application APKs build successfully.
The install set's original signed hook and both helpers retain their reference hashes; the main
APK source and Proton Drive destination are compared with SHA-256 before handoff.

No phone was connected for this work. Installation, Pixel listening behavior, call audio in both
directions, return to music, and Android Auto dock behavior still require device testing.

## Rolling-history follow-up: AAARP 0.2.2

Version 0.2.2 (code 4) retains the recovered baseline and Pixel fixes, and adds the private
48-hour history described in README.md. Save Diagnostics now streams that history and a fresh
snapshot into one text file. Bluetooth events do not create automatic exports. Background
capture includes route, mode, playback, SCO, and connection transitions, plus two-minute root
snapshots while root support and the monitor are enabled.

The app build, all 120 app unit tests, and app lint pass with no errors. The unchanged Hook and
Volume modules retain their earlier verification and signed artifacts. The recovery archive
was refreshed with the current source and main APK; only the main app needs updating.
