package tv.dumbtv.home;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * The note-world family: scenes where the music physically moves objects. Each note (from the existing note
 * estimator, "feels right", not a transcription) makes something visibly happen; no notes means calm drift.
 * Nine scenes, four presets each. A preset picks the backdrop (dusk clay, midnight blue, deep olive, soft
 * charcoal, all dark), the lane count and direction, the palette and the camera (top-down, 30 degree tilt, follow).
 * Everything is flat shapes in a clay look with a contact shadow; no soft round sprites, at most ~400 shapes.
 */
final class NoteWorld {
    static final String[] NAMES = {"note_marble", "note_keys", "note_bottles", "note_harp", "note_musicbox", "note_stars",
        "note_ribbon", "note_parade", "note_water"};

    /** The note events the scenes read (Pulse adapts its Notes estimator to this). */
    interface Events {
        /** Total events so far; the newest is index total()-1. */
        long total();

        int midi(long i);

        float vel(long i);

        /** Is this MIDI note sounding now, and how loud (0..1). */
        boolean on(int midi);

        float level(int midi);
    }

    private static final int[] BG = {0xFF3A2E2A, 0xFF0E1630, 0xFF1A2414, 0xFF22242A}; // dusk clay, midnight, olive, charcoal
    private static final int[] BG_DAY = {0xFF2F93A8, 0xFFD9806A, 0xFF4E9E6C, 0xFF7C70C6}; // teal, coral, leaf green, violet (mid tones)
    private static final int[] LANES = {12, 16, 20, 24};
    private static final int MAXL = 24;

    private boolean lightBg;
    private float capS = 0.58f, capV = 0.80f;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private long seen = -1;
    private int curScene = -1, curPreset = -1;
    private float t, camX, idle;
    private float hue = 200f;

    // per-lane state shared by scenes
    private final float[] glow = new float[MAXL], press = new float[MAXL], swing = new float[MAXL], swingT = new float[MAXL], vib = new float[MAXL], vibT = new float[MAXL];
    // events in a ring: x, y, age, vel, lane, extra
    private static final int N = 96;
    private final float[] ex = new float[N], ey = new float[N], eage = new float[N], evel = new float[N], elane = new float[N], eext = new float[N];
    private int eN;
    // marble
    private float mx, my, mfx, mfy, mtx, mty, mp = 1f, mvy;
    private boolean mDrop = true;
    private float lastNoteT;
    // ribbon history
    private final float[] rh = new float[512], rt = new float[512];
    private int rn;
    private float rLane = 0.5f, rAcc;
    // parade
    private final float[] px = new float[24], py = new float[24], psz = new float[24], prock = new float[24];
    private final int[] pk = new int[24];
    private boolean paradeInit;
    private int lastStar = -1;

    private int hsv(float h, float s, float v, int a) {
        h = ((h % 360f) + 360f) % 360f;
        s = Math.min(s, capS);
        v = Math.min(v, capV);
        float c = v * s, x = c * (1f - Math.abs((h / 60f) % 2f - 1f)), m = v - c, rr, gg, bb;
        int sec = (int) (h / 60f);
        switch (sec) {
            case 0: rr = c; gg = x; bb = 0; break;
            case 1: rr = x; gg = c; bb = 0; break;
            case 2: rr = 0; gg = c; bb = x; break;
            case 3: rr = 0; gg = x; bb = c; break;
            case 4: rr = x; gg = 0; bb = c; break;
            default: rr = c; gg = 0; bb = x; break;
        }
        return (a << 24) | (Math.round((rr + m) * 255f) << 16) | (Math.round((gg + m) * 255f) << 8) | Math.round((bb + m) * 255f);
    }

    private static int al(int col, float a) {
        return (Math.max(0, Math.min(255, (int) (a * 255f))) << 24) | (col & 0xFFFFFF);
    }

    private static int mix(int a, int b, float k) {
        int ra = (a >> 16) & 255, ga = (a >> 8) & 255, ba = a & 255, rb = (b >> 16) & 255, gb = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | (Math.round(ra + (rb - ra) * k) << 16) | (Math.round(ga + (gb - ga) * k) << 8) | Math.round(ba + (bb - ba) * k);
    }

    private int bgBase(int preset) {
        return lightBg ? BG_DAY[Math.abs(preset) & 3] : BG[Math.abs(preset) & 3];
    }

    private int lanes(int preset) {
        return LANES[(preset >> 2) & 3];
    }

    private boolean rev(int preset) {
        return (preset & 1) == 1;
    }

    private int laneOf(int midi, int L, boolean rev) {
        float pos = Math.max(0f, Math.min(0.999f, (midi - 36) / 60f));
        int l = (int) (pos * L);
        return rev ? L - 1 - l : l;
    }

    /**
     * @param scene  0..8 (index into NAMES)
     * @param preset any integer; the low two bits pick the backdrop, bit 0 also flips the lane direction,
     *               bits 2-3 the lane count and the camera cycles with the preset
     */
    void draw(Canvas c, float w, float h, float dt, int scene, int preset, Events ev, float kick, float snare, float hat, float accentHue) {
        dt = Math.min(dt, 0.05f);
        t += dt;
        hue = accentHue;
        if (scene != curScene || preset != curPreset) {
            curScene = scene;
            curPreset = preset;
            reset(w, h);
            seen = ev.total();
        }
        // by day two presets in three get a vibrant mid-tone backdrop (teal, coral, green, violet); at night they stay dark
        lightBg = DayLight.k() > 0.7f && ((preset >> 4) % 3) != 0;
        capS = lightBg ? 0.78f : 0.72f;
        capV = lightBg ? 0.94f : 0.88f;
        int L = lanes(preset);
        boolean rv = rev(preset);
        for (int i = 0; i < MAXL; i++) {
            glow[i] = Math.max(0f, glow[i] - dt * 2.2f);
            press[i] = Math.max(0f, press[i] - dt * 3.5f);
            swingT[i] += dt;
            vibT[i] += dt;
        }
        // new notes since the last frame (at most 12 at once)
        long tot = ev.total();
        if (seen < 0 || tot - seen > 12) seen = Math.max(0, tot - 12);
        for (; seen < tot; seen++) {
            int lane = laneOf(ev.midi(seen), L, rv);
            note(lane, ev.vel(seen), ev.midi(seen), w, h, L, preset);
        }
        idle += dt;
        float tilt = (preset % 3 == 1) ? 1f : 0f;
        c.save();
        background(c, w, h, preset);
        int cam = Math.abs(preset) % 3; // 0 top-down, 1 tilt, 2 follow
        if (cam == 1) c.scale(1f, 0.86f, w / 2f, h);
        if (cam == 2) {
            float target = (focusX(w, L) - w / 2f) * -0.06f;
            float d = target - camX;
            float mxs = 0.02f * w * dt;
            camX += Math.max(-mxs, Math.min(mxs, d));
            c.translate(camX, 0f);
        }
        switch (scene) {
            case 0: marble(c, w, h, dt, L, preset, ev); break;
            case 1: keys(c, w, h, dt, ev, preset); break;
            case 2: bottles(c, w, h, dt, L, preset); break;
            case 3: harp(c, w, h, dt, L, preset); break;
            case 4: musicBox(c, w, h, dt, L, preset); break;
            case 5: stars(c, w, h, dt, L, preset); break;
            case 6: ribbon(c, w, h, dt, ev, preset, L); break;
            case 7: parade(c, w, h, dt, preset, ev, kick, snare); break;
            default: water(c, w, h, dt, preset, L); break;
        }
        c.restore();
    }

    private float focusX(float w, int L) {
        return mx > 0 ? mx : w / 2f;
    }

    private void reset(float w, float h) {
        eN = 0;
        for (int i = 0; i < MAXL; i++) glow[i] = press[i] = swing[i] = vib[i] = 0f;
        mDrop = true;
        mx = w * 0.5f;
        my = -h * 0.05f;
        mvy = 0f;
        mp = 1f;
        rn = 0;
        paradeInit = false;
        lastStar = -1;
        idle = 0f;
    }

    private void note(int lane, float vel, int midi, float w, float h, int L, int preset) {
        lane = Math.max(0, Math.min(L - 1, lane));
        vel = Math.max(0.25f, Math.min(1f, vel));
        glow[lane] = 1f;
        press[lane] = 1f;
        swing[lane] = vel;
        swingT[lane] = 0f;
        vib[lane] = vel;
        vibT[lane] = 0f;
        lastNoteT = t;
        idle = 0f;
        rAcc = lane / (float) Math.max(1, L - 1);
        int k = eN++ % N;
        elane[k] = lane;
        evel[k] = vel;
        eage[k] = 0f;
        eext[k] = midi;
        switch (curScene) {
            case 0: { // marble heads for that tile
                float tw = w * 0.84f / L;
                mtx = w * 0.08f + (lane + 0.5f) * tw;
                mty = tileY(lane, h, preset) - h * 0.016f;
                mfx = mx;
                mfy = my;
                mp = 0f;
                mDrop = false;
                break;
            }
            case 2: ex[k] = w * 0.08f + (lane + 0.5f) * w * 0.84f / L; ey[k] = h * 0.62f; break;
            case 8: ex[k] = w * 0.06f + (lane + 0.5f) * w * 0.88f / L + (hash(eN) - 0.5f) * w * 0.03f; ey[k] = h * (0.38f + 0.5f * hash(eN * 3)); break;
            case 5: ex[k] = w * 0.80f; ey[k] = h * (0.12f + 0.62f * (1f - lane / (float) Math.max(1, L - 1))); elane[k] = lane; break;
            case 1: ex[k] = midi; ey[k] = 0f; break; // bar spawned in keys()
            default: break;
        }
    }

    private static float hash(int n) {
        float s = (float) Math.sin(n * 12.9898f) * 43758.547f;
        return s - (float) Math.floor(s);
    }

    // ---- look

    private void background(Canvas c, float w, float h, int preset) {
        int base = lightBg ? BG_DAY[Math.abs(preset) & 3] : BG[Math.abs(preset) & 3];
        int top = lightBg ? mix(base, 0xFFFFFFFF, 0.14f) : mix(base, 0xFF000000, 0.35f);
        int bot = lightBg ? mix(base, 0xFF000000, 0.18f) : mix(base, 0xFFFFFFFF, 0.06f);
        int bands = 20;
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < bands; i++) {
            p.setColor(mix(top, bot, i / (float) (bands - 1)));
            c.drawRect(-w * 0.1f, h * i / bands, w * 1.1f, h * (i + 1) / bands + 1f, p);
        }
    }

    /** A flat clay block: soft contact shadow under it, a top highlight, it sinks a little when pressed. */
    private void clay(Canvas c, float x, float y, float bw, float bh, float rad, int col, float pr) {
        float sink = pr * bh * 0.35f;
        p.setStyle(Paint.Style.FILL);
        for (int i = 3; i >= 1; i--) {
            p.setColor(al(0x000000, 0.10f));
            float e = i * bh * 0.10f;
            r.set(x - e, y + sink + bh * 0.25f, x + bw + e, y + sink + bh + e * 1.4f);
            c.drawRoundRect(r, rad + e, rad + e, p);
        }
        p.setColor(col);
        r.set(x, y + sink, x + bw, y + sink + bh);
        c.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, bh * 0.06f));
        p.setColor(mix(col, 0xFFFFFFFF, 0.28f));
        c.drawLine(x + rad, y + sink + 1f, x + bw - rad, y + sink + 1f, p);
        p.setStyle(Paint.Style.FILL);
    }

    private int objCol(int idx, float v, int a) {
        float off = (idx % 3 == 0 ? 0f : idx % 3 == 1 ? 60f : -60f);
        return hsv(hue + off + curPreset * 17f, lightBg ? 0.62f : 0.62f, lightBg ? Math.min(0.92f, v + 0.24f) : v, a);
    }

    private float tileY(int lane, float h, int preset) {
        return h * (0.58f + 0.16f * (float) Math.sin(lane * 1.7f + preset)) + (lane % 3) * h * 0.012f;
    }

    // ---- a. piano-key marble run

    private void marble(Canvas c, float w, float h, float dt, int L, int preset, Events ev) {
        float tw = w * 0.84f / L, th = h * 0.045f;
        for (int i = 0; i < L; i++) {
            float x = w * 0.08f + i * tw, y = tileY(i, h, preset);
            boolean black = (i % 12 == 1 || i % 12 == 3 || i % 12 == 6 || i % 12 == 8 || i % 12 == 10);
            int base = black ? mix(objCol(i, 0.35f, 255), 0xFF000000, 0.4f) : mix(objCol(i, 0.62f, 255), 0xFFFFFFFF, 0.1f);
            int col = mix(base, hsv(hue + 20f, 0.3f, 0.8f, 255), glow[i] * 0.7f);
            clay(c, x + tw * 0.06f, y, tw * 0.88f, th, th * 0.35f, col, press[i]);
        }
        if (mDrop) { // waits for a phrase: falls from above onto a tile
            mvy += h * 1.4f * dt;
            my += mvy * dt;
            if (my > tileY(L / 2, h, preset) - h * 0.016f) {
                mDrop = false;
                mvy = 0f;
                my = tileY(L / 2, h, preset) - h * 0.016f;
                mp = 1f;
            }
        } else if (mp < 1f) {
            mp = Math.min(1f, mp + dt / 0.36f);
            mx = mfx + (mtx - mfx) * mp;
            my = mfy + (mty - mfy) * mp - (float) Math.sin(Math.PI * mp) * h * 0.14f;
        }
        if (idle > 3.5f && !mDrop && mp >= 1f) { // a quiet stretch: the marble re-drops
            mDrop = true;
            mx = w * (0.2f + 0.6f * hash((int) (t * 7f)));
            my = -h * 0.04f;
            mvy = 0f;
            idle = 0f;
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(al(0x000000, 0.25f));
        c.drawCircle(mx, mty > 0 ? Math.max(my, 0) + h * 0.02f : my + h * 0.02f, h * 0.012f, p);
        p.setColor(0xFFE8E6DC);
        c.drawCircle(mx, my, h * 0.014f, p);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(mx - h * 0.004f, my - h * 0.004f, h * 0.004f, p);
    }

    // ---- b. keyboard with note bars (history rolls up from the key; see the note on latency in the report)

    private static boolean isBlack(int midi) {
        int n = midi % 12;
        return n == 1 || n == 3 || n == 6 || n == 8 || n == 10;
    }

    private float keyX(int midi, float w) {
        int whites = 0;
        for (int m = 21; m < midi; m++) if (!isBlack(m)) whites++;
        float ww = w * 0.96f / 52f;
        return w * 0.02f + (whites + (isBlack(midi) ? 0f : 0.5f)) * ww;
    }

    private final float[] bm = new float[N], bb = new float[N], bt = new float[N], bv = new float[N];
    private final boolean[] balive = new boolean[N];
    private int bN;

    private void keys(Canvas c, float w, float h, float dt, Events ev, int preset) {
        float kh = h * 0.18f, ky = h - kh, ww = w * 0.96f / 52f;
        // spawn bars for the notes that arrived
        for (int i = 0; i < Math.min(eN, N); i++) {
            if (eage[i] == 0f && eext[i] >= 21 && eext[i] <= 108) {
                int k = bN++ % N;
                bm[k] = eext[i];
                bb[k] = ky;
                bt[k] = ky - h * 0.02f;
                bv[k] = evel[i];
                balive[k] = true;
                eage[i] = 0.001f;
            }
        }
        float speed = h * 0.22f;
        for (int k = 0; k < N; k++) {
            if (!balive[k]) continue;
            boolean sounding = ev.on((int) bm[k]) && bb[k] >= ky - 1f;
            if (sounding) bt[k] -= speed * dt;
            else {
                bb[k] -= speed * dt;
                bt[k] -= speed * dt;
            }
            if (bb[k] < -h * 0.05f) balive[k] = false;
            float x = keyX((int) bm[k], w);
            int col = objCol((int) bm[k] / 12, 0.62f, 255);
            float bw = (isBlack((int) bm[k]) ? ww * 0.55f : ww * 0.8f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(al(col, 0.55f + 0.4f * bv[k]));
            r.set(x - bw / 2f + (isBlack((int) bm[k]) ? 0f : 0f), bt[k], x + bw / 2f, bb[k]);
            c.drawRoundRect(r, bw * 0.3f, bw * 0.3f, p);
        }
        // the keys: white first, black on top
        p.setStyle(Paint.Style.FILL);
        for (int m = 21; m <= 108; m++) {
            if (isBlack(m)) continue;
            float x = keyX(m, w) - ww / 2f;
            boolean lit = ev.on(m) || glowOfMidi(m) > 0f;
            p.setColor(lit ? mix(0xFFCFCBC0, hsv(hue + 40f, 0.5f, 0.8f, 255), 0.6f) : 0xFFB9B5AA);
            r.set(x + 1f, ky + (lit ? h * 0.006f : 0f), x + ww - 1f, h);
            c.drawRoundRect(r, ww * 0.12f, ww * 0.12f, p);
        }
        for (int m = 21; m <= 108; m++) {
            if (!isBlack(m)) continue;
            float x = keyX(m, w);
            boolean lit = ev.on(m) || glowOfMidi(m) > 0f;
            p.setColor(lit ? hsv(hue + 40f, 0.5f, 0.7f, 255) : 0xFF16171B);
            r.set(x - ww * 0.3f, ky, x + ww * 0.3f, ky + kh * 0.62f + (lit ? h * 0.004f : 0f));
            c.drawRoundRect(r, ww * 0.1f, ww * 0.1f, p);
        }
    }

    private float glowOfMidi(int midi) {
        return 0f;
    }

    // ---- c. chime bottles

    private void bottles(Canvas c, float w, float h, float dt, int L, int preset) {
        float rail = h * 0.12f;
        p.setStyle(Paint.Style.FILL);
        clay(c, w * 0.04f, rail - h * 0.02f, w * 0.92f, h * 0.022f, h * 0.01f, mix(bgBase(preset), 0xFFFFFFFF, 0.25f), 0f);
        float tw = w * 0.84f / L;
        for (int i = 0; i < L; i++) {
            float x = w * 0.08f + (i + 0.5f) * tw;
            float pos = i / (float) Math.max(1, L - 1);
            float len = h * (0.40f - 0.20f * pos); // low notes hang longer
            float ang = (float) (swing[i] * 0.35f * Math.sin(swingT[i] * 9.0) * Math.exp(-swingT[i] * 1.7));
            c.save();
            c.rotate((float) Math.toDegrees(ang), x, rail);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f, h * 0.002f));
            p.setColor(al(0xFFFFFF, 0.35f));
            c.drawLine(x, rail, x, rail + h * 0.05f, p);
            float bw = tw * 0.62f, by = rail + h * 0.05f;
            int col = objCol(i, 0.70f, 255);
            p.setStyle(Paint.Style.FILL);
            p.setColor(al(col, 0.30f + 0.30f * glow[i]));
            r.set(x - bw * 0.2f, by, x + bw * 0.2f, by + len * 0.30f);
            c.drawRoundRect(r, bw * 0.1f, bw * 0.1f, p);
            r.set(x - bw / 2f, by + len * 0.22f, x + bw / 2f, by + len);
            c.drawRoundRect(r, bw * 0.35f, bw * 0.35f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f, h * 0.003f));
            p.setColor(al(mix(col, 0xFFFFFFFF, 0.5f), 0.55f + 0.4f * glow[i]));
            c.drawRoundRect(r, bw * 0.35f, bw * 0.35f, p);
            c.restore();
        }
        // thin ripple rings leave the bottle that was struck
        p.setStyle(Paint.Style.STROKE);
        for (int k = 0; k < Math.min(eN, N); k++) {
            eage[k] += dt;
            if (eage[k] > 1.6f) continue;
            float a = 1f - eage[k] / 1.6f;
            p.setStrokeWidth(Math.max(1.5f, h * 0.0025f));
            p.setColor(al(mix(objCol((int) elane[k], 0.7f, 255), 0xFFFFFFFF, 0.3f), 0.6f * a));
            float rr = eage[k] * h * 0.20f * (0.5f + evel[k]);
            r.set(ex[k] - rr, h * 0.86f - rr * 0.30f, ex[k] + rr, h * 0.86f + rr * 0.30f);
            c.drawOval(r, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    // ---- d. harp strings

    private void harp(Canvas c, float w, float h, float dt, int L, int preset) {
        float x0 = w * 0.14f, x1 = w * 0.86f, base = h * 0.90f;
        // frame: a curved neck above, a pillar on the right
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(h * 0.012f);
        p.setColor(mix(bgBase(preset), 0xFFFFFFFF, 0.3f));
        path.reset();
        path.moveTo(x0 - w * 0.01f, h * 0.22f);
        path.cubicTo(w * 0.35f, h * 0.05f, w * 0.7f, h * 0.10f, x1 + w * 0.01f, h * 0.40f);
        c.drawPath(path, p);
        c.drawLine(x1 + w * 0.01f, h * 0.40f, x1 + w * 0.01f, base + h * 0.02f, p);
        c.drawLine(x0 - w * 0.01f, base + h * 0.02f, x1 + w * 0.01f, base + h * 0.02f, p);
        for (int i = 0; i < L; i++) {
            float f = i / (float) Math.max(1, L - 1);
            float x = x0 + f * (x1 - x0);
            float top = cubicY(f) ;
            float amp = (float) (vib[i] * h * 0.016f * Math.exp(-vibT[i] / 0.5) * Math.sin(vibT[i] * 38.0 + i));
            float a = (float) Math.exp(-vibT[i] / 1.5);
            int col = mix(objCol(i, 0.66f, 255), 0xFFFFFFFF, 0.35f * a);
            p.setStrokeWidth(Math.max(1.5f, h * (0.002f + 0.002f * a)));
            p.setColor(al(col, 0.45f + 0.5f * Math.min(1f, a + (vib[i] > 0 ? 0f : 0f))));
            path.reset();
            int seg = 18;
            for (int s = 0; s <= seg; s++) {
                float u = s / (float) seg;
                float y = top + (base - top) * u;
                float dx = amp * (float) Math.sin(Math.PI * u);
                if (s == 0) path.moveTo(x + dx, y);
                else path.lineTo(x + dx, y);
            }
            c.drawPath(path, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private float cubicY(float f) {
        // y of the neck curve at fraction f along x (sampled from the same bezier as the frame)
        float u = f;
        for (int k = 0; k < 6; k++) { // solve x(u) = f
            float xu = 3f * (1f - u) * (1f - u) * u * 0.35f + 3f * (1f - u) * u * u * 0.7f + u * u * u;
            float dx = 3f * (1f - u) * (1f - u) * 0.35f + 6f * (1f - u) * u * (0.7f - 0.35f) + 3f * u * u * (1f - 0.7f);
            u -= (xu - f) / Math.max(0.2f, dx);
            u = Math.max(0f, Math.min(1f, u));
        }
        float yu = (1f - u) * (1f - u) * (1f - u) * 0.22f + 3f * (1f - u) * (1f - u) * u * 0.05f + 3f * (1f - u) * u * u * 0.10f + u * u * u * 0.40f;
        return yu * curH + curH * 0.02f;
    }

    private float curH = 1080f;

    // ---- e. music-box comb with a rotating pin cylinder

    private void musicBox(Canvas c, float w, float h, float dt, int L, int preset) {
        curH = h;
        float cx0 = w * 0.08f, cw = w * 0.84f, tw = cw / L;
        float cylTop = h * 0.14f, cylH = h * 0.50f, comb = cylTop + cylH + h * 0.02f;
        clay(c, cx0 - w * 0.01f, cylTop, cw + w * 0.02f, cylH, h * 0.02f, mix(bgBase(preset), 0xFFFFFFFF, 0.18f), 0f);
        // pins: new ones appear on the comb line and roll away upward with the cylinder
        float roll = h * 0.12f;
        p.setStyle(Paint.Style.FILL);
        for (int k = 0; k < Math.min(eN, N); k++) {
            eage[k] += dt;
            float y = cylTop + cylH - eage[k] * roll;
            if (y < cylTop + h * 0.01f) continue;
            float x = cx0 + (elane[k] + 0.5f) * tw;
            p.setColor(mix(objCol((int) elane[k], 0.78f, 255), 0xFFFFFFFF, Math.max(0f, 1f - eage[k] * 1.5f) * 0.6f));
            c.drawCircle(x, y, Math.max(2.5f, h * 0.0065f), p);
        }
        // comb teeth: longer for low notes, the struck one trembles
        for (int i = 0; i < L; i++) {
            float f = i / (float) Math.max(1, L - 1);
            float len = h * (0.20f - 0.12f * f);
            float x = cx0 + (i + 0.5f) * tw;
            float dx = (float) (vib[i] * h * 0.008f * Math.exp(-vibT[i] / 0.4) * Math.sin(vibT[i] * 55.0));
            p.setColor(mix(0xFF9A978C, hsv(hue + 40f, 0.5f, 0.8f, 255), glow[i]));
            r.set(x - tw * 0.30f + dx, comb, x + tw * 0.30f + dx, comb + len);
            c.drawRoundRect(r, tw * 0.15f, tw * 0.15f, p);
        }
    }

    // ---- f. melody constellation

    private void stars(Canvas c, float w, float h, float dt, int L, int preset) {
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 48; i++) { // distant crisp stars
            float sx = (hash(i * 7) * w - t * 6f * (0.3f + hash(i))) % w;
            if (sx < 0) sx += w;
            p.setColor(al(0xFFFFFF, 0.18f + 0.15f * hash(i + 99)));
            c.drawCircle(sx, hash(i * 3) * h, Math.max(1.2f, h * 0.0012f), p);
        }
        float speed = w * 0.045f;
        int prev = -1;
        p.setStyle(Paint.Style.STROKE);
        int start = Math.max(0, eN - N);
        for (int n = start; n < eN; n++) {
            int k = n % N;
            ex[k] -= speed * dt;
            eage[k] += dt;
        }
        for (int n = start; n < eN; n++) {
            int k = n % N;
            if (ex[k] < -w * 0.05f) {
                prev = -1;
                continue;
            }
            if (prev >= 0) {
                p.setStrokeWidth(Math.max(1.5f, h * 0.0022f));
                p.setColor(al(mix(objCol(1, 0.7f, 255), 0xFFFFFFFF, 0.3f), 0.45f));
                c.drawLine(ex[prev], ey[prev], ex[k], ey[k], p);
            }
            prev = k;
        }
        p.setStyle(Paint.Style.FILL);
        for (int n = start; n < eN; n++) {
            int k = n % N;
            if (ex[k] < -w * 0.05f) continue;
            float a = Math.max(0.3f, 1f - eage[k] * 0.05f);
            float rad = h * (0.004f + 0.006f * evel[k]) * (1f + (eage[k] < 0.3f ? (0.3f - eage[k]) * 2.5f : 0f));
            p.setColor(al(mix(objCol(k, 0.8f, 255), 0xFFFFFFFF, 0.5f), a));
            c.drawCircle(ex[k], ey[k], rad, p);
        }
    }

    // ---- g. melody ribbon

    private void ribbon(Canvas c, float w, float h, float dt, Events ev, int preset, int L) {
        float target = 0.5f;
        int act = 0;
        float sum = 0f;
        for (int m = 36; m <= 96; m++) if (ev.on(m)) {
            act++;
            sum += (m - 36) / 60f * ev.level(m);
            target += 0;
        }
        float tot = 0f;
        for (int m = 36; m <= 96; m++) if (ev.on(m)) tot += ev.level(m);
        boolean any = act > 0 && tot > 0.001f;
        if (any) rLane += ((sum / tot) - rLane) * Math.min(1f, dt / 0.06f);
        float sustain = any ? Math.min(1f, tot) : 0f;
        rAcc += dt;
        // sample at 60 Hz
        if (rAcc > 1f / 60f) {
            rAcc = 0f;
            if (rn >= rh.length) {
                System.arraycopy(rh, 1, rh, 0, rh.length - 1);
                System.arraycopy(rt, 1, rt, 0, rt.length - 1);
                rn = rh.length - 1;
            }
            rh[rn] = rev(preset) ? 1f - rLane : rLane;
            rt[rn] = any ? 0.4f + 0.6f * sustain : 0.12f;
            rn++;
        }
        float step = w * 0.55f / 240f * 1f; // ~240 samples across 55% of the width: about 4 s of history
        float headX = w * 0.82f;
        int n0 = Math.max(0, rn - 300);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int pass = 0; pass < 2; pass++) {
            path.reset();
            for (int i = n0; i < rn; i++) {
                float x = headX - (rn - 1 - i) * step * 1.5f;
                float y = h * (0.78f - 0.56f * rh[i]);
                if (i == n0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            float th = h * (0.006f + 0.016f * (rn > 0 ? rt[rn - 1] : 0.12f));
            p.setStrokeWidth(pass == 0 ? th * 1.8f : th);
            p.setColor(pass == 0 ? al(objCol(0, 0.7f, 255), 0.25f) : objCol(0, 0.78f, 255));
            c.drawPath(path, p);
        }
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL);
        if (rn > 0) {
            p.setColor(0xFFEDEBE0);
            c.drawCircle(headX, h * (0.78f - 0.56f * rh[rn - 1]), h * 0.010f, p);
        }
    }

    // ---- h. instrument parade (pianos, violins, drums drift through; they rock to the music)

    private void parade(Canvas c, float w, float h, float dt, int preset, Events ev, float kick, float snare) {
        if (!paradeInit) {
            paradeInit = true;
            for (int i = 0; i < px.length; i++) {
                px[i] = hash(i * 5 + preset) * w * 1.2f - w * 0.1f;
                py[i] = h * (0.14f + 0.72f * ((i % 6) + 0.5f * hash(i)) / 6.4f);
                psz[i] = h * (0.07f + 0.04f * hash(i + 40));
                pk[i] = i % 3;
            }
        }
        float high = 0f, mid = 0f;
        for (int m = 76; m < 100; m++) if (ev.on(m)) high = Math.max(high, ev.level(m));
        for (int m = 52; m < 76; m++) if (ev.on(m)) mid = Math.max(mid, ev.level(m));
        for (int i = 0; i < px.length; i++) {
            px[i] += dt * w * 0.02f * (0.6f + 0.8f * (psz[i] / (h * 0.11f)));
            if (px[i] > w * 1.1f) px[i] = -w * 0.1f;
            float target = pk[i] == 2 ? Math.max(kick, snare * 0.8f) : pk[i] == 1 ? high : mid;
            prock[i] += (target - prock[i]) * Math.min(1f, dt * 14f);
            float ang = (float) Math.sin(t * 12f + i) * prock[i] * 9f;
            c.save();
            c.translate(px[i], py[i]);
            c.rotate(ang);
            float s = psz[i];
            int col = objCol(i, 0.55f + 0.15f * prock[i], 255);
            for (int sh = 3; sh >= 1; sh--) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(al(0x000000, 0.08f));
                r.set(-s * 0.9f - sh * s * 0.03f, s * 0.35f, s * 0.9f + sh * s * 0.03f, s * 0.35f + s * 0.12f + sh * s * 0.04f);
                c.drawOval(r, p);
            }
            p.setStyle(Paint.Style.FILL);
            if (pk[i] == 0) { // grand piano seen from above: a long case that curves in to the tail, keys along the front
                p.setColor(mix(col, 0xFF000000, 0.35f));
                path.reset();
                path.moveTo(-s * 0.95f, -s * 0.42f);
                path.lineTo(s * 0.05f, -s * 0.42f);
                path.cubicTo(s * 0.45f, -s * 0.42f, s * 0.55f, -s * 0.05f, s * 0.98f, -s * 0.02f);
                path.cubicTo(s * 0.90f, s * 0.25f, s * 0.40f, s * 0.42f, s * 0.05f, s * 0.42f);
                path.lineTo(-s * 0.95f, s * 0.42f);
                path.close();
                c.drawPath(path, p);
                p.setColor(0xFFD2CEC2);
                r.set(-s * 0.95f, s * 0.26f, -s * 0.30f, s * 0.42f);
                c.drawRect(r, p);
                p.setColor(0xFF15161A);
                for (int kx = 0; kx < 7; kx++) {
                    float bx = -s * 0.92f + kx * s * 0.09f;
                    r.set(bx, s * 0.26f, bx + s * 0.04f, s * 0.35f);
                    c.drawRect(r, p);
                }
                p.setColor(mix(col, 0xFFFFFFFF, 0.2f));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2f, s * 0.03f));
                c.drawLine(-s * 0.9f, -s * 0.36f, s * 0.0f, -s * 0.36f, p);
                p.setStyle(Paint.Style.FILL);
            } else if (pk[i] == 1) { // violin
                p.setColor(mix(col, 0xFF2A1A10, 0.5f));
                r.set(-s * 0.32f, -s * 0.42f, s * 0.32f, -s * 0.02f);
                c.drawOval(r, p);
                r.set(-s * 0.40f, -s * 0.06f, s * 0.40f, s * 0.46f);
                c.drawOval(r, p);
                p.setColor(0xFF1B1410);
                r.set(-s * 0.04f, -s * 0.95f, s * 0.04f, -s * 0.4f);
                c.drawRect(r, p);
            } else { // drum
                p.setColor(mix(col, 0xFF000000, 0.35f));
                r.set(-s * 0.5f, -s * 0.5f, s * 0.5f, s * 0.5f);
                c.drawOval(r, p);
                p.setColor(0xFFD7D3C8);
                r.set(-s * 0.42f, -s * 0.42f, s * 0.42f, s * 0.42f);
                c.drawOval(r, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2f, s * 0.04f));
                p.setColor(mix(col, 0xFFFFFFFF, 0.2f));
                c.drawOval(r, p);
            }
            c.restore();
        }
        p.setStyle(Paint.Style.FILL);
    }

    // ---- i. raindrop-water piano: thin rings only

    private void water(Canvas c, float w, float h, float dt, int preset, int L) {
        p.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 14; i++) { // faint sheen lines on the water
            p.setStrokeWidth(Math.max(1f, h * 0.001f));
            p.setColor(al(0xFFFFFF, 0.04f));
            float y = h * (0.35f + 0.6f * i / 14f);
            c.drawLine(0, y + (float) Math.sin(t * 0.4f + i) * h * 0.003f, w, y + (float) Math.sin(t * 0.3f + i * 2f) * h * 0.003f, p);
        }
        for (int k = 0; k < Math.min(eN, N); k++) {
            eage[k] += dt;
            if (eage[k] > 2.6f) continue;
            float a = 1f - eage[k] / 2.6f;
            float persp = 0.25f + 0.35f * (ey[k] / h);
            for (int ring = 0; ring < 3; ring++) {
                float age = eage[k] - ring * 0.18f;
                if (age <= 0f) continue;
                float rr = age * h * (0.16f + 0.14f * evel[k]);
                p.setStrokeWidth(Math.max(1.5f, h * 0.0025f));
                p.setColor(al(mix(objCol((int) elane[k], 0.7f, 255), 0xFFFFFFFF, 0.35f), 0.7f * a * (1f - ring * 0.25f)));
                r.set(ex[k] - rr, ey[k] - rr * persp, ex[k] + rr, ey[k] + rr * persp);
                c.drawOval(r, p);
            }
        }
        p.setStyle(Paint.Style.FILL);
    }
}
