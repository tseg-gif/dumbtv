package tv.dumbtv.home;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Cinema: a pre-rendered clip drawn full screen from an external texture, with a live beat layer and a comfort grade in
 * one shader. Two MediaPlayers allow a crossfade between clips; if the device cannot decode two 1080p streams smoothly
 * (the playing clip falls under 50 fps while the second one runs) it falls back to one player and a short dip through
 * dark with a zoom blur. GL calls happen on the GL thread; MediaPlayer calls on the main thread.
 */
final class CinemaGl {
    private static final String TAG = "dumbcinema";

    private static final String VS = "attribute vec2 aP;\n"
        + "varying vec2 vUV;\n"
        + "void main() { vUV = aP * 0.5 + 0.5; gl_Position = vec4(aP, 0.0, 1.0); }\n";

    // beat layer + comfort grade: zoom punch and sideways drift (a transform), a restrained highlight bloom, crisp hat sparkles,
    // then luminance cap with a soft knee, a saturation cap, and the dark-mode grade
    private static final String FS = "#extension GL_OES_EGL_image_external : require\n"
        + "precision mediump float;\n"
        + "varying vec2 vUV;\n"
        + "uniform samplerExternalOES uTex;\n"
        + "uniform mat4 uMat;\n"
        + "uniform vec2 uRes, uDrift;\n"
        + "uniform float uAlpha, uZoom, uBloom, uHat, uTime, uNight, uBlur, uGain;\n"
        + "float h21(vec2 p) { vec3 q = fract(vec3(p.xyx) * 0.1031); q += dot(q, q.yzx + 33.33); return fract((q.x + q.y) * q.z); }\n"
        + "vec3 tap(vec2 uv) { uv = clamp(uv, 0.002, 0.998); return texture2D(uTex, (uMat * vec4(uv, 0.0, 1.0)).xy).rgb; }\n"
        + "void main() {\n"
        + "  vec2 c0 = vec2(0.5);\n"
        + "  vec2 uv = (vUV - c0) / uZoom + c0 + uDrift;\n"
        + "  vec3 c = tap(uv);\n"
        + "  if (uBlur > 0.001) {\n"
        + "    vec2 dir = (uv - c0) * uBlur;\n"
        + "    c = (c + tap(uv - dir * 0.5) + tap(uv - dir) + tap(uv - dir * 1.5)) * 0.25;\n"
        + "  }\n"
        + "  if (uBloom > 0.002) {\n"
        + "    vec2 px = 5.0 / uRes;\n"
        + "    vec3 b = tap(uv + vec2(px.x, 0.0)) + tap(uv - vec2(px.x, 0.0)) + tap(uv + vec2(0.0, px.y)) + tap(uv - vec2(0.0, px.y));\n"
        + "    b = max(b * 0.25 - 0.45, vec3(0.0));\n"
        + "    c += b * uBloom;\n"
        + "  }\n"
        + "  if (uHat > 0.01) {\n"
        + "    vec2 px = vUV * uRes;\n"
        + "    vec2 cell = floor(px / 44.0);\n"
        + "    float h = h21(cell + floor(uTime * 3.0));\n"
        + "    vec2 ctr = (cell + 0.2 + 0.6 * vec2(h21(cell + 7.0), h21(cell + 13.0))) * 44.0;\n"
        + "    float d = length(px - ctr);\n"
        + "    c += vec3(1.0, 0.96, 0.86) * step(0.94, h) * uHat * smoothstep(2.2, 1.2, d) * 0.55;\n"
        + "  }\n"
        + "  // comfort grade: luminance cap 0.8 (0.5 at night) with a soft knee, saturation at most 0.6\n"
        + "  float cap = mix(0.80, 0.50, uNight);\n"
        + "  float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));\n"
        + "  float knee = cap * 0.72;\n"
        + "  if (lum > knee) {\n"
        + "    float l2 = knee + (cap - knee) * (1.0 - exp(-(lum - knee) / (cap - knee)));\n"
        + "    c *= l2 / lum;\n"
        + "  }\n"
        + "  float mx = max(c.r, max(c.g, c.b)), mn = min(c.r, min(c.g, c.b));\n"
        + "  float s = (mx - mn) / max(mx, 0.001);\n"
        + "  if (s > 0.60) c = mix(vec3(mx), c, 0.60 / s);\n"
        + "  c *= mix(1.0, 0.82, uNight) * uGain;\n"
        + "  gl_FragColor = vec4(c, uAlpha);\n"
        + "}\n";

    private static final class P {
        int tex;
        SurfaceTexture st;
        Surface surf;
        MediaPlayer mp;
        volatile boolean ready, frame, live;
        volatile int frames;
        final float[] mat = new float[16];
        boolean got;
        String name = "";
        long winAt;
        float fps = 60f;
        int winFrames;
    }

    private final Context ctx;
    private final GLSurfaceView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final P[] p = {new P(), new P()};
    private int cur = -1, out = -1;
    private int prog, vbo;
    private boolean two = true, twoTested;
    private int dipDir; // single-player mode: 1 darkening, -1 coming back, 0 none
    private float dip;
    private CinemaLib.Clip pendingClip;
    private long startedAt;
    private CinemaLib.Clip curClip;
    private float punch, bloomEnv, driftV, driftDir = 1f;
    private long lastBloomMs;
    private final java.util.HashMap<String, Integer> locs = new java.util.HashMap<String, Integer>();
    private boolean glOk;

    CinemaGl(Context c, GLSurfaceView v) {
        ctx = c.getApplicationContext();
        view = v;
    }

    // ---- GL thread

    void glInit() {
        try {
            int vs = compile(GLES20.GL_VERTEX_SHADER, VS), fs = compile(GLES20.GL_FRAGMENT_SHADER, FS);
            prog = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog, vs);
            GLES20.glAttachShader(prog, fs);
            GLES20.glBindAttribLocation(prog, 0, "aP");
            GLES20.glLinkProgram(prog);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                Log.w(TAG, "cinema shader failed: " + GLES20.glGetProgramInfoLog(prog));
                glOk = false;
                return;
            }
            locs.clear();
            FloatBuffer fb = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
            int[] b = new int[1];
            GLES20.glGenBuffers(1, b, 0);
            vbo = b[0];
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 32, fb, GLES20.GL_STATIC_DRAW);
            for (int i = 0; i < 2; i++) {
                final P q = p[i];
                int[] t = new int[1];
                GLES20.glGenTextures(1, t, 0);
                q.tex = t[0];
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, q.tex);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                if (q.st != null) q.st.release();
                q.st = new SurfaceTexture(q.tex);
                q.st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                    @Override
                    public void onFrameAvailable(SurfaceTexture s) {
                        q.frame = true;
                        q.frames++;
                    }
                });
                q.got = false;
            }
            cur = out = -1;
            glOk = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "cinema init: " + e);
            glOk = false;
        }
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.w(TAG, "shader failed: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private int loc(String n) {
        Integer i = locs.get(n);
        if (i == null) {
            i = GLES20.glGetUniformLocation(prog, n);
            locs.put(n, i);
        }
        return i;
    }

    boolean usable() {
        return glOk;
    }

    boolean active() {
        return glOk && (cur >= 0 || out >= 0);
    }

    boolean curReady() {
        return cur >= 0 && p[cur].ready && p[cur].got;
    }

    String clipName() {
        return curClip == null ? "-" : curClip.id;
    }

    String clipTags() {
        return curClip == null ? "-" : curClip.tagText();
    }

    String mode() {
        return two ? "2 players" : "1 player+dip";
    }

    float curFps() {
        return cur >= 0 ? p[cur].fps : 0f;
    }

    /** A new Cinema scene begins with this clip (GL thread). The clip that was playing becomes the outgoing one. */
    void start(CinemaLib.Clip clip) {
        if (!glOk || clip == null) return;
        if (cur >= 0 && !two) { // one player: dip through dark, then swap
            pendingClip = clip;
            dipDir = 1;
            return;
        }
        if (cur >= 0) {
            if (out >= 0) releasePlayer(out);
            out = cur;
        }
        int idx = out == 0 ? 1 : 0;
        cur = idx;
        beginPlayer(idx, clip);
    }

    /** The scene left Cinema: the clip fades out with the old scene's crossfade, then goes (GL thread). */
    void leave() {
        if (cur < 0) return;
        if (out >= 0) releasePlayer(out);
        out = cur;
        cur = -1;
        curClip = null;
    }

    /** The crossfade is over: the outgoing clip's decoder is not needed any more. */
    void dropOut() {
        if (out >= 0) {
            releasePlayer(out);
            out = -1;
        }
    }

    private void beginPlayer(final int idx, final CinemaLib.Clip clip) {
        final P q = p[idx];
        q.ready = false;
        q.got = false;
        q.frame = false;
        q.frames = 0;
        q.winFrames = 0;
        q.winAt = 0;
        q.name = clip.id;
        curClip = clip;
        startedAt = SystemClock.elapsedRealtime();
        final Surface old = q.surf;
        q.surf = new Surface(q.st);
        final Surface sf = q.surf;
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (old != null) old.release();
                    MediaPlayer m = new MediaPlayer();
                    m.setSurface(sf);
                    m.setDataSource(clip.path);
                    m.setLooping(true);
                    m.setVolume(0f, 0f);
                    m.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(MediaPlayer x) {
                            q.ready = true;
                            q.live = true;
                            x.start();
                            Log.i(TAG, "clip ready " + clip.id + " after " + (SystemClock.elapsedRealtime() - startedAt) + " ms");
                        }
                    });
                    m.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        @Override
                        public boolean onError(MediaPlayer x, int what, int extra) {
                            Log.w(TAG, "player error " + what + "/" + extra + " on " + clip.id);
                            q.ready = false;
                            return true;
                        }
                    });
                    q.mp = m;
                    m.prepareAsync();
                } catch (Exception e) {
                    Log.w(TAG, "clip unavailable " + clip.id + ": " + e);
                }
            }
        });
    }

    private void releasePlayer(int idx) {
        final P q = p[idx];
        q.ready = false;
        q.live = false;
        final MediaPlayer m = q.mp;
        q.mp = null;
        if (m != null) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        m.release();
                    } catch (RuntimeException e) {
                        // already gone
                    }
                }
            });
        }
    }

    void glRelease() {
        for (int i = 0; i < 2; i++) releasePlayer(i);
        cur = out = -1;
        glOk = false;
    }

    /**
     * One frame (GL thread): updates the textures, runs the single-player dip, and draws the outgoing and current clips over
     * what is already on screen.
     *
     * @param eCur  how much of the current clip shows (the scene crossfade)
     * @param outOver true when the outgoing clip is being replaced by another clip (it stays opaque underneath)
     */
    void draw(float dt, float eCur, boolean outOver, float kickEnv, boolean kickEdge, boolean downbeat, boolean snareEdge,
            float snareEnv, float hatEnv, float night, long nowMs, int w, int h) {
        if (!glOk) return;
        for (int i = 0; i < 2; i++) {
            P q = p[i];
            if (q.live && q.frame) {
                q.frame = false;
                try {
                    q.st.updateTexImage();
                    q.st.getTransformMatrix(q.mat);
                    q.got = true;
                } catch (RuntimeException e) {
                    Log.w(TAG, "updateTexImage: " + e);
                }
                q.winFrames++;
            }
            if (q.live) {
                if (q.winAt == 0) q.winAt = nowMs;
                if (nowMs - q.winAt >= 2000) {
                    q.fps = q.winFrames * 1000f / (nowMs - q.winAt);
                    q.winAt = nowMs;
                    q.winFrames = 0;
                }
            }
        }
        // is a second decode hurting the first? (only judged once, while both run and the first has been playing a while)
        if (two && !twoTested && cur >= 0 && out >= 0 && p[out].live && p[cur].live && p[cur].got && p[out].got
                && nowMs - startedAt > 3500) {
            twoTested = true;
            float f = Math.min(p[cur].fps, p[out].fps);
            Log.i(TAG, "two decodes at once: " + p[cur].fps + " and " + p[out].fps + " fps");
            if (f < 50f) {
                two = false;
                Log.w(TAG, "two concurrent decodes are not smooth: one player with a dip from now on");
            }
        }
        // single-player dip: dark in 0.2 s, swap the clip while dark, back in 0.2 s
        if (dipDir == 1) {
            dip = Math.min(1f, dip + dt / 0.2f);
            if (dip >= 1f && pendingClip != null) {
                if (cur >= 0) releasePlayer(cur);
                int idx = cur >= 0 ? cur : 0;
                cur = idx;
                beginPlayer(idx, pendingClip);
                pendingClip = null;
                dipDir = -2; // waiting for the new clip's first frame
            }
        } else if (dipDir == -2) {
            if (curReady()) dipDir = -1;
        } else if (dipDir == -1) {
            dip = Math.max(0f, dip - dt / 0.2f);
            if (dip <= 0f) dipDir = 0;
        }
        // beat layer envelopes
        if (kickEdge) {
            punch = downbeat ? 1.6f : 1f;
            if (nowMs - lastBloomMs >= 500) { // at most two light pulses a second
                lastBloomMs = nowMs;
                bloomEnv = 1f;
            }
        }
        punch *= (float) Math.exp(-dt * 11f); // about 250 ms to settle
        bloomEnv *= (float) Math.exp(-dt * 7f);
        if (snareEdge) driftDir = -driftDir;
        driftV = driftDir * 0.004f * snareEnv;

        float gain = 1f - dip;
        if (cur < 0 && out < 0) return;
        GLES20.glUseProgram(prog);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        float blur = dip * 0.10f, zoomDip = 1f + dip * 0.10f;
        if (out >= 0 && p[out].got) drawOne(p[out], outOver ? 1f : 1f - eCur, zoomDip, blur, gain, 0f, 0f, hatEnv, night, nowMs, w, h);
        if (cur >= 0 && p[cur].got) {
            drawOne(p[cur], Math.max(0f, Math.min(1f, eCur)), zoomDip, blur, gain, bloomEnv, driftV, hatEnv, night, nowMs, w, h);
        }
        GLES20.glDisableVertexAttribArray(0);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
    }

    private void drawOne(P q, float alpha, float zoomDip, float blur, float gain, float bloom, float drift, float hat, float night,
            long nowMs, int w, int h) {
        if (alpha <= 0.002f) return;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, q.tex);
        GLES20.glUniform1i(loc("uTex"), 0);
        GLES20.glUniformMatrix4fv(loc("uMat"), 1, false, q.mat, 0);
        GLES20.glUniform2f(loc("uRes"), w, h);
        GLES20.glUniform2f(loc("uDrift"), drift, 0f);
        GLES20.glUniform1f(loc("uAlpha"), alpha);
        GLES20.glUniform1f(loc("uZoom"), (1f + 0.03f * punch) * zoomDip);
        GLES20.glUniform1f(loc("uBloom"), 0.10f * bloom);
        GLES20.glUniform1f(loc("uHat"), Math.min(1f, hat));
        GLES20.glUniform1f(loc("uTime"), (nowMs % 100000) / 1000f);
        GLES20.glUniform1f(loc("uNight"), night);
        GLES20.glUniform1f(loc("uBlur"), blur);
        GLES20.glUniform1f(loc("uGain"), gain);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    String debug(float since, float target) {
        return "clip=" + clipName() + " tags=" + clipTags() + "\nleft=" + Math.max(0, Math.round(target - since)) + "s mode=" + mode()
            + " videoFps=" + Math.round(curFps());
    }
}
