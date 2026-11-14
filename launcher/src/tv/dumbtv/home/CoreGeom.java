package tv.dumbtv.home;

import java.util.ArrayList;

/**
 * The centrepiece of the waveform (blob) family when "Show people" is off: crisp rings, ticks and rotating polygons
 * with a diamond at the centre. The rings and the diamond follow the kick, the tick ring follows the vocal band
 * (mid), the outer ring follows the bass. No face, no profile, no body: only radially symmetric geometry, and
 * every stroke is thin and hard-edged. Pure Java so the offline preview draws the same shapes as the TV.
 */
final class CoreGeom {
    private final ArrayList<RigGeom.Prim> pool = new ArrayList<>();
    final ArrayList<RigGeom.Prim> out = new ArrayList<>();
    private int used;

    private RigGeom.Prim next(int kind, int col, float a, float w) {
        RigGeom.Prim p;
        if (used < pool.size()) p = pool.get(used);
        else {
            p = new RigGeom.Prim();
            pool.add(p);
        }
        used++;
        p.kind = kind;
        p.col = col;
        p.a = Math.min(RigGeom.MAX_ALPHA, a);
        p.w = w;
        p.n = 0;
        out.add(p);
        return p;
    }

    private void line(int col, float a, float w, float x1, float y1, float x2, float y2) {
        RigGeom.Prim p = next(RigGeom.LINE, col, a, w);
        p.x1 = x1;
        p.y1 = y1;
        p.x2 = x2;
        p.y2 = y2;
    }

    private void ring(int col, float a, float w, float cx, float cy, float r) {
        RigGeom.Prim p = next(RigGeom.ELLIPSE, col, a, w);
        p.x1 = cx;
        p.y1 = cy;
        p.x2 = r;
        p.y2 = r;
    }

    private void polygon(int col, float a, float w, float cx, float cy, float r, int n, double rot) {
        for (int i = 0; i < n; i++) {
            double a0 = rot + 2 * Math.PI * i / n, a1 = rot + 2 * Math.PI * (i + 1) / n;
            line(col, a, w, cx + (float) Math.cos(a0) * r, cy + (float) Math.sin(a0) * r, cx + (float) Math.cos(a1) * r, cy + (float) Math.sin(a1) * r);
        }
    }

    /**
     * @param t seconds, kick/vocal/bass 0..1 smooth envelopes, R the radius of the free space inside the spectrum bars
     */
    ArrayList<RigGeom.Prim> frame(float cx, float cy, float R, float t, float kick, float vocal, float bass) {
        out.clear();
        used = 0;
        float w = Math.max(2f, R * 0.012f);
        kick = Math.max(0f, Math.min(1f, kick));
        vocal = Math.max(0f, Math.min(1f, vocal));
        bass = Math.max(0f, Math.min(1f, bass));
        // outer ring: bass breathes it, 25% brighter on a kick (smooth: the envelopes are)
        ring(RigGeom.COL_ALT, 0.38f + 0.10f * kick, w, cx, cy, R * (0.93f + 0.04f * bass));
        // tick ring: lengths follow the vocal band, slowly turning
        int ticks = 48;
        for (int i = 0; i < ticks; i++) {
            double a = t * 0.12 + 2 * Math.PI * i / ticks;
            float lv = 0.5f + 0.5f * (float) Math.sin(i * 0.9 + t * 1.3);
            float len = R * (0.03f + 0.10f * vocal * (0.4f + 0.6f * lv));
            float r0 = R * 0.80f;
            line(RigGeom.COL_MAIN, 0.40f, w * 0.8f, cx + (float) Math.cos(a) * r0, cy + (float) Math.sin(a) * r0,
                cx + (float) Math.cos(a) * (r0 + len), cy + (float) Math.sin(a) * (r0 + len));
        }
        // main ring: the kick pushes it out
        ring(RigGeom.COL_MAIN, 0.50f, w * 1.3f, cx, cy, R * (0.62f + 0.09f * kick));
        // three rotating polygons, opposite directions
        polygon(RigGeom.COL_ALT, 0.42f, w, cx, cy, R * (0.52f + 0.05f * kick), 6, t * 0.30);
        polygon(RigGeom.COL_MAIN, 0.42f, w, cx, cy, R * (0.36f + 0.06f * vocal), 3, -t * 0.45);
        polygon(RigGeom.COL_WHITE, 0.30f, w * 0.8f, cx, cy, R * 0.22f, 4, t * 0.7);
        // centre diamond: size from the vocal band, a little bigger on the kick
        float d = R * (0.07f + 0.12f * vocal + 0.03f * kick);
        RigGeom.Prim p = next(RigGeom.POLY, RigGeom.COL_MAIN, 0.50f, 0f);
        p.n = 4;
        p.poly[0] = cx;
        p.poly[1] = cy - d;
        p.poly[2] = cx + d;
        p.poly[3] = cy;
        p.poly[4] = cx;
        p.poly[5] = cy + d;
        p.poly[6] = cx - d;
        p.poly[7] = cy;
        return out;
    }
}
