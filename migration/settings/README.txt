HISTORICAL AAARP SETTINGS FALLBACK

AAARP-historical-settings-20260803.json is an importable settings backup derived
from the preserved August 3, 2026 aaarp-prefs.xml capture. It is not a fresh phone
export and does not establish the phone's current settings or access grants.

PREFER A FRESH EXPORT
Before changing APK signing keys or uninstalling AAARP, open the currently
installed app and use Backup Settings to create a fresh in-app export. Copy
that export and current logs somewhere safe. Prefer that new export to this
historical fallback whenever it is available.

USE THIS FALLBACK ONLY IF NEEDED
1. Save a backup of any settings you want to keep. Restore Settings REPLACES
   existing SharedPreferences: the current implementation calls editor.clear().
2. Stop Monitor before restoring if it is running. Copy the JSON to a document
   location accessible by the phone and choose it with AAARP's Restore Settings.
3. Review profiles and Bluetooth targets. Old addresses, route IDs and Wi-Fi
   identities may require reselection on the current device/environment.
4. Grant/check required Android permissions, root-manager approval and battery
   settings on the current phone. This derived file contains no access snapshot
   and cannot reproduce grants or a battery optimization exemption.
5. Start Monitor manually when ready, then verify actual routing during Android
   Auto, media playback, calls and disconnect. Import omits monitor_enabled and
   does not start monitoring for you. It also does not stop an already-running
   service, which is why step 2 matters.

FORMAT AND VERIFICATION
The format matches the active SettingsBackup.java implementation:
format: dev.voxvargr.aaarp.settings
version: 1
preferences: typed entries (string / boolean / stringSet)

Source XML has 80 entries. JSON retains 79: 26 strings, 52 booleans and one
stringSet. monitor_enabled is excluded to match shouldBackupKey(). Legacy keys
are also excluded using the exact AppPrefs.isLegacyMediaRelayKey rule:
media_relay_enabled, or keys starting profile_ and ending _media_relay_enabled.
No such legacy keys or unsupported XML types were present in this source.
All 79 retained keys, types and values were independently checked against the
source XML after writing and reparsing the JSON. Set values were checked as sets.

No createdAtMillis or access block is invented. The extra provenance object
records the historical source path/date/hash and conversion exclusions; the
current restore code ignores this metadata. The capture date is inferred from
the source capture directory, not a live phone observation.

The raw source XML and 107-file preservation manifest are unchanged. The JSON
is a derived convenience file and is not listed in PRESERVED-FILES.sha256,
which covers only original preserved copies. It still contains personal
Bluetooth/Wi-Fi/profile identifiers from the raw source, so publishing the JSON
also publishes those identifiers.
