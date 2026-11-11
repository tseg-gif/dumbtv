package tv.dumbtv.home;

import java.util.Calendar;

/** How much daylight the room has: 1 by day, fading to 0.3 from 7 pm to 9 pm, and back from 5 am to 7 am. Cached for 30 s. */
final class DayLight {
    private DayLight() {
    }

    private static long at;
    private static float k = 1f;

    static float k() {
        long now = System.currentTimeMillis();
        if (now - at > 30000L) {
            at = now;
            Calendar c = Calendar.getInstance();
            float t = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f;
            if (t >= 7f && t < 19f) k = 1f;
            else if (t >= 19f && t < 21f) k = 1f - (t - 19f) / 2f * 0.7f;
            else if (t >= 5f && t < 7f) k = 0.3f + (t - 5f) / 2f * 0.7f;
            else k = 0.3f;
        }
        return k;
    }
}
