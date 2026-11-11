package tv.dumbtv.home;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.util.Log;

import java.util.Random;

/**
 * The stage: a floor, a city (or mountains) behind it and two speakers standing on that floor in the left and
 * right thirds. The left speaker works the kick and sub-bass, the right one the snare, clap and hats, each with
 * its own adaptive transient detector; cones are damped springs. Snares throw electric arcs between the two,
 * big kicks throw sparks, windows light per frequency band, searchlights sweep with the tempo, hats glitch a
 * scanline. Everything is filled and shaded, nothing is outlined, nothing allocates while drawing, and the
 * now-playing card (plus a margin) is clipped out of every effect.
 */
final class Speakers {
    private static final String TAG = "dumbtvspk";
    private static final float TAU = (float) (Math.PI * 2);

    static final String[] NAMES = {"floor stack", "club sub", "tower", "line array", "monitor", "column"};
    /** Cabinet width / height. */
    private static final float[] BW = {0.62f, 0.95f, 0.42f, 0.30f, 0.80f, 0.50f};
    /** Drivers per style: x from the centre, y from the top, radius (all in cabinet heights), kind 0 woofer / 1 tweeter. */
    private static final float[][] DRV = {
        {0f, 0.11f, 0.06f, 1, 0f, 0.31f, 0.1f, 0, 0f, 0.69f, 0.22f, 0},
        {0f, 0.09f, 0.05f, 1, -0.245f, 0.6f, 0.21f, 0, 0.245f, 0.6f, 0.21f, 0},
        {0f, 0.08f, 0.05f, 1, 0f, 0.3f, 0.12f, 0, 0f, 0.58f, 0.13f, 0, 0f, 0.85f, 0.09f, 0},
        {0f, 0.08f, 0.04f, 1, 0f, 0.26f, 0.075f, 0, 0f, 0.46f, 0.075f, 0, 0f, 0.66f, 0.075f, 0, 0f, 0.86f, 0.075f, 0},
        {0f, 0.12f, 0.07f, 1, 0f, 0.63f, 0.29f, 0},
        {0f, 0.10f, 0.05f, 1, 0f, 0.38f, 0.17f, 0, 0f, 0.76f, 0.17f, 0}};

    // inputs, set by the Stage every frame
    boolean kickNow, snareNow, hatNow, debug;
    float bass, flux, ener, sub, kickEnv, snareEnv, hatEnv, cap, alpha, bpm = 100f;
    int level, section;
    /** The now-playing card, already grown by its margin, in this view's pixels. */
    float cl, ct, cr, cb;
    boolean hasCard;
    /** From the choreography: a swing added to the searchlight angles, and how far their colour follows the bass line. */
    float lightSwing, lightMix;

    int accentA() {
        return a0;
    }

    int accentB() {
        return comp;
    }

    int kind; // 0 city, 1 mountains
    /** 0..1 how much of the stage (setting, floor, speakers) shows, and how aggressive the music is. */
    float stageVis = 1f, hard;
    private int setting;
    private static int lastSetting = -1;
    private static final int[] HARD_SET = {0, 1, 2, 7, 8, 6}, SOFT_SET = {0, 3, 4, 5, 6, 1};
    private static final float[] GY = {0.88f, 0.89f, 0.9f, 0.86f, 0.9f, 0.85f, 0.88f, 0.9f, 0.9f};
    private static final float[] SCALE = {1f, 1f, 1f, 1f, 1f, 0.85f, 1f, 0.95f, 1f};
    // palette: cover-art accents plus the complement of the first one
    private int a0 = 0xFFE8A040, a1 = 0xFF5090E0, a2 = 0xFFE05080, hor = 0xFF302840, dark = 0xFF08080C, comp = 0xFF40A0E0;

    private final Random rng = new Random(3);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float[] hsv = new float[3];

    private float time, dt;
    private final int[] style = {-1, -1}, prevStyle = {-1, -1}, motion = new int[2];
    private final float[] size = new float[2], ph = new float[2];
    private final boolean[] want = new boolean[2];
    private final float[] slide = {1f, 1f}, tmr = new float[2], sinceStyle = new float[2];
    private int lastSection = -1;

    // physics and detectors
    private final float[] x = new float[2], v = new float[2], hold = new float[2];
    private final float[] vf = new float[2], vs = new float[2], var = new float[2], pvf = new float[2], lastTrig = new float[2];
    private final float[] shake = new float[2], tick = new float[2], flash = new float[2];
    private final float[] ringT = {-1f, -1f, -1f, -1f, -1f, -1f}, ringA = new float[6];
    private int ringN0, ringN1;
    private final float[] lastAmp = new float[2];
    private final int[] trig = new int[2];
    private final boolean[] sparkReq = new boolean[2];
    private int hats, arcCount, sparkCount;
    private float logT;

    // geometry of the current frame
    private float gy, topX0, topY0, topX1, topY1;
    private final float[] cx = new float[2], hh = new float[2], ww = new float[2];
    private final float[] coneX = new float[2], coneY = new float[2];

    // the world behind: buildings with windows, or two ridges
    private float worldW, worldH;
    private static final int NB = 28, NW = 380;
    private final float[] bX = new float[NB], bW = new float[NB], bH = new float[NB];
    private final boolean[] bFar = new boolean[NB];
    private final float[] wX = new float[NW], wY = new float[NW], wBand = new float[NW], wThr = new float[NW],
            wLit = new float[NW], wCol = new float[NW], wPh = new float[NW];
    private final int[] wB = new int[NW];
    private int wn;
    private final float[] ridge = new float[2 * 33];
    private float ridgeSeed;
    private float rippleT = -1f, rippleX;
    private float scroll, sweep, glitchT, glitchY, glitchGap;

    // arcs, sparks, dots
    private final float[][] arcX = new float[3][17], arcY = new float[3][17];
    private final float[][][] brX = new float[3][2][6], brY = new float[3][2][6];
    private final float[] arcAge = {-1f, -1f, -1f}, arcRegen = new float[3], arcLife = {0.5f, 0.5f, 0.5f};
    private float lastArc, dropT, dropSpark;
    // where each speaker stands (fraction of the width) and how big, easing over ~2 s when a section changes
    private final float[] pxC = {0.19f, 0.81f}, pxT = {0.19f, 0.81f}, szT = new float[2];
    private float sinceMove;
    private static final int NS = 96;
    private final float[] sx = new float[NS], sy = new float[NS], svx = new float[NS], svy = new float[NS], sl = new float[NS];
    private final byte[] sc = new byte[NS];
    private int sNext;
    private final float[][] dots = new float[2][1800];
    private final int[] dotN = new int[2];

    void setPalette(int p0, int p1, int p2, int horizon, int darkC) {
        a0 = p0 | 0xFF000000;
        a1 = p1 | 0xFF000000;
        a2 = p2 | 0xFF000000;
        hor = horizon | 0xFF000000;
        dark = darkC | 0xFF000000;
        Color.colorToHSV(a0, hsv);
        hsv[0] = (hsv[0] + 180f) % 360f;
        hsv[1] = Math.max(0.55f, hsv[1]);
        hsv[2] = Math.max(0.85f, hsv[2]);
        comp = Color.HSVToColor(hsv);
    }

    private int songSeed, pendSetting = -1;
    private boolean poolHard, poolCalm;
    private float sceneFade = 1f;

    /** A new scene on a bar line: new setting, new speaker design (or none), crossfaded through a dip. */
    void newScene(int idx, int sec, int sceneId) {
        if (idx <= 1) return;
        Random r = new Random(songSeed * 131L + sceneId * 17L + 5L); // the same scene id returns to the same look
        int[] pool = poolHard ? HARD_SET : poolCalm ? SOFT_SET : null;
        int pick, guard = 0;
        do {
            pick = pool == null ? r.nextInt(9) : pool[r.nextInt(pool.length)];
        } while (pick == setting && ++guard < 20);
        pendSetting = pick;
        sceneNone = r.nextFloat() < 0.18f || pick == 4;
        sceneStyleSeed = r.nextLong();
        if (debug) Log.i("dumbtvscene", "scene=" + idx + " section=" + sec + " id=" + sceneId + " setting=" + pick
                + " speakers=" + (sceneNone ? "none" : "pair"));
    }

    private boolean sceneNone;
    private long sceneStyleSeed;

    void newSong(int seed, int pack, boolean hardTag) {
        songSeed = seed;
        poolHard = hardTag;
        poolCalm = pack == 1 || pack == 5 || pack == 6;
        pendSetting = -1;
        sceneFade = 1f;
        rng.setSeed(seed * 31L + 7L);
        kind = (pack == 0 || pack == 2 || pack == 3 || pack == 8) ? 0 : 1;
        // one setting per song from its seed and mood, never the same as the song before
        boolean calm = pack == 1 || pack == 5 || pack == 6;
        int[] pool = hardTag ? HARD_SET : calm ? SOFT_SET : null;
        int pick;
        int guard = 0;
        do {
            pick = pool == null ? rng.nextInt(9) : pool[rng.nextInt(pool.length)];
        } while (pick == lastSetting && ++guard < 20);
        setting = pick;
        lastSetting = pick;
        if (debug) Log.i(TAG, "setting=" + setting + " hard=" + hardTag);
        worldW = 0f; // rebuild the world for this song
        ridgeSeed = rng.nextFloat() * 20f;
        for (int s = 0; s < 2; s++) {
            want[s] = false;
            slide[s] = 1f;
            tmr[s] = 6f + rng.nextFloat() * 5f + s * 1.5f; // they come in on the first drop, or after a while
            style[s] = -1;
            x[s] = v[s] = hold[s] = 0f;
        }
        lastSection = -1;
    }

    private void restyle(int s) {
        if (s == 1) return;
        int other = style[1 - s], st, tries = 0;
        do {
            st = rng.nextInt(NAMES.length);
        } while ((st == style[s] || st == prevStyle[s] && tries < 3 || st == other && tries < 6) && ++tries < 12);
        prevStyle[s] = style[s];
        style[s] = st;
        motion[s] = rng.nextInt(2);
        size[s] = szT[s] = 0.20f + 0.12f * rng.nextFloat(); // 20-32% of the screen height
        pxC[0] = pxT[0] = 0.12f + 0.13f * rng.nextFloat();
        pxC[1] = pxT[1] = 1f - pxC[0];
        ph[s] = rng.nextFloat() * TAU;
        sinceStyle[s] = 0f;
        // a perforated panel behind the drivers: dots in cabinet-local units, built once per style
        float[] d = DRV[st];
        float step = 0.04f, bwN = BW[st];
        int n = 0;
        float[] out = dots[s];
        for (float py = 0.03f; py < 0.99f; py += step) {
            for (float px = -bwN * 0.5f + 0.05f; px < bwN * 0.5f - 0.04f; px += step) {
                boolean hit = false;
                for (int i = 0; i < d.length; i += 4) {
                    float ddx = px - d[i], ddy = py - d[i + 1], rr = d[i + 2] * 1.2f;
                    if (ddx * ddx + ddy * ddy < rr * rr) {
                        hit = true;
                        break;
                    }
                }
                if (!hit && n + 2 <= out.length) {
                    out[n++] = px;
                    out[n++] = py;
                }
            }
        }
        dotN[s] = n;
        style[1] = st;
        prevStyle[1] = prevStyle[0];
        motion[1] = motion[0];
        size[1] = szT[1] = size[0];
        ph[1] = ph[0];
        sinceStyle[1] = 0f;
        dotN[1] = n;
        System.arraycopy(dots[0], 0, dots[1], 0, n);
        if (debug) Log.i(TAG, (s == 0 ? "L " : "R ") + "style=" + NAMES[st] + " size=" + Math.round(size[s] * 100) + "% motion=" + motion[s]);
    }

    /** Adaptive transient detector: how far the fast level jumps over the slow one, in units of its own deviation. */
    private float detect(int s, float in, float gap) {
        vf[s] += (in - vf[s]) * Math.min(1f, dt / 0.04f);
        float d = vf[s] - vs[s];
        var[s] += (d * d - var[s]) * Math.min(1f, dt / 2f);
        vs[s] += (in - vs[s]) * Math.min(1f, dt / 0.9f);
        float sd = (float) Math.sqrt(var[s]) + 0.02f, amp = 0f;
        if (d > 1.4f * sd && d > 0.05f && vf[s] > pvf[s] && time - lastTrig[s] > gap) {
            amp = Math.min(1f, d / (3.2f * sd));
            lastTrig[s] = time;
        }
        pvf[s] = vf[s];
        return amp;
    }

    private void startRing(int s) {
        int i = s == 0 ? ringN0++ % 3 : 3 + ringN1++ % 3;
        ringT[i] = 0f;
        ringA[i] = 0.4f;
    }

    private float pendA, pendT;

    /** One kick on cabinet s: spring impulse, pressure ring, sparks, shake. */
    private void kickHit(int s, float amp) {
        v[s] += amp * 20f;
        lastAmp[s] = amp;
        trig[s]++;
        if (amp > 0.6f) {
            startRing(s);
            if (s == 0 && (rippleT < 0f || rippleT > 0.5f)) {
                rippleT = 0f;
                rippleX = cx[0];
            }
        }
        if (amp > 0.8f) startSparks(s, 10);
        if (amp > 0.85f) shake[s] = 1f;
    }

    private void startSparks(int s, int n) {
        if (n >= 0) return; // sparks and electric glow are gone from the speakers
        sparkReq[s] = true;
        sparkCount += n;
    }

    private void update() {
        time += dt;
        float es = 0.4f + 0.9f * Math.min(1f, ener * 1.5f);
        float sf = section == 2 ? 1.15f : section == 0 ? 0.85f : section == 3 ? 0.65f : 1f;
        float scale = es * sf;

        float aL = detect(0, bass, 0.16f);
        if (kickNow) aL = Math.max(aL, 0.8f);
        float aR = detect(1, flux * 5f, 0.12f);
        if (snareNow) aR = Math.max(aR, 0.9f);
        aL = Math.min(1.2f, aL * scale);
        aR = Math.min(1.2f, aR * scale);
        if (aL > 0.05f) {
            kickHit(0, aL);
            pendA = aL;
            pendT = 0.02f;
        }
        if (pendT > 0f) {
            pendT -= dt;
            if (pendT <= 0f) {
                kickHit(1, pendA);
                pendA = 0f;
            }
        }
        if (aR > 0.05f) { // snare: a crack of sparks off both cabinets and a flash on the rings
            flash[0] = flash[1] = 1f;
            if (aR > 0.6f) {
                startSparks(0, 6);
                startSparks(1, 6);
            }
            if (aR > 0.8f) {
                startRing(0);
                startRing(1);
            }
        }
        if (hatNow) {
            tick[1] = 1f;
            tick[0] = Math.max(tick[0], 0.25f);
            hats++;
            if (time - glitchGap > 0.25f) {
                glitchGap = time;
                glitchT = 1f;
                glitchY = 0.1f + 0.8f * rng.nextFloat();
            }
        }
        glitchT = Math.max(0f, glitchT - dt * 7f);
        // 808: a deep note holds the left cone out and lets it ring down slowly
        hold[0] = Math.max(hold[0] - dt * 0.45f, sub > 0.55f ? sub * 0.55f * scale : 0f);
        hold[1] = 0f;
        // the cones breathe a little with the overall level, so they are never perfectly still
        float idle = 0.03f * (float) Math.sin(time * 1.7f) * (0.4f + ener);
        for (int s = 0; s < 2; s++) {
            v[s] += (-220f * (x[s] - hold[s] - idle) - 11f * v[s]) * dt;
            x[s] += v[s] * dt;
            if (x[s] > 1.15f) {
                x[s] = 1.15f;
                v[s] = Math.min(v[s], 0f);
            } else if (x[s] < -0.3f) {
                x[s] = -0.3f;
                v[s] = Math.max(v[s], 0f);
            }
            shake[s] = Math.max(0f, shake[s] - dt * 7f);
            tick[s] = Math.max(0f, tick[s] - dt * 10f);
            flash[s] = Math.max(0f, flash[s] - dt * 6f);
            sinceStyle[s] += dt;
        }
        for (int i = 0; i < 6; i++) if (ringT[i] >= 0f) ringT[i] = ringT[i] + dt > 1.1f ? -1f : ringT[i] + dt;
        if (rippleT >= 0f) {
            rippleT += dt * 0.9f;
            if (rippleT > 1f) rippleT = -1f;
        }
        for (int i = 0; i < 3; i++) {
            if (arcAge[i] > -0.5f) {
                arcAge[i] += dt;
                if (arcAge[i] > arcLife[i]) arcAge[i] = -1f;
            }
        }
        // a drop builds a longer crackle: up to 3 arcs over 600 ms and a rising shower of sparks
        if (dropT > 0f) {
            dropT -= dt;
            dropSpark -= dt;
            if (dropSpark <= 0f) {
                dropSpark = 0.07f;
                startSparks(0, 6);
                startSparks(1, 4);
            }
        }
        sinceMove += dt;
        scroll = (scroll + dt * (0.12f + 0.5f * ener)) % 1f;
        sweep += dt * (bpm > 40f ? bpm : 100f) / 60f / 14f * TAU;
        lightVis += ((time % 80f < 14f ? 1f : 0f) - lightVis) * Math.min(1f, dt * 0.8f); // the stage lights show for ~14 s in every ~80
        for (int i = 0; i < NS; i++) {
            if (sl[i] > 0f) {
                sl[i] -= dt * 1.1f;
                svy[i] += dt * 1.5f * worldH;
                sx[i] += svx[i] * dt;
                sy[i] += svy[i] * dt;
            }
        }

        // presence: in on the drop, out for 20-40 s now and then, restyled on the way back
        if (section == 2 && lastSection != 2 && lastSection >= 0) {
            for (int s = 0; s < 2; s++) if (!want[s]) tmr[s] = Math.min(tmr[s], 0.4f + s * 0.5f);
            dropT = 0.6f;
            dropSpark = 0f;
        }
        if (section != lastSection && lastSection >= 0 && sinceMove > 6f) { // new place and size for the new section
            sinceMove = 0f;
            pxT[0] = 0.11f + 0.15f * rng.nextFloat();
            pxT[1] = 1f - pxT[0];
            szT[0] = 0.20f + 0.12f * rng.nextFloat();
            szT[1] = szT[0];
        }
        for (int s = 0; s < 2; s++) { // glide there in about two seconds
            float k = Math.min(1f, dt * 1.8f);
            pxC[s] += (pxT[s] - pxC[s]) * k;
            size[s] += (szT[s] - size[s]) * k;
        }
        if (section != lastSection && lastSection >= 0 && section != 2) {
            for (int s = 0; s < 2; s++) {
                if (want[s] && sinceStyle[s] > 25f && rng.nextFloat() < 0.4f) tmr[s] = 0f;
            }
        }
        lastSection = section;
        for (int s = 0; s < 1; s++) {
            tmr[s] -= dt;
            if (want[s]) {
                if (tmr[s] <= 0f) {
                    want[s] = false;
                    boolean quick = sinceStyle[s] > 25f && rng.nextFloat() < 0.5f;
                    tmr[s] = quick ? 2.5f + rng.nextFloat() * 2f : 20f + rng.nextFloat() * 20f;
                }
            } else if (tmr[s] <= 0f) {
                restyle(s);
                want[s] = true;
                tmr[s] = 55f + rng.nextFloat() * 70f;
            }
            float to = want[s] ? 0f : 1f;
            slide[s] += Math.signum(to - slide[s]) * Math.min(Math.abs(to - slide[s]), dt / 1.4f);
        }
        want[1] = want[0];
        slide[1] = slide[0];
        tmr[1] = tmr[0];
        logT += dt;
        if (debug && logT >= 1f) {
            Log.i(TAG, "L=" + (style[0] < 0 ? "-" : NAMES[style[0]]) + " R=" + (style[1] < 0 ? "-" : NAMES[style[1]])
                    + " backdrop=" + (kind == 0 ? "city" : "mountains") + " arcs/s=" + Math.round(arcCount / logT)
                    + " sparks/s=" + Math.round(sparkCount / logT) + " trig L/R=" + trig[0] + "/" + trig[1] + " hats=" + hats
                    + " punch L/R=" + Math.round(lastAmp[0] * 100) + "/" + Math.round(lastAmp[1] * 100)
                    + " slide=" + Math.round(slide[0] * 100) + "/" + Math.round(slide[1] * 100) + " sec=" + section);
            logT = 0f;
            arcCount = sparkCount = 0;
            trig[0] = trig[1] = hats = 0;
            lastAmp[0] = lastAmp[1] = 0f;
        }
    }

    // ---- colors --------------------------------------------------------------------------------

    private static int withA(int c, float a) {
        return (Math.max(0, Math.min(255, (int) (a * 255f))) << 24) | (c & 0xFFFFFF);
    }

    private static int mix(int p, int q, float t) {
        int r = (int) (((p >> 16) & 255) * (1f - t) + ((q >> 16) & 255) * t);
        int g = (int) (((p >> 8) & 255) * (1f - t) + ((q >> 8) & 255) * t);
        int b = (int) ((p & 255) * (1f - t) + (q & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int grey(float a, int r, int g, int b) {
        return (Math.max(0, Math.min(255, (int) (a * 255f))) << 24) | (r << 16) | (g << 8) | b;
    }

    // ---- world ---------------------------------------------------------------------------------

    private void buildWorld(float w, float h) {
        worldW = w;
        worldH = h;
        Random r = new Random(rng.nextLong());
        float px = -0.03f * w;
        wn = 0;
        for (int i = 0; i < NB; i++) {
            bW[i] = w * (0.032f + 0.04f * r.nextFloat());
            bX[i] = px;
            px += bW[i] * (0.86f + 0.2f * r.nextFloat());
            bFar[i] = r.nextFloat() < 0.45f;
            bH[i] = h * (bFar[i] ? 0.12f + 0.2f * r.nextFloat() : 0.07f + 0.2f * r.nextFloat());
            if (bFar[i]) continue;
            float ws = h * 0.011f, gap = ws * 0.9f;
            int cols = (int) ((bW[i] - gap) / (ws + gap)), rows = Math.min(9, (int) ((bH[i] - gap * 2f) / (ws + gap)));
            for (int cI = 0; cI < cols; cI++) {
                for (int rI = 0; rI < rows && wn < NW; rI++) {
                    wB[wn] = i;
                    wX[wn] = bX[i] + gap + cI * (ws + gap);
                    wY[wn] = gap * 1.5f + rI * (ws + gap); // up from the building's foot
                    wBand[wn] = Math.max(0f, Math.min(1f, (bX[i] + bW[i] * 0.5f) / w));
                    wThr[wn] = 0.12f + 0.7f * r.nextFloat();
                    wCol[wn] = r.nextFloat();
                    wPh[wn] = r.nextFloat() * 20f;
                    wn++;
                }
            }
        }
        for (int i = 0; i < 33; i++) {
            ridge[i] = 0.14f + 0.1f * (float) Math.sin(i * 0.37f + ridgeSeed) + 0.06f * (float) Math.sin(i * 0.91f + ridgeSeed * 2f)
                    + 0.035f * (float) Math.sin(i * 1.9f + ridgeSeed * 3f);
            ridge[33 + i] = 0.1f + 0.08f * (float) Math.sin(i * 0.53f + ridgeSeed * 1.7f) + 0.05f * (float) Math.sin(i * 1.3f + ridgeSeed);
        }
    }

    private float bandVal(float f) {
        if (f < 0.33f) return Math.max(sub * 0.9f, Math.max(kickEnv, bass));
        if (f < 0.66f) return Math.max(snareEnv, Math.min(1f, flux * 4f));
        return Math.max(hatEnv, ener * 0.5f);
    }

    private void bar(Canvas c, float l, float t, float r, float b, int col, float a) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withA(col, a));
        c.drawRect(l, t, r, b, paint);
    }

    /** Rooftop skyline: two depths of buildings with rooftop glow and windows lit per frequency band. */
    private void cityLayer(Canvas c, float w, float h, float a, float hs) {
        float base = gy - 0.015f * h, pulse = 0.2f + 0.8f * Math.max(kickEnv, sub * 0.7f);
        float farX = (float) Math.sin(time / 23f) * w * 0.012f, nearX = (float) Math.sin(time / 17f) * w * 0.022f;
        paint.setStyle(Paint.Style.FILL);
        int farC = mix(dark, hor, 0.38f), nearC = mix(dark, hor, 0.14f);
        for (int pass = 0; pass < 2; pass++) {
            boolean far = pass == 0;
            paint.setColor(withA(far ? farC : nearC, (far ? 0.5f : 0.7f) * a));
            for (int i = 0; i < NB; i++) {
                if (bFar[i] != far) continue;
                float bx = bX[i] + (far ? farX : nearX);
                c.drawRect(bx, base - bH[i] * hs, bx + bW[i], base, paint);
            }
        }
        paint.setColor(withA(a1, (0.2f + 0.5f * pulse) * a)); // rooftop edge glow, pulsing with the low end
        for (int i = 0; i < NB; i++) {
            float bx = bX[i] + (bFar[i] ? farX : nearX);
            c.drawRect(bx, base - bH[i] * hs - h * 0.002f, bx + bW[i], base - bH[i] * hs + h * 0.002f, paint);
        }
        int step = level >= 3 ? 2 : 1;
        float rad = rippleT >= 0f ? rippleT * w * 0.8f : -1f, ws = h * 0.011f;
        for (int i = 0; i < wn; i += step) {
            float wxA = wX[i] + nearX, wyA = base - wY[i] * hs;
            if (wY[i] * hs > bH[wB[i]] * hs) continue;
            float target = bandVal(wBand[i]) > wThr[i] ? 1f : (((int) (time * 0.35f + wPh[i]) % 6) == 0 ? 0.45f : 0f);
            if (rad >= 0f) {
                float dd = Math.abs((float) Math.sqrt((wxA - rippleX) * (wxA - rippleX) + (wyA - base) * (wyA - base) * 0.5f) - rad);
                if (dd < w * 0.07f) target = Math.max(target, 1f - dd / (w * 0.07f));
            }
            wLit[i] += (target - wLit[i]) * Math.min(1f, dt * 10f);
            if (wLit[i] < 0.04f) continue;
            paint.setColor(withA(mix(a0, comp, wCol[i] * wCol[i]), 0.85f * wLit[i] * a));
            c.drawRect(wxA, wyA - ws, wxA + ws, wyA, paint);
        }
    }

    private void ridges(Canvas c, float w, float h, float a, float tint) {
        float base = gy - 0.015f * h, pulse = 0.2f + 0.8f * Math.max(kickEnv, sub * 0.7f);
        float farX = (float) Math.sin(time / 23f) * w * 0.012f, nearX = (float) Math.sin(time / 17f) * w * 0.022f;
        for (int pass = 0; pass < 2; pass++) {
            float shift = pass == 0 ? farX : nearX, hs = pass == 0 ? 1.55f : 1f;
            path.rewind();
            path.moveTo(-w * 0.05f, base);
            for (int i = 0; i < 33; i++) path.lineTo(i * (w * 1.1f / 32f) - w * 0.05f + shift, base - ridge[pass * 33 + i] * h * hs);
            path.lineTo(w * 1.05f, base);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withA(pass == 0 ? mix(dark, hor, 0.4f + tint) : mix(dark, hor, 0.15f), (pass == 0 ? 0.5f : 0.72f) * a));
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(h * 0.003f);
            paint.setColor(withA(pass == 0 ? a2 : a1, (0.12f + 0.4f * pulse) * a));
            c.drawPath(path, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /** Light pairs sliding along a lane: headlights of passing cars. */
    private void cars(Canvas c, float w, float y, float size, int n, float a) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            float sp = 0.05f + 0.035f * i, dirv = (i & 1) == 0 ? 1f : -1f;
            float p = ((time * sp * dirv + i * 0.37f) % 1f + 1f) % 1f, x0 = p * w * 1.2f - w * 0.1f;
            int col = dirv > 0 ? 0xFFFFE8B0 : 0xFFE03030;
            paint.setColor(withA(col, 0.18f * a));
            c.drawCircle(x0, y, size * 2.6f, paint);
            c.drawCircle(x0 + size * 3f, y, size * 2.6f, paint);
            paint.setColor(withA(col, 0.9f * a));
            c.drawCircle(x0, y, size * 0.8f, paint);
            c.drawCircle(x0 + size * 3f, y, size * 0.8f, paint);
        }
    }

    private void backdrop(Canvas c, float w, float h, float a) {
        float base = gy - 0.015f * h, pulse = 0.2f + 0.8f * Math.max(kickEnv, sub * 0.7f);
        paint.setStyle(Paint.Style.FILL);
        switch (setting) {
            case 1: { // street at night: tall near buildings, street lamps, passing cars
                cityLayer(c, w, h, a, 1.5f);
                for (int i = 0; i < 6; i++) {
                    float lx = ((i / 6f) * w * 1.1f + (float) Math.sin(time / 15f) * w * 0.03f) % (w * 1.1f);
                    bar(c, lx, base - h * 0.2f, lx + h * 0.006f, base, dark, 0.8f * a);
                    paint.setColor(withA(a0, (0.14f + 0.12f * pulse) * a));
                    c.drawCircle(lx, base - h * 0.2f, h * 0.05f, paint);
                    paint.setColor(withA(0xFFFFE0A0, 0.8f * a));
                    c.drawCircle(lx, base - h * 0.2f, h * 0.008f, paint);
                }
                cars(c, w, gy + h * 0.04f, h * 0.008f, 3, a);
                break;
            }
            case 2: { // highway overpass: a slab across the frame on pillars, traffic above and below
                cityLayer(c, w, h, a, 0.55f);
                float sy = h * 0.27f;
                bar(c, 0, sy, w, sy + h * 0.06f, mix(dark, hor, 0.1f), 0.92f * a);
                bar(c, 0, sy + h * 0.06f, w, sy + h * 0.2f, 0xFF000000, 0.35f * a);
                for (int i = 0; i < 5; i++) bar(c, w * (0.1f + 0.2f * i), sy + h * 0.06f, w * (0.1f + 0.2f * i) + w * 0.03f, base, mix(dark, hor, 0.12f), 0.9f * a);
                cars(c, w, sy - h * 0.008f, h * 0.006f, 3, a);
                paint.setColor(withA(a1, (0.25f + 0.4f * pulse) * a)); // light strip under the slab
                c.drawRect(0, sy + h * 0.06f, w, sy + h * 0.064f, paint);
                break;
            }
            case 3: { // harbor: cranes, a ship, water with shimmer
                cityLayer(c, w, h, a, 0.4f);
                float wy = gy - 0.13f * h, shift = (float) Math.sin(time / 19f) * w * 0.02f;
                for (int i = 0; i < 3; i++) {
                    float cxc = w * (0.18f + 0.3f * i) + shift;
                    bar(c, cxc, wy - h * 0.2f, cxc + h * 0.008f, wy, dark, 0.85f * a);
                    bar(c, cxc - h * 0.1f, wy - h * 0.2f, cxc + h * 0.15f, wy - h * 0.19f + h * 0.006f, dark, 0.85f * a);
                    paint.setColor(withA(a1, (0.4f + 0.5f * (((int) (time * 1.3f + i) & 1))) * a));
                    c.drawCircle(cxc + h * 0.004f, wy - h * 0.205f, h * 0.007f, paint);
                }
                float sx = ((time * 0.012f) % 1.3f) * w - w * 0.15f;
                path.rewind();
                path.moveTo(sx, wy - h * 0.045f);
                path.lineTo(sx + h * 0.34f, wy - h * 0.045f);
                path.lineTo(sx + h * 0.3f, wy);
                path.lineTo(sx + h * 0.03f, wy);
                path.close();
                paint.setColor(withA(mix(dark, hor, 0.15f), 0.9f * a));
                c.drawPath(path, paint);
                bar(c, 0, wy, w, gy - h * 0.015f, mix(dark, a1, 0.18f), 0.85f * a);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1.5f, h * 0.002f));
                for (int i = 0; i < 10; i++) {
                    float yy = wy + h * 0.012f * (i + 1), off2 = (float) Math.sin(time * 0.9f + i * 1.7f) * w * 0.03f;
                    paint.setColor(withA(mix(a0, 0xFFFFFFFF, 0.3f), (0.06f + 0.14f * Math.max(0f, (float) Math.sin(time * 1.3f + i))) * a));
                    c.drawLine(w * (0.1f + 0.09f * i) + off2, yy, w * (0.1f + 0.09f * i) + off2 + w * 0.05f, yy, paint);
                }
                break;
            }
            case 4: { // desert road: ridges and poles ticking past
                ridges(c, w, h, a, 0.12f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(2f, h * 0.0035f));
                float prevX = Float.NaN, prevY = 0;
                for (int i = 0; i < 7; i++) {
                    float p = ((i / 7f) - time * 0.02f) % 1f;
                    if (p < 0) p += 1f;
                    float px = p * w * 1.2f - w * 0.1f, ph2 = h * (0.1f + 0.12f * p);
                    paint.setColor(withA(dark, 0.9f * a));
                    c.drawLine(px, base, px, base - ph2, paint);
                    c.drawLine(px - ph2 * 0.3f, base - ph2 * 0.9f, px + ph2 * 0.3f, base - ph2 * 0.9f, paint);
                    if (!Float.isNaN(prevX) && Math.abs(px - prevX) < w * 0.3f) {
                        paint.setColor(withA(dark, 0.6f * a));
                        c.drawLine(prevX, prevY, px, base - ph2 * 0.9f, paint);
                    }
                    prevX = px;
                    prevY = base - ph2 * 0.9f;
                }
                // heat shimmer
                paint.setStrokeWidth(Math.max(1.5f, h * 0.002f));
                for (int i = 0; i < 6; i++) {
                    paint.setColor(withA(a0, 0.05f * a));
                    float yy = base - h * 0.02f * i, off2 = (float) Math.sin(time * 1.4f + i) * w * 0.02f;
                    c.drawLine(w * 0.2f + off2, yy, w * 0.8f + off2, yy, paint);
                }
                paint.setStyle(Paint.Style.FILL);
                break;
            }
            case 5: { // studio booth: acoustic panels, glass, a console with faders
                bar(c, 0, h * 0.1f, w, gy, mix(dark, hor, 0.08f), 0.8f * a);
                int cols = 12, rows = 4;
                for (int rI = 0; rI < rows; rI++) {
                    for (int cI = 0; cI < cols; cI++) {
                        float px0 = w * cI / cols, py0 = h * (0.12f + 0.12f * rI);
                        bar(c, px0 + 3f, py0 + 3f, px0 + w / cols - 3f, py0 + h * 0.12f - 3f, ((cI + rI) & 1) == 0 ? 0xFF000000 : mix(dark, hor, 0.22f), 0.35f * a);
                    }
                }
                bar(c, w * 0.34f, h * 0.2f, w * 0.66f, h * 0.45f, mix(a1, dark, 0.7f), 0.55f * a); // booth glass
                bar(c, w * 0.34f, h * 0.2f, w * 0.66f, h * 0.205f, 0xFFFFFFFF, 0.1f * a);
                paint.setColor(withA(0xFFE02020, (0.3f + 0.6f * (((int) (time * 1.1f)) & 1)) * a)); // recording light
                c.drawCircle(w * 0.5f, h * 0.17f, h * 0.012f, paint);
                float cy2 = gy - h * 0.09f;
                path.rewind();
                path.moveTo(w * 0.28f, cy2);
                path.lineTo(w * 0.72f, cy2);
                path.lineTo(w * 0.78f, gy);
                path.lineTo(w * 0.22f, gy);
                path.close();
                paint.setColor(withA(mix(dark, hor, 0.2f), 0.95f * a));
                c.drawPath(path, paint);
                for (int i = 0; i < 18; i++) { // faders follow the music
                    float fx = w * (0.3f + 0.4f * i / 17f), lv2 = bandVal(i / 17f) * (0.5f + 0.5f * (float) Math.sin(time * 3f + i));
                    bar(c, fx, cy2 + h * 0.012f, fx + w * 0.006f, gy - h * 0.012f, 0xFF000000, 0.5f * a);
                    bar(c, fx - w * 0.003f, gy - h * 0.02f - lv2 * h * 0.05f, fx + w * 0.009f, gy - h * 0.012f - lv2 * h * 0.05f, mix(a0, comp, i / 17f), 0.85f * a);
                }
                break;
            }
            case 6: { // stadium: stands with phone lights, floodlight towers
                float top2 = h * 0.34f;
                path.rewind();
                path.moveTo(-w * 0.05f, base);
                path.lineTo(w * 0.1f, top2);
                path.lineTo(w * 0.9f, top2);
                path.lineTo(w * 1.05f, base);
                path.close();
                paint.setColor(withA(mix(dark, hor, 0.12f), 0.82f * a));
                c.drawPath(path, paint);
                for (int i = 0; i < 90; i++) {
                    float fx = rngHash(i) * w, fy = top2 + (base - top2) * (0.1f + 0.85f * rngHash(i + 200));
                    float bl = 0.35f + 0.65f * (float) Math.max(0, Math.sin(time * (0.8f + rngHash(i + 50) * 2f) + i));
                    paint.setColor(withA(mix(0xFFFFFFFF, comp, rngHash(i + 90) * 0.5f), (0.15f + 0.5f * bl * (0.4f + 0.6f * ener)) * a));
                    c.drawCircle(fx, fy, h * 0.0035f, paint);
                }
                for (int side2 = 0; side2 < 2; side2++) {
                    float tx = side2 == 0 ? w * 0.05f : w * 0.95f;
                    bar(c, tx - h * 0.006f, top2 - h * 0.1f, tx + h * 0.006f, base, dark, 0.9f * a);
                    bar(c, tx - h * 0.04f, top2 - h * 0.14f, tx + h * 0.04f, top2 - h * 0.1f, 0xFFFFFFFF, (0.5f + 0.3f * pulse) * a);
                }
                break;
            }
            case 7: { // alley with neon signs, steam
                bar(c, 0, h * 0.12f, w * 0.2f, gy, mix(dark, hor, 0.12f), 0.92f * a);
                bar(c, w * 0.8f, h * 0.12f, w, gy, mix(dark, hor, 0.12f), 0.92f * a);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, h * 0.0015f));
                for (int i = 0; i < 16; i++) {
                    float by2 = h * 0.12f + (gy - h * 0.12f) * i / 16f;
                    paint.setColor(withA(0xFF000000, 0.25f * a));
                    c.drawLine(0, by2, w * 0.2f, by2, paint);
                    c.drawLine(w * 0.8f, by2, w, by2, paint);
                }
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withA(mix(a0, 0xFFFFFFFF, 0.3f), (0.08f + 0.08f * pulse) * a)); // far end of the alley
                c.drawRect(w * 0.42f, h * 0.3f, w * 0.58f, gy - h * 0.02f, paint);
                for (int i = 0; i < 5; i++) {
                    float sxn = i < 3 ? w * (0.02f + 0.05f * i) : w * (0.84f + 0.05f * (i - 3)), syn = h * (0.25f + 0.13f * (i % 3));
                    float flick = 0.7f + 0.3f * (float) Math.sin(time * (7f + i * 3f)) * (rngHash(i + 7) > 0.6f ? 1f : 0.2f);
                    int nc = i % 3 == 0 ? a0 : i % 3 == 1 ? comp : a1;
                    paint.setColor(withA(nc, 0.16f * flick * a));
                    c.drawRect(sxn - h * 0.012f, syn - h * 0.012f, sxn + w * 0.04f + h * 0.012f, syn + h * 0.06f + h * 0.012f, paint);
                    paint.setColor(withA(mix(nc, 0xFFFFFFFF, 0.3f), 0.9f * flick * a));
                    c.drawRect(sxn, syn, sxn + w * 0.04f, syn + h * 0.06f, paint);
                }
                for (int i = 0; i < 6; i++) { // steam rising from the vents
                    float p = ((time * 0.14f + i * 0.17f) % 1f), sx2 = w * (i < 3 ? 0.24f + 0.03f * i : 0.7f + 0.03f * i);
                    paint.setColor(withA(0xFFFFFFFF, 0.12f * (1f - p) * a));
                    c.drawCircle(sx2 + (float) Math.sin(p * 6f + i) * w * 0.01f, base - p * h * 0.3f, h * (0.02f + 0.05f * p), paint);
                }
                break;
            }
            case 8: { // subway platform: tiled wall, pillars, a train rolling in
                bar(c, 0, h * 0.18f, w, gy - h * 0.1f, mix(dark, hor, 0.16f), 0.85f * a);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, h * 0.0015f));
                paint.setColor(withA(0xFFFFFFFF, 0.05f * a));
                for (int i = 0; i < 24; i++) c.drawLine(w * i / 24f, h * 0.18f, w * i / 24f, gy - h * 0.1f, paint);
                for (int i = 0; i < 8; i++) c.drawLine(0, h * 0.18f + (gy - h * 0.28f) * i / 8f, w, h * 0.18f + (gy - h * 0.28f) * i / 8f, paint);
                paint.setStyle(Paint.Style.FILL);
                float tp = ((time / 22f) % 1f) * 1.7f - 0.35f, tx = tp * w;
                bar(c, tx, gy - h * 0.21f, tx + w * 0.55f, gy - h * 0.03f, mix(dark, hor, 0.3f), 0.92f * a);
                for (int i = 0; i < 9; i++) bar(c, tx + w * (0.02f + 0.06f * i), gy - h * 0.17f, tx + w * (0.055f + 0.06f * i), gy - h * 0.1f, 0xFFFFE8B0, 0.7f * a);
                paint.setColor(withA(0xFFFFFFFF, 0.5f * a));
                c.drawCircle(tx + w * 0.55f, gy - h * 0.1f, h * 0.012f, paint);
                for (int i = 0; i < 5; i++) bar(c, w * (0.1f + 0.2f * i), h * 0.18f, w * (0.1f + 0.2f * i) + w * 0.025f, gy - h * 0.02f, mix(dark, hor, 0.1f), 0.9f * a);
                bar(c, 0, gy - h * 0.025f, w, gy - h * 0.015f, 0xFFE0B020, (0.4f + 0.3f * pulse) * a);
                break;
            }
            default:
                cityLayer(c, w, h, a, 1.0f);
        }
    }

    private float rngHash(int i) {
        float s = (float) Math.sin(i * 12.9898f + ridgeSeed) * 43758.545f;
        return s - (float) Math.floor(s);
    }

    /** Clouds and birds: the sky always has something moving in it. */
    /** 0..1: the piano and disk scenes own the sky, so the clouds and birds fade out. */
    float moversOff;

    private void skyMovers(Canvas c, float w, float h, float a) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) { // slow dark clouds
            float p = ((time * (0.006f + 0.003f * i) + i * 0.27f) % 1f), cx0 = p * w * 1.5f - w * 0.25f, cy0 = h * (0.07f + 0.07f * i);
            paint.setColor(withA(mix(dark, hor, 0.3f), 0.2f * a));
            for (int k = 0; k < 3; k++) {
                rect.set(cx0 + (k - 1) * h * 0.1f - h * 0.12f, cy0 - h * 0.025f, cx0 + (k - 1) * h * 0.1f + h * 0.12f, cy0 + h * 0.025f + k * h * 0.004f);
                c.drawOval(rect, paint);
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, h * 0.0025f));
        if (hard < 0.5f) { // birds, calm songs
            for (int i = 0; i < 6; i++) {
                float p = ((time * (0.03f + 0.004f * i) + i * 0.13f) % 1f), bx = p * w * 1.2f - w * 0.1f, by = h * (0.14f + 0.12f * ((i * 7) % 5) / 5f) + (float) Math.sin(time * 0.7f + i) * h * 0.012f;
                float fl = (float) Math.sin(time * 7f + i * 2f) * h * 0.008f;
                paint.setColor(withA(dark, 0.55f * a));
                c.drawLine(bx - h * 0.014f, by - fl, bx, by, paint);
                c.drawLine(bx, by, bx + h * 0.014f, by - fl, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private float lightVis;

    private void searchlights(Canvas c, float w, float h, float a) {
        if (lightVis < 0.02f) return;
        a *= lightVis;
        int n = level >= 3 ? 2 : 3;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            float ox = w * (0.32f + 0.18f * i), ang = (float) Math.sin(sweep * (i == 1 ? -1f : 1f) + i * 2.1f) * 0.55f + lightSwing * (i == 1 ? -0.6f : 0.6f);
            float len = h * 1.1f, half = (0.045f + 0.02f * (float) Math.sin(time / 3f + i)) * (1f - 0.45f * hard);
            float x1 = ox + (float) Math.sin(ang - half) * len, y1 = gy - (float) Math.cos(ang - half) * len;
            float x2 = ox + (float) Math.sin(ang + half) * len, y2 = gy - (float) Math.cos(ang + half) * len;
            path.rewind();
            path.moveTo(ox, gy - h * 0.02f);
            path.lineTo(x1, y1);
            path.lineTo(x2, y2);
            path.close();
            paint.setColor(withA(mix(mix(i == 1 ? comp : a1, i == 1 ? a1 : comp, lightMix), 0xFFFFFFFF, 0.45f), (0.05f + 0.05f * Math.max(bass, kickEnv)) * (1f + 1.8f * hard) * a));
            c.drawPath(path, paint);
        }
    }

    private void floor(Canvas c, float w, float h, float a) {
        float lip = gy - 0.012f * h;
        paint.setStyle(Paint.Style.FILL);
        int bands = 8;
        float fh = h - lip;
        int fcol = setting == 6 ? mix(dark, 0xFF204a30, 0.35f) : setting == 5 ? mix(dark, hor, 0.25f) : dark;
        for (int i = 0; i < bands; i++) {
            paint.setColor(withA(mix(fcol, 0xFF000000, i / (float) bands * 0.6f), (0.5f + 0.4f * i / bands) * a));
            c.drawRect(0, lip + fh * i / bands, w, lip + fh * (i + 1) / bands + 1f, paint);
        }
        // stage lip light
        paint.setColor(withA(a1, (0.35f + 0.4f * Math.max(kickEnv, sub * 0.6f)) * a));
        c.drawRect(0, lip, w, lip + h * 0.004f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, h * 0.0022f));
        if (setting == 1 || setting == 2 || setting == 4) { // road: lane lines converging, dashes rushing towards the viewer
            for (int i = 0; i < 6; i++) {
                float f = (i + scroll) / 6f, yy = lip + fh * f * f, half = (0.004f + 0.02f * f) * w;
                paint.setStrokeWidth(Math.max(1.5f, h * (0.002f + 0.006f * f)));
                paint.setColor(withA(0xFFF0E0B0, (0.1f + 0.35f * f) * a));
                c.drawLine(w * 0.5f - half, yy, w * 0.5f + half, yy, paint);
            }
            paint.setStrokeWidth(Math.max(1.5f, h * 0.002f));
            paint.setColor(withA(a0, 0.12f * a));
            c.drawLine(w * 0.5f - w * 0.02f, lip, w * 0.12f, h, paint);
            c.drawLine(w * 0.5f + w * 0.02f, lip, w * 0.88f, h, paint);
        } else if (setting == 6) { // pitch: stripes and a chalk line
            for (int i = 0; i < 5; i++) {
                float f0 = (i + 0.0f) / 5f, f1 = (i + 1f) / 5f;
                if ((i & 1) == 0) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(withA(0xFFFFFFFF, 0.04f * a));
                    c.drawRect(0, lip + fh * f0 * f0, w, lip + fh * f1 * f1, paint);
                    paint.setStyle(Paint.Style.STROKE);
                }
            }
            paint.setColor(withA(0xFFFFFFFF, 0.2f * a));
            c.drawLine(0, lip + fh * 0.35f, w, lip + fh * 0.35f, paint);
        } else if (setting == 7) { // wet street: neon streaks running down
            for (int i = 0; i < 5; i++) {
                float sx2 = w * (0.18f + 0.16f * i), flick = 0.5f + 0.5f * (float) Math.sin(time * (3f + i));
                paint.setStrokeWidth(Math.max(3f, h * 0.006f));
                paint.setColor(withA(i % 3 == 0 ? a0 : i % 3 == 1 ? comp : a1, (0.05f + 0.07f * flick) * a));
                c.drawLine(sx2, lip, sx2 + (i - 2) * w * 0.02f, h, paint);
            }
        } else if (setting == 8) { // platform: tiles and the yellow edge
            for (int i = 0; i < 6; i++) {
                float f = (i + 0.5f) / 6f, yy = lip + fh * f * f;
                paint.setColor(withA(0xFFFFFFFF, 0.05f * a));
                c.drawLine(0, yy, w, yy, paint);
            }
            for (int i = -6; i <= 6; i++) c.drawLine(w * 0.5f + i * w * 0.02f, lip, w * 0.5f + i * w * 0.15f, h, paint);
        } else if (setting == 3) { // pier planks
            for (int i = -7; i <= 7; i++) {
                paint.setColor(withA(0xFF000000, 0.25f * a));
                c.drawLine(w * 0.5f + i * w * 0.03f, lip, w * 0.5f + i * w * 0.13f, h, paint);
            }
        } else { // grid rushing toward the viewer
            for (int i = 0; i < 6; i++) {
                float f = (i + scroll) / 6f, yy = lip + fh * f * f;
                paint.setColor(withA(a0, (0.04f + 0.12f * f) * a));
                c.drawLine(0, yy, w, yy, paint);
            }
            for (int i = -5; i <= 5; i++) {
                paint.setColor(withA(a0, 0.07f * a));
                c.drawLine(w * 0.5f + i * w * 0.02f, lip, w * 0.5f + i * w * 0.2f, h, paint);
            }
        }
        // pressure rings travelling across the floor
        for (int i = 0; i < 6; i++) {
            if (ringT[i] < 0f) continue;
            float t = ringT[i] / 1.1f, rx = t * w * 0.4f, ry = rx * 0.12f, ox = cx[i / 3];
            paint.setStrokeWidth(Math.max(2f, h * 0.0035f));
            paint.setColor(withA(mix(i < 3 ? a0 : a2, 0xFFFFFFFF, 0.3f), ringA[i] * (1f - t) * a));
            rect.set(ox - rx, gy + h * 0.015f - ry, ox + rx, gy + h * 0.015f + ry);
            c.drawOval(rect, paint);
        }
    }

    // ---- speakers ------------------------------------------------------------------------------

    void draw(Canvas c, float w, float h, float dtIn) {
        dt = Math.min(0.033f, dtIn);
        if (w != worldW || h != worldH) buildWorld(w, h);
        gy = h * GY[setting];
        cx[0] = w * pxC[0] + (float) Math.sin(time / 19f + ph[0]) * w * 0.012f;
        cx[1] = w - cx[0];
        update();
        if (pendSetting >= 0) {
            sceneFade -= dt;
            if (sceneFade <= 0f) {
                sceneFade = 0f;
                setting = pendSetting;
                lastSetting = setting;
                pendSetting = -1;
                worldW = 0f;
                buildWorld(w, h);
                rng.setSeed(sceneStyleSeed);
                restyle(0);
                want[0] = want[1] = !sceneNone;
                slide[0] = slide[1] = sceneNone ? 1f : 0f;
                tmr[0] = tmr[1] = sceneNone ? 14f : 40f;
                if (debug) Log.i("dumbtvscene", "design=" + NAMES[Math.max(0, style[0])] + " setting=" + setting + " none=" + sceneNone);
            }
        } else if (sceneFade < 1f) {
            sceneFade = Math.min(1f, sceneFade + dt);
        }
        float a0v = Math.min(0.8f, alpha) * (sceneFade * sceneFade * (3f - 2f * sceneFade)), a = a0v * stageVis;
        if (a0v <= 0.01f) return;
        c.save();
        if (hasCard) c.clipRect(cl, ct, cr, cb, Region.Op.DIFFERENCE);
        if (moversOff < 0.99f) skyMovers(c, w, h, a0v * (1f - moversOff));
        if (a > 0.01f) {
            backdrop(c, w, h, a);
            searchlights(c, w, h, a);
            floor(c, w, h, a);
            for (int s = 0; s < 2; s++) {
                if (setting == 4 || style[s] < 0 || slide[s] > 0.995f) continue; // the desert road has no speakers
                drawOne(c, s, w, h, a);
            }
            if (setting != 4) {
                sparks(c, h, a);
            }
            if (glitchT > 0.02f) { // a thin, faint scanline tear on hats
                paint.setStyle(Paint.Style.FILL);
                float gyy = glitchY * h;
                paint.setColor(withA(0xFFFFFFFF, 0.10f * glitchT * a));
                c.drawRect(0, gyy, w, gyy + Math.max(2f, h * 0.003f), paint);
                paint.setColor(withA(comp, 0.07f * glitchT * a));
                c.drawRect(w * 0.1f * glitchT, gyy + h * 0.006f, w, gyy + h * 0.008f, paint);
            }
        }
        c.restore();
    }

    private void body(Canvas c, float W, float H, float a, boolean lightOnRight, boolean fine) {
        paint.setStyle(Paint.Style.FILL);
        float rr = H * 0.02f;
        int base = mix(0xFF22232B, mix(dark, hor, 0.3f), 0.35f);
        paint.setColor(withA(base, 0.95f * a));
        rect.set(0, 0, W, H);
        c.drawRoundRect(rect, rr, rr, paint);
        // depth: the body darkens towards the floor
        for (int i = 0; i < 6; i++) {
            paint.setColor(withA(0xFF000000, 0.05f * i * a));
            c.drawRect(0, H * i / 6f, W, H * (i + 1) / 6f, paint);
        }
        // bevel: lit edge, shaded edge, top edge, plinth
        float bevel = W * 0.07f;
        paint.setColor(withA(0xFFFFFFFF, 0.13f * a));
        if (lightOnRight) c.drawRect(W - bevel, rr, W, H - rr, paint);
        else c.drawRect(0, rr, bevel, H - rr, paint);
        paint.setColor(withA(0xFF000000, 0.32f * a));
        if (lightOnRight) c.drawRect(0, rr, bevel, H - rr, paint);
        else c.drawRect(W - bevel, rr, W, H - rr, paint);
        paint.setColor(withA(0xFFFFFFFF, 0.14f * a));
        c.drawRect(rr, 0, W - rr, H * 0.018f, paint);
        paint.setColor(withA(0xFF000000, 0.45f * a));
        c.drawRect(0, H * 0.965f, W, H, paint);
        // recessed front panel
        paint.setColor(withA(0xFF000000, 0.2f * a));
        rect.set(bevel * 1.3f, H * 0.03f, W - bevel * 1.3f, H * 0.95f);
        c.drawRoundRect(rect, rr, rr, paint);
    }

    private void led(Canvas c, int s, float W, float H, float a, boolean fine) {
        int segs = 6;
        float chase = (time * (0.8f + ener * 2f) + s * 3f) % segs, thump = Math.min(1f, Math.max(0f, x[s]));
        float inset = W * 0.034f, top = H * 0.12f, bot = H * 0.93f, seg = (bot - top) / segs;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.BUTT);
        for (int side = 0; side < 2; side++) {
            float lx = side == 0 ? inset : W - inset;
            int col = side == 0 ? a0 : (s == 0 ? a1 : comp);
            for (int k = 0; k < segs; k++) {
                float dist = Math.abs(k - chase);
                dist = Math.min(dist, segs - dist);
                float lv = 0.28f + 0.72f * Math.max(thump, Math.max(0f, 1f - dist * 0.7f)) * (0.5f + 0.5f * ener + 0.5f * thump);
                lv = Math.min(1f, lv);
                if (fine) {
                    paint.setStrokeWidth(W * 0.06f);
                    paint.setColor(withA(col, 0.13f * lv * a));
                    c.drawLine(lx, top + k * seg + seg * 0.1f, lx, top + (k + 1) * seg - seg * 0.1f, paint);
                }
                paint.setStrokeWidth(W * 0.016f);
                paint.setColor(withA(mix(col, 0xFFFFFFFF, 0.25f * lv), 0.9f * lv * a));
                c.drawLine(lx, top + k * seg + seg * 0.1f, lx, top + (k + 1) * seg - seg * 0.1f, paint);
            }
        }
    }

    private void drawOne(Canvas c, int s, float w, float h, float a) {
        int st = style[s];
        float H = size[s] * h * SCALE[setting];
        if (hasCard && cx[0] - BW[st] * H * 0.5f < cr && cx[0] + BW[st] * H * 0.5f > cl) {
            H = Math.max(h * 0.2f, Math.min(H, gy - cb - 0.03f * h)); // never reach into the now-playing card
        }
        float W = BW[st] * H, side = s == 0 ? 1f : -1f;
        float e = slide[s] * slide[s] * (3f - 2f * slide[s]);
        float off = e * (W + w * 0.25f) * -side;
        float sh = shake[s] * shake[s] * 6f;
        float shx = (float) Math.sin(time * 70f + s) * sh, shy = (float) Math.cos(time * 63f) * sh * 0.6f;
        float bx = cx[s] - W * 0.5f + off + shx, by = gy - H + shy;
        ww[s] = W;
        hh[s] = H;
        boolean fine = level < 3;

        // floor shadow, soft and wide
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 3; i++) {
            paint.setColor(withA(0xFF000000, (0.16f - 0.04f * i) * a));
            float sw = W * (0.95f + 0.28f * i), sy2 = H * (0.035f + 0.016f * i);
            rect.set(bx + W * 0.5f - sw, gy - sy2 * 0.3f, bx + W * 0.5f + sw, gy + sy2);
            c.drawOval(rect, paint);
        }
        // faint reflection in the floor
        if (fine) {
            c.save();
            c.clipRect(0, gy, w, gy + h * 0.1f);
            c.translate(bx, gy + H);
            c.scale(1f, -1f);
            body(c, W, H, a * 0.16f, s == 1, false);
            led(c, s, W, H, a * 0.2f, false);
            c.restore();
        }

        float th = Math.max(0f, Math.min(1f, x[s])), tsc = 1f + 0.03f * th;
        c.save();
        c.translate(bx + W * 0.5f, by + H + th * 1.5f);
        c.scale(tsc, tsc);
        c.translate(-W * 0.5f, -H);
        body(c, W, H, a, s == 1, fine);
        if (fine) { // perforated metal behind the drivers
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(Math.max(1.5f, H * 0.011f));
            paint.setColor(withA(0xFFC8CCDC, 0.13f * a));
            c.save();
            c.translate(W * 0.5f + (float) Math.sin(time * 110f + s) * th * H * 0.004f, 0f);
            c.scale(H, H);
            paint.setStrokeWidth(0.011f);
            c.drawPoints(dots[s], 0, dotN[s], paint);
            c.restore();
            paint.setStrokeCap(Paint.Cap.BUTT);
        }
        led(c, s, W, H, a, fine);

        float[] d = DRV[st];
        int firstWoofer = -1;
        for (int i = 0; i < d.length; i += 4) {
            float dx = W * 0.5f + d[i] * H, dy = d[i + 1] * H, r = d[i + 2] * H;
            if (d[i + 3] > 0.5f) {
                tweeter(c, dx, dy, r, s, a, fine);
                if (i == 0) { // arcs jump between the two tweeters
                    if (s == 0) {
                        topX0 = bx + dx;
                        topY0 = by + dy;
                    } else {
                        topX1 = bx + dx;
                        topY1 = by + dy;
                    }
                }
            } else {
                if (firstWoofer < 0) {
                    firstWoofer = i;
                    coneX[s] = bx + dx;
                    coneY[s] = by + dy;
                }
                woofer(c, dx, dy, r, x[s] * (1f - 0.1f * ((i >> 2) & 1)), s, a, fine);
            }
        }
        c.restore();
        if (sparkReq[s]) {
            sparkReq[s] = false;
            for (int k = 0; k < 10; k++) {
                int q = sNext++ % NS;
                float ang = -(0.15f + 0.7f * rng.nextFloat()) * (float) Math.PI, sp = h * (0.35f + 0.8f * rng.nextFloat());
                sx[q] = coneX[s];
                sy[q] = coneY[s];
                svx[q] = (float) Math.cos(ang) * sp;
                svy[q] = (float) Math.sin(ang) * sp;
                sl[q] = 0.55f + 0.5f * rng.nextFloat();
                sc[q] = (byte) rng.nextInt(3);
            }
        }
    }

    /** A woofer built from fills only: rubber surround, ringed cone, specular highlight, dust cap, glowing ring and bloom. */
    private void woofer(Canvas c, float cx0, float cy0, float r, float e, int s, float a, boolean fine) {
        float pe = Math.max(0f, Math.min(1f, e));
        paint.setStyle(Paint.Style.FILL);
        // bloom grows as the cone pushes out
        if (fine && pe > 0.08f) {
            for (int i = 0; i < 3; i++) {
                paint.setColor(withA(s == 0 ? a0 : a1, (0.05f + 0.07f * pe) * (1f - i * 0.3f) * a));
                c.drawCircle(cx0, cy0, r * (1.5f - i * 0.12f + 0.2f * pe), paint);
            }
        }
        // LED ring: a filled disc in the song color with the housing laid over it
        float fl = flash[s];
        paint.setColor(withA(mix(s == 0 ? a0 : a1, 0xFFFFFFFF, 0.2f * fl), (0.22f + 0.55f * pe + 0.4f * fl) * a));
        c.drawCircle(cx0, cy0, r * 1.12f, paint);
        paint.setColor(withA(0xFF050507, 0.96f * a));
        c.drawCircle(cx0, cy0, r * 1.05f, paint);
        // rubber surround flexes with the cone
        int sg = (int) (36 + 30 * pe);
        paint.setColor(withA(0xFF000000 | (sg << 16) | (sg << 8) | (sg + 6), 0.97f * a));
        c.drawCircle(cx0, cy0, r * (1.0f + 0.07f * pe), paint); // the surround flexes outward with the cone
        paint.setColor(withA(0xFF08080B, 0.97f * a));
        c.drawCircle(cx0, cy0, r * 0.84f, paint);
        // cone: concentric rings, lighter and nearer when pushed out
        int rings = fine ? 7 : 3;
        for (int i = 0; i < rings; i++) {
            float rr = r * (0.80f - i * (0.56f / rings)) * (1f + 0.07f * pe);
            int g = 22 + i * 5 + (int) (22 * pe) - ((i & 1) == 0 ? 0 : 7);
            paint.setColor(withA(0xFF000000 | (g << 16) | (g << 8) | (g + 5), 0.97f * a));
            c.drawCircle(cx0 - r * 0.015f * pe, cy0 - r * 0.015f * pe, rr, paint);
        }
        // specular highlight on the cone and a tighter glint
        paint.setColor(withA(0xFFFFFFFF, (0.10f + 0.10f * pe) * a));
        rect.set(cx0 - r * 0.62f, cy0 - r * 0.68f, cx0 + r * 0.05f, cy0 - r * 0.16f);
        c.drawOval(rect, paint);
        paint.setColor(withA(0xFFFFFFFF, (0.2f + 0.2f * pe) * a));
        rect.set(cx0 - r * 0.46f, cy0 - r * 0.56f, cx0 - r * 0.2f, cy0 - r * 0.4f);
        c.drawOval(rect, paint);
        // dust cap, up to 12% bigger on a hit
        float cr0 = r * 0.27f * (1f + 0.15f * pe);
        int cg = 56 + (int) (46 * pe);
        paint.setColor(withA(0xFF000000 | (cg << 16) | (cg << 8) | (cg + 10), 0.98f * a));
        c.drawCircle(cx0, cy0, cr0, paint);
        paint.setColor(withA(0xFFFFFFFF, 0.28f * a));
        rect.set(cx0 - cr0 * 0.62f, cy0 - cr0 * 0.72f, cx0 + cr0 * 0.05f, cy0 - cr0 * 0.22f);
        c.drawOval(rect, paint);
    }

    /** The tweeter above the woofers: a dome in a plate with a glowing ring that ticks on hats. */
    private void tweeter(Canvas c, float cx0, float cy0, float r, int s, float a, boolean fine) {
        float tk = tick[s], rr = r * (1f + 0.12f * tk);
        paint.setStyle(Paint.Style.FILL);
        if (fine && tk > 0.1f) {
            paint.setColor(withA(comp, 0.1f * tk * a));
            c.drawCircle(cx0, cy0, rr * 2.1f, paint);
        }
        paint.setColor(withA(mix(comp, 0xFFFFFFFF, 0.2f * tk), (0.3f + 0.6f * tk) * a));
        c.drawCircle(cx0, cy0, rr * 1.28f, paint);
        paint.setColor(withA(0xFF060608, 0.97f * a));
        c.drawCircle(cx0, cy0, rr * 1.18f, paint);
        int g = 44 + (int) (50 * tk);
        paint.setColor(withA(0xFF000000 | (g << 16) | (g << 8) | (g + 10), 0.97f * a));
        c.drawCircle(cx0, cy0, rr * 0.86f, paint);
        paint.setColor(withA(0xFFFFFFFF, 0.3f * a));
        rect.set(cx0 - rr * 0.55f, cy0 - rr * 0.62f, cx0 - rr * 0.05f, cy0 - rr * 0.2f);
        c.drawOval(rect, paint);
    }

    // ---- crackle -------------------------------------------------------------------------------

    private void genArc(int i, float ax, float ay, float bx, float by) {
        float[] X = arcX[i], Y = arcY[i];
        X[0] = ax;
        Y[0] = ay;
        X[16] = bx;
        Y[16] = by;
        float dx = bx - ax, dy = by - ay, len = (float) Math.sqrt(dx * dx + dy * dy) + 1f, nx = -dy / len, ny = dx / len, amp = len * 0.1f;
        for (int step = 8; step >= 1; step >>= 1) {
            for (int k = step; k < 16; k += 2 * step) {
                float o = (rng.nextFloat() - 0.5f) * 2f * amp * step / 8f;
                X[k] = (X[k - step] + X[k + step]) * 0.5f + nx * o;
                Y[k] = (Y[k - step] + Y[k + step]) * 0.5f + ny * o;
            }
        }
        for (int k = 0; k <= 16; k++) Y[k] -= (float) Math.sin(Math.PI * k / 16f) * len * 0.07f;
        for (int b = 0; b < 2; b++) {
            int from = 3 + rng.nextInt(10);
            float ang = rng.nextFloat() * TAU, px = X[from], py = Y[from], seg = len * 0.028f;
            for (int j = 0; j < 6; j++) {
                brX[i][b][j] = px;
                brY[i][b][j] = py;
                ang += (rng.nextFloat() - 0.5f) * 1.2f;
                px += (float) Math.cos(ang) * seg;
                py += (float) Math.sin(ang) * seg;
            }
        }
    }

    private void arcs(Canvas c, float w, float h, float a) {
        if (slide[0] > 0.5f || slide[1] > 0.5f) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 3; i++) {
            if (arcAge[i] < 0f) continue; // idle, or waiting for its start delay
            arcRegen[i] -= dt;
            if (arcRegen[i] <= 0f) { // re-roll the path every ~70 ms so it crackles
                arcRegen[i] = 0.07f;
                genArc(i, topX0, topY0 - hh[0] * 0.02f, topX1, topY1 - hh[1] * 0.02f);
            }
            float age = arcAge[i];
            float life = arcLife[i], inT = Math.min(0.2f, life * 0.4f);
            float al = (age < inT ? age / inT : 1f - (age - inT) / (life - inT)) * 0.75f * a;
            if (al <= 0.01f) continue;
            path.rewind();
            path.moveTo(arcX[i][0], arcY[i][0]);
            for (int k = 1; k <= 16; k++) path.lineTo(arcX[i][k], arcY[i][k]);
            for (int b = 0; b < 2; b++) {
                path.moveTo(brX[i][b][0], brY[i][b][0]);
                for (int j = 1; j < 6; j++) path.lineTo(brX[i][b][j], brY[i][b][j]);
            }
            paint.setStrokeWidth(Math.max(4f, h * 0.006f));
            paint.setColor(withA(i == 0 ? comp : a2, 0.22f * al));
            c.drawPath(path, paint);
            paint.setStrokeWidth(Math.max(1.5f, h * 0.002f));
            paint.setColor(withA(mix(i == 0 ? comp : a2, 0xFFFFFFFF, 0.7f), al));
            c.drawPath(path, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void sparks(Canvas c, float h, float a) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Math.max(2f, h * 0.0028f));
        for (int i = 0; i < NS; i++) {
            if (sl[i] <= 0f) continue;
            int col = sc[i] == 0 ? a0 : sc[i] == 1 ? comp : 0xFFFFF4E0;
            paint.setColor(withA(col, Math.min(1f, sl[i] * 1.4f) * 0.85f * a));
            c.drawLine(sx[i], sy[i], sx[i] - svx[i] * 0.035f, sy[i] - svy[i] * 0.035f, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
    }
}
