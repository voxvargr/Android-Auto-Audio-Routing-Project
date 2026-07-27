package dev.voxvargr.aaarp;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class AudioRouteController {
    private static final int GLOBAL_AUDIO_SESSION = 0;
    private static final int MEDIA_BOOST_GAIN_MB = 1200;
    private static final float DYNAMICS_INPUT_GAIN_DB = 4.0f;
    private static final float DYNAMICS_MBC_PRE_GAIN_DB = 6.0f;
    private static final float DYNAMICS_MBC_POST_GAIN_DB = 1.5f;
    private static final float DYNAMICS_MBC_THRESHOLD_DB = -32.0f;
    private static final float DYNAMICS_MBC_RATIO = 2.8f;
    private static final float DYNAMICS_LIMITER_THRESHOLD_DB = -1.0f;
    private static final float DYNAMICS_LIMITER_RATIO = 10.0f;

    private final Context context;
    private final AudioManager audioManager;
    private final RootShell rootShell;
    private LoudnessEnhancer mediaBoost;
    private DynamicsProcessing dynamicsBoost;

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
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && selected.isRealDevice()) {
                AudioDeviceInfo device = findCommunicationDevice(selected);
                if (device == null) {
                    log.append("device disappeared before routing.\n");
                    return new RoutingResult(false, log.toString());
                }
                boolean accepted = audioManager.setCommunicationDevice(device);
                log.append(accepted ? "accepted" : "rejected").append('\n');
                log.append("Current communication device: ").append(currentCommunicationDevice()).append('\n');
                return new RoutingResult(accepted, log.toString());
            }

            applyLegacyBluetoothSco(log);
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
        audioManager.setMode(AudioManager.MODE_NORMAL);
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

    String enableMediaBoost(boolean enableDynamicsProcessing) {
        StringBuilder summary = new StringBuilder();
        try {
            if (mediaBoost == null) {
                mediaBoost = new LoudnessEnhancer(GLOBAL_AUDIO_SESSION);
            }
            mediaBoost.setTargetGain(MEDIA_BOOST_GAIN_MB);
            mediaBoost.setEnabled(true);
            summary.append("loudness=on gainMb=").append((int) mediaBoost.getTargetGain());
        } catch (RuntimeException e) {
            releaseMediaBoostQuietly();
            summary.append("loudness=failed ").append(shortMessage(e));
        }
        summary.append(" ");
        if (enableDynamicsProcessing) {
            summary.append(enableDynamicsBoost());
        } else {
            summary.append(disableDynamicsBoost());
        }
        return summary.toString();
    }

    String disableMediaBoost() {
        StringBuilder summary = new StringBuilder();
        if (mediaBoost == null) {
            summary.append("loudness=off already");
        } else {
            try {
                mediaBoost.setEnabled(false);
                mediaBoost.release();
                summary.append("loudness=off");
            } catch (RuntimeException e) {
                summary.append("loudness=clear_failed ").append(shortMessage(e));
            } finally {
                mediaBoost = null;
            }
        }
        summary.append(" ").append(disableDynamicsBoost());
        return summary.toString();
    }

    private String enableDynamicsBoost() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return "dynamics=unavailable";
        }
        try {
            if (dynamicsBoost == null) {
                DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                        2,
                        false,
                        0,
                        true,
                        1,
                        false,
                        0,
                        true
                )
                        .setInputGainAllChannelsTo(DYNAMICS_INPUT_GAIN_DB)
                        .build();
                dynamicsBoost = new DynamicsProcessing(0, GLOBAL_AUDIO_SESSION, config);
            }
            applyDynamicsSettings();
            dynamicsBoost.setEnabled(true);
            return "dynamics=on inputGainDb=" + DYNAMICS_INPUT_GAIN_DB;
        } catch (RuntimeException e) {
            releaseDynamicsBoostQuietly();
            return "dynamics=failed " + shortMessage(e);
        }
    }

    private String disableDynamicsBoost() {
        if (dynamicsBoost == null) {
            return "dynamics=off already";
        }
        try {
            dynamicsBoost.setEnabled(false);
            dynamicsBoost.release();
            return "dynamics=off";
        } catch (RuntimeException e) {
            return "dynamics=clear_failed " + shortMessage(e);
        } finally {
            dynamicsBoost = null;
        }
    }

    VolumeAdjustment ensureMusicVolumeAtLeastPercent(int percent) {
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int safePercent = Math.max(0, Math.min(percent, 100));
        int targetVolume = Math.max(0, Math.min(maxVolume, Math.round(maxVolume * safePercent / 100f)));
        if (currentVolume < targetVolume) {
            setMusicStreamVolume(targetVolume);
            return new VolumeAdjustment(currentVolume, targetVolume, maxVolume, true);
        }
        return new VolumeAdjustment(currentVolume, currentVolume, maxVolume, false);
    }

    String restoreMusicVolumeIfStillAt(int expectedVolume, int restoreVolume) {
        if (restoreVolume < 0) {
            return "musicVolumeRestore=skipped";
        }
        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (currentVolume == expectedVolume) {
            setMusicStreamVolume(restoreVolume);
            return "musicVolumeRestore=restored " + restoreVolume + "/" + maxVolume;
        }
        return "musicVolumeRestore=left_alone current=" + currentVolume + "/" + maxVolume
                + " expected=" + expectedVolume
                + " restore=" + restoreVolume;
    }

    String musicVolumeSummary() {
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                + "/" + audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }

    int musicStreamVolume() {
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
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

        try {
            Method method = AudioManager.class.getDeclaredMethod("setBluetoothA2dpOn", boolean.class);
            method.setAccessible(true);
            method.invoke(audioManager, true);
            result.append(" setBluetoothA2dpOn(true)=ok");
        } catch (Throwable e) {
            result.append(" setBluetoothA2dpOn(true)=").append(shortMessage(e));
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

    boolean isInCallAudioMode() {
        return audioManager.getMode() == AudioManager.MODE_IN_CALL;
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

    private void setMusicStreamVolume(int volume) {
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int safeVolume = Math.max(0, Math.min(volume, maxVolume));
        audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                safeVolume,
                AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
        );
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

    private void applyDynamicsSettings() {
        if (dynamicsBoost == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        dynamicsBoost.setInputGainAllChannelsTo(DYNAMICS_INPUT_GAIN_DB);

        DynamicsProcessing.MbcBand band = dynamicsBoost.getMbcBandByChannelIndex(0, 0);
        band.setPreGain(DYNAMICS_MBC_PRE_GAIN_DB);
        band.setPostGain(DYNAMICS_MBC_POST_GAIN_DB);
        band.setThreshold(DYNAMICS_MBC_THRESHOLD_DB);
        band.setRatio(DYNAMICS_MBC_RATIO);
        band.setAttackTime(8.0f);
        band.setReleaseTime(120.0f);
        band.setNoiseGateThreshold(-80.0f);
        band.setExpanderRatio(1.0f);
        dynamicsBoost.setMbcBandAllChannelsTo(0, band);

        DynamicsProcessing.Limiter limiter = dynamicsBoost.getLimiterByChannelIndex(0);
        limiter.setThreshold(DYNAMICS_LIMITER_THRESHOLD_DB);
        limiter.setRatio(DYNAMICS_LIMITER_RATIO);
        limiter.setAttackTime(1.0f);
        limiter.setReleaseTime(60.0f);
        limiter.setPostGain(0.0f);
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

    static final class VolumeAdjustment {
        final int previousVolume;
        final int currentVolume;
        final int maxVolume;
        final boolean changed;

        VolumeAdjustment(int previousVolume, int currentVolume, int maxVolume, boolean changed) {
            this.previousVolume = previousVolume;
            this.currentVolume = currentVolume;
            this.maxVolume = maxVolume;
            this.changed = changed;
        }

        String summary() {
            return "musicVolume=" + currentVolume + "/" + maxVolume
                    + (changed ? " raisedFrom=" + previousVolume : " unchanged");
        }
    }
}
