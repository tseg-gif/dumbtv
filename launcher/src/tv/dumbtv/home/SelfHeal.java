package tv.dumbtv.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * Keeps the Guard accessibility service switched on. Fire OS drops a service from
 * enabled_accessibility_services after a crash or a reinstall, and then Amazon's launcher stays
 * in front. Needs `pm grant tv.dumbtv.home android.permission.WRITE_SECURE_SETTINGS` once;
 * without it this does nothing.
 *
 * A ContentObserver reacts to the setting changing, and a 30 s poll catches a service that is
 * listed but whose connection died (Guard.connected() false twice in a row).
 */
final class SelfHeal {
    private static final long POLL_MS = 30000;
    private static final long COOLDOWN_MS = 15000; // our own writes fire the observer; let a bind settle
    private static final String KEY = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;

    private static boolean started;
    private static boolean writing;
    private static int unbound;
    private static long lastWrite;
    private static Handler handler;
    private static Context app;

    private SelfHeal() {
    }

    static synchronized void start(Context c) {
        if (started) {
            check();
            return;
        }
        started = true;
        app = c.getApplicationContext();
        handler = new Handler(Looper.getMainLooper());
        ContentObserver watch = new ContentObserver(handler) {
            @Override
            public void onChange(boolean self) {
                check();
            }
        };
        app.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(KEY), false, watch);
        app.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, watch);
        handler.post(new Runnable() {
            @Override
            public void run() {
                check();
                handler.postDelayed(this, POLL_MS);
            }
        });
    }

    static synchronized void check() {
        if (app == null || writing) return;
        if (app.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastWrite < COOLDOWN_MS) return;
        try {
            String guard = new ComponentName(app, Guard.class).flattenToString();
            String cur = Settings.Secure.getString(app.getContentResolver(), KEY);
            boolean listed = false;
            StringBuilder rest = new StringBuilder();
            if (cur != null) {
                for (String s : cur.split(":")) {
                    if (s.isEmpty()) continue;
                    if (s.equals(guard)) listed = true;
                    else rest.append(rest.length() > 0 ? ":" : "").append(s);
                }
            }
            boolean on = Settings.Secure.getInt(app.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
            if (listed && on && Guard.connected()) {
                unbound = 0;
                return;
            }
            if (listed && on && ++unbound < 2) return; // give a fresh bind a moment
            unbound = 0;
            lastWrite = now;
            writing = true;
            try {
                if (listed) {
                    // Clearing first makes the accessibility manager rebind a dead service.
                    Settings.Secure.putString(app.getContentResolver(), KEY, rest.toString());
                    Thread.sleep(500);
                }
                Settings.Secure.putString(app.getContentResolver(), KEY,
                        (rest.length() > 0 ? rest + ":" : "") + guard);
                Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                android.util.Log.i("dumbtvtest", "selfheal: guard re-enabled");
            } finally {
                writing = false;
            }
        } catch (Throwable t) {
            android.util.Log.w("dumbtvtest", "selfheal failed: " + t);
        }
    }
}
