package tv.dumbtv.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;

import java.util.ArrayList;

/**
 * The singing silhouette. One sprite sheet is baked per song (jaw angles x two head poses, plus a static body),
 * then drawn as textured quads. The mouth opens on the mid-band syllable envelope, gated by synced lyrics when
 * LRCLIB has them. The look (hair, hat, headphones, microphone) comes from the song's seed only.
 */
final class Singer {
    static final int JAWS = 6, POSES = 2;
    private static final float MAX_JAW = 21f;
    /** Atlas pixels per geometry unit. */
    private static final int S = 230;
    private static final float HX0 = -0.90f, HY0 = -1.02f, HW = 1.85f, HH = 1.70f; // head cell in units
    private static final float BX0 = -1.06f, BY0 = 0.0f, BW = 2.12f, BH = 1.55f; // body cell in units

    private Bitmap atlas;
    private volatile boolean baking;
    private int cellW, cellH, bodyW, bodyH, seed = -1;
    private final Paint tint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    // mouth state
    private float env, gate = 1f, openNow;
    private float bob, tilt;
    private int lastTint = 0;

    // synced lyrics: sorted line starts and ends in ms
    private long[] lineA = new long[0], lineB = new long[0];
    private volatile boolean haveLyrics;
    private long posMs, posAt;
    private boolean playing;

    boolean ready() {
        return atlas != null;
    }

    /** Called with the playback position from the media session (extrapolated from its last update). */
    void sync(long positionMs, boolean isPlaying) {
        posMs = positionMs;
        posAt = SystemClock.elapsedRealtime();
        playing = isPlaying;
    }

    /** Parses LRC text ("[mm:ss.xx] words"); an empty line marks a gap. */
    void setLrc(String lrc) {
        ArrayList<long[]> rows = new ArrayList<long[]>();
        ArrayList<Boolean> sung = new ArrayList<Boolean>();
        if (lrc != null) {
            for (String ln : lrc.split("\n")) {
                int e = ln.indexOf(']');
                if (!ln.startsWith("[") || e < 5 || ln.charAt(3) != ':') continue;
                try {
                    int m = Integer.parseInt(ln.substring(1, 3));
                    float s = Float.parseFloat(ln.substring(4, e));
                    rows.add(new long[] {(long) ((m * 60 + s) * 1000f)});
                    sung.add(ln.substring(e + 1).trim().length() > 0);
                } catch (NumberFormatException ignored) {
                    // not a timed line
                }
            }
        }
        ArrayList<long[]> spans = new ArrayList<long[]>();
        for (int i = 0; i < rows.size(); i++) {
            if (!sung.get(i)) continue;
            long a = rows.get(i)[0];
            long end = i + 1 < rows.size() ? rows.get(i + 1)[0] : a + 5000;
            end = Math.min(end, a + 7000); // lines rarely hold longer than this; the rest is a gap
            spans.add(new long[] {a, end});
        }
        long[] A = new long[spans.size()], B = new long[spans.size()];
        for (int i = 0; i < A.length; i++) {
            A[i] = spans.get(i)[0];
            B[i] = spans.get(i)[1];
        }
        lineA = A;
        lineB = B;
        haveLyrics = A.length > 3;
    }

    void clearLyrics() {
        haveLyrics = false;
        lineA = new long[0];
        lineB = new long[0];
    }

    private boolean sungNow(long delayMs) {
        long t = posMs + (playing ? SystemClock.elapsedRealtime() - posAt : 0) + delayMs;
        long[] A = lineA, B = lineB;
        int lo = 0, hi = A.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (A[mid] <= t) lo = mid + 1;
            else hi = mid - 1;
        }
        return hi >= 0 && hi < B.length && t < B[hi] - 150;
    }

    /** Bakes the sheet for a song seed on a background thread; the old picture keeps drawing until it is ready. */
    void prepare(final int songSeed) {
        if (songSeed == seed && (atlas != null || baking)) return;
        seed = songSeed;
        baking = true;
        final int hair = Math.abs(songSeed % SingerGeom.HAIRS);
        final boolean mic = (Math.abs(songSeed / 7) % 3) != 0;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bm = null;
                try {
                    bm = bake(hair, mic);
                } catch (OutOfMemoryError e) {
                    bm = null; // the layer just skips the singer
                } catch (RuntimeException e) {
                    bm = null;
                }
                final Bitmap fin = bm;
                synchronized (Singer.this) {
                    if (songSeed == seed) {
                        Bitmap old = atlas;
                        atlas = fin;
                        if (old != null && old != fin) old.recycle();
                    } else if (fin != null) {
                        fin.recycle();
                    }
                    baking = false;
                }
            }
        }, "singer-bake");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private Bitmap bake(int hair, boolean mic) {
        cellW = (int) (HW * S);
        cellH = (int) (HH * S);
        bodyW = (int) (BW * S);
        bodyH = (int) (BH * S);
        int w = Math.max(cellW * JAWS, bodyW), h = cellH * POSES + bodyH;
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        CanvasGfx g = new CanvasGfx(c);
        final int body = 0xFF0A0C14, rim = 0xFFFFFFFF;
        for (int pose = 0; pose < POSES; pose++) {
            for (int j = 0; j < JAWS; j++) {
                c.save();
                c.translate(j * cellW - HX0 * S, pose * cellH - HY0 * S);
                c.scale(S, S);
                SingerGeom.render(g, hair, mic, MAX_JAW * j / (JAWS - 1), pose == 0 ? 0f : 6f, rim, body, 0.016f, false, true);
                c.restore();
            }
        }
        c.save();
        c.translate(-BX0 * S, POSES * cellH - BY0 * S);
        c.scale(S, S);
        SingerGeom.render(g, hair, mic, 0f, 0f, rim, body, 0.016f, true, false);
        c.restore();
        return bm;
    }

    /**
     * Draws the bust centred on (cx, headY) with the head about headH tall. mid is the 300-3000 Hz level, kick and
     * snare the band triggers; color is the song's rim light.
     */
    void draw(Canvas c, float cx, float headY, float headH, float dt, float mid, float kick, float snare, int color, int alpha) {
        Bitmap bm = atlas;
        if (bm == null) return;
        // syllable envelope: ~40 ms attack, fast decay; the lyrics (when known) decide whether anyone is singing
        float target = Math.max(0f, Math.min(1f, (mid - 0.12f) * 2.2f));
        if (target > env) env += (target - env) * (1f - (float) Math.exp(-dt / 0.040f));
        else env *= (float) Math.exp(-dt / 0.070f);
        float g = haveLyrics ? (sungNow(0) ? 1f : 0.05f) : 1f;
        gate += (g - gate) * (1f - (float) Math.exp(-dt / 0.12f));
        openNow = env * gate;
        bob += ((kick) - bob) * Math.min(1f, dt * 16f);
        tilt += ((snare) - tilt) * Math.min(1f, dt * 12f);

        int jaw = Math.min(JAWS - 1, (int) (openNow * (JAWS - 0.2f)));
        int pose = bob > 0.45f ? 1 : 0;
        float u = headH / 1.1f; // screen pixels per geometry unit
        if (color != lastTint) {
            tint.setColorFilter(new LightingColorFilter(color | 0xFF000000, 0));
            lastTint = color;
        }
        tint.setAlpha(alpha);
        c.save();
        // the head and shoulders sway about the base of the neck
        float px = cx - 0.10f * u, py = headY + 0.45f * u;
        c.translate(0f, bob * u * 0.035f);
        c.rotate((tilt * 2.2f - 0.6f) * 1.0f, px, py);
        // body
        float by = headY - 0.0f * u;
        src.set(0, POSES * cellH, bodyW, POSES * cellH + bodyH);
        dst.set(cx + BX0 * u, by + BY0 * u, cx + (BX0 + BW) * u, by + (BY0 + BH) * u);
        c.drawBitmap(bm, src, dst, tint);
        // head
        src.set(jaw * cellW, pose * cellH, (jaw + 1) * cellW, (pose + 1) * cellH);
        dst.set(cx + HX0 * u, headY + HY0 * u, cx + (HX0 + HW) * u, headY + (HY0 + HH) * u);
        c.drawBitmap(bm, src, dst, tint);
        c.restore();
    }

    /** Where the debug overlay reads the mouth. */
    float open() {
        return openNow;
    }

    /** Canvas adapter for SingerGeom. */
    private static final class CanvasGfx implements SingerGeom.Gfx {
        private final Canvas c;
        private final Path p = new Path();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CanvasGfx(Canvas c) {
            this.c = c;
        }

        public void save() {
            c.save();
        }

        public void restore() {
            c.restore();
        }

        public void translate(float x, float y) {
            c.translate(x, y);
        }

        public void rotate(float deg) {
            c.rotate(deg);
        }

        public void moveTo(float x, float y) {
            p.moveTo(x, y);
        }

        public void lineTo(float x, float y) {
            p.lineTo(x, y);
        }

        public void cubicTo(float a, float b, float cc, float d, float e, float f) {
            p.cubicTo(a, b, cc, d, e, f);
        }

        public void fill(int argb) {
            p.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(argb);
            c.drawPath(p, paint);
            p.reset();
        }

        public void circle(float cx, float cy, float r, int argb) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(argb);
            c.drawCircle(cx, cy, r, paint);
        }

        public void line(float x0, float y0, float x1, float y1, float w, int argb) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(w);
            paint.setColor(argb);
            c.drawLine(x0, y0, x1, y1, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }
}
