package tv.dumbtv.home;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * The vignettes: small drawn scenes (private jet, falling bills, night drive, lasers, rain, sunrise,
 * campfire, planets ...), ten theme packs. All procedural, nothing is loaded and nothing is allocated
 * while drawing. Stage sets the inputs below once a frame and calls {@link #draw}. Scene ids are
 * pack * 8 + index (see Variety).
 */
final class Scenes {
    private static final int N = 72;
    private static final float TAU = (float) (Math.PI * 2);
    private static final Path HEART = new Path();

    static {
        HEART.moveTo(0f, 0.35f);
        HEART.cubicTo(-0.6f, -0.05f, -0.35f, -0.55f, 0f, -0.2f);
        HEART.cubicTo(0.35f, -0.55f, 0.6f, -0.05f, 0f, 0.35f);
        HEART.close();
    }

    // inputs, set by Stage every frame
    float w, h, dt, age, alpha = 1f, cap = 0.7f, kick, snare, hat, bass, ener, beat, speed = 1f, sat = 1f;
    boolean kicked, snared, hatted, night, barEdge, clockOn;
    String[] order = new String[0];
    float camDir = 1f, camSpeed = 1f, progress = -1f;
    int tod, wx;
    long beatN;
    /** 0 sky top, 1 sky middle, 2 horizon, 3..5 accents, 6 dark, 7 light. */
    final int[] pc = new int[8];

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int id = -1;
    private int seed;
    private final float[] x = new float[N], y = new float[N], vx = new float[N], vy = new float[N],
            life = new float[N], sz = new float[N], rot = new float[N];
    private final int[] col = new int[N];
    private int next;
    private float acc1, acc2, acc3;
    private final float[] pile = new float[16];

    Scenes() {
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    void reset(int scene, int seed) {
        id = scene;
        this.seed = seed;
        age = 0;
        next = 0;
        acc1 = acc2 = acc3 = 0;
        for (int i = 0; i < N; i++) {
            life[i] = 0;
            x[i] = y[i] = vx[i] = vy[i] = 0;
        }
        java.util.Arrays.fill(pile, 0f);
    }

    int scene() {
        return id;
    }

    // ---- helpers -------------------------------------------------------------------------------

    private float rnd(int i) {
        int v = (i + seed) * 0x9E3779B1;
        v ^= v >>> 15;
        v *= 0x85EBCA6B;
        v ^= v >>> 13;
        return (v & 0xFFFFFF) / (float) 0x1000000;
    }

    private static float cl(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** Color with alpha a (times the scene alpha), kept under the luminance cap. */
    private int ac(int c, float a) {
        int r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
        float l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
        if (l > cap) {
            float k = cap / l;
            r = (int) (r * k);
            g = (int) (g * k);
            b = (int) (b * k);
        }
        return ((int) (cl(a * alpha, 0f, 1f) * 255f) << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerp(int a, int b, float t) {
        t = cl(t, 0f, 1f);
        int r = (int) ((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t);
        int g = (int) ((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private void fill(int c, float a) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(ac(c, a));
    }

    private void stroke(int c, float a, float wd) {
        p.setStyle(Paint.Style.STROKE);
        p.setColor(ac(c, a));
        p.setStrokeWidth(wd);
    }

    private void rect(Canvas c, float l, float t, float r, float b, int col, float a) {
        if ((r - l) * (b - t) > 0.25f * w * h) { // large areas: capped colour and always a gradient, darker towards the top
            int lo = Variety.cap(col);
            float bh = (b - t) / 6f;
            for (int i = 0; i < 6; i++) {
                fill(lerp(lerp(lo, 0xFF000000, 0.3f), lo, i / 5f), a);
                c.drawRect(l, t + i * bh, r, t + (i + 1) * bh + 1f, p);
            }
            return;
        }
        fill(col, a);
        c.drawRect(l, t, r, b, p);
    }

    /** The now-playing card (with its margin), from the Stage: big discs such as sun and moon keep clear of it. */
    boolean keepOn;
    float keepL, keepT, keepR, keepB;

    private void circ(Canvas c, float cx, float cy, float r, int col, float a) {
        if (keepOn && r > h * 0.03f && cx + r > keepL && cx - r < keepR && cy + r > keepT && cy - r < keepB) {
            cx = Math.max(cx, keepR + h * 0.17f);
        }
        fill(col, a);
        c.drawCircle(cx, cy, r, p);
    }

    private void glow(Canvas c, float cx, float cy, float r, int col, float a) {
        for (int i = 0; i < 4; i++) circ(c, cx, cy, r * (1f - i * 0.22f), col, a * 0.22f);
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2, int col, float a, float wd) {
        stroke(col, a, wd);
        c.drawLine(x1, y1, x2, y2, p);
    }

    /** Vertical gradient in 14 flat bands: no shader to build. */
    private void sky(Canvas c, int top, int bot, float y0, float y1, float a) {
        top = Variety.cap(lerp(top, 0xFF000000, 0.3f)); // the sky is always a gradient with a darker top
        bot = Variety.cap(bot);
        float bh = (y1 - y0) / 14f;
        for (int i = 0; i < 14; i++) {
            fill(lerp(top, bot, i / 13f), a);
            c.drawRect(0, y0 + i * bh, w, y0 + (i + 1) * bh + 1f, p);
        }
    }

    private void stars(Canvas c, int n, float limitY, float a) {
        for (int i = 0; i < n; i++) {
            float tw = 0.55f + 0.45f * (float) Math.sin(age * (0.6f + rnd(i + 9) * 1.2f) + i);
            circ(c, rnd(i) * w, rnd(i + 300) * limitY, h * (0.0016f + 0.0016f * rnd(i + 50)), pc[7], a * tw);
        }
    }

    private void heart(Canvas c, float cx, float cy, float s, int col, float a) {
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        fill(col, a);
        c.drawPath(HEART, p);
        c.restore();
    }

    private int spawn() {
        int i = next;
        next = (next + 1) % N;
        life[i] = 1f;
        return i;
    }

    private void skyline(Canvas c, float base, float hs, float scroll, int col, float a, int salt, float winA) {
        fill(col, a);
        float bw = w / 14f;
        int first = (int) Math.floor(scroll / bw);
        for (int i = first - 1; i <= first + 16; i++) {
            float bx = i * bw - scroll;
            float bh = hs * (0.35f + 0.65f * rnd(i * 7 + salt));
            c.drawRect(bx, base - bh, bx + bw * 0.92f, base + 2f, p);
            if (winA > 0f) {
                for (int r = 0; r < 6; r++) {
                    float wy = base - bh + (r + 1) * bh / 8f;
                    if (rnd(i * 31 + r + salt) < 0.45f && wy < base - 4f) {
                        fill(pc[7], winA * (0.5f + 0.5f * rnd(i + r * 5)));
                        c.drawRect(bx + bw * 0.2f, wy, bx + bw * 0.34f, wy + bh / 16f, p);
                        c.drawRect(bx + bw * 0.55f, wy, bx + bw * 0.69f, wy + bh / 16f, p);
                        fill(col, a);
                    }
                }
            }
        }
    }

    private void hills(Canvas c, float base, float amp, float freq, float phase, int col, float a) {
        fill(col, a);
        path.reset();
        path.moveTo(0, h);
        for (int i = 0; i <= 24; i++) {
            float fx = i / 24f;
            path.lineTo(fx * w, base - amp * (0.5f + 0.5f * (float) Math.sin(fx * TAU * freq + phase)
                    + 0.3f * (float) Math.sin(fx * TAU * freq * 2.3f + phase * 1.7f)));
        }
        path.lineTo(w, h);
        path.close();
        c.drawPath(path, p);
    }

    // ---- entry ---------------------------------------------------------------------------------

    void draw(Canvas c) {
        if (id < 0 || alpha <= 0.002f) return;
        switch (id) {
            case 0: jet(c); break;
            case 1: mansion(c); break;
            case 2: contract(c); break;
            case 3: chain(c); break;
            case 4: bills(c); break;
            case 5: coins(c); break;
            case 8: heartsRise(c); break;
            case 9: rooftopLove(c); break;
            case 10: petals(c); break;
            case 11: heartPulse(c); break;
            case 16: nightDrive(c); break;
            case 17: tailLights(c); break;
            case 18: rainGlass(c); break;
            case 19: neonTunnel(c); break;
            case 24: lasers(c); break;
            case 25: discoBall(c); break;
            case 26: confetti(c); break;
            case 27: crowd(c); break;
            case 32: rainStreet(c); break;
            case 33: umbrella(c); break;
            case 34: fog(c); break;
            case 35: leaves(c); break;
            case 40: sunrise(c); break;
            case 41: beamsCross(c); break;
            case 42: doves(c); break;
            case 43: roseWindow(c); break;
            case 48: waves(c, false); break;
            case 49: boat(c); break;
            case 50: dolphins(c); break;
            case 51: waves(c, true); break;
            case 56: campfire(c); break;
            case 57: flames(c); break;
            case 58: embers(c); break;
            case 59: lava(c); break;
            case 64: cityDusk(c); break;
            case 65: rooftops(c); break;
            case 66: neonStreaks(c); break;
            case 67: subway(c); break;
            case 68: bridgeNight(c); break;
            case 72: warp(c); break;
            case 73: planet(c); break;
            case 74: nebula(c); break;
            case 75: astronaut(c); break;
            case 80: journey(c); break;
            default: break;
        }
    }

    // ---- money ---------------------------------------------------------------------------------

    private void jet(Canvas c) {
        sky(c, pc[0], pc[2], 0, h, 0.92f);
        glow(c, w * 0.78f, h * 0.7f, h * 0.45f, pc[3], 0.5f);
        circ(c, w * 0.78f, h * 0.7f, h * 0.07f, pc[7], 0.8f);
        for (int i = 0; i < 4; i++) {
            float cx = ((rnd(i) * 1.4f + age * (0.006f + 0.004f * i)) % 1.4f - 0.2f) * w;
            rect(c, cx, h * (0.55f + 0.06f * i), cx + w * (0.16f + 0.1f * rnd(i + 4)), h * (0.575f + 0.06f * i), pc[4], 0.35f);
        }
        float u = (age * 0.075f * speed) % 1.5f;
        float jx = (u - 0.25f) * w, jy = h * (0.34f - 0.05f * u) + (float) Math.sin(age * 0.9f) * h * 0.008f;
        float s = h * 0.1f;
        line(c, jx - s * 9f, jy + s * 0.05f, jx - s * 1.8f, jy, pc[7], 0.18f, s * 0.12f); // trail
        fill(pc[6], 0.95f);
        c.save();
        c.translate(jx, jy);
        c.rotate(-3f);
        c.drawOval(-s * 2.2f, -s * 0.28f, s * 2f, s * 0.28f, p);
        path.reset();
        path.moveTo(-s * 0.2f, 0);
        path.lineTo(-s * 1.3f, s * 1.1f);
        path.lineTo(-s * 0.7f, s * 1.1f);
        path.lineTo(s * 0.5f, 0);
        path.close();
        c.drawPath(path, p);
        path.reset();
        path.moveTo(-s * 1.9f, -s * 0.1f);
        path.lineTo(-s * 2.3f, -s * 0.95f);
        path.lineTo(-s * 1.7f, -s * 0.1f);
        path.close();
        c.drawPath(path, p);
        c.drawOval(-s * 1.3f, s * 0.05f, -s * 0.8f, s * 0.3f, p);
        for (int i = 0; i < 7; i++) circ(c, s * (1.4f - i * 0.28f), -s * 0.06f, s * 0.045f, pc[7], 0.8f);
        circ(c, -s * 2.2f, 0, s * (0.12f + 0.06f * kick), pc[3], 0.7f);
        c.restore();
        hills(c, h * 1.0f, h * 0.12f, 3f, 1f, pc[6], 0.95f);
    }

    private void mansion(Canvas c) {
        sky(c, pc[0], pc[2], 0, h * 0.75f, 0.92f);
        glow(c, w * 0.3f, h * 0.55f, h * 0.55f, pc[3], 0.6f);
        circ(c, w * 0.3f, h * 0.55f, h * 0.1f, pc[7], 0.85f);
        float scroll = age * w * 0.01f * speed;
        hills(c, h * 0.78f, h * 0.06f, 2f, 0.5f, lerp(pc[2], pc[6], 0.5f), 0.9f);
        // villas: a wide block, a hip roof, columns and lit windows
        float vw = w / 4f;
        int first = (int) Math.floor(scroll / vw);
        for (int i = first - 1; i <= first + 5; i++) {
            float bx = i * vw - scroll + vw * 0.5f, bh = h * (0.13f + 0.08f * rnd(i + 3));
            float base = h * 0.86f;
            fill(pc[6], 0.96f);
            c.drawRect(bx - vw * 0.4f, base - bh, bx + vw * 0.4f, base, p);
            path.reset();
            path.moveTo(bx - vw * 0.44f, base - bh);
            path.lineTo(bx - vw * 0.28f, base - bh - h * 0.05f);
            path.lineTo(bx + vw * 0.28f, base - bh - h * 0.05f);
            path.lineTo(bx + vw * 0.44f, base - bh);
            path.close();
            c.drawPath(path, p);
            for (int k = 0; k < 6; k++) {
                float lit = 0.5f + 0.4f * (float) Math.sin(age * 0.7f + i * 3 + k);
                fill(pc[3], 0.55f * lit);
                c.drawRect(bx - vw * 0.34f + k * vw * 0.125f, base - bh * 0.7f, bx - vw * 0.34f + k * vw * 0.125f + vw * 0.07f,
                        base - bh * 0.35f, p);
            }
        }
        rect(c, 0, h * 0.86f, w, h, pc[6], 0.95f);
        // the pool shows the sky
        for (int i = 0; i < 8; i++) {
            float yy = h * 0.88f + i * h * 0.015f, sh = (float) Math.sin(age * 1.2f + i) * w * 0.01f;
            rect(c, w * 0.2f + sh, yy, w * 0.8f + sh, yy + h * 0.008f, pc[3], 0.25f - i * 0.02f);
        }
        for (int i = 0; i < 3; i++) {
            float px = ((i * 0.37f + rnd(i) * 0.1f) * w - scroll * 1.6f) % (w * 1.1f);
            if (px < 0) px += w * 1.1f;
            line(c, px, h * 0.86f, px + w * 0.01f, h * 0.7f, pc[6], 0.95f, w * 0.004f);
            for (int k = -2; k <= 2; k++) line(c, px + w * 0.01f, h * 0.7f, px + w * (0.01f + k * 0.012f), h * (0.7f + 0.03f + 0.01f * Math.abs(k)), pc[6], 0.95f, w * 0.003f);
        }
    }

    private void contract(Canvas c) {
        sky(c, pc[6], lerp(pc[6], pc[1], 0.4f), 0, h, 0.94f);
        for (int i = 0; i < 14; i++) {
            glow(c, rnd(i) * w, h * (0.1f + 0.35f * rnd(i + 20)), h * (0.03f + 0.03f * rnd(i + 40)), i % 3 == 0 ? pc[3] : pc[4], 0.35f);
        }
        rect(c, 0, h * 0.74f, w, h, lerp(pc[6], pc[3], 0.18f), 0.97f);
        float slide = cl(age / 1.6f, 0f, 1f);
        slide = 1f - (1f - slide) * (1f - slide);
        float pw = w * 0.34f, ph = h * 0.62f, px = w * 0.5f - pw / 2 + (1f - slide) * -w * 0.6f, py = h * 0.2f;
        c.save();
        c.rotate(-2f, w / 2f, h / 2f);
        rect(c, px - 6, py + 8, px + pw + 6, py + ph + 10, pc[6], 0.5f);
        rect(c, px, py, px + pw, py + ph, pc[7], 0.94f);
        for (int i = 0; i < 9; i++) {
            float ly = py + ph * (0.1f + i * 0.065f), lw = pw * (i == 0 ? 0.5f : 0.78f - 0.1f * rnd(i));
            rect(c, px + pw * 0.1f, ly, px + pw * 0.1f + lw, ly + ph * 0.014f, pc[6], 0.3f);
        }
        // the signature is drawn after the page has arrived
        float prog = cl((age - 2f) / 3.2f, 0f, 1f);
        int steps = (int) (prog * 36);
        float lx = px + pw * 0.18f, ly = py + ph * 0.82f;
        stroke(pc[4], 0.95f, h * 0.007f);
        float ox = lx, oy = ly;
        for (int i = 1; i <= steps; i++) {
            float t = i / 36f;
            float nx = lx + t * pw * 0.55f, ny = ly + (float) Math.sin(t * 14f) * ph * 0.04f * (1f - t * 0.5f) - (float) Math.sin(t * 5f) * ph * 0.02f;
            c.drawLine(ox, oy, nx, ny, p);
            ox = nx;
            oy = ny;
        }
        if (prog > 0f && prog < 1f) {
            line(c, ox, oy, ox + h * 0.12f, oy - h * 0.2f, pc[3], 0.95f, h * 0.012f);
        }
        c.restore();
        if (age > 11f) age = 0f; // slides in again
    }

    private void chain(Canvas c) {
        sky(c, pc[6], lerp(pc[6], pc[1], 0.35f), 0, h, 0.95f);
        glow(c, w * 0.5f, h * 0.55f, h * 0.7f, pc[3], 0.28f);
        float ang = 0.42f * (float) Math.sin(age * 1.6f * speed) * (1f + 0.15f * kick);
        float ax = w * 0.5f, ay = -h * 0.02f, len = h * 0.78f;
        int links = 26;
        for (int i = 0; i < links; i++) {
            float t = i / (float) links, a2 = ang * (0.55f + 0.45f * t);
            float lx = ax + (float) Math.sin(a2) * len * t, ly = ay + (float) Math.cos(a2) * len * t;
            stroke(pc[3], 0.95f, h * 0.007f);
            c.save();
            c.translate(lx, ly);
            c.rotate((float) Math.toDegrees(-a2) + (i & 1) * 90f);
            c.drawOval(-h * 0.014f, -h * 0.008f, h * 0.014f, h * 0.008f, p);
            c.restore();
        }
        float px = ax + (float) Math.sin(ang) * len, py = ay + (float) Math.cos(ang) * len;
        c.save();
        c.translate(px, py + h * 0.06f);
        c.rotate((float) Math.toDegrees(-ang) + 45f);
        float d = h * 0.075f;
        rect(c, -d, -d, d, d, pc[3], 0.98f);
        rect(c, -d * 0.7f, -d * 0.7f, d * 0.7f, d * 0.7f, lerp(pc[3], pc[7], 0.5f), 0.9f);
        rect(c, -d * 0.35f, -d * 0.35f, d * 0.35f, d * 0.35f, pc[3], 0.95f);
        c.restore();
        if (kicked && acc1 <= 0f) acc1 = 1f; // sparkle, at most about twice a second
        if (acc1 > 0f) {
            acc2 = acc1 * acc1;
            for (int i = 0; i < 5; i++) {
                double a = i * TAU / 5 + age;
                line(c, px + (float) Math.cos(a) * d * 1.4f * acc2, py + h * 0.06f + (float) Math.sin(a) * d * 1.4f * acc2,
                        px + (float) Math.cos(a) * d * 2.4f * acc2, py + h * 0.06f + (float) Math.sin(a) * d * 2.4f * acc2, pc[7], 0.8f * acc2, h * 0.004f);
            }
            acc1 = Math.max(0f, acc1 - dt * (acc1 > 0.5f ? 2f : 2.6f));
            if (acc1 < 0.02f) acc1 = -0.5f;
        } else if (acc1 < 0f) {
            acc1 = Math.min(0f, acc1 + dt);
        }
    }

    private void bills(Canvas c) {
        sky(c, pc[6], lerp(pc[6], pc[3], 0.2f), 0, h, 0.95f);
        float ty = h * 0.8f;
        if (kicked || (beat > 0.97f && rnd((int) beatN) < 0.2f)) {
            for (int k = 0; k < 3; k++) {
                int i = spawn();
                x[i] = (0.1f + 0.8f * rnd((int) (beatN * 3 + k))) * w;
                y[i] = -h * 0.05f;
                vx[i] = (rnd(i + 7) - 0.5f) * w * 0.05f;
                vy[i] = h * (0.28f + 0.2f * rnd(i + 11));
                rot[i] = rnd(i + 13) * 360f;
                sz[i] = 0;
            }
        }
        for (int i = 0; i < N; i++) {
            if (life[i] <= 0f) continue;
            vx[i] *= 0.995f;
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            rot[i] += (30f + 70f * rnd(i)) * dt * (sz[i] > 0 ? 0f : 1f);
            int slot = (int) cl(x[i] / w * 16f, 0f, 15f);
            float floor = ty + h * 0.03f - pile[slot];
            if (y[i] >= floor) {
                if (sz[i] < 2f && vy[i] > h * 0.1f) {
                    y[i] = floor;
                    vy[i] = -vy[i] * 0.28f;
                    sz[i] += 1f;
                    pile[slot] = Math.min(h * 0.16f, pile[slot] + h * 0.012f);
                } else {
                    y[i] = floor;
                    vy[i] = 0;
                    life[i] -= dt * 0.7f;
                }
            } else if (sz[i] > 0) {
                vy[i] += h * 2.2f * dt;
            }
            drawBill(c, x[i], y[i], rot[i], life[i]);
        }
        rect(c, 0, ty + h * 0.03f, w, h, lerp(pc[6], pc[4], 0.25f), 0.98f);
        rect(c, 0, ty + h * 0.03f, w, ty + h * 0.045f, pc[3], 0.4f);
        for (int s = 0; s < 16; s++) {
            if (pile[s] <= 0f) continue;
            for (int k = 0; k < 4; k++) {
                int hh = (int) (pile[s] / (h * 0.012f));
                if (k >= Math.min(4, hh)) break;
                drawBill(c, (s + 0.5f) * w / 16f + (k - 1.5f) * 4f, ty + h * 0.03f - pile[s] + k * h * 0.012f, 4f * (k - 2), 1f);
            }
            pile[s] = Math.max(0f, pile[s] - dt * h * 0.002f);
        }
    }

    private void drawBill(Canvas c, float bx, float by, float deg, float a) {
        float bw = w * 0.05f, bh = bw * 0.46f;
        c.save();
        c.translate(bx, by);
        c.rotate(deg);
        rect(c, -bw / 2, -bh / 2, bw / 2, bh / 2, lerp(pc[4], pc[7], 0.15f), 0.95f * Math.min(1f, a * 2f));
        stroke(pc[6], 0.5f * a, 1.5f);
        c.drawRect(-bw / 2 + 3, -bh / 2 + 3, bw / 2 - 3, bh / 2 - 3, p);
        c.drawCircle(0, 0, bh * 0.26f, p);
        c.restore();
    }

    private void coins(Canvas c) {
        sky(c, pc[6], lerp(pc[6], pc[3], 0.18f), 0, h, 0.95f);
        if (kicked) {
            for (int k = 0; k < 4; k++) {
                int i = spawn();
                x[i] = w * (0.3f + 0.4f * rnd(i + (int) beatN));
                y[i] = h * 1.02f;
                vx[i] = (rnd(i + 3) - 0.5f) * w * 0.3f;
                vy[i] = -h * (0.9f + 0.5f * rnd(i + 5));
                rot[i] = rnd(i) * TAU;
                sz[i] = h * (0.03f + 0.02f * rnd(i + 9));
            }
        }
        // the big coin spins in the middle
        float cs = (float) Math.cos(age * 1.8f * speed), r = h * 0.2f;
        glow(c, w / 2f, h * 0.5f, r * 2f, pc[3], 0.4f);
        float ex = Math.max(0.04f, Math.abs(cs));
        fill(lerp(pc[3], pc[6], 0.4f), 0.97f);
        c.drawOval(w / 2f - r * ex - r * 0.07f, h * 0.5f - r, w / 2f + r * ex + r * 0.07f, h * 0.5f + r, p);
        fill(pc[3], 0.98f);
        c.drawOval(w / 2f - r * ex, h * 0.5f - r, w / 2f + r * ex, h * 0.5f + r, p);
        stroke(lerp(pc[3], pc[7], 0.5f), 0.8f, h * 0.008f);
        c.drawOval(w / 2f - r * ex * 0.78f, h * 0.5f - r * 0.78f, w / 2f + r * ex * 0.78f, h * 0.5f + r * 0.78f, p);
        float gl = (age * 0.5f) % 1.6f - 0.3f;
        rect(c, w / 2f - r + gl * r * 2f, h * 0.5f - r * 0.9f, w / 2f - r + gl * r * 2f + r * 0.12f, h * 0.5f + r * 0.9f, pc[7], 0.18f * ex);
        for (int i = 0; i < N; i++) {
            if (life[i] <= 0f) continue;
            vy[i] += h * 1.6f * dt;
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            rot[i] += dt * 7f;
            if (y[i] > h * 1.1f) life[i] = 0f;
            float e = Math.max(0.1f, Math.abs((float) Math.cos(rot[i])));
            fill(pc[3], 0.95f);
            c.drawOval(x[i] - sz[i] * e, y[i] - sz[i], x[i] + sz[i] * e, y[i] + sz[i], p);
            stroke(pc[7], 0.5f, 2f);
            c.drawOval(x[i] - sz[i] * e * 0.7f, y[i] - sz[i] * 0.7f, x[i] + sz[i] * e * 0.7f, y[i] + sz[i] * 0.7f, p);
        }
    }

    // ---- love ----------------------------------------------------------------------------------

    private void heartsRise(Canvas c) {
        sky(c, pc[0], pc[2], 0, h, 0.92f);
        if ((int) (age * 4f) > (int) ((age - dt) * 4f)) {
            int i = spawn();
            x[i] = rnd(i + (int) (age * 10)) * w;
            y[i] = h * 1.05f;
            sz[i] = h * (0.04f + 0.09f * rnd(i + 1));
            vy[i] = -h * (0.05f + 0.07f * rnd(i + 2)) * speed;
            rot[i] = rnd(i + 3) * TAU;
            col[i] = (i % 3 == 0) ? pc[3] : (i % 3 == 1) ? pc[4] : pc[5];
        }
        for (int i = 0; i < N; i++) {
            if (life[i] <= 0f) continue;
            y[i] += vy[i] * dt;
            float sx = x[i] + (float) Math.sin(age * 0.9f + rot[i]) * w * 0.03f;
            if (y[i] < -sz[i]) life[i] = 0f;
            heart(c, sx, y[i], sz[i] * (1f + 0.06f * kick), col[i], 0.55f * cl(y[i] / h * 1.5f, 0f, 1f));
        }
    }

    private void rooftopLove(Canvas c) {
        sky(c, pc[0], pc[2], 0, h * 0.8f, 0.94f);
        stars(c, 40, h * 0.5f, 0.6f);
        circ(c, w * 0.72f, h * 0.2f, h * 0.07f, pc[7], 0.85f);
        glow(c, w * 0.72f, h * 0.2f, h * 0.25f, pc[7], 0.2f);
        skyline(c, h * 0.78f, h * 0.35f, age * w * 0.004f, lerp(pc[6], pc[1], 0.3f), 0.95f, 5, 0.5f);
        rect(c, 0, h * 0.78f, w, h, pc[6], 0.98f);
        // the pair on the ledge
        float cx = w * 0.4f, by = h * 0.78f, sw = (float) Math.sin(age * 0.5f) * 0.01f;
        fill(pc[6], 1f);
        c.drawCircle(cx - h * 0.04f, by - h * 0.22f, h * 0.026f, p);
        c.drawCircle(cx + h * 0.04f + sw * h, by - h * 0.21f, h * 0.024f, p);
        c.drawRoundRect(cx - h * 0.075f, by - h * 0.19f, cx + h * 0.0f, by, h * 0.02f, h * 0.02f, p);
        c.drawRoundRect(cx + h * 0.0f, by - h * 0.18f, cx + h * 0.075f + sw * h, by, h * 0.02f, h * 0.02f, p);
        // string lights
        for (int i = 0; i <= 12; i++) {
            float lx = w * (0.05f + i * 0.075f), ly = h * 0.58f + (float) Math.sin(i * 0.52f) * h * 0.04f;
            float tw = 0.6f + 0.4f * (float) Math.sin(age * 2f + i * 1.7f);
            glow(c, lx, ly, h * 0.02f, pc[3], 0.55f * tw);
            circ(c, lx, ly, h * 0.005f, pc[7], 0.9f);
        }
        for (int i = 0; i < 8; i++) {
            float fx = (rnd(i) + (float) Math.sin(age * 0.2f + i) * 0.05f) * w, fy = h * (0.5f + 0.25f * rnd(i + 8)) + (float) Math.cos(age * 0.3f + i) * h * 0.03f;
            circ(c, fx, fy, h * 0.004f, pc[7], 0.5f + 0.4f * (float) Math.sin(age * 1.5f + i));
        }
    }

    private void petals(Canvas c) {
        sky(c, pc[6], lerp(pc[6], pc[1], 0.5f), 0, h, 0.95f);
        if ((int) (age * 6f) > (int) ((age - dt) * 6f)) {
            int i = spawn();
            x[i] = rnd(i * 3 + (int) (age * 7)) * w;
            y[i] = -h * 0.05f;
            vy[i] = h * (0.07f + 0.06f * rnd(i + 1));
            sz[i] = h * (0.012f + 0.014f * rnd(i + 2));
            rot[i] = rnd(i + 3) * TAU;
            col[i] = i % 2 == 0 ? pc[3] : pc[4];
        }
        for (int i = 0; i < N; i++) {
            if (life[i] <= 0f) continue;
            y[i] += vy[i] * dt;
            rot[i] += dt * (1f + rnd(i));
            float px = x[i] + (float) Math.sin(rot[i] * 0.8f) * w * 0.04f;
            if (y[i] > h * 1.05f) life[i] = 0f;
            c.save();
            c.translate(px, y[i]);
            c.rotate((float) Math.toDegrees(rot[i]));
            fill(col[i], 0.8f);
            c.drawOval(-sz[i], -sz[i] * 0.55f * Math.abs((float) Math.cos(rot[i] * 1.3f) + 0.2f), sz[i], sz[i] * 0.55f * Math.abs((float) Math.cos(rot[i] * 1.3f) + 0.2f), p);
            c.restore();
        }
        // a candle
        float cx = w * 0.5f, by = h * 0.92f;
        glow(c, cx, by - h * 0.22f, h * 0.35f, pc[3], 0.3f + 0.1f * ener);
        rect(c, cx - h * 0.02f, by - h * 0.16f, cx + h * 0.02f, by, pc[7], 0.9f);
        float fl = (float) Math.sin(age * 9f) * 0.12f + (float) Math.sin(age * 5.3f) * 0.1f;
        fill(pc[3], 0.95f);
        c.drawOval(cx - h * 0.014f, by - h * (0.24f + fl * 0.05f), cx + h * 0.014f, by - h * 0.165f, p);
        fill(pc[7], 0.9f);
        c.drawOval(cx - h * 0.007f, by - h * 0.2f, cx + h * 0.007f, by - h * 0.168f, p);
    }

    private void heartPulse(Canvas c) {
        sky(c, pc[6], lerp(pc[6], pc[3], 0.3f), 0, h, 0.95f);
        float s = h * (0.55f + 0.07f * kick + 0.015f * bass);
        for (int i = 4; i >= 1; i--) {
            float ph = (age * 0.5f * speed + i * 0.25f) % 1f;
            heart(c, w / 2f, h * 0.5f, s * (1f + ph * 1.1f), i % 2 == 0 ? pc[3] : pc[4], 0.18f * (1f - ph));
        }
        heart(c, w / 2f, h * 0.52f, s, pc[3], 0.95f);
        heart(c, w / 2f - s * 0.1f, h * 0.48f, s * 0.7f, lerp(pc[3], pc[7], 0.35f), 0.6f);
        for (int i = 0; i < 18; i++) {
            float a = i * TAU / 18 + age * 0.2f, r = s * (0.8f + 0.25f * (float) Math.sin(age + i));
            circ(c, w / 2f + (float) Math.cos(a) * r * 1.25f, h * 0.5f + (float) Math.sin(a) * r * 0.85f, h * 0.005f, pc[7], 0.5f);
        }
    }

    // ---- street --------------------------------------------------------------------------------

    private void road(Canvas c, float horizon, float a) {
        rect(c, 0, horizon, w, h, lerp(pc[6], pc[1], 0.15f), a);
        float vx0 = w / 2f;
        path.reset();
        path.moveTo(vx0 - w * 0.01f, horizon);
        path.lineTo(vx0 + w * 0.01f, horizon);
        path.lineTo(w * 0.95f, h);
        path.lineTo(w * 0.05f, h);
        path.close();
        fill(lerp(pc[6], pc[1], 0.35f), a);
        c.drawPath(path, p);
        float ph = (age * 0.9f * speed) % 1f;
        for (int i = 0; i < 9; i++) {
            float t = ((i + ph) / 9f), z = t * t;
            float y0 = horizon + (h - horizon) * z, y1 = horizon + (h - horizon) * (t + 0.05f) * (t + 0.05f);
            float lw = (w * 0.012f) * (0.2f + 3f * z);
            rect(c, vx0 - lw / 2f, y0, vx0 + lw / 2f, y1, pc[4], 0.75f * t);
        }
    }

    private void nightDrive(Canvas c) {
        float hz = h * 0.5f;
        sky(c, pc[0], pc[2], 0, hz, 0.94f);
        stars(c, 30, hz * 0.8f, 0.4f);
        skyline(c, hz, h * 0.2f, age * w * 0.006f * speed, pc[6], 0.95f, 11, 0.4f);
        road(c, hz, 0.97f);
        float ph = (age * 0.9f * speed) % 1f;
        for (int k = -1; k <= 1; k += 2) {
            for (int i = 0; i < 6; i++) {
                float t = (i + ph) / 6f, z = t * t;
                float lx = w / 2f + k * (w * 0.03f + w * 0.42f * z), ly = hz + (h - hz) * z, hh = h * 0.2f * (0.1f + z);
                line(c, lx, ly, lx, ly - hh, pc[6], 0.9f, 2f + 6f * z);
                glow(c, lx, ly - hh, h * 0.05f * (0.2f + z), pc[3], 0.6f * t);
            }
        }
    }

    private void tailLights(Canvas c) {
        float hz = h * 0.46f;
        sky(c, pc[0], pc[2], 0, hz, 0.94f);
        glow(c, w / 2f, hz, h * 0.4f, pc[3], 0.3f);
        skyline(c, hz, h * 0.14f, age * w * 0.004f, pc[6], 0.95f, 21, 0.3f);
        road(c, hz, 0.97f);
        for (int i = 0; i < 9; i++) {
            float lane = (i % 3 - 1) * 0.5f, t = 0.12f + 0.75f * ((rnd(i) + age * 0.015f * (0.4f + rnd(i + 5)) * speed) % 1f);
            float z = t * t, cx = w / 2f + lane * w * (0.1f + 0.5f * z), cy = hz + (h - hz) * z;
            float r = h * (0.006f + 0.03f * z);
            glow(c, cx - r * 3f, cy, r * 3f, pc[5], 0.7f);
            glow(c, cx + r * 3f, cy, r * 3f, pc[5], 0.7f);
            circ(c, cx - r * 3f, cy, r * 0.6f, pc[7], 0.9f);
            circ(c, cx + r * 3f, cy, r * 0.6f, pc[7], 0.9f);
        }
    }

    private void rainGlass(Canvas c) {
        sky(c, pc[6], lerp(pc[6], pc[1], 0.5f), 0, h, 0.96f);
        for (int i = 0; i < 26; i++) {
            float bx = rnd(i) * w, by = h * (0.2f + 0.6f * rnd(i + 30)), r = h * (0.03f + 0.05f * rnd(i + 60));
            float tw = 0.6f + 0.4f * (float) Math.sin(age * 0.8f + i);
            circ(c, bx, by, r, i % 3 == 0 ? pc[3] : i % 3 == 1 ? pc[4] : pc[5], 0.3f * tw);
        }
        if (life[0] <= 0f || (int) (age * 9f) > (int) ((age - dt) * 9f)) {
            int i = spawn();
            x[i] = rnd(i * 5 + (int) (age * 11)) * w;
            y[i] = -h * 0.02f;
            vy[i] = h * (0.1f + 0.3f * rnd(i + 2));
            sz[i] = h * (0.004f + 0.006f * rnd(i + 4));
        }
        for (int i = 0; i < N; i++) {
            if (life[i] <= 0f) continue;
            y[i] += vy[i] * dt;
            if (y[i] > h * 1.05f) life[i] = 0f;
            line(c, x[i], y[i] - sz[i] * 6f, x[i] + 1f, y[i], pc[7], 0.4f, sz[i] * 1.4f);
            circ(c, x[i], y[i], sz[i], pc[7], 0.5f);
        }
        float wp = ((age * 0.18f) % 1f);
        float pivotX = w * 0.5f, pivotY = h * 1.05f;
        double ang = Math.PI * 1.15f - wp * Math.PI * 1.1f;
        line(c, pivotX, pivotY, pivotX + (float) Math.cos(ang) * h * 1.15f, pivotY - (float) Math.sin(ang) * h * 1.15f, pc[6], 0.8f, h * 0.012f);
    }

    private void neonTunnel(Canvas c) {
        sky(c, pc[6], pc[6], 0, h, 0.97f);
        float ph = (age * 0.5f * speed + bass * 0.2f) % 1f;
        for (int i = 11; i >= 0; i--) {
            float t = (i + ph) / 12f, z = t * t * t;
            float hw, hh;
            hw = w * 0.04f + w * 0.5f * z;
            hh = h * 0.04f + h * 0.5f * z;
            stroke(i % 2 == 0 ? pc[3] : pc[4], 0.85f * (1f - t * 0.5f), 2f + 7f * z);
            c.drawRect(w / 2f - hw, h / 2f - hh, w / 2f + hw, h / 2f + hh, p);
        }
        for (int i = 0; i < 4; i++) {
            float a = i * TAU / 4 + 0.78f;
            line(c, w / 2f, h / 2f, w / 2f + (float) Math.cos(a) * w, h / 2f + (float) Math.sin(a) * w, pc[5], 0.25f, 2f);
        }
    }

    // ---- party ---------------------------------------------------------------------------------

    private void lasers(Canvas c) {
        sky(c, pc[6], pc[6], 0, h, 0.97f);
        glow(c, w / 2f, 0, h * 0.8f, pc[3], 0.12f);
        int n = 7;
        for (int i = 0; i < n; i++) {
            float ox = w * (i + 0.5f) / n, ang = (float) Math.sin(age * (0.5f + 0.15f * (i % 3)) * speed + i) * 0.7f;
            int colr = i % 3 == 0 ? pc[3] : i % 3 == 1 ? pc[4] : pc[5];
            float ex = ox + (float) Math.sin(ang) * h * 1.4f, ey = (float) Math.cos(ang) * h * 1.4f;
            line(c, ox, 0, ex, ey, colr, 0.18f, h * 0.03f);
            line(c, ox, 0, ex, ey, colr, 0.55f, h * 0.007f);
        }
        float fl = Math.max(0f, kick - 0.6f);
        rect(c, 0, h * 0.9f, w, h, pc[3], 0.12f + 0.2f * fl);
    }

    private void discoBall(Canvas c) {
        sky(c, pc[6], lerp(pc[6], pc[1], 0.5f), 0, h, 0.96f);
        float cx = w / 2f, cy = h * 0.36f, r = h * 0.17f;
        line(c, cx, 0, cx, cy - r, pc[7], 0.6f, 2f);
        circ(c, cx, cy, r, lerp(pc[6], pc[7], 0.45f), 0.97f);
        for (int i = 1; i < 6; i++) {
            float e = r * (float) Math.cos(i * 0.5f);
            stroke(pc[6], 0.6f, 2f);
            c.drawOval(cx - e, cy - r, cx + e, cy + r, p);
            c.drawLine(cx - r, cy - r + i * r / 3f, cx + r, cy - r + i * r / 3f, p);
        }
        for (int i = 0; i < 40; i++) {
            float a = rnd(i) * TAU + age * 0.25f, d = h * (0.2f + 0.6f * rnd(i + 20));
            float dx = cx + (float) Math.cos(a) * d * 1.6f, dy = cy + (float) Math.sin(a) * d * 0.9f;
            float tw = 0.5f + 0.5f * (float) Math.sin(age * 2f + i * 1.9f);
            circ(c, dx, dy, h * (0.008f + 0.01f * rnd(i + 3)), i % 3 == 0 ? pc[3] : i % 3 == 1 ? pc[4] : pc[5], 0.5f * tw);
        }
        glow(c, cx, cy, r * 2.2f, pc[7], 0.12f);
    }

    private void confetti(Canvas c) {
        sky(c, pc[6], lerp(pc[6], pc[1], 0.6f), 0, h, 0.95f);
        int perSec = 28;
        if ((int) (age * perSec) > (int) ((age - dt) * perSec) || kicked) {
            for (int k = 0; k < (kicked ? 4 : 1); k++) {
                int i = spawn();
                x[i] = rnd(i + (int) (age * 17)) * w;
                y[i] = -h * 0.03f;
                vy[i] = h * (0.14f + 0.18f * rnd(i + 1));
                vx[i] = (rnd(i + 2) - 0.5f) * w * 0.06f;
                sz[i] = h * (0.008f + 0.01f * rnd(i + 3));
                rot[i] = rnd(i + 4) * TAU;
                col[i] = i % 3 == 0 ? pc[3] : i % 3 == 1 ? pc[4] : pc[5];
            }
        }
        for (int i = 0; i < N; i++) {
            if (life[i] <= 0f) continue;
            y[i] += vy[i] * dt;
            x[i] += (vx[i] + (float) Math.sin(rot[i] * 2f) * w * 0.02f) * dt;
            rot[i] += dt * 5f;
            if (y[i] > h * 1.05f) life[i] = 0f;
            float e = Math.abs((float) Math.cos(rot[i]));
            fill(col[i], 0.85f);
            c.drawRect(x[i] - sz[i], y[i] - sz[i] * e, x[i] + sz[i], y[i] + sz[i] * e, p);
        }
    }

    private void crowd(Canvas c) {
        sky(c, pc[6], pc[1], 0, h, 0.96f);
        for (int i = 0; i < 4; i++) {
            float bx = w * (0.15f + 0.23f * i), a = 0.25f + 0.3f * ener;
            path.reset();
            path.moveTo(bx, 0);
            path.lineTo(bx - w * 0.12f, h * 0.8f);
            path.lineTo(bx + w * 0.12f, h * 0.8f);
            path.close();
            fill(i % 2 == 0 ? pc[3] : pc[4], 0.1f + 0.08f * a);
            c.drawPath(path, p);
        }
        fill(pc[6], 1f);
        for (int row = 0; row < 3; row++) {
            for (int i = 0; i < 14; i++) {
                float px = (i + 0.5f + row * 0.4f) * w / 13.5f, py = h * (0.82f + row * 0.07f);
                float bob = (float) Math.sin(age * 4f * speed + i * 1.3f + row) * h * 0.01f * (0.5f + beat);
                py += bob;
                c.drawCircle(px, py - h * 0.1f, h * (0.026f + row * 0.004f), p);
                c.drawRoundRect(px - h * 0.04f, py - h * 0.075f, px + h * 0.04f, h, h * 0.02f, h * 0.02f, p);
                if (((i + row) % 3) != 0) {
                    float up = h * (0.1f + 0.05f * (float) Math.sin(age * 3f + i));
                    line(c, px - h * 0.03f, py - h * 0.06f, px - h * 0.06f, py - h * 0.06f - up, pc[6], 1f, h * 0.014f);
                    line(c, px + h * 0.03f, py - h * 0.06f, px + h * 0.06f, py - h * 0.06f - up * 0.9f, pc[6], 1f, h * 0.014f);
                    fill(pc[6], 1f);
                }
            }
        }
    }

    // ---- grief ---------------------------------------------------------------------------------

    /** 0..1 from the Stage: rain comes in bursts on beats and drops. */
    float burst = 1f;

    private void rainDrops(Canvas c, float ground, float a, int rate) {
        rate = (int) (rate * burst);
        if (rate > 0 && (int) (age * rate) > (int) ((age - dt) * rate)) {
            int i = spawn();
            x[i] = rnd(i * 5 + (int) (age * 13)) * w;
            y[i] = -h * 0.05f;
            vy[i] = h * (0.9f + 0.4f * rnd(i + 1));
            sz[i] = 0;
        }
        for (int i = 0; i < N; i++) {
            if (life[i] <= 0f) continue;
            y[i] += vy[i] * dt;
            if (y[i] > ground + h * 0.12f * rnd(i + 8)) {
                life[i] = 0f;
                if ((i & 3) == 0 && next < N) {
                    pile[i & 15] = 1f;
                    x[(i + 40) % N] = x[i];
                }
                continue;
            }
            line(c, x[i], y[i] - h * 0.04f, x[i] - h * 0.004f, y[i], pc[7], a, 1.5f);
        }
        for (int s = 0; s < 16; s++) {
            if (pile[s] <= 0f) continue;
            float r = (1f - pile[s]) * h * 0.04f + 2f;
            stroke(pc[7], 0.3f * pile[s], 1.5f);
            c.drawOval((s + 0.5f) * w / 16f - r * 2f, ground + h * 0.03f - r * 0.4f, (s + 0.5f) * w / 16f + r * 2f, ground + h * 0.03f + r * 0.4f, p);
            pile[s] = Math.max(0f, pile[s] - dt * 1.4f);
        }
    }

    private void rainStreet(Canvas c) {
        float hz = h * 0.55f;
        sky(c, pc[6], lerp(pc[6], pc[1], 0.6f), 0, h, 0.96f);
        skyline(c, hz, h * 0.28f, age * w * 0.003f, lerp(pc[6], pc[1], 0.25f), 0.95f, 31, 0.28f);
        rect(c, 0, hz, w, h, lerp(pc[6], pc[1], 0.2f), 0.96f);
        for (int i = 0; i < 6; i++) {
            float lx = w * (0.1f + 0.16f * i);
            glow(c, lx, hz + h * 0.1f + (i % 2) * h * 0.1f, h * 0.05f, i % 2 == 0 ? pc[3] : pc[4], 0.25f);
        }
        rainDrops(c, hz, 0.35f, 40);
    }

    private void umbrella(Canvas c) {
        sky(c, pc[6], lerp(pc[6], pc[1], 0.4f), 0, h, 0.97f);
        float lx = w * 0.62f, gy = h * 0.88f;
        path.reset();
        path.moveTo(lx, h * 0.18f);
        path.lineTo(lx - w * 0.16f, gy);
        path.lineTo(lx + w * 0.16f, gy);
        path.close();
        fill(pc[3], 0.1f + 0.03f * (float) Math.sin(age * 7f));
        c.drawPath(path, p);
        line(c, lx, gy, lx, h * 0.18f, pc[6], 1f, h * 0.008f);
        glow(c, lx, h * 0.18f, h * 0.09f, pc[3], 0.7f);
        rect(c, 0, gy, w, h, pc[6], 0.98f);
        float fx = w * 0.45f;
        fill(pc[6], 1f);
        c.drawCircle(fx, gy - h * 0.24f, h * 0.024f, p);
        c.drawRoundRect(fx - h * 0.03f, gy - h * 0.21f, fx + h * 0.03f, gy, h * 0.02f, h * 0.02f, p);
        path.reset();
        path.moveTo(fx - h * 0.14f, gy - h * 0.31f);
        path.quadTo(fx, gy - h * 0.42f, fx + h * 0.14f, gy - h * 0.31f);
        path.close();
        fill(pc[4], 0.9f);
        c.drawPath(path, p);
        line(c, fx, gy - h * 0.31f, fx, gy - h * 0.2f, pc[6], 1f, 2f);
        rainDrops(c, gy - h * 0.02f, 0.45f, 55);
    }

    private void fog(Canvas c) {
        sky(c, pc[1], pc[2], 0, h, 0.94f);
        for (int l = 0; l < 4; l++) {
            hills(c, h * (0.55f + l * 0.1f), h * 0.12f, 2f + l, age * 0.05f * (l + 1) * (l % 2 == 0 ? 1 : -1) + l, lerp(pc[2], pc[6], 0.25f + l * 0.2f), 0.85f);
            for (int i = 0; i < 6; i++) {
                float fx = ((rnd(i + l * 9) + age * 0.01f * (l + 1)) % 1.4f - 0.2f) * w;
                glow(c, fx, h * (0.5f + l * 0.1f), h * 0.2f, pc[7], 0.08f);
            }
        }
        float bx = ((age * 0.04f) % 1.3f - 0.15f) * w, by = h * (0.25f + 0.03f * (float) Math.sin(age * 1.2f));
        line(c, bx - h * 0.03f, by + h * 0.01f * (float) Math.sin(age * 6f), bx, by, pc[6], 0.9f, 3f);
        line(c, bx + h * 0.03f, by + h * 0.01f * (float) Math.sin(age * 6f), bx, by, pc[6], 0.9f, 3f);
    }

    private void leaves(Canvas c) {
        sky(c, lerp(pc[1], pc[7], 0.15f), lerp(pc[2], pc[6], 0.2f), 0, h, 0.93f);
        if ((int) (age * 3f) > (int) ((age - dt) * 3f)) {
            int i = spawn();
            x[i] = (0.2f + 0.9f * rnd(i + (int) (age * 5))) * w;
            y[i] = -h * 0.04f;
            vy[i] = h * (0.05f + 0.05f * rnd(i + 1));
            vx[i] = -w * (0.01f + 0.02f * rnd(i + 2));
            sz[i] = h * (0.012f + 0.012f * rnd(i + 3));
            rot[i] = rnd(i + 4) * TAU;
            col[i] = lerp(pc[3], pc[6], 0.35f + 0.3f * rnd(i + 9));
        }
        for (int i = 0; i < N; i++) {
            if (life[i] <= 0f) continue;
            y[i] += vy[i] * dt;
            x[i] += vx[i] * dt;
            rot[i] += dt * 1.6f;
            if (y[i] > h || x[i] < -w * 0.1f) life[i] = 0f;
            c.save();
            c.translate(x[i] + (float) Math.sin(rot[i]) * w * 0.02f, y[i]);
            c.rotate((float) Math.toDegrees(rot[i]));
            fill(col[i], 0.85f);
            c.drawOval(-sz[i], -sz[i] * 0.45f, sz[i], sz[i] * 0.45f, p);
            c.restore();
        }
        rect(c, 0, h * 0.86f, w, h, pc[6], 0.95f);
        // a lone bench
        float bx = w * 0.7f, by = h * 0.86f;
        rect(c, bx - w * 0.07f, by - h * 0.07f, bx + w * 0.07f, by - h * 0.055f, pc[6], 1f);
        rect(c, bx - w * 0.07f, by - h * 0.13f, bx + w * 0.07f, by - h * 0.115f, pc[6], 1f);
        rect(c, bx - w * 0.06f, by - h * 0.055f, bx - w * 0.055f, by, pc[6], 1f);
        rect(c, bx + w * 0.055f, by - h * 0.055f, bx + w * 0.06f, by, pc[6], 1f);
    }

    // ---- faith ---------------------------------------------------------------------------------

    private void sunrise(Canvas c) {
        float up = cl(age / 40f, 0f, 1f);
        sky(c, pc[0], lerp(pc[2], pc[3], 0.4f), 0, h * 0.8f, 0.94f);
        float sx = w * 0.5f, sy = h * (0.78f - 0.34f * up);
        for (int i = 0; i < 12; i++) {
            float a = i * TAU / 12 + age * 0.03f;
            path.reset();
            path.moveTo(sx, sy);
            path.lineTo(sx + (float) Math.cos(a - 0.05f) * w, sy + (float) Math.sin(a - 0.05f) * w);
            path.lineTo(sx + (float) Math.cos(a + 0.05f) * w, sy + (float) Math.sin(a + 0.05f) * w);
            path.close();
            fill(pc[7], 0.05f + 0.025f * bass);
            c.drawPath(path, p);
        }
        glow(c, sx, sy, h * 0.5f, pc[3], 0.5f);
        circ(c, sx, sy, h * 0.09f, pc[7], 0.95f);
        hills(c, h * 0.84f, h * 0.1f, 2f, 1f, lerp(pc[2], pc[6], 0.55f), 0.95f);
        hills(c, h * 0.92f, h * 0.08f, 3f, 3f, pc[6], 0.98f);
    }

    private void beamsCross(Canvas c) {
        sky(c, pc[6], lerp(pc[1], pc[2], 0.5f), 0, h, 0.95f);
        for (int i = 0; i < 7; i++) {
            float bx = w * (0.1f + 0.13f * i) + (float) Math.sin(age * 0.2f + i) * w * 0.02f;
            path.reset();
            path.moveTo(bx - w * 0.01f, h * 0.18f);
            path.lineTo(bx + w * 0.01f, h * 0.18f);
            path.lineTo(bx + w * 0.07f + (i - 3) * w * 0.02f, h);
            path.lineTo(bx - w * 0.07f + (i - 3) * w * 0.02f, h);
            path.close();
            fill(pc[7], 0.07f + 0.04f * (float) Math.sin(age * 0.5f + i));
            c.drawPath(path, p);
        }
        for (int i = 0; i < 6; i++) {
            float cx = ((rnd(i) + age * 0.006f * (1 + i % 3)) % 1.3f - 0.15f) * w;
            glow(c, cx, h * (0.12f + 0.07f * (i % 3)), h * 0.16f, pc[1], 0.4f);
        }
        hills(c, h * 0.86f, h * 0.1f, 1.5f, 0.2f, pc[6], 0.98f);
        float cx = w * 0.7f, cy = h * 0.74f;
        rect(c, cx - h * 0.006f, cy - h * 0.14f, cx + h * 0.006f, cy, pc[6], 1f);
        rect(c, cx - h * 0.045f, cy - h * 0.1f, cx + h * 0.045f, cy - h * 0.088f, pc[6], 1f);
    }

    private void doves(Canvas c) {
        sky(c, lerp(pc[0], pc[7], 0.3f), lerp(pc[2], pc[7], 0.25f), 0, h, 0.93f);
        glow(c, w * 0.2f, h * 0.25f, h * 0.5f, pc[7], 0.3f);
        for (int i = 0; i < 9; i++) {
            float t = (age * 0.03f * speed * (0.7f + 0.6f * rnd(i)) + rnd(i + 3)) % 1.2f;
            float bx = (t - 0.1f) * w, by = h * (0.2f + 0.5f * rnd(i + 6)) - (float) Math.sin(t * 3f) * h * 0.1f;
            float s = h * (0.025f + 0.025f * rnd(i + 9)), fl = (float) Math.sin(age * (5f + 2f * rnd(i)) + i);
            stroke(pc[7], 0.95f, s * 0.35f);
            c.drawLine(bx, by, bx - s * 1.4f, by - s * (0.7f + fl * 0.8f), p);
            c.drawLine(bx, by, bx + s * 1.4f, by - s * (0.7f + fl * 0.8f), p);
            c.drawLine(bx - s * 0.4f, by, bx + s * 0.4f, by, p);
        }
    }

    private void roseWindow(Canvas c) {
        sky(c, pc[6], pc[6], 0, h, 0.97f);
        float cx = w / 2f, cy = h / 2f, r = h * 0.42f;
        circ(c, cx, cy, r * 1.08f, lerp(pc[6], pc[7], 0.3f), 0.95f);
        for (int ring = 3; ring >= 0; ring--) {
            int petals = 8 + ring * 4;
            float rr = r * (0.3f + 0.23f * ring);
            for (int i = 0; i < petals; i++) {
                float a = i * TAU / petals + age * 0.04f * (ring % 2 == 0 ? 1 : -1);
                int colr = (i + ring) % 3 == 0 ? pc[3] : (i + ring) % 3 == 1 ? pc[4] : pc[5];
                float lit = 0.55f + 0.2f * (float) Math.sin(age * 0.9f + i + ring) + 0.12f * bass;
                circ(c, cx + (float) Math.cos(a) * rr, cy + (float) Math.sin(a) * rr, r * (0.11f - ring * 0.012f), colr, lit);
            }
        }
        circ(c, cx, cy, r * 0.12f, pc[7], 0.8f);
        stroke(pc[6], 0.95f, h * 0.012f);
        c.drawCircle(cx, cy, r, p);
        for (int i = 0; i < 12; i++) {
            float a = i * TAU / 12 + age * 0.04f;
            line(c, cx, cy, cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, pc[6], 0.8f, h * 0.006f);
        }
    }

    // ---- ocean ---------------------------------------------------------------------------------

    private void sea(Canvas c, float hz, int layers, float amp, float spd, float a) {
        for (int l = 0; l < layers; l++) {
            float yy = hz + (h - hz) * (l + 0.5f) / layers;
            fill(lerp(pc[2], pc[6], (l + 1f) / (layers + 0.5f)), a);
            path.reset();
            path.moveTo(0, h);
            for (int i = 0; i <= 28; i++) {
                float fx = i / 28f;
                path.lineTo(fx * w, yy + amp * (l + 1) * ((float) Math.sin(fx * TAU * (1.5f + l) + age * spd * (l % 2 == 0 ? 1 : -1))
                        + 0.4f * (float) Math.sin(fx * TAU * 4.3f + age * spd * 1.7f)));
            }
            path.lineTo(w, h);
            path.close();
            c.drawPath(path, p);
        }
    }

    private void waves(Canvas c, boolean storm) {
        float hz = h * (storm ? 0.4f : 0.52f);
        sky(c, storm ? pc[6] : pc[0], storm ? lerp(pc[6], pc[1], 0.6f) : pc[2], 0, hz + 2f, 0.94f);
        if (!storm) {
            glow(c, w * 0.5f, hz, h * 0.4f, pc[3], 0.5f);
            circ(c, w * 0.5f, hz - h * 0.02f, h * 0.07f, pc[7], 0.9f);
            for (int i = 0; i < 8; i++) {
                float gw = w * (0.01f + 0.05f * rnd(i)) * (1f + 0.5f * (float) Math.sin(age * 2f + i));
                rect(c, w * 0.5f - gw / 2, hz + h * (0.02f + 0.06f * i), w * 0.5f + gw / 2, hz + h * (0.03f + 0.06f * i), pc[7], 0.3f);
            }
        } else {
            for (int i = 0; i < 4; i++) glow(c, ((rnd(i) + age * 0.01f) % 1.3f - 0.15f) * w, h * 0.12f, h * 0.3f, pc[1], 0.4f);
            if (kicked && acc1 <= 0f) acc1 = 1f;
            if (acc1 > 0f) {
                rect(c, 0, 0, w, hz, pc[7], 0.13f * acc1 * acc1);
                acc1 = Math.max(0f, acc1 - dt * 2.4f);
                if (acc1 <= 0f) acc1 = -0.5f;
            } else if (acc1 < 0f) {
                acc1 = Math.min(0f, acc1 + dt);
            }
        }
        sea(c, hz, storm ? 5 : 4, h * (storm ? 0.018f + 0.01f * bass : 0.008f + 0.004f * bass), storm ? 1.6f : 0.8f, 0.95f);
        if (storm) rainDrops(c, h, 0.25f, 30);
    }

    private void boat(Canvas c) {
        float hz = h * 0.55f;
        sky(c, pc[0], pc[2], 0, hz + 2f, 0.94f);
        circ(c, w * 0.8f, h * 0.22f, h * 0.06f, pc[7], 0.85f);
        glow(c, w * 0.8f, h * 0.22f, h * 0.3f, pc[3], 0.3f);
        sea(c, hz, 3, h * 0.008f, 0.7f, 0.95f);
        float bx = w * 0.45f + (float) Math.sin(age * 0.1f) * w * 0.12f, by = hz + h * 0.05f + (float) Math.sin(age * 1.1f) * h * 0.008f;
        c.save();
        c.translate(bx, by);
        c.rotate((float) Math.sin(age * 1.1f) * 3f);
        fill(pc[6], 1f);
        path.reset();
        path.moveTo(-h * 0.12f, 0);
        path.lineTo(h * 0.14f, 0);
        path.lineTo(h * 0.09f, h * 0.04f);
        path.lineTo(-h * 0.08f, h * 0.04f);
        path.close();
        c.drawPath(path, p);
        rect(c, -h * 0.004f, -h * 0.28f, h * 0.004f, 0, pc[6], 1f);
        path.reset();
        path.moveTo(h * 0.012f, -h * 0.27f);
        path.lineTo(h * 0.11f, -h * 0.02f);
        path.lineTo(h * 0.012f, -h * 0.02f);
        path.close();
        fill(pc[7], 0.9f);
        c.drawPath(path, p);
        path.reset();
        path.moveTo(-h * 0.012f, -h * 0.22f);
        path.lineTo(-h * 0.09f, -h * 0.02f);
        path.lineTo(-h * 0.012f, -h * 0.02f);
        path.close();
        fill(lerp(pc[7], pc[3], 0.3f), 0.9f);
        c.drawPath(path, p);
        c.restore();
        for (int i = 0; i < 3; i++) {
            float gx = ((age * 0.03f * (1 + i * 0.3f) + rnd(i)) % 1.2f - 0.1f) * w, gy = h * (0.18f + 0.08f * i) + (float) Math.sin(age * 2f + i) * h * 0.01f;
            line(c, gx - h * 0.02f, gy + h * 0.008f * (float) Math.sin(age * 5f + i), gx, gy, pc[6], 0.8f, 2f);
            line(c, gx + h * 0.02f, gy + h * 0.008f * (float) Math.sin(age * 5f + i), gx, gy, pc[6], 0.8f, 2f);
        }
    }

    private void dolphins(Canvas c) {
        float hz = h * 0.5f;
        sky(c, pc[0], pc[2], 0, hz + 2f, 0.94f);
        glow(c, w * 0.3f, hz, h * 0.4f, pc[3], 0.4f);
        if (kicked && life[0] <= 0f) {
            life[0] = 1f;
            x[0] = w * (0.15f + 0.4f * rnd((int) beatN));
            vy[0] = 0;
            rot[0] = 0;
        }
        sea(c, hz, 4, h * 0.01f, 0.9f, 0.95f);
        if (life[0] > 0f) {
            rot[0] += dt * 0.9f;
            float t = rot[0], jx = x[0] + t * w * 0.28f, jy = hz + h * 0.03f - (float) Math.sin(t * Math.PI) * h * 0.28f;
            float ang = (float) Math.toDegrees(Math.atan2(-Math.cos(t * Math.PI) * 0.28f * h * Math.PI, w * 0.28f));
            c.save();
            c.translate(jx, jy);
            c.rotate(ang);
            fill(pc[6], 0.98f);
            c.drawOval(-h * 0.1f, -h * 0.024f, h * 0.1f, h * 0.024f, p);
            path.reset();
            path.moveTo(-h * 0.02f, -h * 0.02f);
            path.lineTo(-h * 0.05f, -h * 0.07f);
            path.lineTo(h * 0.01f, -h * 0.02f);
            path.close();
            c.drawPath(path, p);
            path.reset();
            path.moveTo(-h * 0.1f, 0);
            path.lineTo(-h * 0.15f, -h * 0.04f);
            path.lineTo(-h * 0.15f, h * 0.04f);
            path.close();
            c.drawPath(path, p);
            c.restore();
            if (t >= 1f) {
                life[0] = 0f;
                for (int i = 1; i < 9; i++) {
                    life[i] = 1f;
                    x[i] = jx;
                    y[i] = hz + h * 0.03f;
                    vx[i] = (rnd(i + 3) - 0.5f) * w * 0.12f;
                    vy[i] = -h * (0.15f + 0.25f * rnd(i));
                }
            }
        }
        for (int i = 1; i < 9; i++) {
            if (life[i] <= 0f) continue;
            vy[i] += h * 1.4f * dt;
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            life[i] -= dt * 1.2f;
            circ(c, x[i], y[i], h * 0.006f, pc[7], 0.7f * life[i]);
        }
    }

    // ---- fire ----------------------------------------------------------------------------------

    private void campfire(Canvas c) {
        sky(c, pc[6], lerp(pc[6], pc[1], 0.4f), 0, h, 0.97f);
        stars(c, 60, h * 0.6f, 0.6f);
        float cx = w / 2f, by = h * 0.86f;
        glow(c, cx, by - h * 0.1f, h * 0.6f, pc[3], 0.25f + 0.1f * bass);
        rect(c, 0, by + h * 0.02f, w, h, pc[6], 0.98f);
        for (int l = 0; l < 4; l++) {
            float fh = h * (0.2f - l * 0.03f) * (1f + 0.15f * (float) Math.sin(age * (7f + l * 3f)) + 0.2f * bass);
            float fw = h * (0.07f - l * 0.012f);
            path.reset();
            path.moveTo(cx - fw, by);
            path.quadTo(cx - fw * 1.3f, by - fh * 0.5f, cx + (float) Math.sin(age * 5f + l) * fw * 0.4f, by - fh);
            path.quadTo(cx + fw * 1.3f, by - fh * 0.5f, cx + fw, by);
            path.close();
            fill(l == 0 ? pc[5] : l == 1 ? pc[3] : l == 2 ? lerp(pc[3], pc[7], 0.5f) : pc[7], 0.9f);
            c.drawPath(path, p);
        }
        fill(pc[6], 1f);
        c.save();
        c.rotate(14f, cx, by);
        c.drawRoundRect(cx - h * 0.14f, by - h * 0.012f, cx + h * 0.14f, by + h * 0.022f, 6f, 6f, p);
        c.restore();
        c.save();
        c.rotate(-14f, cx, by);
        c.drawRoundRect(cx - h * 0.14f, by - h * 0.012f, cx + h * 0.14f, by + h * 0.022f, 6f, 6f, p);
        c.restore();
        sparks(c, cx, by - h * 0.12f, 0.5f);
    }

    private void sparks(Canvas c, float sx, float sy, float rate) {
        if ((int) (age * 22f * rate) > (int) ((age - dt) * 22f * rate) || kicked) {
            int i = spawn();
            x[i] = sx + (rnd(i + (int) (age * 9)) - 0.5f) * h * 0.1f;
            y[i] = sy;
            vx[i] = (rnd(i + 2) - 0.5f) * w * 0.03f;
            vy[i] = -h * (0.12f + 0.22f * rnd(i + 3));
            sz[i] = h * (0.003f + 0.004f * rnd(i + 5));
        }
        for (int i = 0; i < N; i++) {
            if (life[i] <= 0f) continue;
            x[i] += (vx[i] + (float) Math.sin(age * 3f + i) * w * 0.01f) * dt;
            y[i] += vy[i] * dt;
            life[i] -= dt * 0.35f;
            circ(c, x[i], y[i], sz[i], life[i] > 0.5f ? pc[7] : pc[3], life[i]);
        }
    }

    private void flames(Canvas c) {
        sky(c, pc[6], lerp(pc[6], pc[5], 0.35f), 0, h, 0.97f);
        for (int l = 0; l < 3; l++) {
            int n = 22 + l * 6;
            fill(l == 0 ? pc[5] : l == 1 ? pc[3] : lerp(pc[3], pc[7], 0.55f), 0.55f + 0.2f * l);
            for (int i = 0; i < n; i++) {
                float fx = (i + 0.5f) * w / n, fh = h * (0.18f + 0.3f * rnd(i + l * 40)) * (1f - l * 0.25f) * (0.75f + 0.25f * (float) Math.sin(age * (3f + 2f * rnd(i)) + i)) * (0.8f + 0.5f * bass);
                float fw = w / n * 1.2f;
                path.reset();
                path.moveTo(fx - fw, h);
                path.quadTo(fx - fw * 0.5f, h - fh * 0.5f, fx + (float) Math.sin(age * 4f + i) * fw * 0.5f, h - fh);
                path.quadTo(fx + fw * 0.5f, h - fh * 0.5f, fx + fw, h);
                path.close();
                c.drawPath(path, p);
            }
        }
        sparks(c, w / 2f, h * 0.7f, 1.2f);
    }

    private void embers(Canvas c) {
        sky(c, pc[6], lerp(pc[6], pc[5], 0.2f), 0, h, 0.97f);
        glow(c, w / 2f, h, h * 0.9f, pc[5], 0.25f);
        for (int k = 0; k < 2; k++) {
            if ((int) (age * 18f) > (int) ((age - dt) * 18f)) {
                int i = spawn();
                x[i] = rnd(i * 3 + (int) (age * 7) + k) * w;
                y[i] = h * 1.03f;
                vx[i] = (rnd(i + 1) - 0.5f) * w * 0.03f;
                vy[i] = -h * (0.08f + 0.18f * rnd(i + 2)) * (0.8f + ener);
                sz[i] = h * (0.003f + 0.01f * rnd(i + 4));
            }
        }
        for (int i = 0; i < N; i++) {
            if (life[i] <= 0f) continue;
            x[i] += (vx[i] + (float) Math.sin(age * 1.5f + i) * w * 0.02f) * dt;
            y[i] += vy[i] * dt;
            life[i] -= dt * 0.18f;
            glow(c, x[i], y[i], sz[i] * 3f, pc[3], life[i] * 0.5f);
            circ(c, x[i], y[i], sz[i], pc[7], life[i]);
        }
    }

    private void lava(Canvas c) {
        sky(c, pc[6], pc[6], 0, h, 0.98f);
        float pulse = 0.55f + 0.25f * (float) Math.sin(age * 0.8f) + 0.15f * bass;
        for (int k = 0; k < 7; k++) {
            float px = rnd(k) * w, py = h * (0.45f + 0.5f * rnd(k + 9));
            stroke(pc[5], 0.35f * pulse, h * 0.03f);
            for (int s = 0; s < 6; s++) {
                float nx = px + (rnd(k * 7 + s) - 0.5f) * w * 0.18f, ny = py + (rnd(k * 11 + s + 3) - 0.3f) * h * 0.1f;
                c.drawLine(px, py, nx, ny, p);
                px = nx;
                py = ny;
            }
        }
        for (int k = 0; k < 7; k++) {
            float px = rnd(k) * w, py = h * (0.45f + 0.5f * rnd(k + 9));
            stroke(pc[3], 0.9f * pulse, h * 0.008f);
            for (int s = 0; s < 6; s++) {
                float nx = px + (rnd(k * 7 + s) - 0.5f) * w * 0.18f, ny = py + (rnd(k * 11 + s + 3) - 0.3f) * h * 0.1f;
                c.drawLine(px, py, nx, ny, p);
                px = nx;
                py = ny;
            }
        }
        for (int i = 0; i < 10; i++) {
            float hx = rnd(i + 70) * w, hy = h * (0.3f - 0.2f * ((age * 0.08f * (0.5f + rnd(i)) + rnd(i + 5)) % 1f)) + h * 0.3f;
            glow(c, hx, hy, h * 0.05f, pc[5], 0.1f);
        }
    }

    // ---- city ----------------------------------------------------------------------------------

    private void cityDusk(Canvas c) {
        float hz = h * 0.7f;
        sky(c, pc[0], pc[2], 0, hz, 0.94f);
        glow(c, w * 0.7f, hz, h * 0.5f, pc[3], 0.35f);
        skyline(c, hz, h * 0.3f, age * w * 0.004f, lerp(pc[2], pc[6], 0.5f), 0.9f, 41, 0f);
        skyline(c, hz, h * 0.45f, age * w * 0.008f, pc[6], 0.97f, 43, 0.45f);
        rect(c, 0, hz, w, h, lerp(pc[6], pc[1], 0.1f), 0.98f);
        for (int i = 0; i < 12; i++) {
            float dir = i % 2 == 0 ? 1f : -1f, t = (rnd(i) + age * 0.08f * speed * (0.6f + rnd(i + 4))) % 1f;
            float cx = dir > 0 ? t * w * 1.1f - w * 0.05f : w * 1.05f - t * w * 1.1f, cy = hz + h * (0.05f + 0.045f * (i % 6));
            glow(c, cx, cy, h * 0.025f, dir > 0 ? pc[7] : pc[5], 0.7f);
        }
    }

    private void rooftops(Canvas c) {
        sky(c, pc[6], lerp(pc[6], pc[1], 0.5f), 0, h, 0.96f);
        stars(c, 35, h * 0.35f, 0.5f);
        circ(c, w * 0.2f, h * 0.18f, h * 0.05f, pc[7], 0.8f);
        skyline(c, h * 0.72f, h * 0.5f, age * w * 0.003f, lerp(pc[6], pc[1], 0.3f), 0.95f, 51, 0.6f);
        skyline(c, h * 0.88f, h * 0.45f, age * w * 0.006f, pc[6], 1f, 53, 0.8f);
        rect(c, w * 0.62f, h * 0.76f, w * 0.7f, h * 0.84f, pc[6], 1f);
        rect(c, w * 0.6f, h * 0.74f, w * 0.72f, h * 0.76f, pc[6], 1f);
        rect(c, w * 0.63f, h * 0.84f, w * 0.635f, h * 0.9f, pc[6], 1f);
        rect(c, w * 0.685f, h * 0.84f, w * 0.69f, h * 0.9f, pc[6], 1f);
    }

    private void neonStreaks(Canvas c) {
        sky(c, pc[6], pc[6], 0, h, 0.97f);
        float cx = w / 2f, cy = h * 0.46f;
        for (int i = 0; i < 24; i++) {
            float a = rnd(i) * TAU, t = (rnd(i + 30) + age * 0.35f * speed * (0.6f + rnd(i + 7))) % 1f;
            float r0 = w * 0.04f + t * t * w * 0.7f, r1 = r0 + w * 0.02f + t * t * w * 0.25f;
            int colr = i % 3 == 0 ? pc[3] : i % 3 == 1 ? pc[4] : pc[5];
            line(c, cx + (float) Math.cos(a) * r0, cy + (float) Math.sin(a) * r0 * 0.7f, cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1 * 0.7f, colr, 0.15f + 0.6f * t, 1.5f + 7f * t);
        }
        for (int i = 0; i < 6; i++) {
            float bx = w * (0.1f + 0.16f * i), bh = h * (0.2f + 0.25f * rnd(i + 70));
            stroke(i % 2 == 0 ? pc[3] : pc[4], 0.5f + 0.2f * (float) Math.sin(age * 0.7f + i), 3f);
            c.drawRect(bx, h - bh, bx + w * 0.08f, h - 2f, p);
        }
    }

    private void subway(Canvas c) {
        sky(c, pc[6], lerp(pc[6], pc[1], 0.4f), 0, h, 0.97f);
        rect(c, 0, h * 0.72f, w, h, lerp(pc[6], pc[1], 0.15f), 0.98f);
        for (int i = 0; i < 9; i++) {
            float px = ((i / 9f + age * 0.05f * speed) % 1f) * w;
            rect(c, px, 0, px + w * 0.012f, h * 0.72f, lerp(pc[6], pc[1], 0.5f), 0.9f);
            glow(c, px, h * 0.12f, h * 0.06f, pc[7], 0.3f);
        }
        float tx = ((age * 0.28f * speed) % 1.8f - 0.5f) * w;
        for (int k = 0; k < 5; k++) {
            float cx = tx - k * w * 0.2f;
            rect(c, cx, h * 0.4f, cx + w * 0.19f, h * 0.72f, pc[6], 1f);
            for (int wdw = 0; wdw < 4; wdw++) rect(c, cx + w * (0.015f + wdw * 0.043f), h * 0.46f, cx + w * (0.04f + wdw * 0.043f), h * 0.56f, pc[3], 0.85f);
            rect(c, cx, h * 0.62f, cx + w * 0.19f, h * 0.635f, pc[4], 0.8f);
        }
        rect(c, 0, h * 0.72f, w, h * 0.735f, pc[7], 0.4f);
    }

    private void bridgeNight(Canvas c) {
        float hz = h * 0.6f;
        sky(c, pc[0], pc[2], 0, hz, 0.94f);
        stars(c, 25, hz * 0.5f, 0.5f);
        skyline(c, hz, h * 0.25f, age * w * 0.003f, pc[6], 0.95f, 61, 0.5f);
        rect(c, 0, hz, w, h, lerp(pc[6], pc[1], 0.3f), 0.96f);
        for (int k = -1; k <= 1; k += 2) {
            float tx = w / 2f + k * w * 0.22f;
            rect(c, tx - w * 0.008f, h * 0.18f, tx + w * 0.008f, hz + h * 0.1f, pc[6], 1f);
            for (int i = 1; i <= 7; i++) {
                line(c, tx, h * 0.2f, tx + k * -1f * i * w * 0.03f, hz - h * 0.04f, pc[6], 0.9f, 2f);
                line(c, tx, h * 0.2f, tx + i * w * 0.03f * k, hz - h * 0.04f, pc[6], 0.9f, 2f);
            }
        }
        rect(c, 0, hz - h * 0.05f, w, hz - h * 0.035f, pc[6], 1f);
        for (int i = 0; i < 10; i++) {
            float dir = i % 2 == 0 ? 1f : -1f, t = (rnd(i) + age * 0.06f * speed) % 1f;
            float cx = dir > 0 ? t * w : w - t * w;
            glow(c, cx, hz - h * (dir > 0 ? 0.043f : 0.052f), h * 0.014f, dir > 0 ? pc[7] : pc[5], 0.8f);
            rect(c, cx - 2f, hz + h * 0.04f + i * 0.0f, cx + 2f, hz + h * 0.14f, dir > 0 ? pc[7] : pc[5], 0.12f);
        }
    }

    // ---- space ---------------------------------------------------------------------------------

    private void warp(Canvas c) {
        sky(c, pc[6], pc[6], 0, h, 0.98f);
        float cx = w / 2f, cy = h / 2f, sp = 0.25f + 0.25f * speed + bass * 0.3f;
        for (int i = 0; i < 60; i++) {
            float a = rnd(i) * TAU, t = (rnd(i + 70) + age * sp * (0.4f + rnd(i + 3))) % 1f;
            float r0 = t * t * w * 0.6f, r1 = r0 + t * t * w * 0.12f + 3f;
            line(c, cx + (float) Math.cos(a) * r0, cy + (float) Math.sin(a) * r0, cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                    i % 5 == 0 ? pc[3] : pc[7], 0.15f + 0.7f * t, 1f + 3f * t);
        }
        glow(c, cx, cy, h * 0.3f, pc[4], 0.1f + 0.05f * ener);
    }

    private void planet(Canvas c) {
        sky(c, pc[6], lerp(pc[6], pc[1], 0.4f), 0, h, 0.97f);
        stars(c, 90, h, 0.7f);
        float cx = w * 0.55f, cy = h * 0.52f, r = h * 0.25f;
        float tilt = -18f;
        c.save();
        c.rotate(tilt, cx, cy);
        stroke(pc[4], 0.55f, h * 0.02f);
        c.drawOval(cx - r * 1.9f, cy - r * 0.5f, cx + r * 1.9f, cy + r * 0.5f, p);
        circ(c, cx, cy, r, lerp(pc[3], pc[6], 0.3f), 0.97f);
        c.save();
        c.clipRect(cx - r, cy - r, cx + r, cy + r);
        for (int b = 0; b < 6; b++) rect(c, cx - r, cy - r + b * r / 3f + (float) Math.sin(age * 0.2f + b) * 3f, cx + r, cy - r + b * r / 3f + r / 6f, b % 2 == 0 ? pc[4] : pc[5], 0.25f);
        c.restore();
        circ(c, cx + r * 0.35f, cy + r * 0.2f, r, pc[6], 0.0f);
        // shadow side
        c.save();
        c.clipRect(cx - r, cy - r, cx + r, cy + r);
        circ(c, cx + r * 0.55f, cy + r * 0.2f, r * 1.05f, pc[6], 0.55f);
        c.restore();
        stroke(pc[4], 0.8f, h * 0.02f);
        c.save();
        c.clipRect(cx - r * 2f, cy, cx + r * 2f, cy + r);
        c.drawOval(cx - r * 1.9f, cy - r * 0.5f, cx + r * 1.9f, cy + r * 0.5f, p);
        c.restore();
        c.restore();
        for (int m = 0; m < 2; m++) {
            float a = age * (0.2f + 0.15f * m) + m * 2f;
            circ(c, cx + (float) Math.cos(a) * r * (2.3f + m * 0.6f), cy + (float) Math.sin(a) * r * 0.9f, h * (0.02f + 0.008f * m), pc[7], 0.85f);
        }
    }

    private void nebula(Canvas c) {
        sky(c, pc[6], pc[6], 0, h, 0.98f);
        for (int i = 0; i < 16; i++) {
            float bx = (rnd(i) + (float) Math.sin(age * 0.05f + i) * 0.05f) * w, by = (0.2f + 0.6f * rnd(i + 20)) * h + (float) Math.cos(age * 0.04f + i) * h * 0.04f;
            glow(c, bx, by, h * (0.2f + 0.3f * rnd(i + 40)), i % 3 == 0 ? pc[3] : i % 3 == 1 ? pc[4] : pc[5], 0.14f + 0.05f * bass);
        }
        stars(c, 100, h, 0.8f);
    }

    private void astronaut(Canvas c) {
        sky(c, pc[6], pc[6], 0, h, 0.98f);
        stars(c, 80, h, 0.7f);
        fill(lerp(pc[3], pc[6], 0.5f), 0.95f);
        c.drawCircle(w * 0.5f, h * 1.9f, h * 1.15f, p);
        stroke(pc[7], 0.4f, h * 0.006f);
        c.drawCircle(w * 0.5f, h * 1.9f, h * 1.15f, p);
        float ax = w * 0.5f + (float) Math.sin(age * 0.2f) * w * 0.12f, ay = h * 0.42f + (float) Math.cos(age * 0.3f) * h * 0.05f;
        c.save();
        c.translate(ax, ay);
        c.rotate((float) Math.sin(age * 0.25f) * 25f);
        float s = h * 0.1f;
        fill(pc[7], 0.95f);
        c.drawRoundRect(-s * 0.7f, s * 0.2f, s * 0.7f, s * 1.9f, s * 0.35f, s * 0.35f, p);
        c.drawCircle(0, 0, s * 0.62f, p);
        c.drawRoundRect(-s * 1.4f, s * 0.35f, -s * 0.6f, s * 0.7f, s * 0.15f, s * 0.15f, p);
        c.drawRoundRect(s * 0.6f, s * 0.35f, s * 1.4f, s * 0.7f, s * 0.15f, s * 0.15f, p);
        c.drawRoundRect(-s * 0.6f, s * 1.8f, -s * 0.15f, s * 2.6f, s * 0.15f, s * 0.15f, p);
        c.drawRoundRect(s * 0.15f, s * 1.8f, s * 0.6f, s * 2.5f, s * 0.15f, s * 0.15f, p);
        circ(c, 0, 0, s * 0.44f, pc[6], 0.95f);
        circ(c, -s * 0.15f, -s * 0.12f, s * 0.12f, pc[4], 0.5f);
        c.restore();
    }

    // ---- journey: a camera route through the artist's city -------------------------------------

    private final int[] lmIdx = new int[3];
    private final float[] lmT = new float[3];
    private final boolean[] lmOn = new boolean[3];
    private int routeIdx;
    private float lastSpawn = -99f;

    private void journey(Canvas c) {
        if (age < dt * 2f) {
            routeIdx = 0;
            lastSpawn = -99f;
            java.util.Arrays.fill(lmOn, false);
        }
        float hz = h * 0.68f, cam = age * w * 0.02f * camSpeed * camDir;
        boolean nightT = tod == 3;
        sky(c, pc[0], pc[2], 0, hz + 2f, 0.96f);
        if (nightT) stars(c, 70, hz * 0.7f, 0.7f);
        float bx = w * (0.2f + 0.6f * rnd(3)), by = tod == 0 ? h * 0.2f : tod == 1 ? h * 0.42f : tod == 2 ? h * 0.5f : h * 0.22f;
        glow(c, bx, by, h * 0.4f, nightT ? pc[7] : pc[3], nightT ? 0.2f : 0.45f);
        circ(c, bx, by, h * 0.055f, pc[7], 0.9f);
        for (int i = 0; i < 4; i++) {
            float cx = ((rnd(i + 90) + age * 0.004f * (1 + i % 2) * camDir) % 1.4f + 1.4f) % 1.4f * w - w * 0.2f;
            glow(c, cx, h * (0.12f + 0.1f * rnd(i + 94)), h * 0.12f, nightT ? pc[1] : pc[7], 0.18f);
        }
        skyline(c, hz, h * 0.22f, cam * 0.25f, lerp(pc[2], pc[6], 0.35f), 0.92f, 71, 0f);
        if (age % 40f > 20f) skyline(c, hz, h * 0.3f, cam * 0.5f, lerp(pc[2], pc[6], 0.65f), 0.9f * Math.min(1f, age % 40f - 20f), 73, 0.12f);
        rect(c, 0, hz, w, h, lerp(pc[6], pc[1], 0.12f), 0.98f);
        // landmarks come in at bar boundaries (every other bar), in the route's order
        int total = order.length;
        if (routeIdx < total) {
            float need = progress >= 0f ? routeIdx / (float) total * 0.88f : 0f;
            boolean okProgress = progress < 0f || progress >= need;
            boolean timeOk = age - lastSpawn > (clockOn ? 3.6f : 6.5f);
            boolean due = okProgress && ((clockOn ? barEdge && ((beatN >> 3) & 1) == 0 : true) && timeOk || age - lastSpawn > 13f);
            if (due && age > 0.5f) {
                for (int i = 0; i < 3; i++) {
                    if (!lmOn[i]) {
                        lmOn[i] = true;
                        lmIdx[i] = routeIdx++;
                        lmT[i] = 0f;
                        lastSpawn = age;
                        break;
                    }
                }
            }
        }
        for (int i = 0; i < 3; i++) {
            if (!lmOn[i]) continue;
            boolean last = lmIdx[i] == total - 1;
            lmT[i] += dt / (11f / camSpeed);
            float t = lmT[i];
            if (last) t = Math.min(t, 0.444f);
            else if (t > 1f) {
                lmOn[i] = false;
                continue;
            }
            float lx = camDir > 0 ? w * (1.3f - 1.8f * t) : w * (-0.3f + 1.8f * t);
            float s = h * ((last ? 0.5f : 0.4f) + 0.3f * t);
            float a = Math.min(1f, t * 12f);
            fill(lerp(pc[6], pc[2], 0.18f), 0.97f * a);
            Landmarks.draw(c, order[lmIdx[i]], lx, hz + h * 0.02f, s, p);
            if (nightT || tod == 2) glow(c, lx, hz - s * 0.4f, s * 0.6f, pc[3], 0.12f * a);
        }
        // road, lamps and palms in front
        float fc = cam * 1.8f;
        rect(c, 0, hz + h * 0.1f, w, h, pc[6], 0.98f);
        for (int i = 0; i < 12; i++) {
            float mx = (((i / 12f) * w - fc * 0.9f) % w + w) % w;
            rect(c, mx, hz + h * 0.19f, mx + w * 0.04f, hz + h * 0.205f, pc[7], 0.35f);
        }
        float lamp = w / 5f;
        int first = (int) Math.floor(fc / lamp);
        for (int i = first - 1; i <= first + 7; i++) {
            float lx = i * lamp - fc;
            line(c, lx, hz + h * 0.1f, lx, hz - h * 0.12f, pc[6], 1f, h * 0.006f);
            if (nightT || tod >= 1) glow(c, lx, hz - h * 0.12f, h * 0.05f, pc[3], 0.55f);
            if (i % 3 == 0) {
                line(c, lx + lamp * 0.5f, hz + h * 0.1f, lx + lamp * 0.5f + h * 0.01f, hz - h * 0.05f, pc[6], 1f, h * 0.007f);
                for (int k = -2; k <= 2; k++) line(c, lx + lamp * 0.5f + h * 0.01f, hz - h * 0.05f, lx + lamp * 0.5f + h * (0.01f + k * 0.026f), hz - h * (0.05f - 0.01f * Math.abs(k)), pc[6], 1f, h * 0.005f);
            }
        }
        if (wx == 1) {
            for (int i = 0, nr = (int) (50 * burst); i < nr; i++) {
                float rx = rnd(i) * w, ry = ((rnd(i + 60) + age * (0.9f + rnd(i + 3))) % 1f) * h;
                line(c, rx, ry, rx - h * 0.006f, ry + h * 0.04f, pc[7], 0.3f, 1.5f);
            }
        } else if (wx == 2 || wx == 3) {
            for (int i = 0; i < 5; i++) glow(c, ((rnd(i + 120) + age * 0.01f * camDir) % 1.3f + 1.3f) % 1.3f * w - w * 0.15f, hz - h * 0.05f * i, h * 0.3f, pc[7], wx == 2 ? 0.1f : 0.05f);
        }
        barEdge = false;
    }
}
