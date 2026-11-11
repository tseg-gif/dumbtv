package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * The home wallpapers, drawn procedurally at any resolution (no bitmaps) and redrawn about eight
 * times a second: nebula, Los Angeles sunset, synthwave, deep ocean and aurora.
 */
final class Aurora extends View {
    static final String[] IDS = {"nebula", "wp_la", "wp_synth", "wp_ocean", "wp_aurora", "wp_tokyo", "wp_dunes", "wp_peaks", "wp_space", "wp_la_blue", "wp_la_rose", "wp_la_night", "wp_la_teal"};
    static final String[] NAMES = {"Nebula", "Los Angeles sunset", "Synthwave", "Deep ocean", "Northern lights", "Tokyo neon rain", "Desert dusk", "Mountain lake", "Ringed planet", "LA blue hour", "LA rose haze", "LA night freeway", "LA teal and gold"};

    private static final int STARS = 64, BLD = 46;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final Path path = new Path();
    private final android.graphics.RectF rect = new android.graphics.RectF();
    private final float[] sx = new float[STARS], sy = new float[STARS], sa = new float[STARS];
    private final float[] bx = new float[BLD], bw = new float[BLD], bh = new float[BLD];
    private Shader base, g1, g2, g3, sun;
    private float w, h;
    private int theme;
    // Los Angeles palette: far hills, near hills, towers, towers 2, ground, windows, headlights, taillights, sun top, sun bottom
    private final int[] lp = new int[10];
    private float sunR, sunY;
    private boolean laStars, laBeams;

    Aurora(Context c) {
        super(c);
        setWillNotDraw(false);
        setFocusable(false);
        long s = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < STARS; i++) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            sx[i] = ((s >>> 40) & 0xFFFF) / 65535f;
            s = s * 6364136223846793005L + 1442695040888963407L;
            sy[i] = ((s >>> 40) & 0xFFFF) / 65535f * 0.6f;
            s = s * 6364136223846793005L + 1442695040888963407L;
            sa[i] = 0.25f + ((s >>> 40) & 0xFF) / 255f * 0.6f;
        }
        float x = -0.02f;
        for (int i = 0; i < BLD; i++) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            bw[i] = 0.014f + ((s >>> 40) & 0xFF) / 255f * 0.026f;
            s = s * 6364136223846793005L + 1442695040888963407L;
            float r = ((s >>> 40) & 0xFF) / 255f;
            bh[i] = 0.05f + r * r * 0.16f;
            bx[i] = x;
            x += bw[i] * 0.92f;
        }
    }

    static int themeOf(String bg) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(bg)) return i;
        return -1;
    }

    void setTheme(int t) {
        if (t < 0 || t == theme) return;
        theme = t;
        rebuild();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = nw;
        h = nh;
        rebuild();
    }

    private static Shader sky(float h, int[] cols, float[] at) {
        return new LinearGradient(0, 0, 0, h, cols, at, Shader.TileMode.CLAMP);
    }

    private static Shader glow(float r, int c) {
        return new RadialGradient(0, 0, r, c, c & 0x00FFFFFF, Shader.TileMode.CLAMP);
    }

    private void rebuild() {
        if (w <= 0) return;
        float r = Math.max(w, h) * 0.55f;
        switch (theme) {
            case 1: // Los Angeles at golden hour
            case 9:
            case 10:
            case 11:
            case 12:
                laPalette(theme);
                break;
            case 2: // synthwave
                base = sky(h, new int[] {0xFF0A0420, 0xFF2A0A55, 0xFF7A1B8C, 0xFF14062E},
                        new float[] {0f, 0.4f, 0.7f, 0.7001f});
                sun = new LinearGradient(0, 0, 0, 1, 0xFFFFE066, 0xFFFF2E93, Shader.TileMode.CLAMP);
                g1 = glow(r, 0x66FF2E93);
                g2 = glow(r, 0x5535D6FF);
                break;
            case 3: // deep ocean
                base = sky(h, new int[] {0xFF0B5C7A, 0xFF084465, 0xFF062A4A, 0xFF03101F},
                        new float[] {0f, 0.3f, 0.65f, 1f});
                g1 = glow(r, 0x4435E0D0);
                break;
            case 4: // northern lights
                base = sky(h, new int[] {0xFF02060F, 0xFF06142A, 0xFF0A1F2E, 0xFF04080E},
                        new float[] {0f, 0.5f, 0.85f, 1f});
                g1 = sky(h * 0.7f, new int[] {0x0034F5A0, 0x8834F5A0, 0x0034F5A0}, new float[] {0f, 0.5f, 1f});
                g2 = sky(h * 0.7f, new int[] {0x00A06BFF, 0x77A06BFF, 0x00A06BFF}, new float[] {0f, 0.5f, 1f});
                break;
            case 5: // neon city in the rain
                base = sky(h, new int[] {0xFF05030F, 0xFF150A2E, 0xFF2A0C3F, 0xFF0A0614}, new float[] {0f, 0.5f, 0.8f, 1f});
                g1 = glow(r, 0x55FF3D9A);
                g2 = glow(r, 0x4429D8FF);
                break;
            case 6: // desert dusk
                base = sky(h, new int[] {0xFF1A1038, 0xFF5B2A63, 0xFFD9604A, 0xFFF7A55C, 0xFF3A1A3A},
                        new float[] {0f, 0.3f, 0.55f, 0.68f, 0.69f});
                g1 = glow(r * 0.7f, 0x44FFB070);
                break;
            case 7: // mountain lake
                base = sky(h, new int[] {0xFF0E1B3D, 0xFF31507A, 0xFFE59A7A, 0xFF1B2A44}, new float[] {0f, 0.4f, 0.62f, 0.63f});
                g1 = glow(r * 0.6f, 0x33FFC9A0);
                break;
            case 8: // ringed planet
                base = sky(h, new int[] {0xFF020309, 0xFF0A0B24, 0xFF06060F}, new float[] {0f, 0.6f, 1f});
                sun = new LinearGradient(0, 0, 0, 1, 0xFFEAD2A8, 0xFF5A3A5E, Shader.TileMode.CLAMP);
                g1 = glow(r, 0x44A06BFF);
                g2 = glow(r, 0x332F6BFF);
                break;
            default:
                base = sky(h, new int[] {0xFF04050D, 0xFF0A1030, 0xFF150C33, 0xFF0A0719},
                        new float[] {0f, 0.45f, 0.8f, 1f});
                g1 = glow(r, 0x6622D3EE);
                g2 = glow(r, 0x66A855F7);
                g3 = glow(r * 0.9f, 0x552F6BFF);
        }
    }

    private void laPalette(int v) {
        int[] cols;
        float[] at = {0f, 0.25f, 0.48f, 0.63f, 0.745f, 0.75f};
        sunR = 0.15f;
        sunY = 0.58f;
        laStars = false;
        laBeams = false;
        int[] q;
        switch (v) {
            case 9: // blue hour: the sky has gone indigo, the city lights are on, a last strip of orange
                cols = new int[] {0xFF070B2B, 0xFF16257A, 0xFF3A55B8, 0xFFB46A9A, 0xFFFF9F6B, 0xFF0B0B26};
                q = new int[] {0xFF2A2F6E, 0xFF1B1C4A, 0xFF10123A, 0xFF171A48, 0xFF0B0B26, 0xDDFFD9A0, 0xFFFFF4D0, 0xFFFF3D5A, 0xFFFFD08A, 0xFFFF7A6A};
                sunR = 0.07f;
                sunY = 0.7f;
                laStars = true;
                break;
            case 10: // rose haze: soft pink and peach, pale low sun
                cols = new int[] {0xFF3B1B63, 0xFF8B3A94, 0xFFE8689A, 0xFFFFA58F, 0xFFFFD9A8, 0xFF3A1A48};
                q = new int[] {0xFFA04A8C, 0xFF74366F, 0xFF4A2058, 0xFF55265F, 0xFF3A1A48, 0x88FFE2B0, 0xFFFFF6DC, 0xFFFF6F8F, 0xFFFFF3C0, 0xFFFFB0A0};
                sunR = 0.19f;
                sunY = 0.56f;
                break;
            case 11: // night freeway: neon, searchlights, no sun
                cols = new int[] {0xFF03030F, 0xFF0D0A2E, 0xFF2A1250, 0xFF5A2570, 0xFF9A3C7A, 0xFF07040F};
                q = new int[] {0xFF1C1040, 0xFF120A2E, 0xFF0A0620, 0xFF100828, 0xFF07040F, 0xDDFFE0A0, 0xFFE0F6FF, 0xFFFF2E63, 0, 0};
                sunR = 0f;
                laStars = true;
                laBeams = true;
                break;
            case 12: // teal and gold: a cool Pacific sky with a warm sun
                cols = new int[] {0xFF0B2A4A, 0xFF1F5F86, 0xFF5BA8A0, 0xFFF2A65A, 0xFFFFD27A, 0xFF0E2A3A};
                q = new int[] {0xFF2E6E7E, 0xFF1D4A5E, 0xFF12303F, 0xFF183A4C, 0xFF0E2A3A, 0x99FFD27A, 0xFFFFF2C8, 0xFFFF5A3C, 0xFFFFF0B0, 0xFFFFA050};
                break;
            default: // golden hour: violet to rose to gold, teal-violet hills for depth
                cols = new int[] {0xFF22124F, 0xFF5A2A8A, 0xFFD0457E, 0xFFFF7F5A, 0xFFFFC078, 0xFF1C0C2C};
                q = new int[] {0xFF6B2F7F, 0xFF40204E, 0xFF2B1240, 0xFF35184A, 0xFF1C0C2C, 0x88FFD08A, 0xFFFFF0C0, 0xFFFF4D6A, 0xFFFFEFA0, 0xFFFF6F7A};
        }
        System.arraycopy(q, 0, lp, 0, 10);
        base = sky(h, cols, at);
        sun = new LinearGradient(0, 0, 0, 1, lp[8], lp[9], Shader.TileMode.CLAMP);
        g1 = glow(Math.max(w, h) * 0.44f, (lp[9] & 0x00FFFFFF) | 0x55000000);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (base == null) return;
        float t = SystemClock.uptimeMillis() / 1000f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(base);
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        switch (theme) {
            case 1: case 9: case 10: case 11: case 12: la(c, t); break;
            case 2: synth(c, t); break;
            case 3: ocean(c, t); break;
            case 4: lights(c, t); break;
            case 5: tokyo(c, t); break;
            case 6: dunes(c, t); break;
            case 7: peaks(c, t); break;
            case 8: space(c, t); break;
            default: nebula(c, t);
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        postInvalidateDelayed(125);
    }

    private void blob(Canvas c, Shader s, float x, float y) {
        m.setTranslate(x, y);
        s.setLocalMatrix(m);
        paint.setShader(s);
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }

    private void stars(Canvas c, float t, float maxY, int col) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < STARS; i++) {
            if (sy[i] * h > maxY) continue;
            float tw = 0.65f + 0.35f * (float) Math.sin(t * 0.6f + i * 1.7f);
            paint.setColor(((int) (sa[i] * tw * 150) << 24) | col);
            c.drawCircle(sx[i] * w, sy[i] * h, i % 9 == 0 ? 2.2f : 1.3f, paint);
        }
    }

    /** Stars drifting sideways at their own speeds: a cheap parallax. */
    private void drift(Canvas c, float t, float maxY, float speed, int rgb) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < STARS; i++) {
            float y = sy[i] / 0.6f * maxY, x = ((sx[i] * w + t * speed * (0.3f + sa[i])) % w + w) % w;
            float tw = 0.6f + 0.4f * (float) Math.sin(t * 0.5f + i * 2.3f);
            paint.setColor(((int) (sa[i] * tw * 130) << 24) | rgb);
            c.drawCircle(x, y, 0.9f + sa[i] * 1.6f, paint);
        }
    }

    /** Soft lights wandering slowly and pulsing, like fireflies or plankton. */
    private void motes(Canvas c, float t, float y0, float y1, int rgb, int n) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            float x = sx[i] * w + (float) Math.sin(t * 0.21f + i * 1.9f) * w * 0.03f;
            float y = y0 + (y1 - y0) * (sy[i] / 0.6f) + (float) Math.sin(t * 0.17f + i * 2.7f) * h * 0.02f;
            float a = Math.max(0f, (float) Math.sin(t * 0.45f + i * 1.3f));
            paint.setColor(((int) (a * 40) << 24) | rgb);
            c.drawCircle(x, y, h * 0.011f, paint);
            paint.setColor(((int) (a * 190) << 24) | rgb);
            c.drawCircle(x, y, h * 0.0034f, paint);
        }
    }

    /** A shooting star once in a while. */
    private void shooting(Canvas c, float t, float period, int rgb) {
        float ph = (t % period) / period;
        if (ph > 0.07f) return;
        float k = ph / 0.07f, x = w * (0.15f + 0.5f * ((int) (t / period) % 3) / 2f) + k * w * 0.18f, y = h * 0.08f + k * h * 0.14f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, h * 0.0025f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(((int) (150 * (1f - k)) << 24) | rgb);
        c.drawLine(x, y, x - w * 0.07f, y - h * 0.045f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void grid(Canvas c, float t, float hy, int rgb, int a) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, h / 1080f));
        for (int i = 0; i < 12; i++) {
            float f = (i + (t * 0.05f) % 1f) / 12f;
            float y = hy + (h - hy) * f * f;
            paint.setColor(((int) (a * f) << 24) | rgb);
            c.drawLine(0, y, w, y, paint);
        }
        paint.setColor(((a / 2) << 24) | rgb);
        for (int i = -10; i <= 10; i++) c.drawLine(w / 2f + i * w * 0.012f, hy, w / 2f + i * w * 0.12f, h, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void nebula(Canvas c, float t) {
        blob(c, g1, w * (0.22f + 0.05f * (float) Math.sin(t * 0.07f)), h * (0.28f + 0.04f * (float) Math.cos(t * 0.09f)));
        blob(c, g2, w * (0.80f + 0.05f * (float) Math.cos(t * 0.06f)), h * (0.62f + 0.05f * (float) Math.sin(t * 0.08f)));
        blob(c, g3, w * (0.50f + 0.08f * (float) Math.sin(t * 0.05f)), h * 1.02f);
        stars(c, t, h, 0xEAF4FF);
        drift(c, t, h, 6f, 0xBFD8FF);
        motes(c, t, h * 0.2f, h * 0.85f, 0x9FEFFF, 18);
        shooting(c, t, 23f, 0xEAF4FF);
        grid(c, t, h * 0.72f, 0x5EEAD4, 34);
    }

    private void synth(Canvas c, float t) {
        blob(c, g1, w * 0.5f, h * 0.58f);
        blob(c, g2, w * (0.15f + 0.03f * (float) Math.sin(t * 0.1f)), h * 0.2f);
        stars(c, t, h * 0.5f, 0xFFD6F5);
        float hy = h * 0.7f, r = h * 0.2f, cx = w * 0.5f, cy = hy - r * 0.55f;
        m.setScale(1f, 2f * r);
        m.postTranslate(0, cy - r);
        sun.setLocalMatrix(m);
        paint.setShader(sun);
        c.save();
        c.clipRect(0, 0, w, hy);
        c.drawCircle(cx, cy, r, paint);
        paint.setShader(null);
        paint.setColor(0xFF14062E); // retro stripes cut across the lower half of the sun
        for (int i = 0; i < 6; i++) {
            float y = cy + r * (0.05f + i * 0.17f), th = r * (0.02f + i * 0.018f);
            c.drawRect(cx - r, y, cx + r, y + th, paint);
        }
        c.restore();
        paint.setStyle(Paint.Style.FILL);
        for (int k = 0; k < 2; k++) { // wireframe mountains either side of the sun
            path.reset();
            path.moveTo(k == 0 ? 0 : w * 0.62f, hy);
            for (int i = 0; i <= 8; i++) {
                float f = i / 8f, x = k == 0 ? w * 0.38f * f : w * 0.62f + w * 0.38f * f;
                float amp = (k == 0 ? 1f - f : f) * h * (0.16f + 0.05f * (float) Math.sin(i * 2.1f + k)) * (0.6f + 0.4f * (i % 2));
                path.lineTo(x, hy - amp);
            }
            path.lineTo(k == 0 ? w * 0.38f : w, hy);
            path.close();
            paint.setColor(0xFF1B0840);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, h * 0.0028f));
            paint.setColor(0x99FF4FD8);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        paint.setColor(0xFF14062E);
        c.drawRect(0, hy, w, h, paint);
        paint.setColor(0x66FF4FD8);
        c.drawRect(0, hy - 1f, w, hy + Math.max(2f, h * 0.003f), paint);
        drift(c, t, hy * 0.7f, 4f, 0xFFD6F5);
        grid(c, t * 3f, hy, 0xFF4FD8, 120);
    }

    private void ocean(Canvas c, float t) {
        blob(c, g1, w * 0.5f, 0);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 5; i++) { // slow light shafts from the surface
            float x = w * (0.1f + 0.2f * i) + (float) Math.sin(t * 0.12f + i) * w * 0.04f;
            path.reset();
            path.moveTo(x - w * 0.02f, 0);
            path.lineTo(x + w * 0.02f, 0);
            path.lineTo(x + w * 0.12f, h);
            path.lineTo(x - w * 0.08f, h);
            path.close();
            paint.setColor(0x0F9FF4FF);
            c.drawPath(path, paint);
        }
        for (int i = 0; i < STARS; i++) { // bubbles rise
            float y = (1f - ((t * (0.012f + sa[i] * 0.02f) + sy[i] * 1.6f) % 1f)) * h;
            float x = sx[i] * w + (float) Math.sin(t * 0.7f + i) * w * 0.006f;
            paint.setColor(((int) (sa[i] * 90) << 24) | 0xCFF6FF);
            c.drawCircle(x, y, 2f + sa[i] * 5f, paint);
        }
        paint.setColor(0xFF020B16); // seabed
        path.reset();
        path.moveTo(0, h);
        for (int i = 0; i <= 12; i++) path.lineTo(w * i / 12f, h * (0.9f + 0.04f * (float) Math.sin(i * 1.3f)));
        path.lineTo(w, h);
        path.close();
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Math.max(3f, h * 0.006f));
        for (int i = 0; i < 11; i++) { // kelp swaying in the current
            float x = w * (0.04f + 0.092f * i), ht = h * (0.12f + 0.08f * ((i * 5) % 4) / 3f), sw = (float) Math.sin(t * 0.5f + i * 1.3f) * h * 0.02f;
            paint.setColor(i % 2 == 0 ? 0xFF0B4A48 : 0xFF0E5C50);
            path.reset();
            path.moveTo(x, h * 0.93f);
            path.quadTo(x + sw, h * 0.93f - ht * 0.5f, x - sw * 0.6f, h * 0.93f - ht);
            c.drawPath(path, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        for (int g = 0; g < 3; g++) { // schools of small fish
            float x0 = ((t * (14f + g * 6f) + g * w * 0.37f) % (w * 1.3f)) - w * 0.15f, y0 = h * (0.3f + 0.17f * g) + (float) Math.sin(t * 0.3f + g) * h * 0.03f;
            for (int i = 0; i < 9; i++) {
                float fx = x0 + (i % 3) * w * 0.016f - (i / 3) * w * 0.008f, fy = y0 + ((i * 7) % 5 - 2) * h * 0.012f;
                paint.setColor(g == 1 ? 0x66FFD27A : 0x6648E0D0);
                rect.set(fx, fy, fx + w * 0.012f, fy + h * 0.007f);
                c.drawOval(rect, paint);
                path.reset();
                path.moveTo(fx, fy + h * 0.0035f);
                path.lineTo(fx - w * 0.005f, fy);
                path.lineTo(fx - w * 0.005f, fy + h * 0.007f);
                path.close();
                c.drawPath(path, paint);
            }
        }
        for (int j = 0; j < 2; j++) { // jellyfish
            float cyc = (t * 0.012f + j * 0.5f) % 1f, x = w * (0.25f + 0.5f * j) + (float) Math.sin(t * 0.25f + j * 3f) * w * 0.03f, y = h * (1.05f - cyc * 1.2f);
            float pu = 1f + 0.12f * (float) Math.sin(t * 1.6f + j), r = h * 0.045f;
            int rgb = j == 0 ? 0xFF7AD8 : 0x9AB8FF;
            paint.setColor((rgb) | 0x0E000000);
            c.drawCircle(x, y, r * 1.7f, paint);
            paint.setColor((rgb) | 0x55000000);
            rect.set(x - r * pu, y - r * 0.8f, x + r * pu, y + r * 0.8f);
            c.drawArc(rect, 180f, 180f, true, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, h * 0.002f));
            for (int k = -2; k <= 2; k++) {
                path.reset();
                path.moveTo(x + k * r * 0.4f, y);
                path.quadTo(x + k * r * 0.4f + (float) Math.sin(t + k) * r * 0.3f, y + r * 1.2f, x + k * r * 0.5f, y + r * 2.2f);
                c.drawPath(path, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void lights(Canvas c, float t) {
        stars(c, t, h * 0.6f, 0xEAF4FF);
        shooting(c, t, 29f, 0xEAF4FF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, w / 520f));
        for (int i = 0; i < 70; i++) { // rays hanging from the ribbons
            float x = w * i / 69f, y0 = h * 0.2f + h * 0.05f * (float) Math.sin(i * 0.5f + t * 0.12f), len = h * (0.16f + 0.1f * (float) Math.sin(i * 0.9f + t * 0.2f));
            paint.setColor(i % 5 == 0 ? 0x1AA06BFF : 0x1434F5A0);
            c.drawLine(x, y0, x + (float) Math.sin(t * 0.1f + i) * w * 0.004f, y0 + len, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        for (int k = 0; k < 2; k++) {
            Shader s = k == 0 ? g1 : g2;
            path.reset();
            float top = h * (0.12f + 0.08f * k), amp = h * (0.06f + 0.03f * k);
            path.moveTo(0, top + h * 0.42f);
            for (int i = 0; i <= 24; i++) {
                float x = w * i / 24f;
                path.lineTo(x, top + amp * (float) Math.sin(i * 0.5f + t * (0.12f + 0.04f * k) + k * 2f)
                        + amp * 0.5f * (float) Math.sin(i * 1.3f - t * 0.1f));
            }
            path.lineTo(w, top + h * 0.42f);
            path.close();
            m.setTranslate(0, top - amp);
            s.setLocalMatrix(m);
            paint.setShader(s);
            c.drawPath(path, paint);
            paint.setShader(null);
        }
        paint.setColor(0xFF03060A); // a cabin under the pines, one window lit
        float cxx = w * 0.78f, cyy = h * 0.93f;
        path.reset();
        path.moveTo(cxx - w * 0.05f, cyy);
        path.lineTo(cxx - w * 0.05f, cyy - h * 0.06f);
        path.lineTo(cxx, cyy - h * 0.11f);
        path.lineTo(cxx + w * 0.05f, cyy - h * 0.06f);
        path.lineTo(cxx + w * 0.05f, cyy);
        path.close();
        c.drawPath(path, paint);
        paint.setColor(0x1EFFC27A);
        c.drawCircle(cxx, cyy - h * 0.034f, h * 0.03f, paint);
        paint.setColor(0xFFFFC27A);
        c.drawRect(cxx - w * 0.012f, cyy - h * 0.045f, cxx + w * 0.012f, cyy - h * 0.022f, paint);
        paint.setColor(0xFF03060A); // pines along the bottom
        path.reset();
        path.moveTo(0, h);
        for (int i = 0; i < 40; i++) {
            float x = w * i / 39f, ph = h * (0.07f + 0.05f * (float) Math.abs(Math.sin(i * 2.7f)));
            path.lineTo(x - w * 0.008f, h * 0.93f);
            path.lineTo(x, h * 0.93f - ph);
            path.lineTo(x + w * 0.008f, h * 0.93f);
        }
        path.lineTo(w, h);
        path.close();
        c.drawPath(path, paint);
    }

    private void ridgeLayer(float y0, float amp, float freq, float ph, int col, float tilt) {
        paint.setColor(col);
        path.reset();
        path.moveTo(0, h);
        for (int i = 0; i <= 28; i++) {
            float x = w * i / 28f;
            path.lineTo(x, y0 - amp * (0.55f + 0.45f * (float) Math.sin(i * freq + ph) + 0.25f * (float) Math.sin(i * freq * 2.3f + ph * 1.7f)) + tilt * x);
        }
        path.lineTo(w, h);
        path.close();
    }

    private void tokyo(Canvas c, float t) {
        blob(c, g1, w * 0.25f, h * 0.7f);
        blob(c, g2, w * 0.78f, h * 0.55f);
        float hy = h * 0.86f;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < BLD; i++) { // a far layer in the haze, with a few windows blinking
            float j = bh[(i * 7 + 3) % BLD], x = i * w / BLD, bhh = h * (0.12f + j * 1.9f);
            paint.setColor(0xFF1C1136);
            c.drawRect(x, hy - bhh, x + w / BLD * 1.1f, hy, paint);
            if ((i + (int) (t * 0.4f)) % 4 == 0) {
                paint.setColor(0x55FFD28A);
                c.drawRect(x + w / BLD * 0.3f, hy - bhh + h * 0.03f, x + w / BLD * 0.6f, hy - bhh + h * 0.036f, paint);
            }
        }
        for (int i = 0; i < BLD; i++) {
            float bhh = h * (0.18f + bh[i] * 2.4f), x = bx[i] * w, ww = bw[i] * w * 1.5f;
            paint.setColor(i % 2 == 0 ? 0xFF0B0716 : 0xFF110A22);
            c.drawRect(x, hy - bhh, x + ww, hy, paint);
            if (i % 3 == 0) { // a vertical neon strip that breathes
                int col = i % 9 == 0 ? 0xFFFF3D9A : i % 9 == 3 ? 0xFF29D8FF : 0xFFFFB347;
                float f = 0.55f + 0.35f * (float) Math.sin(t * 0.8f + i);
                paint.setColor(((int) (f * 200) << 24) | (col & 0xFFFFFF));
                c.drawRect(x + ww * 0.42f, hy - bhh * 0.9f, x + ww * 0.58f, hy - bhh * 0.35f, paint);
            }
            paint.setColor(0x44FFD28A);
            for (int k = 0; k < 4; k++) c.drawRect(x + ww * 0.12f, hy - bhh + h * 0.02f + k * h * 0.045f, x + ww * 0.3f, hy - bhh + h * 0.026f + k * h * 0.045f, paint);
        }
        paint.setColor(0xFF08040F); // wet street with a pink reflection
        c.drawRect(0, hy, w, h, paint);
        paint.setColor(0x22FF3D9A);
        c.drawRect(0, hy, w, hy + h * 0.02f, paint);
        for (int i = 0; i < BLD; i += 3) { // neon smeared on the wet street
            float bhh = h * (0.18f + bh[i] * 2.4f), x = bx[i] * w, ww = bw[i] * w * 1.5f;
            int col = i % 9 == 0 ? 0xFF3D9A : i % 9 == 3 ? 0x29D8FF : 0xFFB347;
            float f = 0.55f + 0.35f * (float) Math.sin(t * 0.8f + i);
            paint.setColor(((int) (f * 46) << 24) | col);
            c.drawRect(x + ww * 0.38f, hy, x + ww * 0.62f, hy + h * (0.04f + 0.1f * f), paint);
        }
        paint.setStyle(Paint.Style.STROKE); // raindrop rings on the street
        paint.setStrokeWidth(Math.max(1f, h / 1000f));
        for (int i = 0; i < 12; i++) {
            float ph = ((t * 0.7f + i * 0.37f) % 1f), rx = sx[i] * w, ry = hy + (h - hy) * (0.2f + 0.7f * sy[i] / 0.6f);
            paint.setColor(((int) ((1f - ph) * 60) << 24) | 0xBFD8FF);
            rect.set(rx - ph * w * 0.012f, ry - ph * h * 0.004f, rx + ph * w * 0.012f, ry + ph * h * 0.004f);
            c.drawOval(rect, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, h / 900f));
        for (int i = 0; i < STARS; i++) { // rain
            float y = ((t * (0.55f + sa[i] * 0.5f) + sy[i] * 1.7f) % 1f) * h;
            float x = sx[i] * w - y * 0.08f;
            paint.setColor(((int) (sa[i] * 70) << 24) | 0xBFD8FF);
            c.drawLine(x, y, x - h * 0.006f, y + h * 0.03f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void dunes(Canvas c, float t) {
        stars(c, t, h * 0.3f, 0xFFE6D2);
        blob(c, g1, w * 0.7f, h * 0.66f);
        paint.setStyle(Paint.Style.FILL);
        ridgeLayer(h * 0.78f, h * 0.05f, 0.5f, 0.3f, 0xFF7A3556, 0.01f);
        c.drawPath(path, paint);
        rim(c, 0x66FFB878);
        ridgeLayer(h * 0.86f, h * 0.07f, 0.42f, 2.1f, 0xFF4E2147, -0.02f);
        c.drawPath(path, paint);
        rim(c, 0x44FF9A78);
        ridgeLayer(h * 0.96f, h * 0.09f, 0.35f, 4.2f, 0xFF2B1130, 0.015f);
        c.drawPath(path, paint);
        rim(c, 0x33E87A8A);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 14; i++) { // windblown sand skimming the ridges
            float y = h * (0.76f + 0.2f * ((i * 5) % 7) / 6f), x = ((t * (40f + 9f * (i % 4)) + i * w * 0.21f) % (w * 1.2f)) - w * 0.1f;
            paint.setColor(0x16FFD2A0);
            c.drawRoundRect(x, y, x + w * 0.07f, y + 2f, 2f, 2f, paint);
        }
        drift(c, t, h * 0.3f, 2f, 0xFFE6D2);
        shooting(c, t, 31f, 0xFFE6D2);
        paint.setColor(0x14FFC890); // heat shimmer
        float y = h * 0.7f + 3f * (float) Math.sin(t * 0.7f);
        c.drawRect(0, y, w, y + 2f, paint);
    }

    private void rim(Canvas c, int col) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, h * 0.0025f));
        paint.setColor(col);
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void peaks(Canvas c, float t) {
        blob(c, g1, w * 0.5f, h * 0.6f);
        stars(c, t, h * 0.25f, 0xFFF0E0);
        paint.setStyle(Paint.Style.FILL);
        ridgeLayer(h * 0.5f, h * 0.2f, 0.62f, 0.4f, 0xFF4A5E86, 0f);
        c.drawPath(path, paint);
        c.save(); // snow on the high peaks, a warm edge from the low sun
        c.clipRect(0, 0, w, h * 0.36f);
        paint.setColor(0xAAEAF0FA);
        c.drawPath(path, paint);
        c.restore();
        ridgeLayer(h * 0.58f, h * 0.18f, 0.55f, 2.4f, 0xFF2F4268, 0f);
        c.drawPath(path, paint);
        ridgeLayer(h * 0.66f, h * 0.14f, 0.7f, 5.1f, 0xFF1B2A44, 0f);
        c.drawPath(path, paint);
        for (int i = 0; i < 3; i++) { // mist drifting between the ridges
            float x = ((t * (5f + 3f * i) + i * w * 0.35f) % (w * 1.5f)) - w * 0.25f;
            paint.setColor(0x16FFFFFF);
            c.drawRoundRect(x, h * (0.5f + 0.06f * i), x + w * 0.4f, h * (0.5f + 0.06f * i) + h * 0.02f, 16f, 16f, paint);
        }
        paint.setColor(0xFF132036); // the lake
        c.drawRect(0, h * 0.7f, w, h, paint);
        c.save(); // the ridges reflected, a little darker, with the ripples cutting across
        c.clipRect(0, h * 0.7f, w, h);
        c.translate(0, h * 1.4f);
        c.scale(1f, -1f);
        ridgeLayer(h * 0.66f, h * 0.14f, 0.7f, 5.1f, 0x661B2A44, 0f);
        c.drawPath(path, paint);
        ridgeLayer(h * 0.58f, h * 0.18f, 0.55f, 2.4f, 0x442F4268, 0f);
        c.drawPath(path, paint);
        c.restore();
        motes(c, t, h * 0.55f, h * 0.9f, 0xFFE6A0, 16);
        for (int i = 0; i < 14; i++) {
            float y = h * 0.71f + (i * i) * h * 0.0015f + 2f * (float) Math.sin(t * 0.6f + i);
            paint.setColor(0x14FFC9A0);
            c.drawRect(w * (0.2f + 0.03f * (i % 5)), y, w * (0.8f - 0.02f * (i % 4)), y + 2f, paint);
        }
    }

    private void space(Canvas c, float t) {
        blob(c, g1, w * (0.3f + 0.03f * (float) Math.sin(t * 0.05f)), h * 0.35f);
        blob(c, g2, w * 0.8f, h * 0.8f);
        stars(c, t, h, 0xEAF4FF);
        float cx = w * 0.74f, cy = h * 0.64f, r = h * 0.2f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(h * 0.012f);
        paint.setColor(0x66E8C9A0);
        rect.set(cx - r * 1.9f, cy - r * 0.38f, cx + r * 1.9f, cy + r * 0.38f);
        c.drawOval(rect, paint);
        paint.setStrokeWidth(h * 0.004f);
        paint.setColor(0x44E8C9A0);
        rect.set(cx - r * 2.15f, cy - r * 0.44f, cx + r * 2.15f, cy + r * 0.44f);
        c.drawOval(rect, paint);
        paint.setStyle(Paint.Style.FILL);
        m.setScale(1f, 2f * r);
        m.postTranslate(0, cy - r);
        sun.setLocalMatrix(m);
        paint.setShader(sun);
        c.drawCircle(cx, cy, r, paint);
        paint.setShader(null);
        paint.setColor(0x55060812); // night side
        c.drawCircle(cx + r * 0.28f, cy + r * 0.12f, r * 0.96f, paint);
        paint.setColor(0x22A07848); // cloud bands
        for (int i = 0; i < 5; i++) {
            float by = cy - r * 0.7f + i * r * 0.32f, half = (float) Math.sqrt(Math.max(0f, r * r - (by - cy) * (by - cy)));
            c.drawRect(cx - half, by, cx + half, by + r * 0.07f, paint);
        }
        paint.setStyle(Paint.Style.STROKE); // the near half of the rings passes in front
        paint.setStrokeWidth(h * 0.012f);
        paint.setColor(0x88E8C9A0);
        rect.set(cx - r * 1.9f, cy - r * 0.38f, cx + r * 1.9f, cy + r * 0.38f);
        c.drawArc(rect, 0f, 180f, false, paint);
        paint.setStrokeWidth(h * 0.004f);
        paint.setColor(0x55E8C9A0);
        rect.set(cx - r * 2.15f, cy - r * 0.44f, cx + r * 2.15f, cy + r * 0.44f);
        c.drawArc(rect, 0f, 180f, false, paint);
        paint.setStyle(Paint.Style.FILL);
        drift(c, t, h, 5f, 0xEAF4FF);
        shooting(c, t, 17f, 0xEAF4FF);
        paint.setColor(0xFFEAD2A8);
        c.drawCircle(w * 0.46f + 6f * (float) Math.sin(t * 0.04f), h * 0.1f, h * 0.01f, paint); // a small moon
    }

    /** Los Angeles: low sun, hills, downtown, and a freeway of headlights and taillights. Colours come from laPalette. */
    private void la(Canvas c, float t) {
        float hy = h * 0.745f;
        if (laStars) stars(c, t, hy * 0.7f, 0xFFFFE8D0);
        if (sunR > 0f) {
            blob(c, g1, w * 0.62f, h * (sunY + 0.08f));
            float r = h * sunR, cx = w * 0.62f, cy = h * sunY + 3f * (float) Math.sin(t * 0.05f);
            m.setScale(1f, 2f * r);
            m.postTranslate(0, cy - r);
            sun.setLocalMatrix(m);
            paint.setShader(sun);
            c.save();
            c.clipRect(0, 0, w, hy);
            c.drawCircle(cx, cy, r, paint);
            paint.setShader(null);
            paint.setColor(lp[9]);
            for (int i = 0; i < 4; i++) c.drawRect(cx - r, cy + r * (0.2f + i * 0.2f), cx + r, cy + r * (0.24f + i * 0.2f + i * 0.02f), paint);
            c.restore();
        }
        if (laBeams) { // two searchlights sweeping slowly
            paint.setStyle(Paint.Style.FILL);
            for (int k = 0; k < 2; k++) {
                float bx0 = w * (k == 0 ? 0.28f : 0.78f), sw = (float) Math.sin(t * 0.18f + k * 2.4f) * w * 0.18f;
                path.reset();
                path.moveTo(bx0 - 6f, hy);
                path.lineTo(bx0 + 6f, hy);
                path.lineTo(bx0 + sw + w * 0.04f, h * 0.08f);
                path.lineTo(bx0 + sw - w * 0.04f, h * 0.08f);
                path.close();
                paint.setColor(0x14BFE6FF);
                c.drawPath(path, paint);
            }
        }
        // thin clouds drifting, lit from below by the sun
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            float x = ((t * (4f + i * 2f) + i * w * 0.3f) % (w * 1.4f)) - w * 0.2f, y = h * (0.14f + 0.07f * i);
            paint.setColor((lp[8] & 0x00FFFFFF) | 0x26000000);
            c.drawRoundRect(x, y, x + w * (0.22f + 0.04f * i), y + h * 0.012f, 12f, 12f, paint);
        }
        // two ranges of hills, the far one lighter in the haze
        for (int rg = 0; rg < 2; rg++) {
            paint.setColor(lp[rg]);
            path.reset();
            path.moveTo(0, hy);
            for (int i = 0; i <= 16; i++) {
                path.lineTo(w * i / 16f, hy - h * ((rg == 0 ? 0.07f : 0.035f) + (0.03f - 0.01f * rg) * (float) Math.sin(i * 0.9f + 1f + rg * 2f) + 0.015f * (float) Math.sin(i * 2.1f + rg)));
            }
            path.lineTo(w, hy);
            path.close();
            c.drawPath(path, paint);
        }
        // downtown: a cluster of towers, one with a stepped crown
        for (int i = 0; i < BLD; i++) {
            float mid = 1f - Math.abs(bx[i] - 0.5f) * 1.6f;
            float bhh = h * bh[i] * (0.4f + Math.max(0f, mid) * 1.7f);
            float x = bx[i] * w, ww = bw[i] * w;
            paint.setColor(i % 3 == 0 ? lp[3] : lp[2]);
            c.drawRect(x, hy - bhh, x + ww, hy, paint);
            if (i == 21) { // the tall crowned tower
                paint.setColor(lp[3]);
                c.drawRect(x + ww * 0.15f, hy - bhh - h * 0.06f, x + ww * 0.85f, hy - bhh, paint);
                c.drawRect(x + ww * 0.3f, hy - bhh - h * 0.085f, x + ww * 0.7f, hy - bhh - h * 0.06f, paint);
                c.drawRect(x + ww * 0.46f, hy - bhh - h * 0.115f, x + ww * 0.54f, hy - bhh - h * 0.085f, paint);
                paint.setColor(lp[7]); // aircraft light on the crown, blinking
                if (((int) (t * 0.8f)) % 2 == 0) c.drawCircle(x + ww * 0.5f, hy - bhh - h * 0.118f, Math.max(2f, h * 0.003f), paint);
            }
            if (i % 2 == 0 && bhh > h * 0.05f) { // lit windows
                for (int k = 0; k < 3; k++) {
                    float wy = hy - bhh + h * 0.012f + k * h * 0.022f * (1f + (i % 4));
                    paint.setColor(laBeams && (i + k) % 5 == 0 ? 0xCC35D6FF : laBeams && (i + k) % 7 == 0 ? 0xCCFF3D9A : lp[5]);
                    if (wy < hy - h * 0.01f) c.drawRect(x + ww * 0.2f, wy, x + ww * 0.8f, wy + 2.5f, paint);
                }
            }
        }
        // ground, a glow on the horizon where the freeway meets the city
        paint.setColor(lp[4]);
        c.drawRect(0, hy, w, h, paint);
        paint.setColor((lp[7] & 0x00FFFFFF) | 0x22000000);
        c.drawRect(0, hy, w, hy + h * 0.012f, paint);
        // the road: dashed lane lines streaming past, then traffic in perspective. Headlights come toward us, taillights go away.
        for (int d = 0; d < 2; d++) {
            float y = hy + (h - hy) * (0.33f + 0.31f * d), dw = w * (0.02f + 0.02f * d), dh = Math.max(1.5f, h * (0.002f + 0.002f * d));
            paint.setColor((lp[6] & 0x00FFFFFF) | 0x30000000);
            for (int i = 0; i < 16; i++) {
                float x = ((i * w / 16f + t * (18f + 18f * d)) % w + w) % w;
                c.drawRect(x, y, x + dw, y + dh, paint);
            }
        }
        for (int lane = 0; lane < 3; lane++) {
            float f = 0.5f + 0.3f * lane, y = hy + (h - hy) * (0.17f + 0.3f * lane), dir = lane == 1 ? -1f : 1f, sp = 26f + 22f * lane;
            float gap = w * 0.006f * f * 1.6f, rr = Math.max(1.6f, h * 0.0028f * f * 1.5f), trail = sp * 0.5f * f;
            for (int i = 0; i < 12; i++) {
                float jit = ((i * 37 + lane * 11) % 13) * w * 0.006f;
                float x = ((i * w / 12f + jit + dir * t * sp) % w + w) % w;
                int col = dir < 0 ? lp[6] : lp[7], rgb = col & 0x00FFFFFF;
                float x0 = dir < 0 ? x : x - trail, x1 = dir < 0 ? x + gap + trail : x + gap; // a streak behind the lights
                paint.setColor(rgb | 0x26000000);
                c.drawRect(x0, y - rr * 0.6f, x1, y + rr * 0.6f, paint);
                paint.setColor(rgb | 0x22000000); // halo
                c.drawCircle(x, y, rr * 2.6f, paint);
                c.drawCircle(x + gap, y, rr * 2.6f, paint);
                paint.setColor(col);
                c.drawCircle(x, y, rr, paint);
                c.drawCircle(x + gap, y, rr, paint);
            }
        }
        // palms in front, swaying very slowly
        palm(c, w * 0.09f, h * 1.02f, h * 0.62f, -0.22f, t, 0f);
        palm(c, w * 0.17f, h * 1.02f, h * 0.46f, 0.12f, t, 1.3f);
        palm(c, w * 0.93f, h * 1.02f, h * 0.55f, 0.2f, t, 2.1f);
    }

    private void palm(Canvas c, float x, float y, float ht, float lean, float t, float ph) {
        float sway = (float) Math.sin(t * 0.35f + ph) * ht * 0.012f;
        float tx = x + lean * ht + sway, ty = y - ht;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xFF0E0418);
        paint.setStrokeWidth(ht * 0.022f);
        path.reset();
        path.moveTo(x, y);
        path.quadTo(x + lean * ht * 0.2f, y - ht * 0.55f, tx, ty);
        c.drawPath(path, paint);
        paint.setStrokeWidth(ht * 0.012f);
        for (int i = 0; i < 9; i++) {
            float a = -3.14159f + (i + 0.5f) * 3.14159f / 9f;
            float len = ht * (0.24f + 0.06f * (float) Math.sin(i * 2.3f)), dx = (float) Math.cos(a) * len, dy = (float) Math.sin(a) * len;
            path.reset();
            path.moveTo(tx, ty);
            path.quadTo(tx + dx * 0.6f, ty + dy * 0.9f - len * 0.25f, tx + dx, ty + dy * 0.4f + len * 0.45f + sway);
            c.drawPath(path, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }
}
