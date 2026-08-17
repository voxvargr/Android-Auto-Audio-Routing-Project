Alright so this may be a niche issue but I vibe coded a solution and looking for testers or if anyone wants to play with the code, whatever, free to use.

I did use AI to make this



AAARP is aimed at solving an issue for me. I use aftermarket wireless android auto headunits in multiple cars and now on my motorcycle (gets my phone off the handlebars and still lets me answer calls and listen to music) Anyways, the problem is android auto doesn't allow me to route audio as cleanly as I want to. In the cars it works okay, sometimes playing through the phone speaker instead of the bluetooth radio BUT the biggest issue is when using it on my motorcycle it defaults to routing everything through the phone speaker and if I switch to "bluetooth" it routes it to my watch not my earbuds. That's where this app comes in with root permissions it hooks into the system after android auto hijacks all audio and lets you switch outputs down to specific bluetooth devices.

Current watchdog behavior: pick a default Bluetooth audio target, start the monitor, and AAARP waits until Android Auto is running and that target is actually connected before it routes anything. If the target is not connected, AAARP leaves Android Auto alone, which is useful when another car/headunit already behaves correctly. For set-and-forget use, leave the monitor running with "Restore monitor after reboot" enabled and request the battery exemption.

Profiles: use "Detect AA" while connected to a wireless Android Auto unit, tune the settings for that unit, then tap "Save Profile". AAARP maps the current Wi-Fi Android Auto identity to that profile and uses it automatically the next time that same unit is detected. If no saved profile matches, the Default profile is used.

GPS warm-up: the optional "Warm up GPS when Android Auto starts" profile setting briefly asks Android's GPS provider for a fresh fix when Android Auto is first detected, then stops listening. This cannot force Google Maps to accept a location, but it can wake the phone's satellite location path before Maps times out. For phone-in-pocket startup, grant Precise Location and set Location to "Allow all the time" in Android's app permission screen.

Android Auto sound tweaks are experimental root options. Notification routing tries to move Android's sonification strategy to speaker, earpiece, or the saved Bluetooth target while Android Auto is active, then clears it afterward. The ducking option temporarily blocks SystemUI audio focus while Android Auto is active, and can optionally stay active outside Android Auto while the monitor is running. Notification sounds can also be muted during media playback, with an optional always-on mode for non-Android Auto listening.

## Android Auto volume controls

The sideloaded build also exposes an Android Auto media entry named **AAARP Volume**. Install the APK on the phone, enable Android Auto developer mode and **Unknown sources**, then reconnect Android Auto. If the entry is hidden, open Android Auto's **Customize launcher** screen on the phone and enable or reorder **AAARP Volume**. Menu names can vary slightly by Android Auto version.

AAARP first tries two custom browse actions: one-step phone media volume down and up. That path is used only when the Android Auto host advertises at least two custom-action slots. The 2026-08-17 DHU/connected-device trace with Gearhead `17.3.662854-release` reported `custom_action_limit=0`, so that host cannot render either browse button. AAARP logs the advertised limit because this is a host capability, not something the APK can raise.

### Optional media relay for hosts with fewer than two browse actions

The fallback is **off by default for every new or unknown profile**. To set it up:

1. In AAARP on the phone, detect or select the Android Auto profile you want to change.
2. Enable **Mirror current phone media in Android Auto**. The checkbox is saved only for that profile.
3. Check the visible **Notification Access** status and tap **Open Notification Access**.
4. On Android 13 or newer, a sideloaded APK may show a restricted-setting warning. Open Android Settings > Apps > AAARP, open the menu, choose **Allow restricted settings**, then return to Notification Access and grant AAARP access. Only do this for an APK/build you trust.
5. Reconnect Android Auto or reopen AAARP Volume if the head unit cached the old browser state.

Keep AAARP's routing monitor running when using a connection-specific relay setting. The monitor supplies the currently detected Android Auto profile to the media shell. If no connection has been detected, the shell deliberately checks the Default profile instead of inheriting whichever profile was last selected in the phone UI.

Notification Access is powerful: Android allows an enabled listener to see notifications. AAARP's limited use of that access is to ask Android for the currently active media sessions. The relay does not inspect notification content, store or transmit notification data, request Accessibility access, or add a runtime permission. AAARP does not send notification data off-device or to a server; media-session metadata is, by design, presented to Android Auto.

When enabled and access is granted, AAARP can publish a mirror of the current phone player so Android Auto has a playback screen where volume actions can be shown. Metadata and transport commands are relayed between media sessions; audio is not recorded, decoded, copied, or played by AAARP. The relay does not request audio focus and does not play silent audio, so it should not pause or duck the real player through Android's audio-focus system.

The relay uses the two side positions around Android Auto's Play/Pause button as a small control menu. Its normal layout is **Volume controls | Play/Pause | Track controls**. Choosing Volume controls temporarily changes the sides to **Volume down | Play/Pause | Volume up**; choosing Track controls changes them to the current player's supported **Previous | Play/Pause | Next** actions. Repeated presses keep that submenu open, and 10 seconds without a related press returns to the normal menu. The mode is process-local, is never saved into a profile, and uses one cancelable main-thread timeout rather than a polling loop. Android Auto and the head-unit maker still decide which controls a minimized Home card renders, so confirm dynamic redraws on each real head unit.

There is still a media-session takeover tradeoff. While the relay surface is active, Android Auto can treat AAARP as the selected media app and send dashboard, steering-wheel, or voice transport commands to AAARP for forwarding. Test while parked with the players you use. The normal and Volume layouts temporarily withhold standard Previous/Next actions so their two side positions can hold AAARP's custom controls; Track mode advertises only the skip directions supported by the mirrored player. Switching to another Android Auto media app should replace the visible surface, but Android Auto can cache media-session state. Disabling the checkbox or revoking Notification Access stops the relay; reconnect Android Auto if its UI remains cached. To disable only one head unit, select that profile and clear the checkbox. To disable the feature for every profile regardless of its saved toggle, revoke AAARP under Android's Notification Access settings. Settings backups include each profile's opt-in, but Android's Notification Access grant is not backed up or restored by AAARP.

Each successful action changes the phone's `STREAM_MUSIC` level by one Android volume step. That may affect media routed through the phone, Bluetooth absolute volume, or Android Auto, but it is not the same thing as the head unit's separate amplifier/knob volume. A fixed-volume device or route can also ignore the request.

When **Use root** is enabled, each Android Auto volume press uses one bounded root command against Android's music stream instead of the background `AudioManager` call. This is the more reliable path on rooted phones and avoids Android 17's background-volume restriction; AAARP never sends both commands for one press. With root disabled, keep the routing monitor running on Android 17 so AAARP has the qualifying foreground-service state required for the normal Android API. Root can make the volume adjustment reliable, but it cannot force an Android Auto host to display a control that the host does not support.

The relay adds no polling loop, wake lock, alarm, network activity, audio focus, or foreground service. Android keeps AAARP's Notification Access service bound as the media-session credential, but AAARP observes active media sessions and retains mirrored artwork only while an enabled Android Auto relay surface is using them. The Android Auto volume service, media session, and volume worker are released when the host disconnects. Existing routing-monitor and optional GPS warm-up battery behavior is unchanged.

Platform references: [custom browse actions](https://developer.android.com/training/cars/media/create-media-browser/custom-browse-actions), [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService), [restricted settings](https://support.google.com/android/answer/12623953), and [Android 17 background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio).



The audio switcher solutions I found either didn't work with my device (Red Magic 11 Pro) or didn't work on android 16 so hence this project was born and the name, surprisingly, wasn't AI picked, I picked it myself and laughed pretty good at it
