Alright so this may be a niche issue but I vibe coded a solution and looking for testers or if anyone wants to play with the code, whatever, free to use.

I did use AI to make this



AAARP is aimed at solving an issue for me. I use aftermarket wireless android auto headunits in multiple cars and now on my motorcycle (gets my phone off the handlebars and still lets me answer calls and listen to music) Anyways, the problem is android auto doesn't allow me to route audio as cleanly as I want to. In the cars it works okay, sometimes playing through the phone speaker instead of the bluetooth radio BUT the biggest issue is when using it on my motorcycle it defaults to routing everything through the phone speaker and if I switch to "bluetooth" it routes it to my watch not my earbuds. That's where this app comes in with root permissions it hooks into the system after android auto hijacks all audio and lets you switch outputs down to specific bluetooth devices.

Current watchdog behavior: pick a default Bluetooth audio target, start the monitor, and AAARP waits until Android Auto is running and that target is actually connected before it routes anything. If the target is not connected, AAARP leaves Android Auto alone, which is useful when another car/headunit already behaves correctly. For set-and-forget use, leave the monitor running with "Restore monitor after reboot" enabled and request the battery exemption.

With "Avoid call-quality Bluetooth during media playback" enabled, the monitor keeps its
Bluetooth SCO route paused through playback gaps of up to 15 seconds. This avoids reopening
the call-quality path between songs. Media-start callbacks release AAARP's own SCO request
before the next full monitor pass. After a call, it can also release a SCO request it still
owns when music resumes. Automatic audio changes yield whenever Android leaves normal audio
mode, including call screening and redirected calls. Device diagnostics prioritize current
communication/Bluetooth state and bound verbose sections so they survive large Pixel dumps.

Profiles: use "Detect AA" while connected to a wireless Android Auto unit, tune the settings for that unit, then tap "Save Profile". AAARP maps the current Wi-Fi Android Auto identity to that profile and uses it automatically the next time that same unit is detected. If no saved profile matches, the Default profile is used.

GPS warm-up: the optional "Warm up GPS when Android Auto starts" profile setting briefly asks Android's GPS provider for a fresh fix when Android Auto is first detected, then stops listening. This cannot force Google Maps to accept a location, but it can wake the phone's satellite location path before Maps times out. For phone-in-pocket startup, grant Precise Location and set Location to "Allow all the time" in Android's app permission screen.

## Loudness leveling (reducing song-to-song jumps)

"Keep songs at a consistent loudness" treats the volume selected with the phone, earbuds,
steering-wheel controls, or AAARP's volume shortcuts as the listener's master setting.
The leveler does not set, raise, restore, or otherwise fight Android's `STREAM_MUSIC`
volume. If the listener presses a volume button, that new setting remains authoritative.

Leveling happens inside the audio signal. Gentle, Strong, and Max never add signal gain.
They use one stereo-linked, window-RMS stage to reduce louder audio toward a quiet anchor;
a window passes unchanged when neither linked channel drives the detector above that anchor.
Both stereo channels receive the same reduction, so the leveler does not pull a left/right
image toward the center. This internal attenuation is separate from Android's volume setting.
Start music, let the leveler attach, and then choose the listening volume you want.

This is a real-time processor on Android's global audio session. It does not scan tracks,
measure integrated LUFS, read ReplayGain, detect true peaks, or know where one song ends and
the next begins. It is therefore an approximation: it should reduce jumps, but it cannot
guarantee identical perceived loudness for every recording or every output. The off-device
scalar model reduces a representative -28 versus -8 dBFS steady-state RMS gap to 8 dB on
Gentle, no more than 1 dB on Strong, and no more than 0.5 dB on Max. Those are curve
calculations, not PCM measurements, peak-safety proof, or device listening results.

The leveler activates only while Android reports media/game playback. Once active, it stays
attached through ordinary pauses and song changes for up to three minutes so it does not
drop back to unprocessed audio between tracks. With Gentle, Strong, or Max already attached,
a notification, navigation prompt, or UI sound during an existing song-gap hold no longer
tears the leveler down; because session zero is global, that standalone prompt also passes
through the attached processor. A prompt by itself never starts a leveler that was not
already active, and the legacy Gain-only boost is not preserved over a prompt-only gap.
Alarms, calls, ringtones, and Assistant audio still suspend it. If a prompt mixes over active
music, AAARP likewise keeps the song path stable instead of cycling the processor off and
on. **Also level songs outside Android Auto** defaults on so earbuds are leveled while music
is playing before driving.
Changing from earbuds to a car system can still change physical loudness because the two
outputs have different amplifiers and acoustics.

Available profile-specific modes are:

- **Off:** no loudness processing.
- **Gain only:** the legacy flat boost; it makes everything louder but does not match songs.
- **Gentle:** lighter correction that preserves more of each recording's original dynamics.
- **Strong:** the recommended balance for mixed playlists.
- **Max:** the most aggressive matching for very uneven sources.

The reviewed curves are:

| Mode | RMS anchor/threshold | Ratio | Attack | Release | Signal gain |
| --- | --- | --- | --- | --- | --- |
| Gentle | -24 dBFS | 4:1 | 1 ms | 140 ms | zero or negative |
| Strong | -28 dBFS | 24:1 | 1 ms | 100 ms | zero or negative |
| Max | -34 dBFS | 50:1 | 1 ms | 100 ms | zero or negative |

Start on **Strong**. Drop to Gentle if music sounds squashed or pumps audibly; use Max only
when quieter and louder recordings still do not meet closely enough. Lowering the anchor
extends matching to quieter material, but also turns normal music down farther and may leave
too little downstream earbud/car volume. The short release is intended to settle a modeled
loud-to-quiet transition in roughly 700 ms, but it can also react to dynamics within a song;
only real playback testing can validate the vendor audio effect. Each saved Android Auto
profile keeps its own mode. Existing installs and restored legacy backups migrate
automatically: either state of the old experimental dynamics checkbox becomes Strong so an
enabled normalizer actually matches songs. Gain only remains available as an explicit manual
legacy choice. The retired media-volume floor is deleted during migration and is not
available in the interface.

If dynamics processing is unavailable, Gentle, Strong, and Max bypass processing and
report the failure while AAARP retries. They never silently turn into a flat boost.
`LoudnessEnhancer` is used only when **Gain only** is selected explicitly. Avoid stacking
multiple aggressive normalizers unless that combination has been tested on the device,
because two independent levelers can pump or over-correct.

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
