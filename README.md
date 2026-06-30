# AAARP

Android Auto Audio Routing Project.

AAARP is a root-aware Android app prototype for keeping a chosen Bluetooth communication device preferred while Android Auto is running.

## Current state

This first version builds the app-side control layer:

- Lists available communication audio devices.
- Shows saved/paired Bluetooth devices by their Android Bluetooth alias where available, such as `VoxVargr Buds`.
- Shows Bluetooth route names, audio addresses, paired devices, aliases, and profile state where Android exposes them.
- Lets you select a saved Bluetooth device first, with an optional custom fallback such as `Shokz` or part of a Bluetooth address.
- Applies the selected device with `AudioManager.setCommunicationDevice()` on Android 12+.
- Falls back to legacy Bluetooth SCO routing on older supported devices.
- Runs a foreground monitor that checks the selected route and only reapplies it when Android changes it.
- Watchdog mode waits for Android Auto before touching audio, and can optionally stop itself after Android Auto disconnects.
- Checks root and collects Android audio-service diagnostics for device-specific routing work.
- Saves diagnostics to `Downloads/AAARP` for easier sharing.

## Important routing reality

Android Auto, phone calls, Bluetooth HFP/SCO, and media playback use different audio policy paths. A normal app can request the preferred communication device, but it cannot reliably force all other apps' media, phone-call audio, or microphone routing once Android Auto or the system phone stack takes priority.

The main win condition for the bike setup is that Android exposes the Shokz earbuds as their own communication route. AAARP now pulls the saved Bluetooth devices first, so you can pick the same friendly name shown in Android settings. If AAARP can map that saved device to an audio route named like the Shokz, or a Bluetooth audio address that matches the earbuds in the inventory panel, the foreground monitor can keep selecting that exact route. If Android only exposes one generic Bluetooth route such as `Bluetooth SCO - 00:00:00:00:00:00` and internally maps that route to the watch, the public API cannot pick the earbuds by BluetoothDevice directly; that is where the root backend comes in.

Bluetooth SCO is the phone-call audio transport. Some head units show a fake or placeholder call when SCO is active, even when Android Auto does not show a real call. If the monitor causes periodic silence, it usually means the route is being reset too often; the monitor now checks first and only reapplies when the current communication route is wrong.

Watchdog mode needs root process visibility to know when Android Auto is running. If Watch Android Auto is enabled, AAARP idles until it sees the Android Auto process, then maintains the selected route. If Auto-stop after Android Auto disconnects is enabled, the foreground service stops after Android Auto disappears for several checks. If you want AAARP to be ready for the next ride without opening the app, leave auto-stop off and grant the battery optimization exemption.

The stronger version will likely need one of these root/system approaches after testing on your phone:

- A Magisk module or privileged system app that can use hidden audio policy APIs.
- A root shell backend using the `cmd audio` commands exposed by your Android build.
- An LSPosed/Xposed hook into Android's audio service if your ROM does not expose usable shell controls.

AAARP is visible to the user by design. The goal is not to hide microphone use from you; the goal is to make the selected route harder for Android Auto to reclaim.

## Android version target

The project targets Android 16 / API 36 and currently uses `minSdk 23`. The practical routing APIs used here are:

- `AudioDeviceInfo` and device enumeration from API 23.
- `AudioManager.setCommunicationDevice()` from API 31.
- Legacy Bluetooth SCO APIs for older fallback behavior.

If "Android 16+" meant API level 16 instead of Android OS version 16, the app can be lowered later, but useful Bluetooth device selection will still require guarded fallbacks.

## Build

Open this folder in Android Studio and let Gradle sync. The project is configured for:

- Android Gradle Plugin 9.2.0
- Compile SDK 36
- Target SDK 36
- JDK 17

This workspace did not have Java, Gradle, Android SDK, or ADB on the command path, so I could not build an APK locally from here.

## First test pass

1. Install on the rooted phone.
2. Pair the Bluetooth device you want to force.
3. Open AAARP, grant Bluetooth/notification permissions, and refresh devices.
4. Select the saved Bluetooth target, for example `VoxVargr Buds`.
5. Optionally type `Shokz`, `OpenFit`, or part of the Bluetooth address in Custom fallback.
6. Check whether the Bluetooth inventory maps that saved device to an available AAARP route.
7. Tap Apply and confirm the Current communication route changes to the earbuds.
8. Leave Watch Android Auto enabled.
9. Use Battery Exempt to let the foreground watchdog keep running reliably.
10. Start Monitor before launching Android Auto.
11. Enable Auto-stop after Android Auto disconnects only if you want AAARP to shut itself down at the end of the ride.
12. Use Save Diagnostics while Android Auto is active if Android Auto still steals the route or Android only shows a generic `00:00:00:00:00:00` Bluetooth route.

The diagnostics output is the key input for the next implementation pass: it tells us whether your ROM exposes a command-line route control we can automate, or whether the project needs a Magisk/LSPosed style backend.

## References

- Android `AudioManager` communication-device APIs: https://developer.android.com/reference/android/media/AudioManager
- Android `AudioDeviceInfo` Bluetooth device types: https://developer.android.com/reference/android/media/AudioDeviceInfo
- Android 16 SDK notes: https://developer.android.com/about/versions/16
- Android Gradle Plugin 9.2.0 notes: https://developer.android.com/build/releases/agp-9-2-0-release-notes
