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
    /** a line or ring whose intended alpha is below this is skipped, never drawn faint; the ones kept are drawn solid in [LINE_LO, LINE_HI] */
    static final float LINE_MIN_PEAK = 0.45f, LINE_LO = 0.6f, LINE_HI = 0.7f;
    static final float BEAM_MAX_S = 12f; // a beam episode ends after this many seconds unless the song entry prefers beams

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
        float beamAge;         // seconds since this beam episode started (0 when no beams are showing)
        boolean beamsFree;     // the song entry lists beams as a preferred family: no time cap
    }

    private final ArrayList<Prim> pool = new ArrayList<>();
    private int used;
    final ArrayList<Prim> out = new ArrayList<>();
    private int W, H;
    private float lw;
    private In in;
    private float swell, fadeA = 1f;

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
        if (kind == LINE || kind == ELLIPSE) { // crisp strokes: solid alpha (only the look crossfade changes it), the beat pulses the width
            p.a = Math.min(LINE_HI, Math.max(LINE_LO, a)) * fadeA;
            p.w = Math.min(w * (1f + 0.4f * Math.max(0f, Math.min(1f, in.kickEnv))), 0.004f * H);
        }
        p.n = 0;
        out.add(p);
        return p;
    }

    private void line(int col, float a, float w, float x1, float y1, float x2, float y2) {
        if (a < LINE_MIN_PEAK) return;
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
        if (a < LINE_MIN_PEAK) return;
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
        fadeA = alpha;
        swell = alpha * (0.75f + 0.25f * Math.max(0f, Math.min(1f, s.kickEnv)));
        float beams = beamLevel(s);
        if (look.beam != 0 && beams > 0.02f) beam(look.beam, look.pattern, s, beams);
        fg(look.fg, s, density);
        accent(look.accent, s, density);
        if (s.ringKick) { // kick style ring: a wide low shockwave leaves the floor on every kick
            float d = (s.t - s.lastKickT) / 0.9f;
            if (d >= 0f && d < 0.75f) ring(COL_ALT, 0.62f, lw * 1.5f, 0.5f * W, 0.62f * H, W * (0.08f + 0.5f * d), H * (0.04f + 0.26f * d));
        }
        return out;
    }

    /**
     * How much of the beam layer shows: only in a drop or chorus, never in a quiet song, growing out from the sources over
     * the first 0.8 s and (unless the song entry prefers beams) retracting after BEAM_MAX_S. The level is the beams' reach,
     * never their alpha: a beam is either a crisp line or absent.
     */
    static float beamLevel(In s) {
        if (s.kind != Director.DROP && s.kind != Director.CHORUS) return 0f;
        float quiet = Math.max(0f, Math.min(1f, (s.ener - 0.25f) / 0.15f));
        float grow = Math.max(0f, Math.min(1f, s.beamAge / 0.8f));
        float cap = s.beamsFree ? 1f : Math.max(0f, Math.min(1f, (BEAM_MAX_S + 1f - s.beamAge) / 1f));
        return quiet * grow * cap;
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
        final float A = 0.64f; // beam strokes are crisp: solid, never faint; lvl is their reach
        float spread = 1f;
        float L = 1.5f * H * lvl;
        double bar = s.barPos;
        switch (type) {
            case 1: { // fan lasers: left cluster answers the kick, right cluster the snare
                int n = new int[] {5, 7, 6}[pat];
                for (int side = 0; side < 2; side++) {
                    float ex = pat == 1 ? 0.5f * W : (side == 0 ? 0.1f : 0.9f) * W;
                    if (pat == 1 && side == 1) break;
                    float ey = pat == 2 ? 1.02f * H : -0.02f * H;
                    float dir = pat == 2 ? -1f : 1f;
                    double sweep = Math.sin(2 * Math.PI * bar / (pat == 0 ? 4 : 2) + side * Math.PI) * 0.26 * spread;
                    for (int i = 0; i < n; i++) {
                        double u = n == 1 ? 0 : (i / (double) (n - 1)) * 2 - 1;
                        double ang = Math.PI / 2 + u * 0.62 * spread + sweep + (side == 0 ? 0.14 : -0.14) * (pat == 1 ? 0 : 1);
                        line(i % 2 == 0 ? COL_MAIN : COL_ALT, A, lw, ex, ey, ex + (float) Math.cos(ang) * L, ey + dir * (float) Math.sin(ang) * L);
                    }
                }
                break;
            }
            case 2: { // moving-head cones: two hard edges each
                int n = new int[] {3, 4, 3}[pat];
                for (int i = 0; i < n; i++) {
                    float ex = (i + 0.5f) / n * W;
                    double ph = i * 0.9 + (i % 2) * Math.PI;
                    double ang = Math.PI / 2 + Math.sin(2 * Math.PI * bar / (pat == 2 ? 8 : 4) + ph) * 0.5 * spread;
                    float half = 0.035f * (pat == 2 ? 2f : 1f);
                    int c = i % 2 == 0 ? COL_MAIN : COL_ALT;
                    line(c, A, lw, ex, 0, ex + (float) Math.cos(ang - half) * L, (float) Math.sin(ang - half) * L);
                    line(c, A, lw, ex, 0, ex + (float) Math.cos(ang + half) * L, (float) Math.sin(ang + half) * L);
                }
                break;
            }
            case 3: { // pillars rising from the stage floor: solid bars with bright edges
                int n = new int[] {5, 7, 4}[pat];
                for (int i = 0; i < n; i++) {
                    float x = (i + 0.5f) / n * W;
                    float breathe = 0.5f + 0.5f * (float) Math.sin(2 * Math.PI * bar / 2 + i * 0.8);
                    float hgt = H * (0.32f + 0.38f * (0.4f * breathe + 0.6f * Math.min(1f, s.bass * 1.6f)) * spread) * lvl;
                    float hw = 0.0045f * W;
                    Prim p = poly(i % 2 == 0 ? COL_MAIN : COL_ALT, MAX_ALPHA, 4);
                    p.poly[0] = x - hw; p.poly[1] = H; p.poly[2] = x + hw; p.poly[3] = H; p.poly[4] = x + hw; p.poly[5] = H - hgt; p.poly[6] = x - hw; p.poly[7] = H - hgt;
                    line(COL_WHITE, A, lw, x - hw, H, x - hw, H - hgt);
                    line(COL_WHITE, A, lw, x + hw, H, x + hw, H - hgt);
                }
                break;
            }
            case 4: { // criss-cross lattice between the top and bottom rigs
                int n = new int[] {4, 5, 6}[pat];
                float slide = (float) Math.sin(2 * Math.PI * bar / 4) * 0.22f * W * spread;
                for (int i = 0; i < n; i++) {
                    float x = (i + 0.5f) / n * W;
                    line(COL_MAIN, A, lw, x, 0, x + slide * lvl, H * lvl);
                    line(COL_ALT, A, lw, x, H, x - slide * lvl, H - H * lvl);
                }
                break;
            }
            case 5: { // ring lasers: ellipses opening from the stage on the beat
                int n = new int[] {3, 4, 5}[pat];
                float cx = 0.5f * W, cy = (pat == 1 ? 0.5f : 0.62f) * H;
                for (int k = 0; k < n; k++) {
                    float u = (k + s.beatPhase) / n;
                    if (u > 0.9f) continue;
                    float rx = u * 0.62f * W * (0.7f + 0.3f * spread) * lvl, ry = rx * 0.34f;
                    ring(k % 2 == 0 ? COL_MAIN : COL_ALT, A, lw, cx, cy, rx, ry);
                }
                break;
            }
            case 6: { // wipers: two long beams from the bottom corners, opposite phase
                for (int side = 0; side < 2; side++) {
                    float ex = side == 0 ? 0f : W;
                    double ang = Math.toRadians(55 + 38 * Math.sin(2 * Math.PI * bar / (pat == 0 ? 4 : 8) + side * Math.PI) * spread);
                    float dx = (float) Math.cos(ang) * (side == 0 ? 1 : -1), dy = -(float) Math.sin(ang);
                    line(side == 0 ? COL_MAIN : COL_ALT, A, lw, ex, H, ex + dx * L, H + dy * L);
                }
                break;
            }
            case 7: { // crossed fans from both bottom corners
                int n = new int[] {4, 5, 6}[pat];
                for (int side = 0; side < 2; side++) {
                    float ex = side == 0 ? 0.04f * W : 0.96f * W;
                    for (int i = 0; i < n; i++) {
                        double u = (i / (double) (n - 1)) * 2 - 1;
                        double ang = Math.toRadians(62 + u * 30 * spread + 10 * Math.sin(2 * Math.PI * bar / 8 + side * Math.PI));
                        float dir = side == 0 ? 1 : -1;
                        line(side == 0 ? COL_MAIN : COL_ALT, A, lw, ex, H, ex + dir * (float) Math.cos(ang) * L, H - (float) Math.sin(ang) * L);
                    }
                }
                break;
            }
            default: { // 8 curtain: a few slanted parallel lines drifting across
                int n = new int[] {6, 8, 10}[pat];
                float slant = 0.35f * H;
                for (int i = 0; i < n; i++) {
                    float u = (float) (((i / (double) n) + bar * (pat == 0 ? 0.25 : 0.5)) % 1.0);
                    float x = u * 1.5f * W - 0.25f * W;
                    line(i % 2 == 0 ? COL_MAIN : COL_ALT, A, lw, x, 0, x - slant * lvl, H * lvl);
                }
            }
        }
    }

    // ---------------- foreground: particles only (no stage-light strips, no faint lines), nothing human ----------------

    private void fg(int type, In s, float density) {
        float t = s.t, mt = s.mt;
        switch (type) {
            case 0: { // rise: four particle fountains climbing from the floor, higher on the bass
                int n = 8 + (int) (10 * density);
                for (int e = 0; e < 4; e++) {
                    float x0 = (0.2f + 0.2f * e) * W;
                    for (int i = 0; i < n; i++) {
                        float u = (float) ((mt * 0.45 + h(i, 80 + e) * 1.0) % 1.0);
                        float rise = H * (0.28f + 0.30f * Math.min(1f, s.bass * 1.6f)) * u;
                        float x = x0 + (h(i, 84 + e) - 0.5f) * 0.07f * W * u + 0.006f * W * (float) Math.sin(mt * 2 + i);
                        dot(e % 2 == 0 ? COL_WHITE : COL_ALT, 0.5f * (1f - u), x, H - rise, 0.0032f * H);
                    }
                }
                break;
            }
            case 1: { // motes: near drifting dust (bigger, slower) and a few sparks floating upward
                for (int i = 0; i < 26; i++) {
                    float depth = 0.4f + 0.6f * h(i, 90);
                    float x = (float) ((h(i, 91) + mt * 0.010 * depth) % 1.0) * W;
                    float y = (float) ((h(i, 92) + 0.02f * Math.sin(mt * 0.4 + i) - mt * 0.006 * depth + 2.0) % 1.0) * H;
                    dot(COL_WHITE, 0.18f + 0.2f * depth, x, y, (0.0025f + 0.003f * depth) * H);
                }
                for (int i = 0; i < 10; i++) {
                    float u = (float) ((mt * 0.3 + h(i, 93)) % 1.0);
                    dot(COL_ALT, 0.40f * (1f - u), h(i, 94) * W + 0.01f * W * (float) Math.sin(t + i), H * (1.0f - u), 0.003f * H);
                }
                break;
            }
            default: // none: the backdrop scene carries the look
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
                        if (life < 0.55f) { // a solid trail while the spark is fast, then just its head
                            line(COL_WHITE, 0.65f, lw * 1.3f, x, y, (x + tx) / 2, (y + ty) / 2);
                            line(COL_ALT, 0.65f, lw, (x + tx) / 2, (y + ty) / 2, tx, ty);
                        }
                        dot(COL_WHITE, 0.55f * (1f - life), x, y, 0.003f * H);
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
                if (u > 0.7f) break; // solid short rays, then gone: no fading strokes
                for (int i = 0; i < 12; i++) {
                    double ang = Math.PI * (0.15 + 0.7 * h(i, 50));
                    float r1 = (0.02f + 0.1f * u) * H * (0.6f + h(i, 51)), r0 = r1 * 0.55f;
                    line(COL_WHITE, 0.65f, lw, cx + (float) Math.cos(ang) * r0, cy - (float) Math.sin(ang) * r0, cx + (float) Math.cos(ang) * r1, cy - (float) Math.sin(ang) * r1);
                }
                break;
            }
            case 3: { // risers through a build: points climbing, faster and more of them as it goes
                if (s.kind != Director.BUILD) break;
                int n = 10 + (int) (24 * s.kindProgress);
                float speed = 0.12f + 0.6f * s.kindProgress * s.kindProgress;
                for (int i = 0; i < n; i++) {
                    float u = (float) (((h(i, 95) * 0.5) + mt * speed * (0.6 + 0.8 * h(i, 96))) % 1.0);
                    dot(COL_WHITE, 0.55f, (0.08f + 0.84f * h(i, 97)) * W, H * (1f - u), 0.0035f * H);
                }
                break;
            }
            case 4: { // ring shockwaves on kicks
                float since = t - s.lastKickT, since2 = t - s.prevKickT;
                for (int k = 0; k < 2; k++) {
                    float d = k == 0 ? since : since2;
                    if (d < 0 || d > 0.8f) continue; // a solid ring that leaves, not a fading one
                    float r = d * 0.9f * H;
                    ring(COL_ALT, 0.62f, lw, 0.5f * W, 0.75f * H, r * 1.6f, r * 0.6f);
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
