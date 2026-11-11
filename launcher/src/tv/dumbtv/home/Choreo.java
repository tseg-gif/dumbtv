package tv.dumbtv.home;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;

import java.util.Random;

/**
 * Four independent targets, each scheduled on the bar and beat grid and varied per song by a seed:
 *   camera    push / pull / orbit through 8-bar phrases, a whip on beat 1 of every 2nd, 4th or 8th bar,
 *             a small bump on kicks and downbeats;
 *   lights    a sweep on beat 2 or 3 every 1-2 bars; their colour follows the bass line;
 *   material  a soft centre glow that swells with the mids and vocals;
 *   particles hats are tiny twinkles, snare backbeats (beats 2 and 4) throw a burst.
 * Moves are slow and small: no flash, no jump in brightness.
 */
final class Choreo {
    private static final String TAG = "dumbtvscene";
    private static final int NP = 96;

    // outputs
    float zoom = 1f, panX, panY, rot; // camera, as fractions of the screen / degrees
    float lightSwing, lightMix; // lights
    float mat; // material, 0..1

    private final Random rng = new Random(1);
    private int whipEvery = 4, whipPhase, sweepEvery = 1, sweepBeat = 2, burstSize = 24, orbitSign = 1, modeSeed;
    private float whipDir = 1f;
    private float panV, rotV, bump, phrase, swingV, bassLine;
    private long lastKey = Long.MIN_VALUE;
    private int kicks;
    private boolean debug;

    private final float[] px = new float[NP], py = new float[NP], vx = new float[NP], vy = new float[NP], life = new float[NP];
    private final byte[] kind = new byte[NP];
    private int next;

    void seed(int songKey, boolean debug) {
        this.debug = debug;
        rng.setSeed(songKey * 7919L + 13L);
        int[] every = {2, 4, 4, 8};
        whipEvery = every[rng.nextInt(every.length)];
        whipPhase = rng.nextInt(whipEvery);
        sweepEvery = 4;
        sweepBeat = rng.nextBoolean() ? 2 : 3; // beat 3 or the "and" of 4, counted from 0
        sweepBeat = 2;
        burstSize = 20 + rng.nextInt(14);
        orbitSign = rng.nextBoolean() ? 1 : -1;
        modeSeed = rng.nextInt(3);
        whipDir = rng.nextBoolean() ? 1f : -1f;
        if (debug) Log.i(TAG, "choreo whip every " + whipEvery + " bars (+" + whipPhase + ") sweep every " + sweepEvery
                + " bar(s) on beat " + (sweepBeat + 1) + " burst " + burstSize + " phrase " + modeSeed);
    }

    /** Advance one frame. bar and beat (0..3) come from the beat clock, or from counted kicks when it has none. */
    void update(float dt, long bar, int beat, float beatPhase, boolean kick, boolean snare, boolean hat,
            float bass, float mids, float w, float h) {
        if (kick) kicks++;
        long key = bar * 4 + beat;
        if (key != lastKey) {
            lastKey = key;
            onBeat(bar, beat);
        }
        if (snare && (beat == 1 || beat == 3)) burst(w * (0.2f + 0.6f * rng.nextFloat()), h * (0.55f + 0.25f * rng.nextFloat()), burstSize, 1);
        else if (snare) burst(w * (0.3f + 0.4f * rng.nextFloat()), h * 0.6f, burstSize / 3, 1);
        if (hat) twinkle(w, h);
        if (kick) bump = Math.min(1f, bump + 0.5f);

        // camera: phrase of 8 bars, mode from the seed, rotating through push / pull / orbit
        phrase = (((bar % 8) + 8) % 8 * 4 + beat + beatPhase) / 32f;
        int mode = (int) (((bar >> 3) + modeSeed) % 3 + 3) % 3;
        float z = 1.06f, px0 = 0f, r0 = 0f;
        float e = phrase * phrase * (3f - 2f * phrase);
        if (mode == 0) z += 0.03f * e; // push in
        else if (mode == 1) z += 0.03f * (1f - e); // pull out
        else {
            px0 = (float) Math.sin(phrase * 6.2832f) * 0.008f * orbitSign;
            r0 = (float) Math.cos(phrase * 6.2832f) * 0.9f * orbitSign;
            z += 0.012f;
        }
        // whip spring: pan and a little roll, returning in about 300 ms
        panV += (-260f * panX - 15f * panV) * dt;
        panX += panV * dt;
        rotV += (-260f * rot - 15f * rotV) * dt;
        rot += rotV * dt;
        bump = Math.max(0f, bump - dt * 5f);
        zoom = z + 0.008f * bump;
        camX = px0;
        camR = r0;

        // lights: a swing that eases out, colour following the slow bass line
        swingV += (-9f * lightSwing - 5f * swingV) * dt;
        lightSwing += swingV * dt;
        bassLine += (bass - bassLine) * Math.min(1f, dt * 2.5f);
        lightMix = Math.min(1f, bassLine * 1.4f);
        // material: mids and vocals, slow
        mat += (Math.min(1f, mids * 2f) - mat) * Math.min(1f, dt * 3f);

        for (int i = 0; i < NP; i++) {
            if (life[i] > 0f) {
                life[i] -= dt * (kind[i] == 0 ? 1.6f : 0.9f);
                px[i] += vx[i] * dt;
                py[i] += vy[i] * dt;
                vy[i] += (kind[i] == 0 ? -8f : 40f) * dt; // twinkles drift up, bursts settle
            }
        }
    }

    private float camX, camR;

    private void onBeat(long bar, int beat) {
        if (beat == 0) {
            bump = Math.min(1f, bump + 0.6f);
            if (bar % whipEvery == whipPhase) { // the whip
                panV += whipDir * 0.4f;
                rotV += -whipDir * 14f;
                whipDir = -whipDir;
                if (debug) Log.i(TAG, "camera whip bar " + bar);
            }
        }
        if (beat == sweepBeat && bar % sweepEvery == 0) {
            swingV += 1.1f * (((bar & 1) == 0) ? 1f : -1f);
            if (debug) Log.i(TAG, "light sweep bar " + bar + " beat " + (beat + 1));
        }
    }

    /** The camera as one canvas transform about the screen centre. */
    void camera(Canvas c, float w, float h) {
        c.translate(w * 0.5f + (panX + camX) * w, h * 0.5f + panY * h);
        c.rotate(rot + camR);
        c.scale(zoom, zoom);
        c.translate(-w * 0.5f, -h * 0.5f);
    }

    int kicksCounted() {
        return kicks;
    }

    private void burst(float x, float y, int n, int k) {
        for (int i = 0; i < n; i++) {
            int q = next++ % NP;
            float a = -3.1416f * (0.1f + 0.8f * rng.nextFloat()), sp = 60f + 220f * rng.nextFloat();
            px[q] = x;
            py[q] = y;
            vx[q] = (float) Math.cos(a) * sp;
            vy[q] = (float) Math.sin(a) * sp * 0.8f;
            life[q] = 0.7f + 0.6f * rng.nextFloat();
            kind[q] = (byte) k;
        }
    }

    private void twinkle(float w, float h) {
        int q = next++ % NP;
        px[q] = w * rng.nextFloat();
        py[q] = h * (0.12f + 0.6f * rng.nextFloat());
        vx[q] = 0f;
        vy[q] = -4f;
        life[q] = 0.8f;
        kind[q] = 0;
    }

    /** Material glow and particles, drawn in screen pixels over the scene. */
    void draw(Canvas c, Paint p, float w, float h, int colA, int colB, float alpha, boolean card,
            float cl, float ct, float cr, float cb) {
        if (alpha <= 0.01f) return;
        p.setStyle(Paint.Style.FILL);
        if (mat > 0.04f) { // a soft glow in the centre: a few stacked discs, never above 12% alpha
            for (int i = 0; i < 4; i++) {
                p.setColor((((int) (255f * 0.03f * mat * alpha)) << 24) | (colB & 0xFFFFFF));
                c.drawCircle(w * 0.5f, h * 0.46f, h * (0.12f + 0.06f * i) * (0.9f + 0.2f * mat), p);
            }
        }
        float unit = h / 1080f;
        for (int i = 0; i < NP; i++) {
            float l = life[i];
            if (l <= 0f) continue;
            if (card && px[i] > cl && px[i] < cr && py[i] > ct && py[i] < cb) continue;
            float tw = kind[i] == 0 ? 0.5f + 0.5f * (float) Math.sin(l * 16f) : Math.min(1f, l * 1.5f);
            p.setColor((((int) (255f * 0.55f * Math.min(1f, l) * tw * alpha)) << 24) | ((kind[i] == 0 ? 0xE8F0FF : colA) & 0xFFFFFF));
            c.drawCircle(px[i], py[i], (kind[i] == 0 ? 2.2f : 3.2f) * unit, p);
        }
    }
}
