package tv.dumbtv.home;

import java.util.ArrayList;

/**
 * Geometry of the concert rig: beams, foreground silhouettes and accents as plain primitives, from a Look and the
 * music state. Pure Java so the offline preview draws the same frames as the TV. Everything is a stateless function
 * of time and the last kick/snare/drop times, so frames can be rendered in any order.
 *
 * Comfort rules built in: thin crisp lines (never wider than 0.4% of the height), alpha capped at 0.55 (peak
 * luminance stays under 0.8), no soft discs (dots are at most 1.2% of the height across), no strobe (every level
 * is a smooth envelope; the beat swell is +-25% of base), no mirrored kaleidoscope (left and right play
 * call-and-response, not copies), camera drift at most 3 degrees a second.
 */
final class RigGeom {
    static final int LINE = 0, POLY = 1, ELLIPSE = 2, DOT = 3;
    static final int COL_MAIN = 0, COL_ALT = 1, COL_WHITE = 2, COL_SHADE = 3;
    static final float MAX_ALPHA = 0.55f;

    static final class Prim {
        int kind, col;
        float x1, y1, x2, y2; // line ends, or ellipse centre (x1,y1) and radii (x2,y2), or dot centre (x1,y1) radius x2
        float[] poly = new float[16];
        int n;
        float w, a;
    }

    /** the music state for one frame */
    static final class In {
        float t;               // seconds
        float mt;              // seconds on the motion clock (t scaled by the entry's motion weight): sweeps and drift
        float punch = 0.04f;   // camera punch of the kick, fraction of the picture
        boolean ringKick;      // kick style ring: a shockwave ring on every kick
        double barPos;         // bars + phase inside the bar
        float beatPhase;       // 0..1 inside the beat
        long beatIdx;
        float kickEnv, snareEnv, bass, mid, high, ener; // smooth 0..1
        float lastKickT = -99f, lastSnareT = -99f, prevKickT = -99f, dropT = -99f;
        int kind;              // Director.INTRO..OUTRO
        float kindProgress;    // build / outro progress 0..1
        float lookAge;         // seconds
    }

    private final ArrayList<Prim> pool = new ArrayList<>();
    private int used;
    final ArrayList<Prim> out = new ArrayList<>();
    private int W, H;
    private float lw;
    private In in;
    private float swell;

    private Prim next(int kind, int col, float a, float w) {
        Prim p;
        if (used < pool.size()) p = pool.get(used);
        else {
            p = new Prim();
            pool.add(p);
        }
        used++;
        p.kind = kind;
        p.col = col;
        p.a = Math.min(MAX_ALPHA, Math.max(0f, a)) * swell;
        if (col == COL_SHADE) p.a = Math.min(0.92f, a); // silhouettes are dark, not light: no cap, no swell
        p.w = Math.min(w, 0.004f * H);
        p.n = 0;
        out.add(p);
        return p;
    }

    private void line(int col, float a, float w, float x1, float y1, float x2, float y2) {
        Prim p = next(LINE, col, a, w);
        p.x1 = x1;
        p.y1 = y1;
        p.x2 = x2;
        p.y2 = y2;
    }

    private void dot(int col, float a, float cx, float cy, float r) {
        Prim p = next(DOT, col, a, 0);
        p.x1 = cx;
        p.y1 = cy;
        p.x2 = Math.min(r, 0.006f * H); // at most 1.2% of the height across
    }

    private void ring(int col, float a, float w, float cx, float cy, float rx, float ry) {
        Prim p = next(ELLIPSE, col, a, w);
        p.x1 = cx;
        p.y1 = cy;
        p.x2 = rx;
        p.y2 = ry;
    }

    private Prim poly(int col, float a, int pts) {
        Prim p = next(POLY, col, a, 0);
        p.n = pts;
        if (p.poly.length < pts * 2) p.poly = new float[pts * 2];
        return p;
    }

    static float h(int a, int b) {
        int x = a * 374761393 + b * 668265263;
        x = (x ^ (x >>> 13)) * 1274126177;
        x ^= x >>> 16;
        return (x & 0xFFFFFF) / (float) 0x1000000;
    }

    /** Draws one frame of the look into {@link #out}. density 0..1 is how full the rig is for the section. */
    ArrayList<Prim> frame(Look look, In s, int w, int hgt, float alpha, float density) {
        out.clear();
        used = 0;
        W = w;
        H = hgt;
        in = s;
        lw = Math.max(2f, 0.0022f * H);
        swell = alpha * (0.75f + 0.25f * Math.max(0f, Math.min(1f, s.kickEnv)));
        float beams = beamLevel(s);
        if (look.beam != 0 && beams > 0.02f) beam(look.beam, look.pattern, s, beams);
        fg(look.fg, s, density);
        accent(look.accent, s, density);
        if (s.ringKick) { // kick style ring: a wide low shockwave leaves the floor on every kick
            float d = (s.t - s.lastKickT) / 0.9f;
            if (d >= 0f && d < 1f) ring(COL_ALT, 0.34f * (1f - d), lw * 1.5f, 0.5f * W, 0.62f * H, W * (0.08f + 0.5f * d), H * (0.04f + 0.26f * d));
        }
        return out;
    }

    /** how much of the beam layer shows in this section (intro none, build opening, drop/chorus full, breakdown none, outro fading) */
    static float beamLevel(In s) {
        switch (s.kind) {
            case Director.INTRO: return 0f;
            case Director.BUILD: return 0.35f + 0.5f * s.kindProgress;
            case Director.DROP: return 1f;
            case Director.CHORUS: return 1f;
            case Director.BREAK: return 0f;
            default: return Math.max(0f, 0.6f * (1f - s.kindProgress * 1.4f));
        }
    }

    static float densityFor(int kind, float progress) {
        switch (kind) {
            case Director.INTRO: return 0.15f;
            case Director.BUILD: return 0.3f + 0.3f * progress;
            case Director.DROP: return 0.85f;
            case Director.CHORUS: return 1f;
            case Director.BREAK: return 0.12f;
            default: return Math.max(0.05f, 0.35f * (1f - progress));
        }
    }

    // ---------------- beams ----------------

    private void beam(int type, int pat, In s, float lvl) {
        float spread = s.kind == Director.BUILD ? 0.55f + 0.45f * s.kindProgress : 1f; // beams widen through a build
        float L = 1.5f * H;
        double bar = s.barPos;
        switch (type) {
            case 1: { // fan lasers: left cluster answers the kick, right cluster the snare
                int n = new int[] {7, 11, 9}[pat];
                for (int side = 0; side < 2; side++) {
                    float ex = pat == 1 ? 0.5f * W : (side == 0 ? 0.1f : 0.9f) * W;
                    if (pat == 1 && side == 1) break;
                    float ey = pat == 2 ? 1.02f * H : -0.02f * H;
                    float dir = pat == 2 ? -1f : 1f;
                    float env = pat == 1 ? 0.7f : (side == 0 ? s.kickEnv : s.snareEnv);
                    double sweep = Math.sin(2 * Math.PI * bar / (pat == 0 ? 4 : 2) + side * Math.PI) * 0.26 * spread;
                    for (int i = 0; i < n; i++) {
                        double u = n == 1 ? 0 : (i / (double) (n - 1)) * 2 - 1;
                        double ang = Math.PI / 2 + u * 0.62 * spread + sweep + (side == 0 ? 0.14 : -0.14) * (pat == 1 ? 0 : 1);
                        float a = lvl * (0.26f + 0.2f * env);
                        line(i % 2 == 0 ? COL_MAIN : COL_ALT, a, lw, ex, ey, ex + (float) Math.cos(ang) * L, ey + dir * (float) Math.sin(ang) * L);
                    }
                }
                break;
            }
            case 2: { // moving-head cones, hard edges
                int n = new int[] {4, 6, 3}[pat];
                for (int i = 0; i < n; i++) {
                    float ex = (i + 0.5f) / n * W;
                    double ph = i * 0.9 + (i % 2) * Math.PI;
                    double ang = Math.PI / 2 + Math.sin(2 * Math.PI * bar / (pat == 2 ? 8 : 4) + ph) * 0.5 * spread;
                    float half = 0.035f * (pat == 2 ? 2f : 1f);
                    float x1 = ex + (float) Math.cos(ang - half) * L, y1 = (float) Math.sin(ang - half) * L;
                    float x2 = ex + (float) Math.cos(ang + half) * L, y2 = (float) Math.sin(ang + half) * L;
                    Prim p = poly(i % 2 == 0 ? COL_MAIN : COL_ALT, lvl * 0.13f, 3);
                    p.poly[0] = ex; p.poly[1] = 0; p.poly[2] = x1; p.poly[3] = y1; p.poly[4] = x2; p.poly[5] = y2;
                    line(i % 2 == 0 ? COL_MAIN : COL_ALT, lvl * 0.4f, lw, ex, 0, x1, y1);
                    line(i % 2 == 0 ? COL_MAIN : COL_ALT, lvl * 0.4f, lw, ex, 0, x2, y2);
                }
                break;
            }
            case 3: { // pillars rising from the stage floor
                int n = new int[] {7, 9, 5}[pat];
                for (int i = 0; i < n; i++) {
                    float x = (i + 0.5f) / n * W;
                    float breathe = 0.5f + 0.5f * (float) Math.sin(2 * Math.PI * bar / 2 + i * 0.8);
                    float hgt = H * (0.32f + 0.38f * (0.4f * breathe + 0.6f * Math.min(1f, s.bass * 1.6f)) * spread);
                    float hw = 0.0045f * W;
                    Prim p = poly(i % 2 == 0 ? COL_MAIN : COL_ALT, lvl * 0.2f, 4);
                    p.poly[0] = x - hw; p.poly[1] = H; p.poly[2] = x + hw; p.poly[3] = H; p.poly[4] = x + hw; p.poly[5] = H - hgt; p.poly[6] = x - hw; p.poly[7] = H - hgt;
                    line(COL_WHITE, lvl * 0.3f, lw, x, H, x, H - hgt);
                }
                break;
            }
            case 4: { // criss-cross lattice between the top and bottom rigs
                int n = new int[] {5, 7, 9}[pat];
                float slide = (float) Math.sin(2 * Math.PI * bar / 4) * 0.22f * W * spread;
                for (int i = 0; i < n; i++) {
                    float x = (i + 0.5f) / n * W;
                    line(COL_MAIN, lvl * 0.3f, lw, x, 0, x + slide, H);
                    line(COL_ALT, lvl * 0.3f, lw, x, H, x - slide, 0);
                }
                break;
            }
            case 5: { // ring lasers: ellipses opening from the stage on the beat
                int n = new int[] {4, 6, 8}[pat];
                float cx = 0.5f * W, cy = (pat == 1 ? 0.5f : 0.62f) * H;
                for (int k = 0; k < n; k++) {
                    float u = (k + s.beatPhase) / n;
                    float rx = u * 0.62f * W * (0.7f + 0.3f * spread), ry = rx * 0.34f;
                    ring(k % 2 == 0 ? COL_MAIN : COL_ALT, lvl * 0.42f * (1f - u), lw, cx, cy, rx, ry);
                }
                break;
            }
            case 6: { // wipers: two long beams from the bottom corners, opposite phase
                for (int side = 0; side < 2; side++) {
                    float ex = side == 0 ? 0f : W;
                    double ang = Math.toRadians(55 + 38 * Math.sin(2 * Math.PI * bar / (pat == 0 ? 4 : 8) + side * Math.PI) * spread);
                    float dx = (float) Math.cos(ang) * (side == 0 ? 1 : -1), dy = -(float) Math.sin(ang);
                    float half = 0.03f;
                    Prim p = poly(side == 0 ? COL_MAIN : COL_ALT, lvl * 0.12f, 3);
                    double a1 = ang - half, a2 = ang + half;
                    p.poly[0] = ex; p.poly[1] = H;
                    p.poly[2] = ex + (float) Math.cos(a1) * L * (side == 0 ? 1 : -1); p.poly[3] = H - (float) Math.sin(a1) * L;
                    p.poly[4] = ex + (float) Math.cos(a2) * L * (side == 0 ? 1 : -1); p.poly[5] = H - (float) Math.sin(a2) * L;
                    line(side == 0 ? COL_MAIN : COL_ALT, lvl * 0.45f, lw, ex, H, ex + dx * L, H + dy * L);
                }
                break;
            }
            case 7: { // crossed fans from both bottom corners
                int n = new int[] {5, 7, 9}[pat];
                for (int side = 0; side < 2; side++) {
                    float ex = side == 0 ? 0.04f * W : 0.96f * W;
                    for (int i = 0; i < n; i++) {
                        double u = (i / (double) (n - 1)) * 2 - 1;
                        double ang = Math.toRadians(62 + u * 30 * spread + 10 * Math.sin(2 * Math.PI * bar / 8 + side * Math.PI));
                        float dir = side == 0 ? 1 : -1;
                        line(side == 0 ? COL_MAIN : COL_ALT, lvl * 0.3f, lw, ex, H, ex + dir * (float) Math.cos(ang) * L, H - (float) Math.sin(ang) * L);
                    }
                }
                break;
            }
            default: { // 8 curtain: slanted parallel lines drifting across
                int n = new int[] {14, 18, 22}[pat];
                float slant = 0.35f * H;
                for (int i = 0; i < n; i++) {
                    float u = (float) (((i / (double) n) + bar * (pat == 0 ? 0.25 : 0.5)) % 1.0);
                    float x = u * 1.5f * W - 0.25f * W;
                    float aMod = 0.5f + 0.5f * (float) Math.sin(i * 1.3 + bar * 1.6);
                    line(i % 2 == 0 ? COL_MAIN : COL_ALT, lvl * (0.18f + 0.18f * aMod), lw, x, 0, x - slant, H);
                }
            }
        }
    }

    // ---------------- foreground: lights and particles only, nothing human ----------------

    private void fg(int type, In s, float density) {
        float t = s.t, mt = s.mt;
        switch (type) {
            case 0: { // edge: light strips along the stage edges, with ticks running along them on the bar clock
                float a = 0.30f + 0.16f * s.kickEnv;
                line(COL_MAIN, a, lw * 1.6f, 0, 0.992f * H, W, 0.992f * H);
                line(COL_ALT, a * 0.8f, lw * 1.4f, 0.006f * W, 0, 0.006f * W, H);
                line(COL_ALT, a * 0.8f, lw * 1.4f, 0.994f * W, 0, 0.994f * W, H);
                float run = (float) (s.barPos * 2 % 1.0);
                for (int i = 0; i < 36; i++) {
                    float x = ((i + run) / 36f) * W;
                    line(COL_WHITE, 0.30f, lw, x, 0.992f * H - 0.014f * H, x, 0.992f * H);
                }
                for (int i = 0; i < 14; i++) {
                    float y = ((i + run) / 14f) * H;
                    line(COL_WHITE, 0.24f, lw, 0.006f * W, y, 0.006f * W + 0.012f * W, y);
                    line(COL_WHITE, 0.24f, lw, 0.994f * W, y, 0.994f * W - 0.012f * W, y);
                }
                break;
            }
            case 1: { // truss: a dark bar across the top with zig-zag struts and a row of lights
                float y = 0.04f * H;
                line(COL_SHADE, 0.9f, 0.01f * H, 0, y, W, y);
                int n = 22;
                for (int i = 0; i < n; i++) {
                    float x0 = i / (float) n * W, x1 = (i + 1) / (float) n * W;
                    line(COL_SHADE, 0.85f, 3f, x0, i % 2 == 0 ? 0 : y, x1, i % 2 == 0 ? y : 0);
                    float pulse = 0.5f + 0.5f * (float) Math.sin(2 * Math.PI * s.barPos / 2 + i * 0.5);
                    dot(i % 2 == 0 ? COL_MAIN : COL_ALT, 0.28f + 0.22f * pulse, (i + 0.5f) / n * W, y + 0.014f * H, 0.004f * H);
                }
                break;
            }
            case 2: { // haze: long streaks of light with hard edges, drifting sideways at different heights
                for (int i = 0; i < 7; i++) {
                    float len = W * (0.30f + 0.3f * h(i, 70));
                    float y = H * (0.50f + 0.42f * h(i, 71));
                    float th = H * (0.004f + 0.006f * h(i, 72));
                    float x = (float) (((h(i, 73) + mt * (0.012 + 0.02 * h(i, 74)) * (i % 2 == 0 ? 1 : -1)) % 1.4 + 1.4) % 1.4) * W - 0.2f * W;
                    float slant = 0.012f * H;
                    Prim p = poly(i % 2 == 0 ? COL_MAIN : COL_ALT, 0.07f + 0.07f * Math.min(1f, s.ener * 1.5f), 4);
                    p.poly[0] = x; p.poly[1] = y; p.poly[2] = x + len; p.poly[3] = y - slant;
                    p.poly[4] = x + len; p.poly[5] = y - slant + th; p.poly[6] = x; p.poly[7] = y + th;
                }
                break;
            }
            case 3: { // floor: a perspective grid and the reflections of the stage lights standing on it
                float vy = 0.74f * H, vx = 0.5f * W;
                int k = 7;
                for (int i = 0; i < k; i++) {
                    float u = (float) (((i + (s.barPos * 2) % 1.0) / k) % 1.0);
                    float y = vy + (H - vy) * u * u;
                    line(COL_MAIN, 0.12f + 0.12f * u, lw, 0, y, W, y);
                }
                for (int i = -6; i <= 6; i++) line(COL_ALT, 0.14f, lw, vx + i * 0.02f * W, vy, vx + i * 0.2f * W, H);
                for (int i = 0; i < 9; i++) {
                    float x = (i + 0.5f) / 9f * W;
                    float sh = 0.5f + 0.5f * (float) Math.sin(2 * Math.PI * s.barPos / 2 + i * 0.9);
                    line(COL_WHITE, 0.08f + 0.12f * Math.min(1f, s.bass * 1.5f) * sh, lw, x, vy + 0.03f * H, x + (h(i, 75) - 0.5f) * 0.04f * W, H);
                }
                break;
            }
            case 4: { // rise: four particle fountains climbing from the floor, higher on the bass
                int n = 8 + (int) (10 * density);
                for (int e = 0; e < 4; e++) {
                    float x0 = (0.2f + 0.2f * e) * W;
                    for (int i = 0; i < n; i++) {
                        float u = (float) ((mt * 0.45 + h(i, 80 + e) * 1.0) % 1.0);
                        float rise = H * (0.28f + 0.30f * Math.min(1f, s.bass * 1.6f)) * u;
                        float x = x0 + (h(i, 84 + e) - 0.5f) * 0.07f * W * u + 0.006f * W * (float) Math.sin(mt * 2 + i);
                        float y = H - rise;
                        float a = 0.5f * (1f - u);
                        line(e % 2 == 0 ? COL_WHITE : COL_ALT, a, lw, x, y, x, y + 0.02f * H);
                        dot(COL_WHITE, a * 0.7f, x, y, 0.0028f * H);
                    }
                }
                break;
            }
            case 5: { // motes: near drifting dust (bigger, slower) and a few sparks floating upward
                for (int i = 0; i < 26; i++) {
                    float depth = 0.4f + 0.6f * h(i, 90);
                    float x = (float) ((h(i, 91) + mt * 0.010 * depth) % 1.0) * W;
                    float y = (float) ((h(i, 92) + 0.02f * Math.sin(mt * 0.4 + i) - mt * 0.006 * depth + 2.0) % 1.0) * H;
                    dot(COL_WHITE, 0.18f + 0.2f * depth, x, y, (0.0025f + 0.003f * depth) * H);
                }
                for (int i = 0; i < 10; i++) {
                    float u = (float) ((mt * 0.3 + h(i, 93)) % 1.0);
                    float x = h(i, 94) * W + 0.01f * W * (float) Math.sin(t + i), y = H * (1.0f - u);
                    line(COL_ALT, 0.40f * (1f - u), lw, x, y, x, y + 0.025f * H);
                }
                break;
            }
            default: { // 6 rail: an LED strip along the stage edge with ticks that glide
                float y = 0.985f * H;
                float a = 0.3f + 0.18f * s.kickEnv;
                line(COL_MAIN, a, lw * 1.5f, 0, y, W, y);
                for (int i = 0; i < 40; i++) {
                    float x = ((i + (float) (s.barPos * 4 % 1.0)) / 40f) * W;
                    line(COL_ALT, 0.25f, lw, x, y - 0.012f * H, x, y);
                }
            }
        }
    }
    // ---------------- accents ----------------

    /** which sections an accent plays in: intro and breakdown strip back to dust, an outro fades everything else out */
    private static boolean accentOn(int type, int kind) {
        switch (type) {
            case 0: return kind == Director.DROP || kind == Director.CHORUS;
            case 1: return kind == Director.DROP || kind == Director.CHORUS;
            case 2: return kind == Director.BUILD || kind == Director.DROP || kind == Director.CHORUS;
            case 3: return kind == Director.BUILD;
            case 4: return kind == Director.BUILD || kind == Director.DROP || kind == Director.CHORUS;
            default: return true;
        }
    }

    private void accent(int type, In s, float density) {
        float t = s.t, mt = s.mt;
        if (!accentOn(type, s.kind)) return;
        switch (type) {
            case 0: { // pyro-style fountains at drops (and a little at chorus peaks)
                float since = t - s.dropT;
                float strength = since >= 0 && since < 3.2f ? (float) Math.exp(-since / 1.4f) : 0f;
                if (s.kind == Director.CHORUS) strength = Math.max(strength, 0.18f * s.ener);
                int n = (int) (44 * strength);
                for (int side = 0; side < 2; side++) {
                    float x0 = side == 0 ? 0.07f * W : 0.93f * W;
                    for (int i = 0; i < n; i++) {
                        float life = (float) ((mt * 0.9 + h(i, side + 10) * 2.2) % 2.2) / 2.2f;
                        float vx = (h(i, 20 + side) - 0.5f) * 0.28f * W * (side == 0 ? 1.2f : -1.2f) + (side == 0 ? 0.1f : -0.1f) * W;
                        float vy = H * (0.75f + 0.5f * h(i, 30));
                        float x = x0 + vx * life, y = H - (vy * life - 0.5f * H * 0.95f * life * life);
                        // a crisp trail (about 6% of a second of flight), warm white at the head, alt colour behind
                        float tx = x - vx * 0.06f, ty = y + (vy - H * 0.95f * life) * 0.06f;
                        line(COL_WHITE, 0.55f * (1f - life), lw * 1.3f, x, y, (x + tx) / 2, (y + ty) / 2);
                        line(COL_ALT, 0.5f * (1f - life), lw, (x + tx) / 2, (y + ty) / 2, tx, ty);
                    }
                }
                break;
            }
            case 1: { // confetti at chorus peaks: small, low saturation
                if (s.kind != Director.CHORUS && s.kind != Director.DROP) break;
                int n = (int) (36 * Math.min(1f, density));
                for (int i = 0; i < n; i++) {
                    float x = (h(i, 40) + 0.03f * (float) Math.sin(mt * 0.8 + i)) * W;
                    float y = (float) ((mt * (0.12 + 0.1 * h(i, 41)) + h(i, 42)) % 1.1) * H - 0.05f * H;
                    dot(COL_WHITE, 0.35f, x, y, 0.0045f * H);
                }
                break;
            }
            case 2: { // sparks on the snare
                float since = t - s.lastSnareT;
                if (since < 0 || since > 0.4f) break;
                boolean left = ((int) (s.lastSnareT * 10)) % 2 == 0;
                float cx = (left ? 0.3f : 0.7f) * W, cy = 0.78f * H;
                float u = since / 0.4f;
                for (int i = 0; i < 12; i++) {
                    double ang = Math.PI * (0.15 + 0.7 * h(i, 50));
                    float r1 = (0.02f + 0.1f * u) * H * (0.6f + h(i, 51)), r0 = r1 * 0.55f;
                    line(COL_WHITE, 0.5f * (1f - u), lw, cx + (float) Math.cos(ang) * r0, cy - (float) Math.sin(ang) * r0, cx + (float) Math.cos(ang) * r1, cy - (float) Math.sin(ang) * r1);
                }
                break;
            }
            case 3: { // riser lines through a build: faster and tighter as it goes
                if (s.kind != Director.BUILD) break;
                int n = 8 + (int) (12 * s.kindProgress);
                float speed = 0.12f + 0.6f * s.kindProgress * s.kindProgress;
                for (int i = 0; i < n; i++) {
                    float u = (float) (((i / (double) n) + mt * speed) % 1.0);
                    float y = H * (1f - u);
                    line(COL_MAIN, 0.14f + 0.2f * s.kindProgress, lw, 0.1f * W, y, 0.9f * W, y);
                }
                break;
            }
            case 4: { // ring shockwaves on kicks
                float since = t - s.lastKickT, since2 = t - s.prevKickT;
                for (int k = 0; k < 2; k++) {
                    float d = k == 0 ? since : since2;
                    if (d < 0 || d > 1.1f) continue;
                    float r = d * 0.9f * H;
                    ring(COL_ALT, 0.38f * (1f - d / 1.1f), lw, 0.5f * W, 0.75f * H, r * 1.6f, r * 0.6f);
                }
                break;
            }
            default: { // 5 dust motes drifting slowly
                for (int i = 0; i < 40; i++) {
                    float x = (float) ((h(i, 60) + mt * 0.008 * (0.5 + h(i, 61))) % 1.0) * W;
                    float y = (h(i, 62) + 0.02f * (float) Math.sin(mt * 0.3 + i)) * H;
                    dot(COL_WHITE, 0.28f, x, y, 0.0025f * H);
                }
            }
        }
    }

    // ---------------- camera ----------------

    /** out: scale, translate x, translate y (px), rotation (degrees). Drift stays under 3 degrees a second; the kick punch is 3-5% and smooth. */
    static void camera(Look look, In s, int w, int hgt, float[] o) {
        float punch = s.punch * Math.max(0f, Math.min(1f, s.kickEnv));
        o[0] = 1f;
        o[1] = o[2] = o[3] = 0f;
        switch (look.cam) {
            case 0: o[0] = 1f + 0.07f * Math.min(1f, s.lookAge / 60f); break;                         // slow push-in
            case 1: o[1] = w * 0.012f * (float) Math.sin(s.mt * 0.11); o[3] = 1.0f * (float) Math.sin(s.mt * 0.07); o[0] = 1.03f; break; // parallax drift
            case 2: o[2] = hgt * 0.015f * (float) Math.sin(s.mt * 0.09); o[0] = 1.03f; break;          // tilt
            default: break;                                                                              // wide
        }
        o[0] += punch;
    }
}
