package dev.voxvargr.aaarp;

import android.content.ComponentName;
import android.service.notification.NotificationListenerService;

/**
 * Supplies the user-granted notification-listener credential to media-session discovery.
 *
 * <p>This service intentionally does not override notification callbacks or inspect notification
 * contents.</p>
 */
public final class MediaRelayNotificationListenerService extends NotificationListenerService {
    private static final long NO_CONNECTION = -1L;

    private ExternalMediaSessionRepository repository;
    private long connectionId = NO_CONNECTION;

    @Override
    public void onCreate() {
        super.onCreate();
        repository = ExternalMediaSessionRepository.getInstance(this);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        disconnectRepository();
        connectionId = repository.notificationListenerConnected(
                new ComponentName(this, MediaRelayNotificationListenerService.class)
        );
    }

    @Override
    public void onListenerDisconnected() {
        disconnectRepository();
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        disconnectRepository();
        repository = null;
        super.onDestroy();
    }

    private void disconnectRepository() {
        if (repository == null || connectionId == NO_CONNECTION) {
            return;
        }
        repository.notificationListenerDisconnected(connectionId);
        connectionId = NO_CONNECTION;
    }
}
