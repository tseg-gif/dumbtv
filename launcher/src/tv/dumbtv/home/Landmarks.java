package tv.dumbtv.home;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Plain silhouettes of places, drawn with paths: no logos, no marks, no text on the sides of buildings.
 * Every shape stands on y = base, is centred on x and is about {@code s} tall (spires and signs may
 * go a little over). The caller sets the paint color.
 */
final class Landmarks {
    private static final Path P = new Path();
    private static final RectF R = new RectF();
    /** Fixed heights for rows of towers, so a skyline looks the same every frame. */
    private static final float[] H = {0.42f, 0.7f, 0.5f, 0.95f, 0.6f, 0.38f, 0.82f, 0.55f, 0.46f, 0.74f, 0.5f,
        0.64f, 0.4f, 0.9f, 0.52f, 0.44f};

    private static final float[] WILLIS = {0.7f, 0.9f, 1f, 0.78f, 0.58f};

    private Landmarks() {
    }

    private static void rect(Canvas c, Paint p, float x, float base, float w, float h, float yOff) {
        c.drawRect(x - w / 2, base - yOff - h, x + w / 2, base - yOff, p);
    }

    private static void tri(Canvas c, Paint p, float x1, float y1, float x2, float y2, float x3, float y3) {
        P.reset();
        P.moveTo(x1, y1);
        P.lineTo(x2, y2);
        P.lineTo(x3, y3);
        P.close();
        c.drawPath(P, p);
    }

    private static void dome(Canvas c, Paint p, float x, float yBase, float r) {
        R.set(x - r, yBase - r, x + r, yBase + r);
        c.drawArc(R, 180, 180, true, p);
    }

    private static void line(Canvas c, Paint p, float w, float x1, float y1, float x2, float y2) {
        float was = p.getStrokeWidth();
        Paint.Style st = p.getStyle();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        c.drawLine(x1, y1, x2, y2, p);
        p.setStrokeWidth(was);
        p.setStyle(st);
    }

    private static void ring(Canvas c, Paint p, float w, float x, float y, float r) {
        float was = p.getStrokeWidth();
        Paint.Style st = p.getStyle();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        c.drawCircle(x, y, r, p);
        p.setStrokeWidth(was);
        p.setStyle(st);
    }

    private static void palm(Canvas c, Paint p, float x, float base, float h) {
        line(c, p, h * 0.03f, x, base, x + h * 0.06f, base - h);
        float tx = x + h * 0.06f, ty = base - h;
        for (int i = -2; i <= 2; i++) {
            line(c, p, h * 0.02f, tx, ty, tx + i * h * 0.17f, ty + h * (0.08f + Math.abs(i) * 0.05f));
        }
    }

    private static void row(Canvas c, Paint p, float x, float base, float s, float width, int from, int n, float hs) {
        float w = width / n;
        for (int i = 0; i < n; i++) {
            float h = H[(from + i) & 15] * s * hs;
            c.drawRect(x - width / 2 + i * w, base - h, x - width / 2 + (i + 1) * w - w * 0.08f, base, p);
        }
    }

    private static void houses(Canvas c, Paint p, float x, float base, float s) {
        for (int i = 0; i < 5; i++) {
            float hx = x + (i - 2) * s * 0.3f, hw = s * 0.24f, hh = s * (0.16f + 0.04f * (i & 1));
            c.drawRect(hx - hw / 2, base - hh, hx + hw / 2, base, p);
            tri(c, p, hx - hw * 0.6f, base - hh, hx, base - hh - s * 0.1f, hx + hw * 0.6f, base - hh);
        }
    }

    private static void bridge(Canvas c, Paint p, float x, float base, float s, boolean cables) {
        float w = s * 1.6f, deck = base - s * 0.22f;
        c.drawRect(x - w / 2, deck, x + w / 2, deck + s * 0.04f, p);
        for (int k = -1; k <= 1; k += 2) {
            float tx = x + k * w * 0.26f;
            rect(c, p, tx, base, s * 0.05f, s * 0.75f, 0f);
            if (cables) {
                for (int i = 1; i <= 5; i++) {
                    line(c, p, s * 0.008f, tx, base - s * 0.72f, tx + k * i * w * 0.07f * -1f, deck);
                    line(c, p, s * 0.008f, tx, base - s * 0.72f, tx + k * i * w * 0.07f, deck);
                }
            }
        }
        for (int i = -3; i <= 3; i++) rect(c, p, x + i * w * 0.14f, base, s * 0.02f, s * 0.22f, 0f);
    }

    private static void stadium(Canvas c, Paint p, float x, float base, float s, boolean towers) {
        float w = s * 1.5f;
        P.reset();
        P.moveTo(x - w / 2, base);
        P.lineTo(x - w * 0.46f, base - s * 0.28f);
        P.lineTo(x + w * 0.46f, base - s * 0.28f);
        P.lineTo(x + w / 2, base);
        P.close();
        c.drawPath(P, p);
        // the wavy roof over the top deck
        for (int i = 0; i < 6; i++) {
            float rx = x - w * 0.4f + i * w * 0.16f;
            dome(c, p, rx, base - s * 0.28f, w * 0.08f);
        }
        c.drawRect(x + w * 0.1f, base - s * 0.42f, x + w * 0.36f, base - s * 0.28f, p); // scoreboard
        if (towers) {
            for (int k = -1; k <= 1; k += 2) {
                float tx = x + k * w * 0.5f;
                rect(c, p, tx, base, s * 0.018f, s * 0.8f, 0f);
                c.drawRect(tx - s * 0.07f, base - s * 0.86f, tx + s * 0.07f, base - s * 0.74f, p);
            }
        }
    }

    /** Draws the landmark named {@code id}; unknown ids draw a plain tower. */
    static void draw(Canvas c, String id, float x, float base, float s, Paint p) {
        p.setStyle(Paint.Style.FILL);
        switch (id) {
            case "dodger":
                stadium(c, p, x, base, s * 0.7f, true);
                break;
            case "stadium":
            case "skydome":
                stadium(c, p, x, base, s * 0.6f, id.equals("stadium"));
                if (id.equals("skydome")) dome(c, p, x, base - s * 0.14f, s * 0.45f);
                break;
            case "hollywood": {
                P.reset();
                P.moveTo(x - s * 0.9f, base);
                P.quadTo(x - s * 0.3f, base - s * 0.55f, x + s * 0.2f, base - s * 0.4f);
                P.quadTo(x + s * 0.6f, base - s * 0.3f, x + s * 0.9f, base);
                P.close();
                c.drawPath(P, p);
                for (int i = 0; i < 9; i++) {
                    float lx = x - s * 0.5f + i * s * 0.115f, ly = base - s * (0.5f - Math.abs(i - 4) * 0.012f);
                    c.drawRect(lx - s * 0.04f, ly - s * 0.16f, lx + s * 0.04f, ly, p);
                    if (i == 0 || i == 3 || i == 8) c.drawRect(lx - s * 0.015f, ly - s * 0.1f, lx + s * 0.015f, ly - s * 0.04f, p);
                }
                break;
            }
            case "griffith":
                dome(c, p, x - s * 0.9f, base - s * 0.12f, s * 0.45f);
                P.reset();
                P.moveTo(x - s * 1.2f, base);
                P.quadTo(x - s * 0.8f, base - s * 0.5f, x, base - s * 0.34f);
                P.lineTo(x + s * 1.2f, base);
                P.close();
                c.drawPath(P, p);
                rect(c, p, x, base, s * 0.8f, s * 0.14f, s * 0.14f);
                dome(c, p, x, base - s * 0.28f, s * 0.12f);
                dome(c, p, x - s * 0.34f, base - s * 0.28f, s * 0.07f);
                dome(c, p, x + s * 0.34f, base - s * 0.28f, s * 0.07f);
                rect(c, p, x, base, s * 0.02f, s * 0.12f, s * 0.4f);
                break;
            case "watts":
                for (int i = 0; i < 3; i++) {
                    float tx = x + (i - 1) * s * 0.22f, th = s * (i == 1 ? 1f : 0.8f - 0.04f * i);
                    tri(c, p, tx - s * 0.05f, base, tx, base - th, tx + s * 0.05f, base);
                    for (int k = 1; k < 5; k++) ring(c, p, s * 0.008f, tx, base - th * k * 0.19f, s * (0.07f - k * 0.01f));
                }
                break;
            case "pier":
                ring(c, p, s * 0.014f, x, base - s * 0.5f, s * 0.38f);
                for (int i = 0; i < 6; i++) {
                    double a = i * Math.PI / 6;
                    line(c, p, s * 0.006f, x - (float) Math.cos(a) * s * 0.38f, base - s * 0.5f - (float) Math.sin(a) * s * 0.38f,
                            x + (float) Math.cos(a) * s * 0.38f, base - s * 0.5f + (float) Math.sin(a) * s * 0.38f);
                }
                tri(c, p, x - s * 0.2f, base - s * 0.04f, x, base - s * 0.5f, x + s * 0.2f, base - s * 0.04f);
                c.drawRect(x - s * 1.3f, base - s * 0.06f, x + s * 1.3f, base - s * 0.03f, p);
                for (int i = -6; i <= 6; i++) rect(c, p, x + i * s * 0.2f, base, s * 0.015f, s * 0.05f, 0f);
                break;
            case "downtown":
            case "skyline":
                row(c, p, x, base, s, s * 1.5f, id.hashCode() & 7, 11, 1f);
                tri(c, p, x - s * 0.06f, base - s * 0.95f, x, base - s * 1.12f, x + s * 0.06f, base - s * 0.95f);
                break;
            case "compton":
                houses(c, p, x, base, s * 0.9f);
                palm(c, p, x - s * 0.7f, base, s * 0.75f);
                palm(c, p, x + s * 0.72f, base, s * 0.6f);
                rect(c, p, x + s * 0.42f, base, s * 0.012f, s * 0.5f, 0f);
                c.drawRect(x + s * 0.42f, base - s * 0.5f, x + s * 0.52f, base - s * 0.48f, p);
                break;
            case "houses":
                houses(c, p, x, base, s);
                break;
            case "palms":
                palm(c, p, x - s * 0.3f, base, s * 0.8f);
                palm(c, p, x, base, s);
                palm(c, p, x + s * 0.3f, base, s * 0.7f);
                break;
            case "empire":
                rect(c, p, x, base, s * 0.5f, s * 0.38f, 0f);
                rect(c, p, x, base, s * 0.34f, s * 0.3f, s * 0.38f);
                rect(c, p, x, base, s * 0.2f, s * 0.2f, s * 0.68f);
                rect(c, p, x, base, s * 0.08f, s * 0.08f, s * 0.88f);
                rect(c, p, x, base, s * 0.012f, s * 0.2f, s * 0.96f);
                break;
            case "chrysler":
                rect(c, p, x, base, s * 0.3f, s * 0.78f, 0f);
                dome(c, p, x, base - s * 0.78f, s * 0.15f);
                dome(c, p, x, base - s * 0.86f, s * 0.1f);
                tri(c, p, x - s * 0.02f, base - s * 0.92f, x, base - s * 1.15f, x + s * 0.02f, base - s * 0.92f);
                break;
            case "wtc":
                tri(c, p, x - s * 0.1f, base, x - s * 0.06f, base - s * 1.05f, x + s * 0.06f, base - s * 1.05f);
                tri(c, p, x - s * 0.06f, base - s * 1.05f, x + s * 0.06f, base - s * 1.05f, x + s * 0.1f, base);
                rect(c, p, x, base, s * 0.012f, s * 0.2f, s * 1.05f);
                break;
            case "liberty":
                rect(c, p, x, base, s * 0.3f, s * 0.25f, 0f);
                tri(c, p, x - s * 0.1f, base - s * 0.25f, x, base - s * 0.8f, x + s * 0.1f, base - s * 0.25f);
                c.drawCircle(x, base - s * 0.84f, s * 0.045f, p);
                for (int i = -2; i <= 2; i++) line(c, p, s * 0.008f, x, base - s * 0.86f, x + i * s * 0.05f, base - s * 0.95f);
                line(c, p, s * 0.022f, x + s * 0.03f, base - s * 0.78f, x + s * 0.12f, base - s * 1.02f);
                c.drawCircle(x + s * 0.12f, base - s * 1.05f, s * 0.03f, p);
                break;
            case "brooklyn":
                bridge(c, p, x, base, s * 0.9f, true);
                break;
            case "bridge":
                bridge(c, p, x, base, s * 0.9f, false);
                break;
            case "cn":
                rect(c, p, x, base, s * 0.035f, s * 1.1f, 0f);
                c.drawOval(x - s * 0.09f, base - s * 0.72f, x + s * 0.09f, base - s * 0.62f, p);
                c.drawOval(x - s * 0.04f, base - s * 0.86f, x + s * 0.04f, base - s * 0.82f, p);
                rect(c, p, x, base, s * 0.01f, s * 0.3f, s * 1.0f);
                tri(c, p, x - s * 0.06f, base, x, base - s * 0.2f, x + s * 0.06f, base);
                break;
            case "tower":
                tri(c, p, x - s * 0.08f, base, x, base - s * 1.0f, x + s * 0.08f, base);
                c.drawOval(x - s * 0.07f, base - s * 0.62f, x + s * 0.07f, base - s * 0.55f, p);
                break;
            case "willis":
                for (int i = 0; i < 5; i++) {
                    float hs = WILLIS[i];
                    rect(c, p, x + (i - 2) * s * 0.06f, base, s * 0.058f, s * hs, 0f);
                }
                rect(c, p, x - s * 0.03f, base, s * 0.008f, s * 0.14f, s * 1.0f);
                rect(c, p, x + s * 0.03f, base, s * 0.008f, s * 0.12f, s * 0.98f);
                break;
            case "hancock":
                P.reset();
                P.moveTo(x - s * 0.15f, base);
                P.lineTo(x - s * 0.09f, base - s * 0.92f);
                P.lineTo(x + s * 0.09f, base - s * 0.92f);
                P.lineTo(x + s * 0.15f, base);
                P.close();
                c.drawPath(P, p);
                rect(c, p, x - s * 0.05f, base, s * 0.01f, s * 0.16f, s * 0.92f);
                rect(c, p, x + s * 0.05f, base, s * 0.01f, s * 0.16f, s * 0.92f);
                break;
            case "bean":
                R.set(x - s * 0.5f, base - s * 0.62f, x + s * 0.5f, base + s * 0.62f);
                c.drawArc(R, 180, 180, true, p);
                break;
            case "el":
                c.drawRect(x - s, base - s * 0.5f, x + s, base - s * 0.46f, p);
                for (int i = -4; i <= 4; i++) rect(c, p, x + i * s * 0.25f, base, s * 0.03f, s * 0.46f, 0f);
                c.drawRect(x - s * 0.45f, base - s * 0.62f, x + s * 0.3f, base - s * 0.5f, p);
                break;
            case "spire":
                rect(c, p, x, base, s * 0.12f, s * 0.9f, 0f);
                tri(c, p, x - s * 0.06f, base - s * 0.9f, x, base - s * 1.1f, x + s * 0.06f, base - s * 0.9f);
                rect(c, p, x, base, s * 0.008f, s * 0.2f, s * 1.1f);
                break;
            case "cyl":
                rect(c, p, x, base, s * 0.14f, s * 0.78f, 0f);
                dome(c, p, x, base - s * 0.78f, s * 0.07f);
                for (int k = -1; k <= 1; k += 2) rect(c, p, x + k * s * 0.11f, base, s * 0.07f, s * 0.4f, 0f);
                break;
            case "overpass":
                c.drawRect(x - s * 1.1f, base - s * 0.34f, x + s * 1.1f, base - s * 0.28f, p);
                for (int i = -3; i <= 3; i++) rect(c, p, x + i * s * 0.3f, base, s * 0.04f, s * 0.28f, 0f);
                break;
            case "bigben":
                rect(c, p, x, base, s * 0.11f, s * 0.78f, 0f);
                tri(c, p, x - s * 0.07f, base - s * 0.78f, x, base - s * 1.05f, x + s * 0.07f, base - s * 0.78f);
                rect(c, p, x - s * 0.22f, base, s * 0.34f, s * 0.3f, 0f);
                for (int i = 0; i < 4; i++) rect(c, p, x - s * 0.36f + i * s * 0.09f, base, s * 0.04f, s * 0.05f, s * 0.3f);
                break;
            case "eye":
                ring(c, p, s * 0.014f, x, base - s * 0.55f, s * 0.5f);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 8;
                    line(c, p, s * 0.005f, x - (float) Math.cos(a) * s * 0.5f, base - s * 0.55f - (float) Math.sin(a) * s * 0.5f,
                            x + (float) Math.cos(a) * s * 0.5f, base - s * 0.55f + (float) Math.sin(a) * s * 0.5f);
                }
                line(c, p, s * 0.02f, x - s * 0.1f, base, x, base - s * 0.55f);
                line(c, p, s * 0.02f, x + s * 0.1f, base, x, base - s * 0.55f);
                break;
            case "towerbridge":
                for (int k = -1; k <= 1; k += 2) {
                    float tx = x + k * s * 0.3f;
                    rect(c, p, tx, base, s * 0.12f, s * 0.6f, 0f);
                    tri(c, p, tx - s * 0.08f, base - s * 0.6f, tx, base - s * 0.78f, tx + s * 0.08f, base - s * 0.6f);
                }
                c.drawRect(x - s * 0.3f, base - s * 0.5f, x + s * 0.3f, base - s * 0.46f, p);
                c.drawRect(x - s * 0.8f, base - s * 0.2f, x + s * 0.8f, base - s * 0.16f, p);
                break;
            case "shard":
                tri(c, p, x - s * 0.12f, base, x, base - s * 1.15f, x + s * 0.12f, base);
                break;
            case "stpauls":
            case "dome":
                rect(c, p, x, base, s * 0.5f, s * 0.2f, 0f);
                rect(c, p, x, base, s * 0.28f, s * 0.14f, s * 0.2f);
                dome(c, p, x, base - s * 0.34f, s * 0.2f);
                rect(c, p, x, base, s * 0.02f, s * 0.12f, s * 0.54f);
                if (id.equals("stpauls")) {
                    rect(c, p, x - s * 0.34f, base, s * 0.1f, s * 0.46f, 0f);
                    rect(c, p, x + s * 0.34f, base, s * 0.1f, s * 0.46f, 0f);
                }
                break;
            case "lekki":
                rect(c, p, x, base, s * 0.04f, s * 0.9f, 0f);
                for (int i = 1; i <= 6; i++) {
                    line(c, p, s * 0.006f, x, base - s * 0.9f + i * s * 0.02f, x - i * s * 0.14f, base - s * 0.2f);
                    line(c, p, s * 0.006f, x, base - s * 0.9f + i * s * 0.02f, x + i * s * 0.14f, base - s * 0.2f);
                }
                c.drawRect(x - s * 1.1f, base - s * 0.22f, x + s * 1.1f, base - s * 0.18f, p);
                break;
            case "theatre":
                R.set(x - s * 0.55f, base - s * 0.5f, x + s * 0.55f, base - s * 0.1f);
                c.drawOval(R, p);
                for (int i = -3; i <= 3; i++) rect(c, p, x + i * s * 0.12f, base, s * 0.03f, s * 0.14f, 0f);
                break;
            case "longbridge":
                c.drawRect(x - s * 1.4f, base - s * 0.2f, x + s * 1.4f, base - s * 0.16f, p);
                for (int i = -6; i <= 6; i++) rect(c, p, x + i * s * 0.22f, base, s * 0.025f, s * 0.16f, 0f);
                break;
            case "market":
                for (int i = -2; i <= 2; i++) {
                    float mx = x + i * s * 0.26f;
                    tri(c, p, mx - s * 0.16f, base - s * 0.2f, mx, base - s * 0.34f, mx + s * 0.16f, base - s * 0.2f);
                    rect(c, p, mx, base, s * 0.012f, s * 0.2f, 0f);
                }
                break;
            case "bluemtn":
            case "hills":
                P.reset();
                P.moveTo(x - s * 1.3f, base);
                P.lineTo(x - s * 0.7f, base - s * 0.55f);
                P.lineTo(x - s * 0.35f, base - s * 0.4f);
                P.lineTo(x + s * 0.1f, base - s * 0.85f);
                P.lineTo(x + s * 0.55f, base - s * 0.45f);
                P.lineTo(x + s * 0.9f, base - s * 0.6f);
                P.lineTo(x + s * 1.3f, base);
                P.close();
                c.drawPath(P, p);
                break;
            case "harbour":
                for (int i = 0; i < 2; i++) {
                    float cx = x - s * 0.6f + i * s * 0.5f;
                    rect(c, p, cx, base, s * 0.02f, s * 0.55f, 0f);
                    c.drawRect(cx, base - s * 0.55f, cx + s * 0.3f, base - s * 0.52f, p);
                }
                for (int i = 0; i < 4; i++) rect(c, p, x + s * 0.3f + i * s * 0.14f, base, s * 0.12f, s * (0.1f + 0.04f * (i & 1)), 0f);
                break;
            case "ship":
                P.reset();
                P.moveTo(x - s * 0.8f, base - s * 0.12f);
                P.lineTo(x + s * 0.8f, base - s * 0.12f);
                P.lineTo(x + s * 0.65f, base);
                P.lineTo(x - s * 0.7f, base);
                P.close();
                c.drawPath(P, p);
                rect(c, p, x, base, s * 1.2f, s * 0.1f, s * 0.12f);
                rect(c, p, x, base, s * 0.9f, s * 0.1f, s * 0.22f);
                rect(c, p, x + s * 0.1f, base, s * 0.12f, s * 0.12f, s * 0.32f);
                break;
            case "lighthouse":
                tri(c, p, x - s * 0.1f, base, x - s * 0.05f, base - s * 0.8f, x + s * 0.05f, base - s * 0.8f);
                tri(c, p, x - s * 0.05f, base - s * 0.8f, x + s * 0.05f, base - s * 0.8f, x + s * 0.1f, base);
                rect(c, p, x, base, s * 0.12f, s * 0.08f, s * 0.8f);
                tri(c, p, x - s * 0.07f, base - s * 0.88f, x, base - s * 1.0f, x + s * 0.07f, base - s * 0.88f);
                break;
            case "entoto":
                P.reset();
                P.moveTo(x - s * 1.4f, base);
                P.quadTo(x - s * 0.6f, base - s * 0.7f, x + s * 0.1f, base - s * 0.55f);
                P.quadTo(x + s * 0.8f, base - s * 0.4f, x + s * 1.4f, base);
                P.close();
                c.drawPath(P, p);
                for (int i = 0; i < 7; i++) tri(c, p, x - s * 0.7f + i * s * 0.22f - s * 0.03f, base, x - s * 0.7f + i * s * 0.22f,
                        base - s * (0.25f + 0.05f * (i & 1)) - s * 0.2f, x - s * 0.7f + i * s * 0.22f + s * 0.03f, base);
                break;
            case "meskel":
                c.drawRect(x - s * 0.6f, base - s * 0.1f, x + s * 0.6f, base, p);
                c.drawRect(x - s * 0.4f, base - s * 0.2f, x + s * 0.4f, base - s * 0.1f, p);
                rect(c, p, x, base, s * 0.05f, s * 0.7f, s * 0.2f);
                rect(c, p, x, base, s * 0.28f, s * 0.05f, s * 0.7f);
                break;
            case "au":
                c.drawRect(x - s * 0.7f, base - s * 0.28f, x + s * 0.2f, base, p);
                rect(c, p, x + s * 0.2f, base, s * 0.16f, s * 0.95f, 0f);
                dome(c, p, x + s * 0.2f, base - s * 0.95f, s * 0.08f);
                break;
            case "deco":
                rect(c, p, x, base, s * 1.2f, s * 0.2f, 0f);
                rect(c, p, x, base, s * 0.8f, s * 0.14f, s * 0.2f);
                rect(c, p, x, base, s * 0.3f, s * 0.16f, s * 0.34f);
                rect(c, p, x - s * 0.1f, base, s * 0.02f, s * 0.4f, s * 0.5f);
                c.drawRect(x - s * 0.1f - s * 0.01f, base - s * 0.9f, x + s * 0.14f, base - s * 0.5f, p);
                break;
            case "lifeguard":
                rect(c, p, x, base, s * 0.3f, s * 0.2f, s * 0.22f);
                tri(c, p, x - s * 0.2f, base - s * 0.42f, x, base - s * 0.56f, x + s * 0.2f, base - s * 0.42f);
                line(c, p, s * 0.015f, x - s * 0.12f, base, x - s * 0.12f, base - s * 0.22f);
                line(c, p, s * 0.015f, x + s * 0.12f, base, x + s * 0.12f, base - s * 0.22f);
                line(c, p, s * 0.015f, x + s * 0.2f, base, x + s * 0.3f, base - s * 0.22f);
                break;
            case "castle":
                c.drawRect(x - s * 0.5f, base - s * 0.3f, x + s * 0.5f, base, p);
                for (int k = -1; k <= 1; k += 2) {
                    rect(c, p, x + k * s * 0.5f, base, s * 0.16f, s * 0.6f, 0f);
                    tri(c, p, x + k * s * 0.5f - s * 0.11f, base - s * 0.6f, x + k * s * 0.5f, base - s * 0.8f,
                            x + k * s * 0.5f + s * 0.11f, base - s * 0.6f);
                }
                break;
            case "hall":
                c.drawRect(x - s * 0.5f, base - s * 0.05f, x + s * 0.5f, base, p);
                for (int i = -3; i <= 3; i++) rect(c, p, x + i * s * 0.14f, base, s * 0.05f, s * 0.3f, s * 0.05f);
                tri(c, p, x - s * 0.55f, base - s * 0.35f, x, base - s * 0.55f, x + s * 0.55f, base - s * 0.35f);
                break;
            default:
                tri(c, p, x - s * 0.08f, base, x, base - s, x + s * 0.08f, base);
        }
    }
}
