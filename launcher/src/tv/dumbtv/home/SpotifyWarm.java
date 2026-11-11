package tv.dumbtv.home;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.util.Log;

import java.net.InetAddress;
import java.util.ArrayDeque;

/**
 * Makes the TV show up in the phone's Spotify device list.
 *
 * The Spotify TV app only announces itself on the home network (mDNS "_spotify-connect._tcp") after
 * its screen has been opened since it last started. After a reboot, or after the TV closed it for
 * memory, the app can be running in the background and still be invisible to the phone. While the
 * home screen is idle, this listens for the announcement; if the TV is not announcing, it opens
 * Spotify for a few seconds and comes back to the home screen.
 */
final class SpotifyWarm {
    private static final String TAG = "dumbtvspot";
    private static final String PKG = "com.spotify.tv.android";
    private static final long IDLE_MS = 40000, LISTEN_MS = 12000, STAY_MS = 9000, EVERY_MS = 10 * 60000;

    private final Context ctx;
    private final Handler ui;
    private final NsdManager nsd;
    private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
    private NsdManager.DiscoveryListener listener;
    private boolean found, resolving;
    private long lastRun;
    private String ownIp = "";

    SpotifyWarm(Context c, Handler ui) {
        ctx = c;
        this.ui = ui;
        nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
    }

    private final Runnable start = new Runnable() {
        @Override
        public void run() {
            listen();
        }
    };

    /** Home came to the front: check once it has been idle for a while. */
    void schedule() {
        ui.removeCallbacks(start);
        if (System.currentTimeMillis() - lastRun < EVERY_MS) return;
        ui.postDelayed(start, IDLE_MS);
    }

    void cancel() {
        ui.removeCallbacks(start);
        stopListening();
    }

    private void listen() {
        if (nsd == null || listener != null) return;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null && am.isMusicActive()) return; // something is playing: leave it alone
        if (ctx.getPackageManager().getLaunchIntentForPackage(PKG) == null
                && ctx.getPackageManager().getLeanbackLaunchIntentForPackage(PKG) == null) return;
        lastRun = System.currentTimeMillis();
        found = false;
        queue.clear();
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            ownIp = (ip & 255) + "." + ((ip >> 8) & 255) + "." + ((ip >> 16) & 255) + "." + ((ip >> 24) & 255);
        } catch (RuntimeException e) {
            ownIp = "";
        }
        listener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { stopListening(); }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceLost(NsdServiceInfo s) { }

            @Override
            public void onServiceFound(final NsdServiceInfo s) {
                ui.post(new Runnable() { @Override public void run() { queue.add(s); next(); } });
            }
        };
        try {
            nsd.discoverServices("_spotify-connect._tcp", NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException e) {
            listener = null;
            return;
        }
        ui.postDelayed(verdict, LISTEN_MS);
    }

    private void next() {
        if (resolving || queue.isEmpty() || nsd == null) return;
        resolving = true;
        NsdServiceInfo s = queue.poll();
        try {
            nsd.resolveService(s, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo i, int e) {
                    done();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo i) {
                    InetAddress h = i.getHost();
                    if (h != null && h.getHostAddress() != null && h.getHostAddress().equals(ownIp)) found = true;
                    done();
                }

                private void done() {
                    ui.post(new Runnable() { @Override public void run() { resolving = false; next(); } });
                }
            });
        } catch (RuntimeException e) {
            resolving = false;
        }
    }

    private final Runnable verdict = new Runnable() {
        @Override
        public void run() {
            stopListening();
            Log.i(TAG, "spotify connect announced by this TV: " + found + " (ip " + ownIp + ")");
            if (!found) warm();
        }
    };

    private void stopListening() {
        ui.removeCallbacks(verdict);
        if (listener != null && nsd != null) {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (RuntimeException ignored) {
                // already stopped
            }
        }
        listener = null;
        resolving = false;
        queue.clear();
    }

    /** Open Spotify's screen so it starts announcing, then return to the home screen. */
    private void warm() {
        Intent i = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(PKG);
        if (i == null) i = ctx.getPackageManager().getLaunchIntentForPackage(PKG);
        if (i == null) return;
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (RuntimeException e) {
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (ctx instanceof android.app.Activity) { // the same call Keeper uses: no activity launch, no app-switch delay
                        ((android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE))
                                .moveTaskToFront(((android.app.Activity) ctx).getTaskId(), 0);
                    }
                } catch (RuntimeException e) {
                    Intent h = new Intent(ctx, Home.class);
                    h.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    try {
                        ctx.startActivity(h);
                    } catch (RuntimeException ignored) {
                        // the user already moved on
                    }
                }
                Log.i(TAG, "opened Spotify so the phone can see this TV; back to home");
            }
        }, STAY_MS);
    }
}
