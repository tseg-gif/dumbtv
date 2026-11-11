package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Calendar;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Draws one Meditation scene. Pass 1 renders the scene's background with a fragment shader
 * (assets/calm_*.glsl) into a small texture (soft anyway, and the Fire TV GPU is slow); pass 2 shows it
 * full screen and draws the rain as streak quads and the window droplets as small refracting quads, whose
 * positions all come from vertex shaders. With a video clip the clip is the background instead.
 * Everything that reaches the picture is smoothed here: audio over ~1.5 s, sky breathing over ~4 s,
 * sparkle over ~0.4 s. There are no flashes: brightness is capped (0.5, or 0.3 late at night), the
 * optional thunder glow rises over several seconds, and the sleep fade only ever goes down.
 */
final class CalmGl implements GLSurfaceView.Renderer {
    private static final String TAG = "dumbcalm";
    static final String DEV_DIR = "/sdcard/Movies/meditation/shaders/";
    private static final float[] BG_SCALE = {0.5f, 0.4f, 0.33f, 0.25f};

    interface Listener {
        /** Called on the GL thread every ~5 s. */
        void onStats(String scene, float fps, int w, int h);
    }

    /** One depth of rain (or the roof's water sheet): a pool of streaks falling from yTop to yEnd. */
    private static final class Rain {
        final int n;
        final float yTop, yEnd, spd, len, wid, alpha, tail, gate;
        final float[] color;
        float soft = 0.75f, span = 1f, shim = 0f, wind = 1f;
        int vbo;

        Rain(int n, float yTop, float yEnd, float spd, float len, float wid, float alpha, float tail, float gate, float[] color) {
            this.n = n;
            this.yTop = yTop;
            this.yEnd = yEnd;
            this.spd = spd;
            this.len = len;
            this.wid = wid;
            this.alpha = alpha;
            this.tail = tail;
            this.gate = gate;
            this.color = color;
        }

        Rain soft(float v) {
            soft = v;
            return this;
        }

        Rain span(float v) {
            span = v;
            return this;
        }

        Rain shim(float v) {
            shim = v;
            return this;
        }

        Rain wind(float v) {
            wind = v;
            return this;
        }
    }

    /** A set of window droplets: mode 0 runs down the glass, mode 1 stays put. */
    private static final class Drops {
        final int n, mode;
        final float rMin, rMax, spdMin, spdMax, trail;
        int vbo;

        Drops(int n, int mode, float rMin, float rMax, float spdMin, float spdMax, float trail) {
            this.n = n;
            this.mode = mode;
            this.rMin = rMin;
            this.rMax = rMax;
            this.spdMin = spdMin;
            this.spdMax = spdMax;
            this.trail = trail;
        }
    }

    private final Context ctx;
    private final CalmAudio audio;
    private final Listener listener;
    private final Random rnd = new Random();

    // set from the UI thread
    private volatile String pendingScene = "garden";
    private volatile String videoPath;
    private volatile boolean lite, thunderOn;
    private volatile long sleepStart, sleepEnd; // 0 = no timer

    private int quadVbo, noiseTex, bgTex, fbo, videoTex;
    private int bgProg, finProg, rainProg, dropProg, simProg, ptProg, normProg;
    private int photoTex, photoBTex, normTex, normFbo;
    private final int[] simTex = new int[2], simFbo = new int[2];
    private int ripCur;
    private boolean ripOk, needRip;
    private volatile float intensity = 1f;
    private float ripYH = 0.45f, ripBase = 12f, ripAmp = 1f, rainScale = 1f, lenMul = 1f;
    private float camX, camY, camZ = 1.02f;
    private double nextDrop;
    private static final int SIM = 256, MAXP = 96;
    private final float[] inj = new float[16], injQ = new float[64];
    private int injN;
    private final float[] pt = new float[MAXP * 8];     // x, y, vx, vy, age, life, landing sx, landing sy
    private final float[] ptOut = new float[MAXP * 3];
    private int ptN;
    private int w = 1, h = 1, bgW = 2, bgH = 2, bgIx = 1;
    private boolean built;
    private String builtKey = "", scene = "garden";
    private boolean builtVid;
    private Rain[] rains = new Rain[0];
    private Drops[] drops = new Drops[0];

    private SurfaceTexture st;
    private Surface surface;
    private MediaPlayer mp;
    private volatile boolean frameReady;
    private boolean videoOk;
    private float vidAspect = 1.7778f;
    private final float[] stm = new float[16];

    private long t0 = 0, last = 0, statAt = 0, capAt = 0, thunderNext = 0, govAfter = 0;
    private int frames;
    private float tSec;
    private float rain = 0.45f, speed = 1f, size = 1f, breath = 0f, spark = 0.2f;
    private float cap = 0.5f;
    private volatile float dipTarget = 1f;
    private float dip = 1f;
    private float thunder, thunderT = -1;

    CalmGl(Context c, CalmAudio a, Listener l) {
        ctx = c.getApplicationContext();
        audio = a;
        listener = l;
    }

    void setScene(String id, String clipPath) {
        videoPath = clipPath;
        pendingScene = id;
    }

    void setLite(boolean v) { lite = v; }

    /** 0 fades the picture to near-black, 1 brings it back; used for a slow change between scenes. */
    void dip(boolean dark) {
        dipTarget = dark ? 0f : 1f;
    }

    /** "drizzle", "steady" or "heavy": scales rain density. */
    void setIntensity(String v) { intensity = "drizzle".equals(v) ? 0.6f : "heavy".equals(v) ? 1.35f : 1f; }

    void setThunder(boolean v) { thunderOn = v; }

    /** Starts a sleep timer: the picture reaches near-black at now + minutes. */
    void setSleep(int minutes) {
        if (minutes <= 0) {
            sleepStart = sleepEnd = 0;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long fade = Math.min(5 * 60000L, minutes * 60000L / 3);
        sleepEnd = now + minutes * 60000L;
        sleepStart = sleepEnd - fade;
    }

    boolean sleepActive() { return sleepEnd != 0; }

    /** 1 = fully awake, 0 = asleep (near-black). */
    float sleepFactor() {
        if (sleepEnd == 0) return 1f;
        long now = SystemClock.elapsedRealtime();
        if (now <= sleepStart) return 1f;
        if (now >= sleepEnd) return 0f;
        float x = (now - sleepStart) / (float) (sleepEnd - sleepStart);
        return 1f - x * x * (3f - 2f * x);
    }

    String sleepState() {
        if (sleepEnd == 0) return "off";
        long left = Math.max(0, sleepEnd - SystemClock.elapsedRealtime()) / 1000;
        return "timer " + left + "s left, factor " + String.format(java.util.Locale.US, "%.2f", sleepFactor());
    }

    /** 4-7-8 breathing: 4 s in, 7 s hold, 8 s out. Returns 0..1 (size) and writes the cue index (0 in, 1 hold, 2 out). */
    static float orbPhase(float t, int[] cue) {
        float c = t % 19f;
        float v;
        int k;
        if (c < 4f) {
            v = c / 4f;
            k = 0;
        } else if (c < 11f) {
            v = 1f;
            k = 1;
        } else {
            v = 1f - (c - 11f) / 8f;
            k = 2;
        }
        if (cue != null) cue[0] = k;
        return v * v * (3f - 2f * v);
    }

    // ---- GL setup ------------------------------------------------------------------------

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        built = false;
        builtKey = "";
        bgProg = finProg = rainProg = dropProg = simProg = ptProg = normProg = 0;
        rains = new Rain[0];
        drops = new Drops[0];
        int[] b = new int[1];
        GLES20.glGenBuffers(1, b, 0);
        quadVbo = b[0];
        FloatBuffer fb = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 32, fb, GLES20.GL_STATIC_DRAW);
        int[] t = new int[8];
        GLES20.glGenTextures(8, t, 0);
        noiseTex = t[0];
        videoTex = t[1];
        bgTex = t[2];
        photoTex = t[3];
        photoBTex = t[4];
        simTex[0] = t[5];
        simTex[1] = t[6];
        normTex = t[7];
        bakeNoise();
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        releaseVideoGl();
        st = new SurfaceTexture(videoTex);
        st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture s) {
                frameReady = true;
            }
        });
        surface = new Surface(st);
        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        fbo = f[0];
        initSim();
        t0 = SystemClock.elapsedRealtime();
        last = 0;
        statAt = 0;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        w = width;
        h = height;
        GLES20.glViewport(0, 0, w, h);
        allocBg();
    }

    private void allocBg() {
        bgW = Math.max(2, Math.round(w * BG_SCALE[bgIx]));
        bgH = Math.max(2, Math.round(h * BG_SCALE[bgIx]));
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, bgW, bgH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, bgTex, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        Log.i(TAG, "background render " + bgW + "x" + bgH + " (surface " + w + "x" + h + ")");
    }

    /** 256x256 tiling value noise: 32x32 random cells, smoothstep-interpolated, so bilinear reads stay smooth. */
    private void bakeNoise() {
        final int g = 32, n = 256, cell = n / g;
        Random r = new Random(7);
        float[] grid = new float[g * g];
        for (int i = 0; i < grid.length; i++) grid[i] = r.nextFloat();
        byte[] px = new byte[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float fx = (x % cell) / (float) cell, fy = (y % cell) / (float) cell;
                fx = fx * fx * (3f - 2f * fx);
                fy = fy * fy * (3f - 2f * fy);
                int x0 = x / cell, y0 = y / cell, x1 = (x0 + 1) % g, y1 = (y0 + 1) % g;
                float top = grid[y0 * g + x0] * (1f - fx) + grid[y0 * g + x1] * fx;
                float bot = grid[y1 * g + x0] * (1f - fx) + grid[y1 * g + x1] * fx;
                px[y * n + x] = (byte) Math.round((top * (1f - fy) + bot * fy) * 255f);
            }
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTex);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, n, n, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE,
                ByteBuffer.wrap(px));
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
    }

    private static void tex8(int tex, int filter, int wrap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, SIM, SIM, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap);
    }

    /** The 256x256 wave heightfield (ping-pong, 16-bit values packed in RGBA8) and its slope texture. */
    private void initSim() {
        int[] f = new int[3];
        GLES20.glGenFramebuffers(3, f, 0);
        simFbo[0] = f[0];
        simFbo[1] = f[1];
        normFbo = f[2];
        ripOk = true;
        for (int i = 0; i < 3; i++) {
            int tex = i < 2 ? simTex[i] : normTex;
            tex8(tex, i < 2 ? GLES20.GL_NEAREST : GLES20.GL_LINEAR, GLES20.GL_REPEAT);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) ripOk = false;
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        clearSim();
        Log.i(TAG, "ripple heightfield " + SIM + "x" + SIM + " 16-bit packed: " + (ripOk ? "ok" : "unsupported, flat water"));
    }

    private void clearSim() {
        for (int i = 0; i < 3; i++) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, i < 2 ? simFbo[i] : normFbo);
            // zero height and velocity: u = 0.5 -> hi = 127, lo = 0.5
            if (i < 2) GLES20.glClearColor(127f / 255f, 128f / 255f, 127f / 255f, 128f / 255f);
            else GLES20.glClearColor(0.5f, 0.5f, 1f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        injN = 0;
        ptN = 0;
        ripCur = 0;
    }

    private static void box(int[] px, int w, int h, int r, boolean horiz) {
        int[] out = new int[px.length];
        int n = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = 0, b = 0, c = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = horiz ? Math.min(w - 1, Math.max(0, x + k)) : x;
                    int yy = horiz ? y : Math.min(h - 1, Math.max(0, y + k));
                    int v = px[yy * w + xx];
                    a += (v >> 16) & 255;
                    b += (v >> 8) & 255;
                    c += v & 255;
                }
                out[y * w + x] = 0xFF000000 | ((a / n) << 16) | ((b / n) << 8) | (c / n);
            }
        }
        System.arraycopy(out, 0, px, 0, px.length);
    }

    /** Photo backdrop: a 1920x1080 texture plus a heavily blurred 480x270 copy for depth of field and reflections. */
    private boolean loadPhoto(String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int ss = 1;
            while (o.outWidth / (ss * 2) >= 1920) ss *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = ss;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bm = BitmapFactory.decodeFile(path, o);
            if (bm == null) return false;
            int bw = bm.getWidth(), bh = bm.getHeight(), cw = bw, ch = bh;
            if (bw * 9 > bh * 16) cw = bh * 16 / 9;
            else ch = bw * 9 / 16;
            if (cw != bw || ch != bh) bm = Bitmap.createBitmap(bm, (bw - cw) / 2, (bh - ch) / 2, cw, ch);
            if (bm.getWidth() > 1920) bm = Bitmap.createScaledBitmap(bm, 1920, 1080, true);
            Bitmap sm = Bitmap.createScaledBitmap(bm, 480, 270, true);
            int[] px = new int[480 * 270];
            sm.getPixels(px, 0, 480, 0, 0, 480, 270);
            for (int i = 0; i < 3; i++) {
                box(px, 480, 270, 4, true);
                box(px, 480, 270, 4, false);
            }
            sm.setPixels(px, 0, 480, 0, 0, 480, 270);
            upload(photoTex, bm);
            upload(photoBTex, sm);
            Log.i(TAG, "photo backdrop " + path + " " + bm.getWidth() + "x" + bm.getHeight());
            return true;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "photo failed, using generated backdrop: " + e);
            return false;
        }
    }

    private void upload(int tex, Bitmap bm) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bm, 0);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    // ---- shaders ---------------------------------------------------------------------------

    private String read(String name) {
        try {
            File dev = new File(DEV_DIR + name + ".glsl");
            InputStream in = dev.isFile() ? new FileInputStream(dev) : ctx.getAssets().open("calm_" + name + ".glsl");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "shader read failed " + name + ": " + e);
            return "";
        }
    }

    private int compile(int type, String src, String what) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, what + " compile failed: " + GLES20.glGetShaderInfoLog(s));
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    private int link(String vs, String fs, String what) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs, what + " vertex"), f = compile(GLES20.GL_FRAGMENT_SHADER, fs, what + " fragment");
        if (v == 0 || f == 0) return 0;
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, what + " link failed: " + GLES20.glGetProgramInfoLog(p));
            return 0;
        }
        return p;
    }

    private static final String FULL_VS = "attribute vec2 aPos; varying vec2 vUv; void main(){ vUv = aPos*0.5+0.5; gl_Position = vec4(aPos,0.,1.); }";

    private void kill(int p) {
        if (p != 0) GLES20.glDeleteProgram(p);
    }

    private static final String PT_VS = "attribute vec3 aP; uniform float uPx; varying float vAl; void main(){ vAl = aP.z; gl_Position = vec4(aP.xy * 2.0 - 1.0, 0.0, 1.0); gl_PointSize = uPx; }";
    private static final String PT_FS = "precision mediump float; varying float vAl; uniform float uBright; void main(){ float d = length(gl_PointCoord - 0.5) * 2.0; float a = smoothstep(1.0, 0.2, d); gl_FragColor = vec4(vec3(0.95, 0.92, 0.85) * (a * vAl * uBright * 1.3), 1.0); }";

    private void build(String id, boolean vid, boolean photoBack) {
        kill(bgProg);
        kill(finProg);
        kill(rainProg);
        kill(dropProg);
        bgProg = finProg = rainProg = dropProg = 0;
        String ext = vid ? "#extension GL_OES_EGL_image_external : require\n#define VID\n" : "";
        String def = (vid || photoBack) ? "#define PHOTO\n" : "";
        if ("autumn".equals(id)) def += "#define AUTUMN\n";
        String hi = "precision highp float;\n";
        String common = read("common");
        String water = ("puddle".equals(id) || "avenue".equals(id) || "garden".equals(id)) ? read("water") : "";
        bgProg = link(FULL_VS, ext + def + hi + common + water + read("autumn".equals(id) ? "canopy" : id) + read("main"), "scene " + id);
        finProg = link(FULL_VS, hi + common + read("final_f"), "final");
        if (rains.length > 0) rainProg = link(read("rain_v"), read("rain_f"), "rain");
        if (drops.length > 0) dropProg = link(read("drop_v"), hi + common + read("drop_f"), "drops");
        if (needRip && simProg == 0) simProg = link(FULL_VS, "precision highp float;\n" + read("sim_f"), "sim");
        if (needRip && ptProg == 0) ptProg = link(PT_VS, PT_FS, "splash");
        if (needRip && normProg == 0) normProg = link(FULL_VS, "precision highp float;\n" + read("norm_f"), "norm");
        builtVid = vid;
        built = true;
        Log.i(TAG, "scene=" + id + " video=" + vid + " photo=" + photoBack + " programs bg=" + (bgProg != 0) + " final=" + (finProg != 0)
                + " rain=" + (rainProg != 0) + " drops=" + (dropProg != 0));
    }

    // ---- overlays: rain streaks and droplets ---------------------------------------------------

    private static final float[] QUAD_STREAK = {-1, 0, 1, 0, -1, 1, 1, 0, 1, 1, -1, 1};
    private static final float[] QUAD_DROP = {-1, -1, 1, -1, -1, 1, 1, -1, 1, 1, -1, 1};

    private int vbo(float[] data) {
        int[] b = new int[1];
        GLES20.glGenBuffers(1, b, 0);
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data).position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, b[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.length * 4, fb, GLES20.GL_STATIC_DRAW);
        return b[0];
    }

    private void makeRain(Rain r, long seed) {
        Random q = new Random(seed);
        float[] d = new float[r.n * 6 * 6];
        int o = 0;
        for (int i = 0; i < r.n; i++) {
            float a = q.nextFloat(), b = q.nextFloat(), c = q.nextFloat(), e = (i + 0.5f) / r.n;
            for (int k = 0; k < 6; k++) {
                d[o++] = a;
                d[o++] = b;
                d[o++] = c;
                d[o++] = e;
                d[o++] = QUAD_STREAK[2 * k];
                d[o++] = QUAD_STREAK[2 * k + 1];
            }
        }
        r.vbo = vbo(d);
    }

    private void makeDrops(Drops s, long seed) {
        Random q = new Random(seed);
        float[] d = new float[s.n * 6 * 8];
        int o = 0;
        for (int i = 0; i < s.n; i++) {
            float x = q.nextFloat(), y = q.nextFloat();
            float spd = s.spdMin + (s.spdMax - s.spdMin) * q.nextFloat();
            float rad = s.rMin + (s.rMax - s.rMin) * q.nextFloat();
            float rd = (i + 0.5f) / s.n, sd = q.nextFloat();
            for (int k = 0; k < 6; k++) {
                d[o++] = x;
                d[o++] = y;
                d[o++] = spd;
                d[o++] = rad;
                d[o++] = rd;
                d[o++] = sd;
                d[o++] = QUAD_DROP[2 * k];
                d[o++] = QUAD_DROP[2 * k + 1];
            }
        }
        s.vbo = vbo(d);
    }

    private static final float[] RC_FAR = {0.70f, 0.85f, 0.80f}, RC_MID = {0.75f, 0.88f, 0.85f}, RC_NEAR = {0.80f, 0.92f, 0.90f};
    private static final float[] RC_ROOF = {0.62f, 0.72f, 0.82f}, RC_SHEET = {0.55f, 0.65f, 0.76f};
    private static final float[] RC_WARM = {1.0f, 0.86f, 0.66f}, RC_GOLD = {1.0f, 0.72f, 0.30f};
    private static final float[] RC_GREEN = {0.78f, 0.90f, 0.80f}, RC_COOL = {0.78f, 0.88f, 0.92f};

    /** Whether the scene can use a photo or clip as its backdrop. */
    static boolean assetScene(String id) {
        return "puddle".equals(id) || "garden".equals(id) || "leaf".equals(id) || "avenue".equals(id) || "roof".equals(id);
    }

    private void setupOverlays(String id) {
        needRip = false;
        if ("canopy".equals(id) || "autumn".equals(id)) {
            // the rain is drawn inside the scene shader (radial, toward the camera); only lens-glass drops are overlays
            rains = new Rain[0];
            drops = new Drops[] {
                new Drops(9, 1, 0.020f, 0.036f, 0f, 0f, 0f),
                new Drops(34, 1, 0.007f, 0.014f, 0f, 0f, 0f),
                new Drops(5, 0, 0.018f, 0.030f, 0.010f, 0.022f, 3.0f),
            };
        } else if ("garden".equals(id)) {
            needRip = true;
            ripYH = 0.30f;
            ripBase = 10f;
            ripAmp = 0.8f;
            rains = new Rain[] {
                new Rain(480, 0.62f, -0.62f, 0.45f, 0.07f, 0.0030f, 0.28f, 5f, 0f, RC_FAR),
                new Rain(200, 0.62f, -0.62f, 0.75f, 0.10f, 0.0042f, 0.36f, 5f, 0f, RC_MID),
                new Rain(70, 0.62f, -0.62f, 1.20f, 0.16f, 0.0065f, 0.50f, 5f, 0f, RC_NEAR).soft(0.55f),
            };
            drops = new Drops[] {
                new Drops(20, 0, 0.032f, 0.056f, 0.012f, 0.026f, 3.5f),
                new Drops(44, 0, 0.016f, 0.028f, 0.016f, 0.034f, 2.5f),
                new Drops(90, 1, 0.006f, 0.012f, 0f, 0f, 0f),
            };
        } else if ("roof".equals(id)) {
            rains = new Rain[] {
                new Rain(520, 0.36f, -0.15f, 0.40f, 0.07f, 0.0030f, 0.26f, 5f, 0f, RC_ROOF),
                new Rain(280, 0.36f, -0.25f, 0.65f, 0.10f, 0.0040f, 0.34f, 5f, 0f, RC_ROOF),
                new Rain(110, 0.36f, -0.37f, 1.00f, 0.15f, 0.0055f, 0.45f, 5f, 0f, RC_ROOF).soft(0.6f),
                new Rain(420, 0.36f, -0.40f, 1.90f, 0.45f, 0.0075f, 0.30f, 1.2f, 1f, RC_SHEET),
            };
            drops = new Drops[0];
        } else if ("puddle".equals(id)) {
            needRip = true;
            ripYH = 0.45f;
            ripBase = 12f;
            ripAmp = 1f;
            rains = new Rain[] {
                new Rain(220, 0.62f, -0.62f, 0.45f, 0.06f, 0.0022f, 0.24f, 5f, 0f, RC_WARM),
                new Rain(110, 0.62f, -0.62f, 0.75f, 0.09f, 0.0034f, 0.30f, 5f, 0f, RC_WARM),
                new Rain(36, 0.62f, -0.62f, 1.20f, 0.16f, 0.0070f, 0.40f, 5f, 0f, RC_WARM).soft(0.5f),
            };
            drops = new Drops[0];
        } else if ("avenue".equals(id)) {
            needRip = true;
            ripYH = 0.34f;
            ripBase = 8f;
            ripAmp = 0.6f;
            rains = new Rain[] {
                new Rain(400, 0.62f, -0.62f, 0.50f, 0.07f, 0.0026f, 0.26f, 5f, 0f, RC_COOL),
                new Rain(180, 0.62f, -0.62f, 0.80f, 0.11f, 0.0040f, 0.32f, 5f, 0f, RC_COOL),
                new Rain(50, 0.62f, -0.62f, 1.20f, 0.18f, 0.0075f, 0.40f, 5f, 0f, RC_COOL).soft(0.5f),
            };
            drops = new Drops[0];
        } else if ("leaf".equals(id)) {
            rains = new Rain[] {
                new Rain(500, 0.62f, -0.62f, 0.50f, 0.08f, 0.0030f, 0.30f, 4.5f, 0f, RC_GREEN),
                new Rain(200, 0.62f, -0.62f, 0.85f, 0.14f, 0.0060f, 0.26f, 4f, 0f, RC_GREEN).soft(0.6f),
                new Rain(70, 0.62f, -0.62f, 1.30f, 0.26f, 0.0110f, 0.24f, 3.5f, 0f, RC_GREEN).soft(0.95f),
                new Rain(14, 0.62f, -0.62f, 0.9f, 0.05f, 0.030f, 0.14f, 3f, 0f, RC_GREEN).soft(1f),
            };
            drops = new Drops[] {
                new Drops(18, 0, 0.035f, 0.065f, 0.010f, 0.024f, 3.5f),
                new Drops(34, 1, 0.018f, 0.040f, 0f, 0f, 0f),
                new Drops(90, 1, 0.006f, 0.014f, 0f, 0f, 0f),
            };
        } else if ("gold".equals(id)) {
            rains = new Rain[] {
                new Rain(1500, 0.10f, -0.30f, 0.20f, 0.07f, 0.0016f, 0.34f, 3f, 0f, RC_GOLD).span(0.62f).shim(1f).wind(0.4f),
                new Rain(1100, 0.10f, -0.30f, 0.30f, 0.10f, 0.0022f, 0.40f, 3f, 0f, RC_GOLD).span(0.62f).shim(1f).wind(0.4f),
                new Rain(500, 0.10f, -0.30f, 0.45f, 0.14f, 0.0036f, 0.46f, 3f, 0f, RC_GOLD).span(0.62f).shim(1f).wind(0.4f).soft(0.5f),
            };
            drops = new Drops[0];
        } else {
            rains = new Rain[0];
            drops = new Drops[0];
        }
        long seed = 11;
        for (Rain r : rains) makeRain(r, seed++);
        for (Drops s : drops) makeDrops(s, seed++);
    }

    // ---- video -------------------------------------------------------------------------------

    private void startVideo(String path) {
        stopVideo();
        try {
            MediaPlayer m = new MediaPlayer();
            m.setDataSource(path);
            m.setSurface(surface);
            m.setVolume(0f, 0f);
            m.setLooping(true);
            m.prepare();
            if (m.getVideoWidth() > 1920 || m.getVideoHeight() > 1080) {
                Log.w(TAG, "clip is " + m.getVideoWidth() + "x" + m.getVideoHeight() + ", above 1080p; run tune-firetv.sh meditation to re-encode");
            }
            if (m.getVideoHeight() > 0) vidAspect = m.getVideoWidth() / (float) m.getVideoHeight();
            m.start();
            mp = m;
            videoOk = true;
            Log.i(TAG, "clip playing: " + path);
        } catch (Exception e) {
            Log.w(TAG, "clip failed, using generated scene: " + e);
            videoOk = false;
            mp = null;
        }
    }

    private void stopVideo() {
        videoOk = false;
        MediaPlayer m = mp;
        mp = null;
        if (m != null) {
            try {
                m.stop();
            } catch (RuntimeException ignored) {
                // not started
            }
            m.release();
        }
    }

    private void releaseVideoGl() {
        stopVideo();
        if (surface != null) surface.release();
        if (st != null) st.release();
        surface = null;
        st = null;
    }

    /** Called from the UI thread when the activity pauses. */
    void release() {
        stopVideo();
    }

    private static float lerpTo(float cur, float target, float dt, float tau) {
        return cur + (target - cur) * (1f - (float) Math.exp(-dt / tau));
    }

    // ---- frame -------------------------------------------------------------------------------

    private int loc(int p, String n) {
        return GLES20.glGetUniformLocation(p, n);
    }

    /** The uniforms every scene program shares. */
    private void common(int p, float rw, float rh, float bright, float orb) {
        GLES20.glUniform2f(loc(p, "uRes"), rw, rh);
        GLES20.glUniform1f(loc(p, "uT"), tSec);
        GLES20.glUniform1f(loc(p, "uRain"), rain);
        GLES20.glUniform1f(loc(p, "uSpeed"), speed);
        GLES20.glUniform1f(loc(p, "uSize"), size);
        GLES20.glUniform1f(loc(p, "uBreath"), breath);
        GLES20.glUniform1f(loc(p, "uSpark"), spark);
        GLES20.glUniform1f(loc(p, "uBright"), bright);
        GLES20.glUniform1f(loc(p, "uThunder"), thunder);
        GLES20.glUniform1f(loc(p, "uLite"), lite ? 1f : 0f);
        GLES20.glUniform1f(loc(p, "uOrb"), orb);
        GLES20.glUniform1f(loc(p, "uHasVid"), builtVid ? 1f : 0f);
        GLES20.glUniform3f(loc(p, "uCam"), camX, camY, camZ);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTex);
        GLES20.glUniform1i(loc(p, "uNoise"), 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoTex);
        GLES20.glUniform1i(loc(p, "uPhoto"), 2);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoBTex);
        GLES20.glUniform1i(loc(p, "uPhotoB"), 3);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, normTex);
        GLES20.glUniform1i(loc(p, "uRip"), 4);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private void drawFull(int p) {
        int a = GLES20.glGetAttribLocation(p, "aPos");
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo);
        GLES20.glEnableVertexAttribArray(a);
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 8, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(a);
    }

    // ---- ripples: wave heightfield, drop injections, crown splashes ------------------------------

    private void inject(float x, float y, float amp, float rad) {
        if (injN >= 16) return;
        injQ[injN * 4] = x;
        injQ[injN * 4 + 1] = y;
        injQ[injN * 4 + 2] = amp;
        injQ[injN * 4 + 3] = rad;
        injN++;
    }

    private void spawnDrop() {
        float sx = 0.08f + 0.84f * rnd.nextFloat();
        float sy = 0.12f + 0.78f * (float) Math.pow(rnd.nextFloat(), 1.3);
        boolean crown = sy < 0.40f && rnd.nextFloat() < 0.35f;
        float a = (0.05f + 0.08f * rnd.nextFloat()) * (crown ? 1.6f : 1f) * ripAmp;
        float r = (0.008f + 0.006f * rnd.nextFloat()) * (crown ? 1.4f : 1f);
        inject(sx, sy, -a, r);
        // a second, lopsided dip so the rings are never perfect circles
        inject(sx + (rnd.nextFloat() - 0.5f) * 0.006f, sy + (rnd.nextFloat() - 0.5f) * 0.006f, -a * 0.45f, r * 1.8f);
        if (crown) crown(sx, sy);
    }

    /** A crown splash: 6-12 micro-droplets thrown in an arc, each landing as a tiny ripple. */
    private void crown(float sx, float sy) {
        float z = sy / 0.045f, d = 1f / z - 0.05f;
        float asp = w / (float) h;
        float uy = ripYH - d, ux = 0.5f + ((sx - 0.5f) / (z * 0.18f)) / asp;
        if (d <= 0.02f || ux < 0.03f || ux > 0.97f || uy < 0.02f) return;
        float sc = Math.min(1f, 2f / z);
        int n = 6 + rnd.nextInt(7);
        for (int i = 0; i < n && ptN < MAXP; i++) {
            float ang = (float) Math.toRadians(25 + 130 * (i + rnd.nextFloat() * 0.8f) / n);
            float sp = (0.10f + 0.12f * rnd.nextFloat()) * sc;
            float vx = (float) Math.cos(ang) * sp * 0.8f, vy = (float) Math.sin(ang) * sp * 1.4f;
            float life = 2f * vy / 1.0f;
            int o = ptN * 8;
            pt[o] = ux;
            pt[o + 1] = uy;
            pt[o + 2] = vx;
            pt[o + 3] = vy;
            pt[o + 4] = 0f;
            pt[o + 5] = life;
            pt[o + 6] = sx + vx * life * asp * z * 0.18f;
            pt[o + 7] = sy;
            ptN++;
        }
    }

    private void stepParticles(float dt) {
        for (int i = 0; i < ptN; ) {
            int o = i * 8;
            pt[o + 4] += dt;
            pt[o] += pt[o + 2] * dt;
            pt[o + 1] += pt[o + 3] * dt;
            pt[o + 3] -= 1.0f * dt;
            if (pt[o + 4] >= pt[o + 5]) {
                inject(pt[o + 6], pt[o + 7], -0.02f * ripAmp, 0.005f);
                ptN--;
                System.arraycopy(pt, ptN * 8, pt, o, 8);
            } else {
                i++;
            }
        }
    }

    private void simulate(float dt) {
        float rate = ripBase * (0.35f + 0.9f * rain) * (0.7f + 0.8f * spark);
        int guard = 0;
        while (tSec >= nextDrop && guard++ < 6) {
            spawnDrop();
            nextDrop += -Math.log(1.0 - rnd.nextDouble()) / Math.max(0.5f, rate);
        }
        if (tSec > nextDrop + 2) nextDrop = tSec;
        stepParticles(dt);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4); // nothing may sample the targets we draw into
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUseProgram(simProg);
        GLES20.glViewport(0, 0, SIM, SIM);
        int steps = lite ? 1 : 2;
        for (int s = 0; s < steps; s++) {
            int dst = 1 - ripCur;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, simFbo[dst]);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, simTex[ripCur]);
            GLES20.glUniform1i(loc(simProg, "uPrev"), 0);
            int take = Math.min(4, injN);
            java.util.Arrays.fill(inj, 0f);
            System.arraycopy(injQ, 0, inj, 0, take * 4);
            System.arraycopy(injQ, take * 4, injQ, 0, (injN - take) * 4);
            injN -= take;
            GLES20.glUniform4fv(loc(simProg, "uInj[0]"), 4, inj, 0);
            drawFull(simProg);
            ripCur = dst;
        }
        if (normProg != 0) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, normFbo);
            GLES20.glUseProgram(normProg);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, simTex[ripCur]);
            GLES20.glUniform1i(loc(normProg, "uSim"), 0);
            drawFull(normProg);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, w, h);
    }

    private void drawSplash(float bright) {
        if (ptN == 0 || ptProg == 0) return;
        for (int i = 0; i < ptN; i++) {
            ptOut[i * 3] = pt[i * 8];
            ptOut[i * 3 + 1] = pt[i * 8 + 1];
            ptOut[i * 3 + 2] = 0.9f * (1f - 0.5f * pt[i * 8 + 4] / pt[i * 8 + 5]);
        }
        FloatBuffer fb = ByteBuffer.allocateDirect(ptN * 12).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(ptOut, 0, ptN * 3).position(0);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
        GLES20.glUseProgram(ptProg);
        GLES20.glUniform1f(loc(ptProg, "uPx"), Math.max(2f, 3f * h / 1080f));
        GLES20.glUniform1f(loc(ptProg, "uBright"), bright);
        int a = GLES20.glGetAttribLocation(ptProg, "aP");
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glEnableVertexAttribArray(a);
        GLES20.glVertexAttribPointer(a, 3, GLES20.GL_FLOAT, false, 12, fb);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, ptN);
        GLES20.glDisableVertexAttribArray(a);
    }

    private static boolean isVideo(String path) {
        String n = path.toLowerCase(java.util.Locale.US);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm") || n.endsWith(".mov");
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = SystemClock.elapsedRealtime();
        float dt = last == 0 ? 0.016f : Math.min(0.1f, (now - last) / 1000f);
        last = now;
        tSec = (now - t0) / 1000f;

        String want = pendingScene;
        String clip = assetScene(want) ? videoPath : null;
        if (("leaf".equals(want) || "avenue".equals(want)) && clip == null) want = "garden"; // hidden scenes need their photo
        boolean isVid = clip != null && isVideo(clip), isImg = clip != null && !isVid;
        String key = want + "|" + (clip == null ? "" : clip);
        if (!built || !key.equals(builtKey)) {
            builtKey = key;
            boolean vok = false, pok = false;
            if (isVid) {
                startVideo(clip);
                vok = videoOk;
            } else {
                stopVideo();
            }
            if (isImg) pok = loadPhoto(clip);
            boolean back = vok || pok;
            if (back) bgIx = 0;
            else if ("garden".equals(want) || "roof".equals(want) || "fireflies".equals(want)) bgIx = 3;
            else bgIx = 1;
            if (lite) bgIx = BG_SCALE.length - 1;
            allocBg();
            setupOverlays(want);
            build(want, vok, back);
            rainScale = vok ? 0.3f : 1f; // a clip that already has rain: 70% fewer overlay streaks
            if (needRip) {
                clearSim();
                nextDrop = tSec + 0.5;
            }
            scene = want;
            govAfter = now + 6000;
        }
        if (finProg == 0 || bgProg == 0) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return;
        }

        // audio -> slow, smooth drivers
        boolean heard = audio != null && audio.heard();
        float loudT = heard ? audio.loud() : 0.5f;
        float lowT = heard ? audio.low() : 0.5f;
        float highT = heard ? audio.high() : 0.3f;
        rain = lerpTo(rain, 0.30f + 0.70f * loudT, dt, 8f); // rain follows loudness, smoothed over 8 s so it suits rain recordings
        speed = lerpTo(speed, 0.80f + 0.45f * loudT, dt, 8f);
        size = lerpTo(size, 0.85f + 0.50f * loudT, dt, 8f);
        breath = lerpTo(breath, (lowT - 0.5f) * 2f, dt, 4f);
        spark = lerpTo(spark, 0.15f + 0.6f * highT, dt, 0.4f);

        float sleep = sleepFactor();
        if (now - capAt > 10000) {
            capAt = now;
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            cap = (hour >= 21 || hour < 5) ? 0.3f : 0.6f;
        }
        dip = lerpTo(dip, dipTarget, dt, 0.9f); // the 5 s scene change: dark in about 2.5 s, back up in about 2.5 s
        float bright = cap * (0.03f + 0.97f * sleep) * dip;
        float savedRain = rain;
        lenMul = (0.85f + 0.35f * savedRain) * (0.9f + 0.1f * intensity);
        rain = Math.min(1f, rain * intensity * (0.15f + 0.85f * sleep)); // density fades with the sleep timer

        // slow camera: a drift, and a forward dolly on the avenue
        float tw = tSec;
        camX = 0.005f * (float) Math.sin(tw * 0.05);
        camY = 0.003f * (float) Math.sin(tw * 0.037 + 1.0);
        camZ = "puddle".equals(want) ? 1.03f : 1.02f;
        if ("avenue".equals(want)) camZ = 1.0f + 0.06f * (0.5f - 0.5f * (float) Math.cos(tw * 2 * Math.PI / 120.0));

        // soft distant glow: rises over ~5 s, falls over ~7 s, every 70-160 s, never a flash
        if (thunderOn && !"orb".equals(want)) {
            if (thunderNext == 0) thunderNext = now + 20000 + rnd.nextInt(60000);
            if (thunderT < 0 && now > thunderNext) thunderT = 0;
            if (thunderT >= 0) {
                thunderT += dt;
                float x = thunderT < 5f ? thunderT / 5f : Math.max(0f, 1f - (thunderT - 5f) / 7f);
                thunder = x * x * (3f - 2f * x);
                if (thunderT > 12f) {
                    thunderT = -1;
                    thunder = 0;
                    thunderNext = now + 70000 + rnd.nextInt(90000);
                }
            }
        } else {
            thunder = lerpTo(thunder, 0f, dt, 1f);
            thunderT = -1;
        }

        float orb = orbPhase(tSec, null);

        if (st != null && videoOk && frameReady) {
            frameReady = false;
            try {
                st.updateTexImage();
                st.getTransformMatrix(stm);
            } catch (RuntimeException e) {
                Log.w(TAG, "updateTexImage: " + e);
            }
        }

        GLES20.glDisable(GLES20.GL_BLEND);
        if (needRip && ripOk && simProg != 0) simulate(dt);
        // pass 1: the backdrop and water effects, in a small texture (960x540 for photo scenes)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, bgW, bgH);
        GLES20.glUseProgram(bgProg);
        common(bgProg, bgW, bgH, bright, orb);
        if (builtVid) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex);
            GLES20.glUniform1i(loc(bgProg, "uTex"), 0);
            GLES20.glUniformMatrix4fv(loc(bgProg, "uST"), 1, false, stm, 0);
            float sa = w / (float) h;
            GLES20.glUniform2f(loc(bgProg, "uCover"), vidAspect > sa ? sa / vidAspect : 1f, vidAspect > sa ? 1f : vidAspect / sa);
        }
        drawFull(bgProg);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, w, h);

        // pass 2: show it, then the overlays
        GLES20.glUseProgram(finProg);
        common(finProg, w, h, bright, orb);
        bindBg(finProg);
        drawFull(finProg);

        if (needRip) drawSplash(bright);

        float aspect = w / (float) h;
        float vib = Math.max(0f, breath) * 0.0019f; // up to ~2 px of tremble on the water curtain
        if (rainProg != 0 && rains.length > 0) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
            GLES20.glUseProgram(rainProg);
            for (int i = 0; i < rains.length; i++) {
                Rain r = rains[i];
                if (lite && i == 0) continue;
                int n = lite ? r.n / 2 : r.n;
                GLES20.glUniform1f(loc(rainProg, "uT"), tSec);
                GLES20.glUniform1f(loc(rainProg, "uAspect"), aspect);
                GLES20.glUniform1f(loc(rainProg, "uSpd"), r.spd * speed);
                GLES20.glUniform1f(loc(rainProg, "uLen"), r.len * lenMul);
                GLES20.glUniform1f(loc(rainProg, "uWid"), r.wid * size);
                GLES20.glUniform1f(loc(rainProg, "uYTop"), r.yTop);
                GLES20.glUniform1f(loc(rainProg, "uYEnd"), r.yEnd);
                GLES20.glUniform1f(loc(rainProg, "uDens"), Math.min(1f, r.gate > 0 ? 0.3f + 0.7f * rain : rain) * (r.gate > 0 ? 1f : rainScale));
                GLES20.glUniform1f(loc(rainProg, "uGate"), r.gate);
                GLES20.glUniform1f(loc(rainProg, "uSpan"), r.span);
                GLES20.glUniform1f(loc(rainProg, "uShim"), r.shim);
                GLES20.glUniform1f(loc(rainProg, "uVib"), vib);
                GLES20.glUniform1f(loc(rainProg, "uWind"), r.wind);
                GLES20.glUniform1f(loc(rainProg, "uSoft"), r.soft);
                GLES20.glUniform3f(loc(rainProg, "uColor"), r.color[0], r.color[1], r.color[2]);
                GLES20.glUniform1f(loc(rainProg, "uAlpha"), r.alpha);
                GLES20.glUniform1f(loc(rainProg, "uTail"), r.tail);
                GLES20.glUniform1f(loc(rainProg, "uBright"), bright);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, r.vbo);
                int aA = GLES20.glGetAttribLocation(rainProg, "aA"), aC = GLES20.glGetAttribLocation(rainProg, "aC");
                GLES20.glEnableVertexAttribArray(aA);
                GLES20.glEnableVertexAttribArray(aC);
                GLES20.glVertexAttribPointer(aA, 4, GLES20.GL_FLOAT, false, 24, 0);
                GLES20.glVertexAttribPointer(aC, 2, GLES20.GL_FLOAT, false, 24, 16);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, n * 6);
                GLES20.glDisableVertexAttribArray(aA);
                GLES20.glDisableVertexAttribArray(aC);
            }
        }
        if (dropProg != 0 && drops.length > 0) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(dropProg);
            common(dropProg, w, h, bright, orb);
            bindBg(dropProg);
            for (Drops s : drops) {
                int n = lite ? s.n / 2 : s.n;
                GLES20.glUniform1f(loc(dropProg, "uAspect"), aspect);
                GLES20.glUniform1f(loc(dropProg, "uMode"), s.mode);
                GLES20.glUniform1f(loc(dropProg, "uRMul"), size);
                GLES20.glUniform1f(loc(dropProg, "uTrail"), s.trail);
                GLES20.glUniform1f(loc(dropProg, "uRainAmt"), 0.45f + 0.55f * rain);
                GLES20.glUniform1f(loc(dropProg, "uSpeedMul"), speed);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, s.vbo);
                int aA = GLES20.glGetAttribLocation(dropProg, "aA"), aB = GLES20.glGetAttribLocation(dropProg, "aB"),
                    aC = GLES20.glGetAttribLocation(dropProg, "aC");
                GLES20.glEnableVertexAttribArray(aA);
                GLES20.glEnableVertexAttribArray(aB);
                GLES20.glEnableVertexAttribArray(aC);
                GLES20.glVertexAttribPointer(aA, 4, GLES20.GL_FLOAT, false, 32, 0);
                GLES20.glVertexAttribPointer(aB, 2, GLES20.GL_FLOAT, false, 32, 16);
                GLES20.glVertexAttribPointer(aC, 2, GLES20.GL_FLOAT, false, 32, 24);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, n * 6);
                GLES20.glDisableVertexAttribArray(aA);
                GLES20.glDisableVertexAttribArray(aB);
                GLES20.glDisableVertexAttribArray(aC);
            }
        }
        GLES20.glDisable(GLES20.GL_BLEND);
        rain = savedRain;

        frames++;
        if (statAt == 0) statAt = now;
        if (now - statAt >= 5000) {
            float fps = frames * 1000f / (now - statAt);
            frames = 0;
            statAt = now;
            if (listener != null) listener.onStats(scene, fps, bgW, bgH);
            if (fps < 45f && now > govAfter && bgIx < BG_SCALE.length - 1) {
                bgIx++;
                Log.i(TAG, "fps " + fps + " < 45: background render scale -> " + BG_SCALE[bgIx]);
                allocBg();
            }
        }
    }

    /** Binds the small rendered backdrop that the final and droplet shaders read. */
    private void bindBg(int p) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTex);
        GLES20.glUniform1i(loc(p, "uBg"), 0);
    }
}
