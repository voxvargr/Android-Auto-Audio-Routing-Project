Alright so this may be a niche issue but I vibe coded a solution and looking for testers or if anyone wants to play with the code, whatever, free to use.

I did use AI to make this



AAARP is aimed at solving an issue for me. I use aftermarket wireless android auto headunits in multiple cars and now on my motorcycle (gets my phone off the handlebars and still lets me answer calls and listen to music) Anyways, the problem is android auto doesn't allow me to route audio as cleanly as I want to. In the cars it works okay, sometimes playing through the phone speaker instead of the bluetooth radio BUT the biggest issue is when using it on my motorcycle it defaults to routing everything through the phone speaker and if I switch to "bluetooth" it routes it to my watch not my earbuds. That's where this app comes in with root permissions it hooks into the system after android auto hijacks all audio and lets you switch outputs down to specific bluetooth devices.

Current watchdog behavior: pick a default Bluetooth audio target, start the monitor, and AAARP waits until Android Auto is running and that target is actually connected before it routes anything. If the target is not connected, AAARP leaves Android Auto alone, which is useful when another car/headunit already behaves correctly. For set-and-forget use, leave the monitor running with "Restore monitor after reboot" enabled and request the battery exemption.

Profiles: use "Detect AA" while connected to a wireless Android Auto unit, tune the settings for that unit, then tap "Save Profile". AAARP maps the current Wi-Fi Android Auto identity to that profile and uses it automatically the next time that same unit is detected. If no saved profile matches, the Default profile is used.

Android Auto sound tweaks are experimental root options. Notification routing tries to move Android's sonification strategy to speaker, earpiece, or the saved Bluetooth target while Android Auto is active, then clears it afterward. The ducking option temporarily blocks SystemUI audio focus while Android Auto is active, and can optionally stay active outside Android Auto while the monitor is running. Notification sounds can also be muted during media playback, with an optional always-on mode for non-Android Auto listening.



The audio switcher solutions I found either didn't work with my device (Red Magic 11 Pro) or didn't work on android 16 so hence this project was born and the name, surprisingly, wasn't AI picked, I picked it myself and laughed pretty good at it
