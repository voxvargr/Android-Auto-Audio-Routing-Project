Alright so this may be a niche issue but I vibe coded a solution and looking for testers or if anyone wants to play with the code, whatever, free to use.

I did use AI to make this



AAARP is aimed at solving an issue for me. I use aftermarket wireless android auto headunits in multiple cars and now on my motorcycle (gets my phone off the handlebars and still lets me answer calls and listen to music) Anyways, the problem is android auto doesn't allow me to route audio as cleanly as I want to. In the cars it works okay, sometimes playing through the phone speaker instead of the bluetooth radio BUT the biggest issue is when using it on my motorcycle it defaults to routing everything through the phone speaker and if I switch to "bluetooth" it routes it to my watch not my earbuds. That's where this app comes in with root permissions it hooks into the system after android auto hijacks all audio and lets you switch outputs down to specific bluetooth devices.

Current watchdog behavior: pick a default Bluetooth audio target, start the monitor, and AAARP waits until Android Auto is running and that target is actually connected before it routes anything. If the target is not connected, AAARP leaves Android Auto alone, which is useful when another car/headunit already behaves correctly. For set-and-forget use, leave the monitor running with "Restore monitor after reboot" enabled and request the battery exemption.

Profiles: use "Detect AA" while connected to a wireless Android Auto unit, tune the settings for that unit, then tap "Save Profile". AAARP maps the current Wi-Fi Android Auto identity to that profile and uses it automatically the next time that same unit is detected. If no saved profile matches, the Default profile is used.

GPS warm-up: the optional "Warm up GPS when Android Auto starts" profile setting briefly asks Android's GPS provider for a fresh fix when Android Auto is first detected, then stops listening. This cannot force Google Maps to accept a location, but it can wake the phone's satellite location path before Maps times out. For phone-in-pocket startup, grant Precise Location and set Location to "Allow all the time" in Android's app permission screen.

Android Auto sound tweaks are experimental root options. Notification routing tries to move Android's sonification strategy to speaker, earpiece, or the saved Bluetooth target while Android Auto is active, then clears it afterward. The ducking option temporarily blocks SystemUI audio focus while Android Auto is active, and can optionally stay active outside Android Auto while the monitor is running. Notification sounds can also be muted during media playback, with an optional always-on mode for non-Android Auto listening.

## Android Auto volume controls

The old **AAARP Volume** media shell and its Notification Access relay have been removed. AAARP no longer advertises itself as a media player, so the real music app keeps its own Android Auto card, playback session, steering-wheel commands, and artwork.

The project now builds two separate helper APKs named **Volume −** and **Volume +**. Each helper owns its bold launcher/dock icon. Opening a helper from the full launcher remains a fallback that sends a signature-protected request to AAARP and immediately closes. The helper does not create a media session, request audio focus, inspect notifications, or relay playback.

Build and install the four debug APKs, with the main AAARP APK first:

1. `app/build/outputs/apk/debug/app-debug.apk`
2. `volume-down/build/outputs/apk/debug/volume-down-debug.apk`
3. `volume-up/build/outputs/apk/debug/volume-up-debug.apk`
4. `aa-trust-hook/build/outputs/apk/debug/aa-trust-hook-debug.apk`

Android Auto owns the bottom app dock. Its native four positions are navigation, media, communication, and one additional recent app; it has no public pinning API and stores only one additional recent app. On the tested Android Auto 17.3 build, the optional root/LSPosed module leaves the navigation and media feeds alone, then substitutes only the final displayed third and fourth items with **Volume +** and **Volume −**. This produces Maps / YouTube Music / Volume + / Volume − without adding a fragile fifth view or changing the real music session. Dock taps are intercepted before Android Auto launches the helper: while Android Auto is actively handling the tap, the hook makes one short authenticated call to the already battery-exempt main AAARP package. AAARP queues its existing background volume step and the current map/dashboard screen stays open. Keeping the command bridge in the main package avoids the RedMagic screen-off policy that force-stops the two icon-only helpers. Android Auto still maintains its normal Phone and recent-app state underneath the two displayed substitutions.

This is a private, device-specific prototype. Android Auto has no official volume-utility category, so both helpers use an IoT declaration that does not truthfully fit a phone-volume utility and is not Google Play-review compliant. The LSPosed module is statically scoped only to Android Auto, spoofs install-source trust only for the two exact helper packages, and applies a version-specific dock-binding hook; an Android Auto update can make that hook fail closed until its obfuscated classes are checked again. Enable the module for Android Auto and reboot before testing. Google's **Unknown sources** developer option does not apply to Car App Library apps. Do not replace the helpers with a media-browser auto-action: Android Auto probes and loads media services without a user tap, which could cause phantom volume changes.

Each successful action changes the phone's `STREAM_MUSIC` level. That can affect phone, Bluetooth absolute volume, or Android Auto audio, but it cannot directly control a head unit's separate amplifier/knob volume. With **Use root for diagnostics and volume shortcuts** enabled, AAARP uses one bounded root command; otherwise it uses Android's normal audio API. The routing monitor recognizes shortcut changes as manual choices and does not restore over them.

Platform references: [Android Auto testing and trusted-source rules](https://developer.android.com/training/cars/testing), [Car App Library setup and categories](https://developer.android.com/training/cars/apps/library/set-up-project), [car app lifecycle](https://developer.android.com/training/cars/apps/library/lifecycles), and [`finishCarApp()`](https://developer.android.com/reference/androidx/car/app/CarContext#finishCarApp()).



The audio switcher solutions I found either didn't work with my device (Red Magic 11 Pro) or didn't work on android 16 so hence this project was born and the name, surprisingly, wasn't AI picked, I picked it myself and laughed pretty good at it
