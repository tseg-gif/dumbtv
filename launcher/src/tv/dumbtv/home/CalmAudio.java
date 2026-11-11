package tv.dumbtv.home;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;
import android.util.Log;

/**
 * Loudness and band energy of the TV's audio output for the Meditation scenes (same Visualizer(0) capture
 * Pulse uses). No beats, kicks or tempo: only how loud it is overall, how much low rumble and how much
 * high hiss, each relative to a slowly falling reference so a steady rain sound reads as "steady".
 */
final class CalmAudio implements Visualizer.OnDataCaptureListener {
    private static final String TAG = "dumbcalm";
    private Visualizer viz;
    private volatile float loud, low, high;
    private volatile long heardAt, frameAt;
    private float refL = 0.2f, refB = 0.2f, refH = 0.2f;

    boolean start(Context c) {
        if (viz != null) return true;
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "audio: no RECORD_AUDIO permission, steady default rain");
            return false;
        }
        try {
            Visualizer v = new Visualizer(0);
            v.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            v.setDataCaptureListener(this, Visualizer.getMaxCaptureRate(), false, true);
            v.setEnabled(true);
            viz = v;
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "audio: capture unavailable, steady default rain: " + e);
            viz = null;
            return false;
        }
    }

    void stop() {
        if (viz == null) return;
        try {
            viz.setEnabled(false);
        } catch (RuntimeException ignored) {
            // already gone
        }
        viz.release();
        viz = null;
    }

    /** True while the output carries sound (heard within the last few seconds). */
    boolean heard() {
        return SystemClock.uptimeMillis() - heardAt < 4000;
    }

    float loud() { return loud; }

    float low() { return low; }

    float high() { return high; }

    @Override
    public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int rate) { }

    @Override
    public void onFftDataCapture(Visualizer v, byte[] fft, int rate) {
        int n = fft.length / 2;
        if (n < 16) return;
        long now = SystemClock.uptimeMillis();
        frameAt = now;
        float all = 0, lo = 0, hi = 0;
        int nl = 0, nh = 0, na = 0;
        for (int k = 1; k < n / 2; k++) {
            float re = fft[2 * k], im = fft[2 * k + 1];
            float m = (float) Math.sqrt(re * re + im * im) / 55f;
            all += m;
            na++;
            if (k < 6) {
                lo += m;
                nl++;
            } else if (k >= n / 8) {
                hi += m;
                nh++;
            }
        }
        all /= na;
        lo /= Math.max(1, nl);
        hi /= Math.max(1, nh);
        if (all > 0.02f) heardAt = now;
        refL = Math.max(0.12f, Math.max(refL * 0.9995f, all));
        refB = Math.max(0.12f, Math.max(refB * 0.9995f, lo));
        refH = Math.max(0.12f, Math.max(refH * 0.9995f, hi));
        loud = Math.min(1f, all / refL);
        low = Math.min(1f, lo / refB);
        high = Math.min(1f, hi / refH);
    }
}
