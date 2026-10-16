package dev.voxvargr.aaarp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

final class AudioRouteController {
    private static final int GLOBAL_AUDIO_SESSION = 0;

    private final Context context;
    private final AudioManager audioManager;
    private final RootShell rootShell;
    private LoudnessEnhancer mediaBoost;
    private DynamicsProcessing dynamicsBoost;
    private MediaLevelerActivation activeMediaLevelerActivation =
            MediaLevelerActivation.off(MediaLevelerProfile.MODE_OFF, "not_requested");
    private String ownedCommunicationRouteKey;

    AudioRouteController(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.rootShell = new RootShell();
    }

    List<RouteDevice> listRouteDevices() {
        List<RouteDevice> devices = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
                devices.add(RouteDevice.from(device));
            }
        } else {
            for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                RouteDevice routeDevice = RouteDevice.from(device);
                if (routeDevice.isBluetooth()) {
                    devices.add(routeDevice);
                }
            }
        }

        if (devices.isEmpty()) {
            devices.add(RouteDevice.legacyBluetoothSco());
        }
        return devices;
    }

    boolean isPreferredBluetoothTargetConnected(String preferredBluetoothTarget) {
        if (!hasPreferredTarget(preferredBluetoothTarget)) {
            return false;
        }

        try {
            for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS | AudioManager.GET_DEVICES_OUTPUTS)) {
                RouteDevice routeDevice = RouteDevice.from(device);
                if (routeDevice.isBluetooth() && routeDevice.matchesTarget(preferredBluetoothTarget)) {
                    return true;
                }
            }
        } catch (SecurityException e) {
            return false;
        }
        return false;
    }

    AndroidAutoConnection currentAndroidAutoConnection() {
        try {
            return AndroidAutoConnection.detect(context, rootShell);
        } catch (RuntimeException e) {
            return AndroidAutoConnection.fallback();
        }
    }

    RoutingResult applyPreferredRoute(String selectedKey) {
        return applyPreferredRoute(selectedKey, null);
    }

    RoutingResult applyPreferredRoute(String selectedKey, String preferredBluetoothTarget) {
        return applyPreferredRoute(selectedKey, preferredBluetoothTarget, true);
    }

    RoutingResult maintainPreferredRoute(String selectedKey, String preferredBluetoothTarget) {
        return applyPreferredRoute(selectedKey, preferredBluetoothTarget, false);
    }

    private RoutingResult applyPreferredRoute(String selectedKey, String preferredBluetoothTarget, boolean forceApply) {
        List<RouteDevice> routeDevices = listRouteDevices();
        RouteDevice selected = findSelected(routeDevices, selectedKey, preferredBluetoothTarget);
        StringBuilder log = new StringBuilder();

        if (isCallOrCommunicationActive()) {
            log.append("Call/ring/communication mode is active; AAARP left the route alone.\n");
            log.append("Audio mode: ").append(audioModeSummary()).append('\n');
            return new RoutingResult(false, log.toString());
        }

        if (hasPreferredTarget(preferredBluetoothTarget)) {
            log.append("Preferred Bluetooth target: ").append(formatPreferredTarget(preferredBluetoothTarget)).append('\n');
            if (!selected.matchesTarget(preferredBluetoothTarget)) {
                log.append("No available Bluetooth route matched that saved device. ");
                log.append("Android is probably exposing only a generic Bluetooth SCO route right now.\n");
                appendBluetoothRoutes(log, routeDevices);
            }
        }
        log.append("Selected route: ").append(selected.detailLabel()).append('\n');
        log.append("Android Auto installed: ").append(AndroidAutoStatus.isInstalled(context) ? "yes" : "no").append('\n');

        if (!forceApply && isCurrentRoute(selected)) {
            log.append("Monitor check: selected route is already current; no reset needed.\n");
            return new RoutingResult(true, log.toString());
        }

        log.append("Public route layer: ");

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && selected.isRealDevice()) {
                AudioDeviceInfo device = findCommunicationDevice(selected);
                if (device == null) {
                    log.append("device disappeared before routing.\n");
                    return new RoutingResult(false, log.toString());
                }
                boolean accepted = audioManager.setCommunicationDevice(device);
                ownedCommunicationRouteKey = accepted ? selected.key() : null;
                log.append(accepted ? "accepted" : "rejected").append('\n');
                log.append("Current communication device: ").append(currentCommunicationDevice()).append('\n');
                return new RoutingResult(accepted, log.toString());
            }

            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            applyLegacyBluetoothSco(log);
            ownedCommunicationRouteKey = selected.key();
            return new RoutingResult(true, log.toString());
        } catch (SecurityException e) {
            log.append("permission blocked: ").append(e.getMessage()).append('\n');
            return new RoutingResult(false, log.toString());
        } catch (RuntimeException e) {
            log.append("failed: ").append(e.getMessage()).append('\n');
            return new RoutingResult(false, log.toString());
        }
    }

    void clearRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice();
        } else {
            clearLegacyBluetoothSco();
        }
        ownedCommunicationRouteKey = null;
    }

    boolean clearOwnedRouteIfCurrentBluetoothSco() {
        if (ownedCommunicationRouteKey == null || !isCurrentCommunicationRouteOwned()) {
            return false;
        }
        if (!isCurrentCommunicationRouteBluetoothSco()) {
            return false;
        }
        clearRoute();
        return true;
    }

    String currentCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo device = audioManager.getCommunicationDevice();
            if (device == null) {
                return "none";
            }
            return RouteDevice.from(device).displayLabel();
        }
        return isBluetoothScoOn() ? "Bluetooth SCO" : "platform default";
    }

    String inputRouteSummary() {
        List<RouteDevice> inputDevices = listInputDevices();
        if (inputDevices.isEmpty()) {
            return "- none";
        }
        StringBuilder summary = new StringBuilder();
        for (RouteDevice device : inputDevices) {
            summary.append("- ").append(device.detailLabel()).append('\n');
        }
        return summary.toString();
    }

    String compactInputRouteSummary() {
        List<RouteDevice> inputDevices = listInputDevices();
        if (inputDevices.isEmpty()) {
            return "none";
        }
        StringBuilder summary = new StringBuilder();
        for (RouteDevice device : inputDevices) {
            if (summary.length() > 0) {
                summary.append("; ");
            }
            summary.append(device.displayLabel());
        }
        String value = summary.toString();
        return value.length() <= 260 ? value : value.substring(0, 257) + "...";
    }

    RootShell.ShellResult rootDiagnostics() {
        return rootShell.diagnostics();
    }

    RootShell.ShellResult autoLogSnapshot() {
        return rootShell.autoLogSnapshot();
    }

    RootShell.ShellResult resetBluetoothWithRoot() {
        return rootShell.resetBluetooth();
    }

    RootShell.ShellResult applyAndroidAutoAudioTweaks(String notificationRouteMode,
                                                      String selectedKey,
                                                      String preferredBluetoothTarget,
                                                      boolean suppressDucking,
                                                      boolean pinMediaToBluetooth) {
        RouteDevice notificationDevice = notificationRouteDevice(
                notificationRouteMode,
                selectedKey,
                preferredBluetoothTarget
        );
        boolean routeNotifications = notificationDevice != null && notificationDevice.audioSystemOutputDevice() != 0;
        int notificationAudioSystemDevice = routeNotifications ? notificationDevice.audioSystemOutputDevice() : 0;
        String notificationAddress = routeNotifications ? notificationDevice.address() : "";

        RouteDevice mediaDevice = mediaRouteDevice(
                pinMediaToBluetooth,
                selectedKey,
                preferredBluetoothTarget
        );
        boolean routeMedia = mediaDevice != null && mediaDevice.audioSystemOutputDevice() != 0;
        int mediaAudioSystemDevice = routeMedia ? mediaDevice.audioSystemOutputDevice() : 0;
        String mediaAddress = routeMedia ? mediaDevice.address() : "";
        return rootShell.applyAndroidAutoAudioTweaks(
                routeNotifications,
                notificationAudioSystemDevice,
                notificationAddress,
                suppressDucking,
                routeMedia,
                mediaAudioSystemDevice,
                mediaAddress
        );
    }

    RootShell.ShellResult clearAndroidAutoAudioTweaks(boolean restoreDucking, boolean clearNotificationRoute,
                                                      boolean clearMediaRoute) {
        return rootShell.clearAndroidAutoAudioTweaks(restoreDucking, clearNotificationRoute, clearMediaRoute);
    }

    boolean isMediaPlaybackActive() {
        return audioManager.isMusicActive();
    }

    MediaLevelerActivation enableMediaBoost(String levelerMode) {
        MediaLevelerProfile profile = MediaLevelerProfile.forMode(levelerMode);
        if (!profile.anyProcessingEnabled()) {
            String cleanup = disableMediaBoost();
            activeMediaLevelerActivation = MediaLevelerActivation.off(profile.mode, cleanup);
            return activeMediaLevelerActivation;
        }

        if (profile.dynamicsEnabled) {
            String dynamicsFailure;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Never stack LoudnessEnhancer with DynamicsProcessing. Android does not
                // guarantee ordering between independent session-zero insert effects.
                releaseMediaBoostQuietly();
                try {
                    String detail = enableDynamicsBoost(profile);
                    activeMediaLevelerActivation = MediaLevelerActivation.active(
                            profile.mode,
                            "dynamics",
                            detail + " " + profile.summary()
                    );
                    return activeMediaLevelerActivation;
                } catch (RuntimeException e) {
                    releaseDynamicsBoostQuietly();
                    dynamicsFailure = shortMessage(e);
                }
            } else {
                dynamicsFailure = "requires_api_28 device_api=" + Build.VERSION.SDK_INT;
            }

            activeMediaLevelerActivation = MediaLevelerActivation.failed(
                    profile.mode,
                    "dynamics",
                    "bypassed dynamics_unavailable=" + dynamicsFailure
            );
            return activeMediaLevelerActivation;
        }

        return enableGainOnlyProcessor(profile, profile.loudnessGainMb);
    }

    /** Describes the leveler tuning currently applied, for diagnostics output. */
    String activeLevelerSummary() {
        return activeMediaLevelerActivation.summary();
    }

    /** Re-checks ownership because another global-session effect can take control at runtime. */
    MediaLevelerActivation inspectMediaLeveler() {
        if (!activeMediaLevelerActivation.processingActive()) {
            return activeMediaLevelerActivation;
        }
        AudioEffect effect = "dynamics".equals(activeMediaLevelerActivation.processor)
                ? dynamicsBoost
                : mediaBoost;
        try {
            if (effect == null) {
                throw new IllegalStateException("effect_missing");
            }
            if (!effect.hasControl()) {
                throw new IllegalStateException("effect_control_lost");
            }
            if (!effect.getEnabled()) {
                throw new IllegalStateException("effect_reported_disabled");
            }
            return activeMediaLevelerActivation;
        } catch (RuntimeException e) {
            String requestedMode = activeMediaLevelerActivation.requestedMode;
            String processor = activeMediaLevelerActivation.processor;
            releaseMediaBoostQuietly();
            releaseDynamicsBoostQuietly();
            activeMediaLevelerActivation = MediaLevelerActivation.failed(
                    requestedMode,
                    processor,
                    "runtime_loss=" + shortMessage(e)
            );
            return activeMediaLevelerActivation;
        }
    }

    String disableMediaBoost() {
        StringBuilder summary = new StringBuilder();
        if (mediaBoost == null) {
            summary.append("loudness=off already");
        } else {
            LoudnessEnhancer effect = mediaBoost;
            mediaBoost = null;
            try {
                effect.setEnabled(false);
                summary.append("loudness=off");
            } catch (RuntimeException e) {
                summary.append("loudness=clear_failed ").append(shortMessage(e));
            }
            try {
                effect.release();
            } catch (RuntimeException e) {
                summary.append(" release_failed=").append(shortMessage(e));
            }
        }
        summary.append(" ").append(disableDynamicsBoost());
        activeMediaLevelerActivation = MediaLevelerActivation.off(
                MediaLevelerProfile.MODE_OFF,
                summary.toString()
        );
        return summary.toString();
    }

    private MediaLevelerActivation enableGainOnlyProcessor(MediaLevelerProfile profile,
                                                           int targetGainMb) {
        releaseDynamicsBoostQuietly();
        try {
            if (mediaBoost == null) {
                mediaBoost = new LoudnessEnhancer(GLOBAL_AUDIO_SESSION);
            }
            mediaBoost.setTargetGain(targetGainMb);
            requireEffectEnabled(mediaBoost, mediaBoost.setEnabled(true));
            String detail = "gainMb=" + (int) mediaBoost.getTargetGain()
                    + " requested_no_song_matching";
            activeMediaLevelerActivation =
                    MediaLevelerActivation.degraded(profile.mode, "gain_only", detail);
            return activeMediaLevelerActivation;
        } catch (RuntimeException e) {
            releaseMediaBoostQuietly();
            activeMediaLevelerActivation = MediaLevelerActivation.failed(
                    profile.mode,
                    "gain_only",
                    "error=" + shortMessage(e)
            );
            return activeMediaLevelerActivation;
        }
    }

    @SuppressLint({"NewApi", "InlinedApi"})
    private String enableDynamicsBoost(MediaLevelerProfile profile) {
        if (dynamicsBoost == null) {
            DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                    // AOSP's legacy and current default implementations reliably construct
                    // the frequency-resolution engine; the time-resolution variant can be
                    // advertised by the API yet rejected or left uninitialized by the effect.
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    MediaLevelerProfile.DYNAMICS_CHANNEL_COUNT,
                    false,
                    0,
                    MediaLevelerProfile.DYNAMICS_MBC_IN_USE,
                    MediaLevelerProfile.DYNAMICS_MBC_BAND_COUNT,
                    false,
                    0,
                    MediaLevelerProfile.DYNAMICS_LIMITER_IN_USE
            )
                    .setInputGainAllChannelsTo(profile.inputGainDb)
                    .build();
            dynamicsBoost = new DynamicsProcessing(0, GLOBAL_AUDIO_SESSION, config);
        }
        applyDynamicsSettings(profile);
        requireEffectEnabled(dynamicsBoost, dynamicsBoost.setEnabled(true));
        return "architecture=frequency mbc=off limiter=stereo_linked"
                + " inputGainDb=" + profile.inputGainDb;
    }

    private void requireEffectEnabled(AudioEffect effect, int enableStatus) {
        if (enableStatus != AudioEffect.SUCCESS) {
            throw new IllegalStateException("enable_status=" + enableStatus);
        }
        if (!effect.hasControl()) {
            throw new IllegalStateException("effect_control_lost");
        }
        if (!effect.getEnabled()) {
            throw new IllegalStateException("effect_reported_disabled");
        }
    }

    private String disableDynamicsBoost() {
        if (dynamicsBoost == null) {
            return "dynamics=off already";
        }
        DynamicsProcessing effect = dynamicsBoost;
        dynamicsBoost = null;
        String summary = "dynamics=off";
        try {
            effect.setEnabled(false);
        } catch (RuntimeException e) {
            summary = "dynamics=clear_failed " + shortMessage(e);
        }
        try {
            effect.release();
        } catch (RuntimeException e) {
            summary += " release_failed=" + shortMessage(e);
        }
        return summary;
    }

    String musicVolumeSummary() {
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                + "/" + audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }

    String reassertBluetoothMediaPath(String selectedKey, String preferredBluetoothTarget) {
        RouteDevice mediaDevice = mediaRouteDevice(true, selectedKey, preferredBluetoothTarget);
        if (mediaDevice == null) {
            return "No Bluetooth media output is currently available for the saved target.";
        }

        StringBuilder result = new StringBuilder();
        result.append("target=").append(mediaDevice.detailLabel());
        try {
            audioManager.setParameters("A2dpSuspended=false");
            result.append(" setParameters(A2dpSuspended=false)=ok");
        } catch (RuntimeException e) {
            result.append(" setParameters(A2dpSuspended=false)=").append(shortMessage(e));
        }

        return result.toString();
    }

    boolean selectedRouteIsBluetoothSco(String selectedKey, String preferredBluetoothTarget) {
        RouteDevice selected = findSelected(listRouteDevices(), selectedKey, preferredBluetoothTarget);
        return selected.isBluetoothSco();
    }

    boolean selectedRouteIsUnmatchedBluetoothScoFallback(String selectedKey, String preferredBluetoothTarget) {
        if (!hasPreferredTarget(preferredBluetoothTarget)) {
            return false;
        }
        List<RouteDevice> routeDevices = listRouteDevices();
        if (findBluetoothTarget(routeDevices, preferredBluetoothTarget) != null) {
            return false;
        }
        RouteDevice selected = findSelected(routeDevices, selectedKey, preferredBluetoothTarget);
        return selected.isBluetoothSco();
    }

    String selectedRouteDetail(String selectedKey, String preferredBluetoothTarget) {
        RouteDevice selected = findSelected(listRouteDevices(), selectedKey, preferredBluetoothTarget);
        return selected.detailLabel();
    }

    boolean isCurrentCommunicationRouteBluetoothSco() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo current = audioManager.getCommunicationDevice();
            return current != null && RouteDevice.from(current).isBluetoothSco();
        }
        return isBluetoothScoOn();
    }

    boolean isCallOrCommunicationActive() {
        return isAutomaticRoutingProtectedForMode(audioManager.getMode());
    }

    boolean isCallOrRingingActive() {
        return isOwnedRouteCleanupProtectedForMode(audioManager.getMode());
    }

    static boolean isAutomaticRoutingProtectedForMode(int mode) {
        // Screening, redirected calls, Assistant modes, and future modes must also yield
        // to the platform. Only normal mode is safe for automatic audio changes.
        return mode != AudioManager.MODE_NORMAL;
    }

    static boolean isOwnedRouteCleanupProtectedForMode(int mode) {
        // Settings restore may unwind our own legacy MODE_IN_COMMUNICATION request.
        // Its caller must verify route ownership before using that one exception.
        return mode != AudioManager.MODE_NORMAL && mode != AudioManager.MODE_IN_COMMUNICATION;
    }

    String audioModeSummary() {
        int mode = audioManager.getMode();
        switch (mode) {
            case AudioManager.MODE_NORMAL:
                return "MODE_NORMAL";
            case AudioManager.MODE_RINGTONE:
                return "MODE_RINGTONE";
            case AudioManager.MODE_IN_CALL:
                return "MODE_IN_CALL";
            case AudioManager.MODE_IN_COMMUNICATION:
                return "MODE_IN_COMMUNICATION";
            case AudioManager.MODE_CALL_SCREENING:
                return "MODE_CALL_SCREENING";
            case AudioManager.MODE_CALL_REDIRECT:
                return "MODE_CALL_REDIRECT";
            case AudioManager.MODE_COMMUNICATION_REDIRECT:
                return "MODE_COMMUNICATION_REDIRECT";
            default:
                return "mode=" + mode;
        }
    }

    int notificationStreamVolume() {
        return audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
    }

    void setNotificationStreamVolume(int volume) {
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
        int safeVolume = Math.max(0, Math.min(volume, maxVolume));
        audioManager.setStreamVolume(
                AudioManager.STREAM_NOTIFICATION,
                safeVolume,
                AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
        );
    }

    boolean isRootAvailable() {
        return rootShell.isAvailable();
    }

    boolean isAndroidAutoRunningWithRoot() {
        try {
            return AndroidAutoStatus.isRunningWithRoot(rootShell)
                    && currentAndroidAutoConnection().specific();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private RouteDevice findSelected(List<RouteDevice> devices, String selectedKey, String preferredBluetoothTarget) {
        RouteDevice targetMatch = findBluetoothTarget(devices, preferredBluetoothTarget);
        if (targetMatch != null) {
            return targetMatch;
        }

        if (hasPreferredTarget(preferredBluetoothTarget)) {
            RouteDevice bluetoothFallback = firstBluetoothRoute(devices);
            if (bluetoothFallback != null) {
                return bluetoothFallback;
            }
        }

        if (selectedKey != null) {
            for (RouteDevice device : devices) {
                if (selectedKey.equals(device.key())) {
                    return device;
                }
            }
        }

        for (RouteDevice device : devices) {
            if (device.isBluetooth()) {
                return device;
            }
        }
        return devices.get(0);
    }

    private RouteDevice firstBluetoothRoute(List<RouteDevice> devices) {
        for (RouteDevice device : devices) {
            if (device.isBluetooth()) {
                return device;
            }
        }
        return null;
    }

    private RouteDevice findBluetoothTarget(List<RouteDevice> devices, String preferredBluetoothTarget) {
        if (!hasPreferredTarget(preferredBluetoothTarget)) {
            return null;
        }
        for (RouteDevice device : devices) {
            if (device.isBluetooth() && device.matchesTarget(preferredBluetoothTarget)) {
                return device;
            }
        }
        return null;
    }

    private RouteDevice findBluetoothMediaTarget(List<RouteDevice> devices, String preferredBluetoothTarget) {
        if (!hasPreferredTarget(preferredBluetoothTarget)) {
            return null;
        }
        for (RouteDevice device : devices) {
            if (device.isBluetoothMediaOutput() && device.matchesTarget(preferredBluetoothTarget)) {
                return device;
            }
        }
        return null;
    }

    private RouteDevice firstBluetoothMediaOutputRoute(List<RouteDevice> devices) {
        for (RouteDevice device : devices) {
            if (device.isBluetoothMediaOutput()) {
                return device;
            }
        }
        return null;
    }

    private RouteDevice notificationRouteDevice(String notificationRouteMode,
                                                String selectedKey,
                                                String preferredBluetoothTarget) {
        if (notificationRouteMode == null || AppPrefs.NOTIFICATION_ROUTE_OFF.equals(notificationRouteMode)) {
            return null;
        }

        List<RouteDevice> outputDevices = listOutputDevices();
        if (AppPrefs.NOTIFICATION_ROUTE_SPEAKER.equals(notificationRouteMode)) {
            return firstDeviceByType(outputDevices, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        }
        if (AppPrefs.NOTIFICATION_ROUTE_EARPIECE.equals(notificationRouteMode)) {
            return firstDeviceByType(outputDevices, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
        }
        if (AppPrefs.NOTIFICATION_ROUTE_BLUETOOTH.equals(notificationRouteMode)) {
            RouteDevice target = findBluetoothTarget(outputDevices, preferredBluetoothTarget);
            if (target != null) {
                return target;
            }
            if (hasPreferredTarget(preferredBluetoothTarget)) {
                return null;
            }
            RouteDevice selected = findDeviceByKey(outputDevices, selectedKey);
            if (selected != null && selected.isBluetooth()) {
                return selected;
            }
            return firstBluetoothRoute(outputDevices);
        }
        return null;
    }

    private RouteDevice mediaRouteDevice(boolean pinMediaToBluetooth,
                                         String selectedKey,
                                         String preferredBluetoothTarget) {
        if (!pinMediaToBluetooth) {
            return null;
        }

        List<RouteDevice> outputDevices = listOutputDevices();
        RouteDevice target = findBluetoothMediaTarget(outputDevices, preferredBluetoothTarget);
        if (target != null) {
            return target;
        }
        if (hasPreferredTarget(preferredBluetoothTarget)) {
            return null;
        }
        RouteDevice selected = findDeviceByKey(outputDevices, selectedKey);
        if (selected != null && selected.isBluetoothMediaOutput()) {
            return selected;
        }
        return firstBluetoothMediaOutputRoute(outputDevices);
    }

    private List<RouteDevice> listOutputDevices() {
        List<RouteDevice> devices = new ArrayList<>();
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            devices.add(RouteDevice.from(device));
        }
        return devices;
    }

    private List<RouteDevice> listInputDevices() {
        List<RouteDevice> devices = new ArrayList<>();
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            devices.add(RouteDevice.from(device));
        }
        return devices;
    }

    private RouteDevice firstDeviceByType(List<RouteDevice> devices, int type) {
        for (RouteDevice device : devices) {
            if (device.type() == type) {
                return device;
            }
        }
        return null;
    }

    private RouteDevice findDeviceByKey(List<RouteDevice> devices, String selectedKey) {
        if (selectedKey == null) {
            return null;
        }
        for (RouteDevice device : devices) {
            if (selectedKey.equals(device.key())) {
                return device;
            }
        }
        return null;
    }

    private boolean hasPreferredTarget(String preferredBluetoothTarget) {
        return preferredBluetoothTarget != null && preferredBluetoothTarget.trim().length() > 0;
    }

    private String shortMessage(Throwable throwable) {
        if (throwable == null) {
            return "failed";
        }
        Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
        String message = cause.getMessage();
        if (message == null || message.length() == 0) {
            message = cause.getClass().getSimpleName();
        }
        return message.replace('\n', ' ');
    }

    private void releaseMediaBoostQuietly() {
        if (mediaBoost == null) {
            return;
        }
        try {
            mediaBoost.release();
        } catch (RuntimeException ignored) {
            // Best-effort cleanup after audio effect failure.
        } finally {
            mediaBoost = null;
        }
    }

    private void applyDynamicsSettings(MediaLevelerProfile profile) {
        if (dynamicsBoost == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        dynamicsBoost.setInputGainAllChannelsTo(profile.inputGainDb);

        // MBC has no public cross-channel link control. Using only the final linked limiter
        // gives both stereo channels one attenuation factor and avoids changing their balance.
        // Input and post gain stay at unity, so the real leveling modes can only attenuate.
        DynamicsProcessing.Limiter limiter = dynamicsBoost.getLimiterByChannelIndex(0);
        limiter.setLinkGroup(profile.limiterLinkGroup);
        limiter.setThreshold(profile.limiterThresholdDb);
        limiter.setRatio(profile.limiterRatio);
        limiter.setAttackTime(profile.limiterAttackMs);
        limiter.setReleaseTime(profile.limiterReleaseMs);
        limiter.setPostGain(profile.limiterPostGainDb);
        limiter.setEnabled(true);
        dynamicsBoost.setLimiterAllChannelsTo(limiter);
    }

    private void releaseDynamicsBoostQuietly() {
        if (dynamicsBoost == null) {
            return;
        }
        try {
            dynamicsBoost.release();
        } catch (RuntimeException ignored) {
            // Best-effort cleanup after audio effect failure.
        } finally {
            dynamicsBoost = null;
        }
    }

    private String formatPreferredTarget(String preferredBluetoothTarget) {
        String[] parts = preferredBluetoothTarget.split("\\|");
        StringBuilder output = new StringBuilder();
        for (String part : parts) {
            String clean = part.trim();
            if (clean.length() == 0) {
                continue;
            }
            if (output.length() > 0) {
                output.append(" / ");
            }
            output.append(clean);
        }
        return output.length() == 0 ? preferredBluetoothTarget.trim() : output.toString();
    }

    private void appendBluetoothRoutes(StringBuilder log, List<RouteDevice> routeDevices) {
        log.append("Visible Bluetooth routes:\n");
        boolean found = false;
        for (RouteDevice routeDevice : routeDevices) {
            if (routeDevice.isBluetooth()) {
                log.append("- ").append(routeDevice.detailLabel()).append('\n');
                found = true;
            }
        }
        if (!found) {
            log.append("- none\n");
        }
    }

    @SuppressLint("WrongConstant")
    private AudioDeviceInfo findCommunicationDevice(RouteDevice selected) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null;
        }
        for (AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
            RouteDevice routeDevice = RouteDevice.from(device);
            if (routeDevice.key().equals(selected.key())) {
                return device;
            }
            if (device.getId() == selected.id() && device.getType() == selected.type()) {
                return device;
            }
        }
        return null;
    }

    @SuppressLint("WrongConstant")
    private boolean isCurrentRoute(RouteDevice selected) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo current = audioManager.getCommunicationDevice();
            if (current == null) {
                return false;
            }
            RouteDevice currentRoute = RouteDevice.from(current);
            return currentRoute.key().equals(selected.key())
                    || current.getId() == selected.id() && current.getType() == selected.type();
        }
        return selected.isBluetooth() && isBluetoothScoOn();
    }

    private boolean isCurrentCommunicationRouteOwned() {
        if (ownedCommunicationRouteKey == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo current = audioManager.getCommunicationDevice();
            if (current == null) {
                return false;
            }
            return ownedCommunicationRouteKey.equals(RouteDevice.from(current).key());
        }
        return isBluetoothScoOn();
    }

    @SuppressWarnings("deprecation")
    private void applyLegacyBluetoothSco(StringBuilder log) {
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            log.append("legacy SCO not available off-call.\n");
            return;
        }
        audioManager.startBluetoothSco();
        audioManager.setBluetoothScoOn(true);
        log.append("legacy Bluetooth SCO requested.\n");
    }

    @SuppressWarnings("deprecation")
    private void clearLegacyBluetoothSco() {
        audioManager.setBluetoothScoOn(false);
        audioManager.stopBluetoothSco();
    }

    @SuppressWarnings("deprecation")
    private boolean isBluetoothScoOn() {
        return audioManager.isBluetoothScoOn();
    }

    static final class RoutingResult {
        final boolean success;
        final String log;

        RoutingResult(boolean success, String log) {
            this.success = success;
            this.log = log == null ? "" : log;
        }
    }

    enum MediaLevelerStatus {
        OFF,
        ACTIVE,
        DEGRADED,
        FAILED
    }

    static final class MediaLevelerActivation {
        final MediaLevelerStatus status;
        final String requestedMode;
        final String processor;
        final String detail;

        private MediaLevelerActivation(MediaLevelerStatus status, String requestedMode,
                                       String processor, String detail) {
            this.status = status;
            this.requestedMode = requestedMode == null ? MediaLevelerProfile.DEFAULT_MODE : requestedMode;
            this.processor = processor == null ? "none" : processor;
            this.detail = detail == null ? "" : detail;
        }

        static MediaLevelerActivation off(String requestedMode, String detail) {
            return new MediaLevelerActivation(
                    MediaLevelerStatus.OFF,
                    requestedMode,
                    "none",
                    detail
            );
        }

        static MediaLevelerActivation active(String requestedMode, String processor, String detail) {
            return new MediaLevelerActivation(
                    MediaLevelerStatus.ACTIVE,
                    requestedMode,
                    processor,
                    detail
            );
        }

        static MediaLevelerActivation degraded(String requestedMode, String processor, String detail) {
            return new MediaLevelerActivation(
                    MediaLevelerStatus.DEGRADED,
                    requestedMode,
                    processor,
                    detail
            );
        }

        static MediaLevelerActivation failed(String requestedMode, String processor, String detail) {
            return new MediaLevelerActivation(
                    MediaLevelerStatus.FAILED,
                    requestedMode,
                    processor,
                    detail
            );
        }

        boolean processingActive() {
            return status == MediaLevelerStatus.ACTIVE || status == MediaLevelerStatus.DEGRADED;
        }

        String summary() {
            return "levelerStatus=" + status.name().toLowerCase(java.util.Locale.US)
                    + " requested=" + requestedMode
                    + " processor=" + processor
                    + " " + detail;
        }
    }
}
