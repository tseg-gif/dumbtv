package tv.dumbtv.home;

import android.util.Log;

/**
 * Note estimator on the Visualizer's FFT (the largest capture size the TV offers). Peaks between
 * about 150 and 4200 Hz are picked with parabolic interpolation and mapped to MIDI notes; at most
 * six sound at once, each with its own onset detector (the rise of its peak over a slow average)
 * and hysteresis. Below C4 a harmonic-product check backs the peak up and the note is marked
 * low-confidence. Also scores how piano-like the sound is.
 */
final class Notes {
    private static final String TAG = "dumbtvnotes";
    static final int MAX = 6, CAP = 512;

    // events, oldest overwritten: midi, velocity 0..1, onset in ms, duration in ms, confidence 0..1
    final int[] evMidi = new int[CAP], evDur = new int[CAP];
    final float[] evVel = new float[CAP], evConf = new float[CAP];
    final long[] evAt = new long[CAP];
    int evCount; // total written; the newest is (evCount - 1) % CAP

    // notes sounding now
    final boolean[] on = new boolean[128];
    final float[] level = new float[128];
    int activeN;

    /** 0..1 how piano-like the music is, and since when it has been above 0.6 (0 if it is not). */
    float score;
    long highSince;

    private final float[] mag = new float[1024], slow = new float[128], frame = new float[128], conf = new float[128];
    private final long[] onAt = new long[128];
    private final int[] onEv = new int[128], quiet = new int[128];
    private final int[] cand = new int[MAX + 1];
    private final float[] candM = new float[MAX + 1], candF = new float[MAX + 1];
    private float ref = 20f, tonal, stable;
    private long lastFrame, lastLog;
    private int logOn, logLow;
    private boolean debug;

    void setDebug(boolean d) {
        debug = d;
    }

    void reset() {
        evCount = 0;
        activeN = 0;
        score = 0f;
        highSince = 0;
        for (int i = 0; i < 128; i++) {
            on[i] = false;
            level[i] = slow[i] = 0f;
            quiet[i] = 0;
        }
    }

    boolean pianoLikely(long now, long ms) {
        return highSince != 0 && now - highSince >= ms;
    }

    void feed(byte[] fft, int rateMilliHz, long now) {
        int n = fft.length / 2;
        if (n < 16 || n > mag.length) return;
        float fs = rateMilliHz > 8000 ? rateMilliHz / 1000f : 44100f;
        float binHz = fs / (2f * n);
        float sum = 0f, logSum = 0f, diff = 0f;
        int kLo = Math.max(2, (int) Math.ceil(150f / binHz)), kHi = Math.min(n - 2, (int) (4200f / binHz));
        for (int k = 0; k < n; k++) {
            float re = fft[2 * k], im = fft[2 * k + 1];
            float m = (float) Math.sqrt(re * re + im * im);
            diff += Math.max(0f, m - mag[k]);
            mag[k] = m;
        }
        for (int k = kLo; k <= kHi; k++) {
            sum += mag[k];
            logSum += (float) Math.log(mag[k] + 1f);
        }
        int span = Math.max(1, kHi - kLo + 1);
        float mean = sum / span;
        float flat = (float) Math.exp(logSum / span) / (mean + 1f);

        // peak picking with parabolic interpolation
        float thr = Math.max(7f, mean * 2.4f);
        int nc = 0;
        for (int k = kLo; k <= kHi; k++) {
            float m = mag[k];
            if (m < thr || m < mag[k - 1] || m < mag[k + 1]) continue;
            float a = (float) Math.log(mag[k - 1] + 1e-3f), b = (float) Math.log(m + 1e-3f), c = (float) Math.log(mag[k + 1] + 1e-3f);
            float den = a - 2f * b + c, p = den == 0f ? 0f : 0.5f * (a - c) / den;
            float f = (k + Math.max(-0.5f, Math.min(0.5f, p))) * binHz;
            int midi = Math.round(69f + 12f * (float) (Math.log(f / 440f) / Math.log(2)));
            if (midi < 36 || midi > 108) continue;
            int at = nc;
            if (nc < MAX) nc++;
            else if (m <= candM[MAX - 1]) continue;
            else at = MAX - 1;
            while (at > 0 && candM[at - 1] < m) {
                cand[at] = cand[at - 1];
                candM[at] = candM[at - 1];
                candF[at] = candF[at - 1];
                at--;
            }
            cand[at] = midi;
            candM[at] = m;
            candF[at] = f;
        }
        for (int i = 0; i < 128; i++) {
            frame[i] = 0f;
            conf[i] = 1f;
        }
        for (int i = 0; i < nc; i++) {
            int midi = cand[i];
            float cf = 1f;
            if (midi < 60) { // below C4: demand harmonic support, and say so
                int k = Math.round(candF[i] / binHz);
                float hps = k * 3 < n ? (mag[k] * mag[k * 2] * mag[k * 3]) : 0f;
                cf = hps > 0f && mag[k * 2] > thr * 0.6f ? 0.45f : 0.2f;
                if (cf < 0.3f && candM[i] < thr * 1.6f) continue;
            }
            if (candM[i] > frame[midi]) {
                frame[midi] = candM[i];
                conf[midi] = cf;
            }
        }

        // onsets, hysteresis, durations
        float top = 0f;
        for (int i = 0; i < 128; i++) top = Math.max(top, frame[i]);
        ref = Math.max(12f, Math.max(ref * 0.998f, top));
        int active = 0;
        float stableSum = 0f;
        for (int i = 36; i <= 108; i++) {
            float m = frame[i];
            level[i] += (m - level[i]) * 0.5f;
            if (!on[i]) {
                if (m > thr * 1.5f && m - slow[i] > thr * 0.8f && active < MAX && now - onAt[i] > 150) {
                    on[i] = true;
                    onAt[i] = now;
                    quiet[i] = 0;
                    int e = evCount % CAP;
                    evMidi[e] = i;
                    evVel[e] = Math.min(1f, m / ref);
                    evAt[e] = now;
                    evDur[e] = 0;
                    evConf[e] = conf[i];
                    onEv[i] = evCount++;
                    logOn++;
                    if (conf[i] < 0.5f) logLow++;
                }
            } else {
                int e = onEv[i] % CAP;
                if (m < Math.max(thr * 0.6f, level[i] * 0.45f)) {
                    if (++quiet[i] >= 3) {
                        on[i] = false;
                        if (evCount - onEv[i] < CAP) evDur[e] = (int) (now - onAt[i]);
                    }
                } else {
                    quiet[i] = 0;
                    if (evCount - onEv[i] < CAP) evDur[e] = (int) (now - onAt[i]);
                    if (now - onAt[i] > 300) stableSum += 1f;
                }
            }
            if (on[i]) active++;
            slow[i] += (m - slow[i]) * (m > slow[i] ? 0.15f : 0.04f);
        }
        activeN = active;

        // piano-likeness: tonal (low flatness), little broadband flux, notes that last
        float dt = lastFrame == 0 ? 0.02f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float tonalNow = Math.max(0f, Math.min(1f, 1.4f - flat * 2.2f));
        float perc = Math.min(1f, diff / (sum + 50f) * 0.8f);
        tonal += (tonalNow - tonal) * Math.min(1f, dt * 2f);
        stable += (Math.min(1f, stableSum / 2f) - stable) * Math.min(1f, dt * 1.5f);
        float inst = top < 12f ? 0f : 0.4f * tonal + 0.3f * (1f - perc) + 0.3f * stable;
        score += (inst - score) * Math.min(1f, dt * 0.25f);
        if (score > 0.38f) {
            if (highSince == 0) highSince = now;
        } else if (score < 0.28f) {
            highSince = 0;
        }
        if (debug && now - lastLog > 2000) {
            Log.i(TAG, "notes on=" + logOn + " lowconf=" + logLow + " active=" + active + " score=" + Math.round(score * 100)
                    + " tonal=" + Math.round(tonal * 100) + " stable=" + Math.round(stable * 100) + " flat=" + Math.round(flat * 100));
            lastLog = now;
            logOn = logLow = 0;
        }
    }

    /** Compact "midi.tenths-of-a-second,..." for events since the song began, capped at 400. */
    String pack(long songStart) {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, evCount - CAP), count = 0;
        for (int i = from; i < evCount && count < 400; i++) {
            int e = i % CAP;
            if (evVel[e] < 0.25f) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(evMidi[e]).append('.').append(Math.max(0, (evAt[e] - songStart) / 100));
            count++;
        }
        return sb.toString();
    }
}
