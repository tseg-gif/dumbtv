package tv.dumbtv.home;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing itself. Android only lets an app read what is playing (song, artist, cover) when
 * it holds notification access, and that access belongs to a NotificationListenerService.
 * adb grants it: cmd notification allow_listener tv.dumbtv.home/tv.dumbtv.home.Now
 */
public final class Now extends NotificationListenerService {
    @Override
    public void onCreate() {
        super.onCreate();
        SelfHeal.start(this);
    }

}
