package tv.dumbtv.home;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;

import java.util.Random;

/**
 * An aerial view of a dark ground plane, tilted about 55 degrees, with a big disk of thin radial
 * spokes. Every spoke carries a row of quantized light cells; a pattern, a function of radius,
 * angle, time and the music, decides how bright each cell is. The camera orbits and rises slowly.
 * Drawn with a handful of point batches, so it costs a few draw calls whatever the cell count.
 */
final class LedDisk {
    private static final String TAG = "dumbtvdisk";
    static final String[] PATTERNS = {"fft ring", "starburst", "spiral arms", "shock rings", "wedges", "chevrons",
        "breathing core", "rose", "ripples"};
    private static final int NC = 48, BUCKETS = 9;
    private static final int[][] PAL = { // inner, outer: indigo/cyan, magenta/gold, teal/green, amber/red, white/ice
        {0xFF5B5BE8, 0xFF35D6FF}, {0xFFE0489A, 0xFFF2C04A}, {0xFF1FB5A8, 0xFF7BE060}, {0xFFF2A03A, 0xFFE0483A}, {0xFFE8F0FF, 0xFF9FD4FF}};
    private static int lastPal = -1;

    boolean on;
    float alpha;

    private int nsp = 288;
    private final float[] cs = new float[360], sn = new float[360], ang = new float[360];
    private final float[] rc = new float[NC];
    private final float[][] pts = new float[BUCKETS][2 * 360 * NC + 16];
    private final int[] cnt = new int[BUCKETS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rng = new Random(3);
    private final float[] bins = new float[32];
    private final float[] ringAt = {-1f, -1f, -1f, -1f}; // shock ring ages
    private int ringN;
    private final int[] plan = new int[5];
    private int planN, planAt, pal, ground, step = 2, autoStep = 2;
    private float since, time, rot, rise, bass, mid, high, kick, phase, fpsT, fps;
    private int frames;
    private final float[] fx = new float[10], fy = new float[10], fp = new float[10], fq = new float[10];

    LedDisk() {
        for (int i = 0; i < NC; i++) rc[i] = 0.07f + 0.93f * (i + 0.5f) / NC;
    }

    /** A new run for this song: 4-5 patterns by seed and mood, a palette that differs from the last song's. */
    void begin(int seed, boolean hard) {
        rng.setSeed(seed * 1013L + 41L);
        nsp = 240 + 12 * rng.nextInt(11); // 240..360 spokes
        for (int i = 0; i < nsp; i++) {
            ang[i] = i * 6.2831855f / nsp;
            cs[i] = (float) Math.cos(ang[i]);
            sn[i] = (float) Math.sin(ang[i]);
        }
        int[] hardSet = {1, 3, 4, 5, 6, 0}, calmSet = {2, 7, 8, 0, 6, 1};
        int[] pool = hard ? hardSet : calmSet;
        planN = 4 + rng.nextInt(2);
        for (int i = 0; i < planN; i++) {
            int p, guard = 0;
            do {
                p = rng.nextFloat() < 0.8f ? pool[rng.nextInt(pool.length)] : rng.nextInt(PATTERNS.length);
            } while (i > 0 && p == plan[i - 1] && ++guard < 10);
            plan[i] = p;
        }
        planAt = 0;
        do {
            pal = rng.nextInt(PAL.length);
        } while (pal == lastPal);
        lastPal = pal;
        ground = rng.nextInt(4); // lawn, plaza, rooftop, beach
        since = 0f;
        for (int i = 0; i < 10; i++) {
            fx[i] = rng.nextFloat() * 2f - 1f;
            fy[i] = 0.5f + rng.nextFloat() * 0.5f;
            fq[i] = rng.nextFloat() * 6.28f;
        }
        on = true;
        Log.i(TAG, "begin spokes=" + nsp + " palette=" + pal + " ground=" + ground + " plan=" + plan[0] + "," + plan[1] + "," + plan[2]);
    }

    private void next(String why) {
        planAt = (planAt + 1) % planN;
        since = 0f;
        Log.i(TAG, "pattern " + PATTERNS[plan[planAt]] + " (" + why + ") fps=" + Math.round(fps));
    }

    void update(float dt, boolean barEdge, boolean drop, boolean snare, boolean kickNow, Pulse p, float kickEnv, float beatPhase, int level) {
        time += dt;
        since += dt;
        rot += dt * 0.06f;
        rise = 0.5f + 0.5f * (float) Math.sin(time * 0.05f);
        bass += (p.bass() - bass) * Math.min(1f, dt * 8f);
        mid += (p.mid() - mid) * Math.min(1f, dt * 6f);
        high += (p.high() - high) * Math.min(1f, dt * 6f);
        kick = kickEnv;
        phase = beatPhase;
        for (int i = 0; i < 32; i++) bins[i] += (p.bin(i * 2) - bins[i]) * Math.min(1f, dt * 14f);
        if (snare) ringAt[ringN++ & 3] = 0f;
        for (int i = 0; i < 4; i++) if (ringAt[i] >= 0f) ringAt[i] = ringAt[i] + dt > 1.1f ? -1f : ringAt[i] + dt;
        step = Math.max(autoStep, level >= 3 ? 3 : level >= 2 ? 2 : 1); // fewer spokes before any feature goes
        // hard cuts on a downbeat or a drop, 8-16 s apart
        if (since > 8f && (barEdge || drop) || since > 16f) next(drop ? "drop" : barEdge ? "downbeat" : "time");
        frames++;
        fpsT += dt;
        if (fpsT >= 2f) {
            fps = frames / fpsT;
            if (fps < 40f) autoStep = Math.min(4, autoStep + 1); // fewer cells until it runs smoothly
            else if (fps > 56f && autoStep > 1) autoStep--;
            fpsT = 0f;
            frames = 0;
        }
    }

    private float value(int pat, float r, float x, float y, float a, int s) {
        switch (pat) {
            case 0: { // spectrum along the radius
                float b = bins[Math.min(31, (int) (r * 31f))];
                return Math.min(1f, b * 1.3f) * (1f - 0.35f * r);
            }
            case 1: { // a star that swells on kicks
                float k = 5f + (planAt & 1) * 2f, edge = 0.3f + 0.22f * kick + 0.12f * (float) Math.cos(k * (a + time * 0.4f));
                float d = Math.abs(r - edge);
                return d < 0.05f ? 1f : r < edge ? 0.25f * (1f - r / edge) : 0f;
            }
            case 2: // spiral arms
                return 0.5f + 0.5f * (float) Math.sin(3f * a - 9f * r + time * 2.2f + bass * 2f);
            case 3: { // shock rings from snares
                float v = 0f;
                for (int i = 0; i < 4; i++) {
                    if (ringAt[i] < 0f) continue;
                    float d = (r - ringAt[i] * 1.1f) / 0.05f;
                    v = Math.max(v, (float) Math.exp(-d * d) * (1f - ringAt[i] * 0.7f));
                }
                return Math.max(v, 0.12f * (float) Math.exp(-r * 4f) + 0.2f * kick * (1f - r));
            }
            case 4: { // rotating wedges
                float w = (float) Math.sin(6f * (a + time * 0.5f));
                return w > 0.25f ? (0.35f + 0.65f * (1f - r)) : 0f;
            }
            case 5: { // chevrons marching outwards
                float k = 6f, q = (float) Math.abs(((a * k / 6.2831855f) % 1f + 1f) % 1f - 0.5f);
                float f = (r * 5f - q * 1.6f - time * 0.8f) % 1f;
                return f < 0f ? (f + 1f < 0.35f ? 1f : 0f) : f < 0.35f ? 1f : 0f;
            }
            case 6: { // breathing core
                float rad = 0.22f + 0.35f * bass + 0.1f * (float) Math.sin(time * 1.7f);
                return r < rad ? 0.4f + 0.6f * (1f - r / rad) : (r < rad + 0.05f ? 0.8f : 0f);
            }
            case 7: { // a 3/5/7/9-fold rose
                float k = 3f + 2f * (planAt % 4);
                float petal = Math.abs((float) Math.cos(k * 0.5f * (a + time * 0.2f)));
                return petal * 1.05f > r * 1.15f ? 0.35f + 0.65f * petal : 0f;
            }
            default: { // two sources, interference
                float d1 = (float) Math.hypot(x - 0.3f * Math.cos(time * 0.3f), y - 0.3f * Math.sin(time * 0.3f));
                float d2 = (float) Math.hypot(x + 0.3f * Math.cos(time * 0.3f), y + 0.3f * Math.sin(time * 0.3f));
                return 0.5f + 0.25f * ((float) Math.sin(16f * d1 - time * 3f) + (float) Math.sin(16f * d2 - time * 3f));
            }
        }
    }

    private int cap(int c) {
        return Variety.cap(c) & 0xFFFFFF;
    }

    void draw(Canvas c, float w, float h, boolean card, float cl, float ct, float cr, float cb) {
        float al = alpha;
        if (al <= 0.01f) return;
        paint.setStyle(Paint.Style.FILL);
        // the dark ground, darker towards the top (the far side)
        int[] gc = {0x0C140E, 0x12131A, 0x15110D, 0x1A1812};
        for (int i = 0; i < 6; i++) {
            int base = gc[ground], rr = Math.min(255, ((base >> 16) & 255) + i * 2), gg = Math.min(255, ((base >> 8) & 255) + i * 2), bb = Math.min(255, (base & 255) + i * 2);
            paint.setColor(((int) (0.97f * al * 255) << 24) | (rr << 16) | (gg << 8) | bb);
            c.drawRect(0, h * i / 6f, w, h * (i + 1) / 6f + 1f, paint);
        }
        float cx = w * 0.5f, cy = h * (0.54f - 0.03f * rise), R = h * (0.40f + 0.06f * rise);
        float tilt = 0.574f, co = (float) Math.cos(rot), si = (float) Math.sin(rot);
        // ground detail by kind
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, h / 700f));
        paint.setColor(((int) (0.06f * al * 255) << 24) | 0xFFFFFF);
        if (ground == 1) for (int i = -6; i <= 6; i++) c.drawLine(cx + i * R * 0.35f * 1.0f, cy - R * tilt * 1.6f, cx + i * R * 0.6f, cy + R * tilt * 1.7f, paint);
        if (ground == 2) c.drawRect(w * 0.06f, h * 0.1f, w * 0.94f, h * 0.96f, paint);
        if (ground == 3) {
            for (int i = 0; i < 4; i++) c.drawLine(0, h * (0.12f + 0.025f * i) + 3f * (float) Math.sin(time * 0.5f + i), w, h * (0.12f + 0.025f * i), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        int c0 = cap(PAL[pal][0]), c1 = cap(PAL[pal][1]);
        // soft ground glow around the disk, growing with the level
        for (int i = 0; i < 4; i++) {
            paint.setColor(((int) ((0.025f + 0.02f * bass) * al * 255) << 24) | c1);
            rect(c, cx, cy, R * (1.05f + 0.12f * i) * 1.0f, R * tilt * (1.05f + 0.12f * i));
        }
        // cells, bucketed by brightness (3) and radius band (3)
        int pat = plan[planAt];
        for (int b = 0; b < BUCKETS; b++) cnt[b] = 0;
        for (int s = 0; s < nsp; s += step) {
            float cxs = cs[s], sns = sn[s];
            for (int i = 0; i < NC; i++) {
                float r = rc[i], x = r * cxs, y = r * sns;
                float v = value(pat, r, x, y, ang[s], s);
                if (v < 0.18f) continue;
                int lvl = v > 0.75f ? 2 : v > 0.45f ? 1 : 0, band = r < 0.38f ? 0 : r < 0.7f ? 1 : 2, b = lvl * 3 + band;
                float rx = x * co - y * si, ry = x * si + y * co;
                float sc = 1f + 0.16f * ry; // near side a little bigger
                float px = cx + rx * R * sc, py = cy + ry * R * tilt * sc;
                if (card && px > cl && px < cr && py > ct && py < cb) continue;
                int k = cnt[b];
                if (k + 2 > pts[b].length) continue;
                pts[b][k] = px;
                pts[b][k + 1] = py;
                cnt[b] = k + 2;
            }
        }
        paint.setStrokeCap(Paint.Cap.SQUARE); // square cells draw as plain quads; round caps are tessellated one by one and far slower
        float cell = Math.max(2.4f, R * 6.28f / nsp * step * 0.55f);
        for (int lvl = 0; lvl < 3; lvl++) {
            for (int band = 0; band < 3; band++) {
                int b = lvl * 3 + band;
                if (cnt[b] == 0) continue;
                int col = mix(c0, c1, band / 2f);
                if (lvl == 2 && step == 1) { // soft bloom under the brightest cells, only when there is room
                    paint.setStrokeWidth(cell * 2.6f);
                    paint.setColor(((int) (0.1f * al * 255) << 24) | col);
                    c.drawPoints(pts[b], 0, cnt[b], paint);
                }
                paint.setStrokeWidth(cell * (lvl == 2 ? 1.2f : lvl == 1 ? 1f : 0.8f));
                paint.setColor(((int) ((lvl == 2 ? 0.8f : lvl == 1 ? 0.5f : 0.25f) * al * 255) << 24) | col);
                c.drawPoints(pts[b], 0, cnt[b], paint);
            }
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        // the small dark circle at the centre
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(((int) (0.95f * al * 255) << 24) | 0x05060A);
        rect(c, cx, cy, R * 0.068f, R * 0.068f * tilt);
        // sparse silhouettes for scale, and fireflies
        paint.setColor(((int) (0.6f * al * 255) << 24) | 0x040508);
        for (int i = 0; i < 6; i++) {
            float x = cx + (i % 2 == 0 ? -1f : 1f) * R * (1.08f + 0.12f * (i / 2)), y = cy + R * tilt * (0.4f - 0.35f * i / 6f);
            rect(c, x, y, h * 0.006f, h * 0.012f);
        }
        for (int i = 0; i < 10; i++) {
            float tw = 0.5f + 0.5f * (float) Math.sin(time * 1.4f + fq[i]);
            paint.setColor(((int) (0.5f * tw * al * 255) << 24) | 0xE8F0A0);
            c.drawCircle(w * (0.5f + 0.48f * fx[i] * (float) Math.cos(time * 0.05f + fq[i])), h * (0.55f + 0.4f * (fy[i] - 0.75f)) + 10f * (float) Math.sin(time * 0.3f + fq[i]), 2.2f, paint);
        }
    }

    private final android.graphics.RectF oval = new android.graphics.RectF();

    private void rect(Canvas c, float cx, float cy, float rx, float ry) {
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry);
        c.drawOval(oval, paint);
    }

    private static int mix(int p, int q, float t) {
        int r = (int) (((p >> 16) & 255) * (1f - t) + ((q >> 16) & 255) * t);
        int g = (int) (((p >> 8) & 255) * (1f - t) + ((q >> 8) & 255) * t);
        int b = (int) ((p & 255) * (1f - t) + (q & 255) * t);
        return (r << 16) | (g << 8) | b;
    }
}
