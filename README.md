# AAARP

Android Auto Audio Routing Project.

AAARP is a root-aware Android app prototype for keeping a chosen Bluetooth communication device preferred while Android Auto is running.

## Current state

This first version builds the app-side control layer:

- Lists available communication audio devices.
- Applies the selected device with `AudioManager.setCommunicationDevice()` on Android 12+.
- Falls back to legacy Bluetooth SCO routing on older supported devices.
- Runs a foreground monitor that reapplies the selected route every few seconds.
- Checks root and collects Android audio-service diagnostics for device-specific routing work.

## Important routing reality

Android Auto, phone calls, Bluetooth HFP/SCO, and media playback use different audio policy paths. A normal app can request the preferred communication device, but it cannot reliably force all other apps' media, phone-call audio, or microphone routing once Android Auto or the system phone stack takes priority.

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
3. Open AAARP, grant Bluetooth/notification permissions, refresh devices, select the Bluetooth communication route, and tap Apply.
4. Start Monitor before launching Android Auto.
5. Enable Root diagnostics and run Diagnostics while Android Auto is active.

The diagnostics output is the key input for the next implementation pass: it tells us whether your ROM exposes a command-line route control we can automate, or whether the project needs a Magisk/LSPosed style backend.

## References

- Android `AudioManager` communication-device APIs: https://developer.android.com/reference/android/media/AudioManager
- Android `AudioDeviceInfo` Bluetooth device types: https://developer.android.com/reference/android/media/AudioDeviceInfo
- Android 16 SDK notes: https://developer.android.com/about/versions/16
- Android Gradle Plugin 9.2.0 notes: https://developer.android.com/build/releases/agp-9-2-0-release-notes
