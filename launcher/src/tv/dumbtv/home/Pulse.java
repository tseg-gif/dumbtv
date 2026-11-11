package tv.dumbtv.home;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The music-reactive layer behind the tiles, drawn from the FFT of the audio the TV is playing.
 *
 * Nothing here is a fixed "visual". Every scene is composed: 2 or 3 layers picked from sixteen
 * kinds (bars, wave, mountains, ring, orbits, fountain, warp, ripples, LED matrix, spirograph,
 * lightning, tunnel, aurora, fireworks, helix, blob), each with its own variant, then a color
 * scheme (eight), a symmetry (none, or 2 to 8 copies, optionally mirrored) and a spin direction.
 * That is tens of thousands of looks. The song's title seeds the dice, and the live audio
 * (tempo from the kicks, how bright or bassy it sounds) steers which layers fit the song.
 * Scenes change on a drop or after 20 to 50 seconds, and a recently used combination is not
 * picked again.
 *
 * Kicks, snares and hats come from spectral flux with adaptive thresholds. The capture is
 * restarted if frames stop arriving. It needs RECORD_AUDIO, which adb grants.
 */
final class Pulse extends View implements Visualizer.OnDataCaptureListener {
    interface Listener {
        /** Called on the main thread when music starts or stops. */
        void onActive(boolean active);
    }

    private static final boolean CLOUDS_OFF = true;
    private static final int BARS = 64;
    private static final int T = 28;
    private static final String[] LN = {"bars", "wave", "mountains", "ring", "orbit", "fountain",
        "warp", "ripples", "matrix", "spiro", "lightning", "tunnel", "aurora", "fireworks",
        "helix", "blob", "synth", "beams", "rays", "-sky", "-peaks", "-clouds", "-sea", "-shore", "-weather", "-weave", "-city", "-meadow"};
    /** Layers that can be repeated around the center. */
    private static final boolean[] SYM = {false, false, false, true, true, false, false, false,
        false, true, false, true, false, false, false, true, false, false, true, false, false, false, false, false, false, false, false, false};
    /** 0 calm and soft, 1 fast and sharp: how well a layer suits the music. */
    private static final float[] CALM = {0.5f, 0.3f, 0.2f, 0.4f, 0.5f, 0.6f, 0.8f, 0.3f, 0.6f,
        0.4f, 0.9f, 0.5f, 0.2f, 0.8f, 0.4f, 0.3f, 0.45f, 0.7f, 0.55f, 0.3f, 0.2f, 0.2f, 0.4f, 0.3f, 0.4f, 0.3f, 0.3f, 0.2f};
    /** Draw order: backgrounds first, sparks and bolts last. */
    private static final int[] Z = {2, 2, 1, 4, 5, 6, 3, 3, 1, 4, 7, 3, 0, 6, 2, 3, 0, 1, 3, -1, 1, 0, 2, 3, 7, 4, 2, 3};
    /** Layers that show the spectrum itself; every scene gets at least one. */
    private static final boolean[] MAIN = {true, true, true, true, true, false, false, false, true,
        true, false, false, false, false, true, true, true, false, true, true, true, true, true, true, true, true, true, true};
    private static final int[] SCHEMES = {0, 0, 1, 2, 3, 5, 6, 7};

    // nature scenes: layers 19.. are drawn from a shared sky/time-of-day state
    private static final int NC = 8, RN = 24, RANGES = 4, NS = 80, WP = 100;
    /** Palette tables, indexed dusk, pastel, ocean, forest, sunset, moonlight; then day, dusk, night. */
    static final int[][] TOP = {
        {0x7C9CC4, 0x5E4B8B, 0x1B1838}, {0x9CC8E0, 0xB5A0D0, 0x2A3050}, {0x5E9DC0, 0x3F6E94, 0x0F2240},
        {0x7BA9A0, 0x56806E, 0x12261F}, {0x78A6C8, 0xB5605E, 0x241A3A}, {0x4C6A94, 0x38507C, 0x0B1530},
        {0x4F8FC7, 0x6AA0CF, 0x0F2A4A}, {0x5FA8C8, 0xC0705A, 0x1A2840}, {0x6FA6C4, 0xB45A6E, 0x22183A},
        {0x78A8C8, 0xB5584A, 0x24162E}, {0x9DB9D6, 0xC58F9A, 0x1A2038}, {0x8FB3DA, 0xA48BBE, 0x1D2442},
        {0x7EA6C0, 0xB65A68, 0x261A3E}, {0x86A9C6, 0xA85E5A, 0x1C2038}, {0x7FA8C8, 0xC0704A, 0x2A2230},
        {0x5E7898, 0x7A5A82, 0x14182E}, {0x6F8FC0, 0x6A5AA8, 0x12143A}, {0x6A7C98, 0x8A5A74, 0x16162A},
        {0x6C7A90, 0x9A5048, 0x181620}, {0x8EAAC4, 0xA690A8, 0x1C2038}};
    static final int[][] HOR = {
        {0xD9D2E0, 0xE59C8A, 0x4A3F6B}, {0xF3E1E8, 0xF4C6B6, 0x6A6E9A}, {0xCDE6EA, 0x9CCBD0, 0x2D5878},
        {0xDDE8D2, 0xC9C98E, 0x3A5A48}, {0xF2DDB5, 0xF0A868, 0x7A4A62}, {0xA9BCD6, 0x7F98BA, 0x31496E},
        {0xE9F1E6, 0xF8E0A0, 0x2F5A70}, {0xCFEBE8, 0xF3B26A, 0x3F6A78}, {0xF4E0B8, 0xF2A070, 0x6A3E5E},
        {0xF5DDB8, 0xF0985A, 0x6E3A4E}, {0xF4E6E6, 0xF1C0B8, 0x4A4E78}, {0xEAF0F6, 0xF0C9D6, 0x56608A},
        {0xF3DDB0, 0xF0A25E, 0x6E3F5A}, {0xF1E0B8, 0xE9A867, 0x4E4A6E}, {0xF3E2BC, 0xF0AE6A, 0x6A4A48},
        {0xB8C4D6, 0xD9A07A, 0x3A3E64}, {0xC4D4EE, 0xB59AD8, 0x3A3E8A}, {0xCBC4C0, 0xD9987A, 0x44385A},
        {0xC8C0B8, 0xE0905F, 0x4A3440}, {0xEFE6D4, 0xE6C7B0, 0x5A5E80}};
    /** Three soft accent colors per palette, used by the abstract layers. */
    static final int[][] ACC = {
        {0x8E7CC3, 0xD98FA6, 0xF0B27A}, {0x9ED2C6, 0xF2B8C6, 0xF7E3A1}, {0x4FA3B8, 0x7FC4C0, 0xB5E0D8},
        {0x6FA67A, 0xA9C77A, 0xE0D68A}, {0xE4796B, 0xF0A868, 0xF5D58A}, {0x7F9CC8, 0xA9BCE0, 0xD9E0F0},
        {0xE0B43A, 0x5E8F45, 0xB9573A}, {0xE0A83E, 0x4FA89A, 0xD9704F}, {0xE08A4F, 0xD95F7A, 0xE7C34F},
        {0xD9604F, 0xE8A33D, 0x4FA39A}, {0xE3A1B0, 0xB8C7D6, 0xD9D2C0}, {0x9BB5E0, 0xE7A9C0, 0xCFE0D8},
        {0xD98B3A, 0xC2577A, 0x4FA3A0}, {0xC9A24F, 0x3F8F94, 0x9A5E7A}, {0xD2A15A, 0xB8693F, 0x8DA062},
        {0x5E7FB0, 0xC49A4F, 0x9A6A8C}, {0x7AA7E0, 0xA98BE0, 0x5FD0C0}, {0xC9A24F, 0x9A7BC0, 0xC0675A},
        {0xC4604F, 0xD9A24F, 0x8A8FA8}, {0xD6C08A, 0x9FB4C8, 0xC9A9B0}};
    private static final int[] SAND = {0xE3D2A8, 0xD6A98A, 0x4A5368};
    private static final int[] CLOUDC = {0xF3EEE6, 0xF3C9AE, 0x8190B0};
    private static final int CREAM = 0xE6DAC2;
    private static final int[] CLOUD_ETH = {0xFAF6EC, 0xFBE9C0, 0x7F95B5};
    private static final int[] MEADOW = {0x6E9A48, 0x86AA52, 0x1F3B2B};
    private static final int MF = 40;
    private static final float[] CCX = {-0.9f, -0.35f, 0.3f, 0.85f, 0f};
    private static final float[] CCY = {0.1f, -0.25f, -0.2f, 0.08f, 0.12f};
    private static final float[] CCR = {0.6f, 0.8f, 0.7f, 0.55f, 0.9f};
    /** Named scenes: layers (sky 19, peaks 20, clouds 21, sea 22, shore 23, weather 24). */
    private static final String[] REC_NAMES = {"beach", "misty", "ocean", "snow", "rain", "fireflies",
        "coast", "moonlit"};
    private static final int[][] REC = {{19, 21, 23}, {19, 21, 20}, {19, 22, 21}, {19, 21, 20, 24},
        {19, 21, 20, 24}, {19, 20, 24}, {19, 21, 20, 22}, {19, 23, 21}};
    private static final int[] REC_WX = {-1, -1, -1, 1, 0, 2, -1, -1};
    private static final int[] REC_PAL = {4, 1, 2, 5, 2, 3, 4, 5};
    /** Where in the day-to-night cycle (0 day, 0.25 dusk, 0.5 night, 0.75 dawn) each scene starts. */
    private static final float[] REC_TOD = {0.22f, 0.02f, 0.5f, 0.08f, 0.1f, 0.45f, 0.2f, 0.5f};

    // origin themes (see Origin): 1 Ethiopia/Eritrea, 2 Caribbean, 3 Afrobeats, 4 Latin, 5 Japan,
    // 6 Korea, 7 India, 8 Middle East, 9 country, 10 jazz/blues, 11 electronic, 12 hip-hop, 13 rock,
    // 14 classical. Their palettes are rows 6..19 of the tables above (palette = 5 + theme).
    private static final int[][] THEME_REC = {null, {19, 21, 20, 27, 25}, {19, 22, 21, 25}, {19, 21, 0, 25},
        {19, 21, 23, 25}, {19, 20, 24, 25}, {19, 21, 24, 25}, {19, 21, 22, 25}, {19, 20, 25},
        {19, 21, 20, 25}, {19, 26, 24}, {16, 17}, {19, 26, 0}, {19, 20, 10}, {19, 21, 20, 22}};
    private static final int[] THEME_WX = {-1, -1, -1, -1, -1, 3, 3, -1, -1, -1, 0, -1, -1, -1, -1};
    /** Starting times of day: 0.76 is sunrise, 0.22 dusk, 0.5 night, 0.02 midday. */
    private static final float[][] THEME_TODS = {null, {0.76f, 0.76f, 0.9f, 0.04f}, {0.2f, 0.02f, 0.5f},
        {0.22f, 0.5f}, {0.22f, 0.5f}, {0.3f, 0.5f, 0.76f}, {0.45f, 0.5f, 0.02f}, {0.76f, 0.2f},
        {0.3f, 0.5f, 0.22f}, {0.22f, 0.76f, 0.02f}, {0.5f, 0.45f}, {0.5f}, {0.5f, 0.45f}, {0.5f, 0.3f},
        {0.76f, 0.02f, 0.22f}};
    /** Border pattern: 0 diamonds, 1 pennants, 2 scallops, 3 eight-point stars, 4 checks, 5 fans, 6 dots. */
    private static final int[] THEME_PAT = {-1, 0, 2, 4, 1, 2, 6, 0, 3, 4, 5, 6, -1, -1, 5};
    /** Motion feel: sway cycles per two beats, sway size, and how hard kicks punch the picture. */
    private static final float[] SWAY_RATE = {0f, 1f, 0.5f, 1f, 1f, 0.25f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0f, 0.5f, 0f, 0.25f};
    private static final float[] SWAY_AMT = {0f, 1.3f, 1f, 1.2f, 1.2f, 0.5f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f, 0f, 0.8f, 0f, 0.4f};
    private static final float[] PUNCH = {1f, 1f, 0.9f, 1.1f, 1.1f, 0.6f, 0.9f, 0.8f, 0.8f, 0.9f, 0.8f, 1.5f, 1.4f, 1.4f, 0.5f};
    private static final float[] STAR_C = new float[16], STAR_S = new float[16];

    static {
        for (int i = 0; i < 16; i++) {
            STAR_C[i] = (float) Math.cos(i * Math.PI / 8);
            STAR_S[i] = (float) Math.sin(i * Math.PI / 8);
        }
    }
    private static final float[] REC_CALM = {0.3f, 0.15f, 0.35f, 0.2f, 0.45f, 0.25f, 0.5f, 0.2f};
    private static final float TWO_PI = (float) (Math.PI * 2);
    private static final int STARS = 150, RIPPLES = 28, DOTS = 60, SPARKS = 200, BOLTS = 3, BOLT_PTS = 16;
    private static final float[] SPANS = {45f, 110f, 190f, 300f};
    private static final int[] SIDES = {3, 4, 5, 6, 8};
    private static final int[] SYMS = {1, 1, 1, 2, 3, 4, 6, 8};
    private static final int[] history = new int[12];
    private static int historyAt;

    // audio, written by the capture callback
    private final float[] target = new float[BARS];
    private final float[] prev = new float[BARS];
    private final float[] shown = new float[BARS];
    private final Singer singer = new Singer();
    private final NoteWorld noteWorld = new NoteWorld();
    private int notePreset, noteSeq;
    private final NoteWorld.Events noteEv = new NoteWorld.Events() {
        @Override
        public long total() {
            return notes.evCount;
        }

        @Override
        public int midi(long i) {
            return notes.evMidi[(int) (i % Notes.CAP)];
        }

        @Override
        public float vel(long i) {
            return notes.evVel[(int) (i % Notes.CAP)];
        }

        @Override
        public boolean on(int m) {
            return m >= 0 && m < 128 && notes.on[m];
        }

        @Override
        public float level(int m) {
            return m >= 0 && m < 128 ? notes.level[m] : 0f;
        }
    };
    private final float[] cap = new float[BARS];
    private final float[] smooth = new float[BARS];
    private volatile int frames;
    private float ref = 0.5f;
    private float avgBassFlux, avgMidFlux, avgHighFlux;
    private float bass, mid, high, ener, fastE, slowE, bpm, bright = 0.3f;
    private long lastFrameAt, lastLoud, lastKick, lastSnare, lastHat;
    private boolean pendKick, pendSnare, pendHat;
    private volatile int nKick, nSnare, nHat;
    private volatile float fluxEma;
    private int planPal = -1;

    // drawing state
    private final float[] hsv = new float[3];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Random rng = new Random();
    private float kick, snare, hat, hue, spin, tpos, fade, sceneAge, sceneLen, sinceDrop, flash;
    private long lastDraw;
    /** Frame-time average; when drawing runs slow the glow passes and extra copies are skipped. */
    private float frameAvg = 0.016f;
    private boolean lite;
    private final float[] pickW = new float[T];
    private final float[] lines = new float[4 * 40];
    private final int[] bandFrom = new int[BARS], bandTo = new int[BARS];
    private int edgesN = -1;

    // scene
    private int nLayers;
    private final int[] lt = new int[6], lv = new int[6], la = new int[6], lb = new int[6];
    private final float[] lf = new float[6], lh = new float[6];
    private final boolean[] has = new boolean[T];
    private int symN = 1, scheme, spinDir = 1, fountainVar, rippleVar;
    private boolean symMirror;
    private float hueBase, span, sat;
    private String style = "auto";
    /** 0 soft (default), 1 vivid, 2 accent only. */
    private int colorMode;
    private boolean soft = true, leaving, dropNext, natural;
    private String song = "";


    // origin state
    private boolean originOn = true;
    private int theme, kw, wxMode = -1;
    private float swayPh, wind, windPos, beat;
    private final float[] mx = new float[MF], my = new float[MF], mv = new float[MF], mph = new float[MF];
    private final float[][] bld = new float[2][24];
    private final float[] winPts = new float[600];
    private int winCount, winKey = -1;

    // nature state
    private int pal, hatN, kickN, skyKey = -1, peakKey = -1;
    private float p, tod, skyH, peakH, bodyFx = 0.5f, bodyX, bodyY, sunY, moonY, bodyVis, wash, washDir, washIdle;
    private int skyTopC, skyHorC, bodyC, sunC;
    private float sunA, moonA;
    private final float[] cloudX = new float[NC], cloudY = new float[NC], cloudS = new float[NC],
            cloudV = new float[NC], puff = new float[NC];
    private final float[][] ridge = new float[RANGES][RN];
    private final float[] roff = new float[RANGES];
    private final float[] stx = new float[NS], sty = new float[NS], starPts = new float[NS];
    private final float[] wx = new float[WP], wy = new float[WP], wv = new float[WP], wph = new float[WP];
    private final float[] rainLines = new float[4 * WP], edgeY = new float[33];
    private final Path path2 = new Path();
    private final Paint skyPaint = new Paint();
    private final Shader[] rangeShader = new Shader[RANGES];

    // particles
    private final float[] sa = new float[STARS], sr = new float[STARS], sv = new float[STARS],
            sh = new float[STARS];
    private final float[] rr = new float[RIPPLES], rl = new float[RIPPLES], rw = new float[RIPPLES],
            rh = new float[RIPPLES], rx = new float[RIPPLES], ry = new float[RIPPLES];
    private final float[] dx = new float[DOTS], dy = new float[DOTS], dvx = new float[DOTS],
            dvy = new float[DOTS], dsize = new float[DOTS], dlife = new float[DOTS],
            dhue = new float[DOTS];
    private final float[] px = new float[SPARKS], py = new float[SPARKS], pvx = new float[SPARKS],
            pvy = new float[SPARKS], plife = new float[SPARKS], phue = new float[SPARKS];
    private final float[] bx = new float[BOLTS * BOLT_PTS], by = new float[BOLTS * BOLT_PTS],
            blife = new float[BOLTS], bhue = new float[BOLTS];
    private int rippleSide, boltAt;

    private Visualizer viz;
    private final Tempo tempo = new Tempo();
    private long lastBar, waitSince, scopeKickAt;
    private String waitEvent;
    private float secE, longE;
    private int secHold;
    private View scopeView;
    private volatile Milk milk;
    private long lastMilkAt;
    private Listener listener;
    private int accent = 0xFF39FF14;
    private float accentHue = 120;
    private int lastFramesSeen, stale;
    private boolean active;
    final Notes notes = new Notes();
    private long loudAt;
    private android.media.AudioManager audio;

    /** Remote click sounds are loud for a moment but are not music: only a playing music stream counts. */
    private boolean musicPlaying() {
        if (audio == null) audio = (android.media.AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        return audio == null || audio.isMusicActive();
    }

    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (viz == null) return;
            long now = SystemClock.uptimeMillis();
            if (VizLab.logging && now - tempoLogAt >= 1000) {
                tempoLogAt = now;
                Log.i("VIZLAB", "{\"ev\":\"tempo\",\"t_ms\":" + now + ",\"bpm\":" + Math.round(tempo.bpm() * 10) / 10f + ",\"conf\":" + Math.round(tempo.conf() * 100) / 100f
                    + ",\"meter_calls\":" + mCalls + ",\"meter_changes\":" + mChanges + "}");
                mCalls = 0;
                mChanges = 0;
            }
            boolean loud = now - lastLoud < 4000 && musicPlaying();
            if (loud && !active) {
                if (loudAt == 0) loudAt = now;
                loud = now - loudAt > 1500;
            } else if (!loud) {
                loudAt = 0;
            }
            if (loud != active) {
                active = loud;
                if (loud && song.isEmpty()) reseed(System.nanoTime());
                if (listener != null) listener.onActive(loud);
            }
            if (frames == lastFramesSeen) {
                if (++stale >= 6) {
                    stale = 0;
                    restart();
                    return;
                }
            } else {
                stale = 0;
                lastFramesSeen = frames;
            }
            handler.postDelayed(this, 500);
        }
    };

    Pulse(Context c) {
        super(c);
        setWillNotDraw(false);
        setFocusable(false);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        reseed(System.nanoTime());
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** react_debug=on: logs one "dumbtvsync" line a second (logcat -s dumbtvsync); off by default. */
    void setDebug(boolean on) {
        if (on == debug) return;
        debug = on;
        notes.setDebug(on);
        handler.removeCallbacks(dbgTick);
        if (on) {
            dbgAt = SystemClock.uptimeMillis();
            handler.postDelayed(dbgTick, 1000);
        }
    }

    private final Runnable dbgTick = new Runnable() {
        @Override
        public void run() {
            if (!debug) return;
            long now = SystemClock.uptimeMillis();
            float secs = Math.max(0.001f, (now - dbgAt) / 1000f);
            dbgAt = now;
            Log.i("dumbtvsync", String.format(java.util.Locale.US,
                    "java capture=%s fft=%.0f/s wave=%.0f/s kicks=%d snares=%d hats=%d bpm=%.0f"
                            + " bass=%.2f mid=%.2f high=%.2f ener=%.2f surge(max fast/slow)=%.2f drops=%d milk=%s"
                            + " tempo=%.1f conf=%.2f beats=%d bars=%d",
                    viz != null ? "on" : "OFF", dbgFft.getAndSet(0) / secs, dbgWave.getAndSet(0) / secs,
                    dbgKicks.getAndSet(0), dbgSnares.getAndSet(0), dbgHats.getAndSet(0), bpm,
                    bass, mid, high, ener, dbgSurge, dbgDrops.getAndSet(0), milk != null ? "on" : "off",
                    tempo.bpm(), tempo.conf(), tempo.beats(now), tempo.bars(now)));
            dbgSurge = 0f;
            handler.postDelayed(this, 1000);
        }
    };

    private boolean debug;
    private long dbgAt;
    private volatile float dbgSurge;
    private double tReg, frameMs = 50;
    private long prevRawAt;
    private final float[] lgPrev = new float[BARS];
    private final AtomicInteger dbgFft = new AtomicInteger(), dbgWave = new AtomicInteger(),
            dbgKicks = new AtomicInteger(), dbgSnares = new AtomicInteger(), dbgHats = new AtomicInteger(),
            dbgDrops = new AtomicInteger();

    /** "auto" composes scenes; a layer name (or kaleido) forces that look. */
    void setStyle(String s) {
        if ("spectrum".equals(s)) s = "bars";
        if ("orbs".equals(s)) s = "orbit";
        if ("milkdrop".equals(s)) s = "auto";
        if (s != null && !s.equals(style)) {
            style = s;
            if (s.startsWith("note_")) notePreset = (song.hashCode() >>> 3) + 5 * noteSeq++; // a repeat of the same scene gets another preset
            newScene();
        }
    }

    /** "soft" (default), "vivid" (the old saturated look; "rainbow" is the same) or "accent". */
    void setColorMode(String m) {
        int mode = "vivid".equals(m) || "rainbow".equals(m) ? 1 : "accent".equals(m) ? 2 : 0;
        if (mode != colorMode) {
            colorMode = mode;
            soft = mode == 0;
            if (nLayers > 0) leaving = true;
        }
    }

    void setAccent(int color) {
        accent = color;
        Color.colorToHSV(color, hsv);
        accentHue = hsv[0];
    }

    void setOriginEnabled(boolean on) {
        if (on == originOn) return;
        originOn = on;
        if (!on) {
            theme = 0;
            kw = 0;
        }
        if (nLayers > 0) leaving = true;
    }

    /** Where the song comes from (null: unknown); the next scene is composed to match. */
    void setOrigin(Origin.Info info) {
        int t = info == null || !originOn ? 0 : info.theme, k = info == null || !originOn ? 0 : info.kw;
        if (t == theme && k == kw) return;
        theme = t;
        kw = k;
        if (nLayers > 0) leaving = true;
    }

    /** The song that is playing; a new one gets its own dice. */
    void setSong(String key) {
        if (key == null) key = "";
        if (key.equals(song)) return;
        song = key;
        singer.clearLyrics();
        tempo.reset();
        waitEvent = null;
        secE = longE = ener;
        bpm = 0;
        bright = 0.3f;
        lastMilkAt = SystemClock.uptimeMillis();
        if (!key.isEmpty()) reseed(key.hashCode() * 2654435761L + 0x5bd1e995L);
    }


    // ---- read by Stage -------------------------------------------------------------------------

    Tempo tempo() {
        return tempo;
    }

    boolean isActive() {
        return active && viz != null;
    }

    /** Playback clock and synced lyrics for the singer's mouth (both optional). */
    void singerSync(long posMs, boolean playing) {
        singer.sync(posMs, playing);
    }

    void singerLrc(String lrc) {
        singer.setLrc(lrc);
    }

    /** The kick envelope: jumps to 1 on every detected kick and decays; the light bars use it when the beat clock is unsure. */
    float kickEnv() {
        return kick;
    }

    float bass() {
        return bass;
    }

    float mid() {
        return mid;
    }

    float high() {
        return high;
    }

    /** Smoothed level (0..1) of spectrum band i of 64, low to high. */
    float bin(int i) {
        return target[Math.max(0, Math.min(BARS - 1, i))];
    }

    float ener() {
        return ener;
    }

    float flux() {
        return fluxEma;
    }

    float mood() {
        return clamp(0.8f * intensity() + 0.4f * ener, 0f, 1f);
    }

    int kicks() {
        return nKick;
    }

    int snares() {
        return nSnare;
    }

    int hats() {
        return nHat;
    }

    /** The palette (0..5) the song's plan chose; scenes use it instead of the artist's theme palette. */
    void setPlanPal(int p) {
        planPal = p;
        if (nLayers > 0) leaving = true;
    }

    // ---- capture ---------------------------------------------------------------------------

    /** Starts listening; false when the permission is missing or the TV refuses the capture. */
    boolean start() {
        wantCapture = true;
        if (viz != null) return true;
        if (getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            if (capTries++ % 20 == 0) Log.w("dumbtv", "RECORD_AUDIO is not granted: run  adb shell pm grant " + getContext().getPackageName() + " android.permission.RECORD_AUDIO");
            handler.removeCallbacks(capRetry);
            handler.postDelayed(capRetry, 3000); // a grant made while the app runs is picked up without a restart
            return false;
        }
        try {
            Visualizer v = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(range[1]); // the largest the TV offers: finer bins for the note estimator
            v.setDataCaptureListener(this, Visualizer.getMaxCaptureRate(), true, true);
            try {
                v.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS);
            } catch (RuntimeException e) {
                Log.w("dumbenv", "measurement mode: " + e);
            }
            v.setEnabled(true);
            viz = v;
            startMeter(v);
            frames = 0;
            lastFramesSeen = 0;
            stale = 0;
            capTries = 0;
            handler.removeCallbacks(watch);
            handler.postDelayed(watch, 500);
            invalidate();
            return true;
        } catch (RuntimeException e) {
            Log.w("dumbtv", "music capture unavailable (attempt " + (++capTries) + "), retrying", e);
            viz = null;
            handler.removeCallbacks(capRetry);
            handler.postDelayed(capRetry, Math.min(5000, 1000 + 500 * capTries));
            return false;
        }
    }

    private boolean wantCapture;
    private int capTries;
    /** The TV can refuse the capture for a moment (another app just released it): keep asking until it is ours. */
    private final Runnable capRetry = new Runnable() {
        @Override
        public void run() {
            if (!wantCapture || viz != null) return;
            if (start()) Log.i("dumbtv", "music capture recovered after " + capTries + " tries");
        }
    };

    private void restart() {
        Log.i("dumbtv", "music capture stalled, restarting");
        release();
        start();
    }

    private void release() {
        handler.removeCallbacks(watch);
        if (viz != null) {
            try {
                viz.setEnabled(false);
            } catch (RuntimeException ignored) {
                // already gone
            }
            meterOn = false;
            viz.release();
            viz = null;
        }
    }

    void stop() {
        wantCapture = false;
        capTries = 0;
        handler.removeCallbacks(capRetry);
        release();
        Arrays.fill(target, 0f);
        Arrays.fill(prev, 0f);
        Arrays.fill(shown, 0f);
        Arrays.fill(cap, 0f);
        bass = mid = high = ener = fastE = slowE = 0f;
        kick = snare = hat = 0f;
        if (active) {
            active = false;
            if (listener != null) listener.onActive(false);
        }
        invalidate();
    }

    private volatile int mCalls, mChanges, lastRms;
    private long tempoLogAt;
    private volatile boolean meterOn;
    private volatile long meterAt;
    private long lastMeterKick;

    /**
     * The tempo tracker's ear. The spectrum callbacks come only ~20 times a second and each covers a ~23 ms slice,
     * so most drum hits fall between slices and the tempo could not be read (autocorrelation peaks of 0.1, a
     * different tempo every few seconds). The effect's own peak/RMS measurement covers all the audio and updates
     * ~60 times a second: its rises in level (fast average over slow average, in dB) make a clean onset envelope
     * (a clear peak at the real tempo, 0.5+), and its peaks drive the phase of the beat clock.
     */
    private void startMeter(final Visualizer v) {
        meterOn = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                Visualizer.MeasurementPeakRms m = new Visualizer.MeasurementPeakRms();
                boolean init = false;
                float fast = 0, slow = 0, binSum = 0, avgV = 0;
                int binN = 0;
                long binStart = 0, lastT = 0;
                while (meterOn && viz == v) {
                    try {
                        if (v.getMeasurementPeakRms(m) != Visualizer.SUCCESS) {
                            Thread.sleep(50);
                            continue;
                        }
                        long t = SystemClock.uptimeMillis();
                        mCalls++;
                        if (m.mRms != lastRms) {
                            mChanges++;
                            lastRms = m.mRms;
                        }
                        float db = Math.max(-90f, m.mRms / 100f);
                        if (!init) {
                            fast = slow = db;
                            init = true;
                            binStart = lastT = t;
                        }
                        float dtm = Math.min(50f, t - lastT);
                        lastT = t;
                        fast += (db - fast) * (1f - (float) Math.exp(-dtm / 15f));
                        slow += (db - slow) * (1f - (float) Math.exp(-dtm / 80f));
                        binSum += Math.max(0f, fast - slow);
                        binN++;
                        if (t - binStart >= 25) {
                            float val = binSum / binN;
                            binSum = 0;
                            binN = 0;
                            binStart = t;
                            meterAt = t;
                            tempo.onset(t, val, 0f, 0f);
                            avgV = avgV * 0.95f + val * 0.05f;
                            float thr = avgV * 2.2f + 0.15f;
                            if (val > thr && t - lastMeterKick > 160) {
                                lastMeterKick = t;
                                tempo.kick(t, clamp(0.5f + 0.5f * (val / thr - 1f), 0.5f, 1f));
                                VizLab.ev(t, "meterkick", val, "actual", "", 0);
                            }
                        }
                        Thread.sleep(4);
                    } catch (Exception e) {
                        Log.w("dumbmeter", "stopped: " + e);
                        return;
                    }
                }
            }
        }, "meter").start();
    }

    @Override
    public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int rate) {
        if (debug) dbgWave.incrementAndGet();
        Milk m = milk;
        if (m != null) m.feed(waveform);
    }

    @Override
    public void onFftDataCapture(Visualizer v, byte[] fft, int rate) {
        int n = fft.length / 2;
        if (n < 8) return;
        frames++;
        notes.feed(fft, rate, SystemClock.uptimeMillis());
        if (debug) dbgFft.incrementAndGet();
        long now = SystemClock.uptimeMillis();
        lastFrameAt = now;
        if (n != edgesN) {
            edgesN = n;
            double lo = 1, hi = n / 4.0;
            for (int i = 0; i < BARS; i++) {
                bandFrom[i] = (int) (lo * Math.pow(hi / lo, i / (double) BARS));
                bandTo[i] = Math.max(bandFrom[i] + 1, (int) (lo * Math.pow(hi / lo, (i + 1) / (double) BARS)));
            }
        }
        float rawMax = 0;
        for (int i = 0; i < BARS; i++) {
            int from = bandFrom[i], to = bandTo[i];
            float sum = 0;
            int count = 0;
            for (int k = from; k < to && k < n; k++) {
                float re = fft[2 * k], im = fft[2 * k + 1];
                sum += (float) Math.sqrt(re * re + im * im);
                count++;
            }
            smooth[i] = count == 0 ? 0 : sum / count / 55f;
            rawMax = Math.max(rawMax, smooth[i]);
        }
        // automatic gain: quiet songs and loud songs fill the screen the same
        ref = Math.max(0.3f, Math.max(ref * 0.9985f, rawMax));
        float total = 0;
        for (int i = 0; i < BARS; i++) {
            prev[i] = target[i];
            target[i] = (float) Math.pow(Math.min(1f, smooth[i] / ref), 0.7);
            total += target[i];
        }
        if (rawMax > 0.06f) lastLoud = now;

        float bf = 0, mf = 0, hf = 0, b = 0, m = 0, h = 0;
        for (int i = 0; i < BARS; i++) {
            float d = Math.max(0f, target[i] - prev[i]);
            if (i < 8) {
                bf += d;
                b += target[i];
            } else if (i < 28) {
                mf += d;
                m += target[i];
            } else {
                hf += d;
                h += target[i];
            }
        }
        float chg = 0;
        for (int i = 0; i < BARS; i++) chg += Math.abs(target[i] - prev[i]);
        fluxEma += (chg / BARS - fluxEma) * 0.1f;
        bass = bass * 0.5f + (b / 8f) * 0.5f;
        mid = mid * 0.5f + (m / 20f) * 0.5f;
        high = high * 0.5f + (h / (BARS - 28f)) * 0.5f;
        ener = total / BARS;
        fastE = fastE * 0.8f + ener * 0.2f;
        slowE = slowE * 0.995f + ener * 0.005f;
        float hb = b / 8f, mm = m / 20f, hh = h / (BARS - 28f);
        bright = bright * 0.99f + (hh / (hb + mm + hh + 0.01f)) * 0.01f;

        avgBassFlux = avgBassFlux * 0.93f + bf * 0.07f;
        avgMidFlux = avgMidFlux * 0.93f + mf * 0.07f;
        avgHighFlux = avgHighFlux * 0.93f + hf * 0.07f;
        // The TV delivers only ~20 spectra a second, and the callbacks arrive with tens of ms of jitter. The tempo
        // tracker gets a steady clock instead (a fitted 50 ms grid that follows the real arrivals slowly) and a
        // log-magnitude flux (what a human hears as "something started"), which is far less ragged than the
        // bar-height differences the kick/snare triggers use.
        if (tReg == 0) {
            tReg = now;
        } else {
            double gap = now - prevRawAt;
            if (gap > 20 && gap < 120) frameMs += (gap - frameMs) * 0.02;
            tReg += frameMs;
            tReg += (now - tReg) * 0.05;
            if (Math.abs(now - tReg) > 200) tReg = now;
        }
        prevRawAt = now;
        long tNow = Math.round(tReg);
        float lbf = 0, lmf = 0, lhf = 0;
        for (int i = 0; i < BARS; i++) {
            float lg = (float) Math.log1p(10f * Math.min(1f, smooth[i] / ref));
            float d = Math.max(0f, lg - lgPrev[i]);
            lgPrev[i] = lg;
            if (i < 8) {
                lbf += d;
                
            } else if (i < 28) {
                lmf += d;
                
            } else {
                lhf += d;
                
            }
        }
        boolean meterLive = now - meterAt < 500;
        if (!meterLive) tempo.onset(tNow, lbf, lmf, lhf);
 // the multiband onset envelope the tempo is read from
        Milk mk = milk;
        boolean kicked = false;
        if (bf > avgBassFlux * 1.7f + 0.45f && hb > 0.2f && now - lastKick > 140) {
            long gap = now - lastKick;
            if (gap > 260 && gap < 1100) {
                float inst = 60000f / gap;
                bpm = bpm == 0 ? inst : bpm * 0.92f + inst * 0.08f;
            }
            lastKick = now;
            pendKick = true;
            nKick++;
            kicked = true;
            if (debug) dbgKicks.incrementAndGet();
            // 0.5 for a marginal kick, 1 for a hard one
            float ks = clamp(0.5f + 0.5f * (bf / (avgBassFlux * 1.7f + 0.45f) - 1f), 0.5f, 1f);
            if (!meterLive) tempo.kick(tNow, ks);
            if (mk != null) {
                mk.beat(0, ks);
                // the beat clock in epoch time, so the page can predict beats between kicks
                mk.clock(tempo.bpm(), tempo.conf(), tempo.period(),
                        System.currentTimeMillis() - (SystemClock.uptimeMillis() - tempo.anchor()), ks);
            }
        }
        if (mf > avgMidFlux * 1.8f + 0.7f && now - lastSnare > 130) {
            lastSnare = now;
            pendSnare = true;
            nSnare++;
            if (debug) dbgSnares.incrementAndGet();
            if (mk != null) mk.beat(1, clamp(0.5f + 0.5f * (mf / (avgMidFlux * 1.8f + 0.7f) - 1f), 0.5f, 1f));
        }
        if (hf > avgHighFlux * 1.9f + 0.8f && now - lastHat > 70) {
            lastHat = now;
            pendHat = true;
            nHat++;
            if (debug) dbgHats.incrementAndGet();
        }
        // sections: the energy of the last ~4 s against the last ~16 s (needs ~10 s of music first)
        secE += (ener - secE) * 0.0125f;
        longE += (ener - longE) * 0.0031f;
        float change = (secE - longE) / (longE + 0.05f);
        secHold = frames > 200 && Math.abs(change) > 0.22f ? secHold + 1 : 0;
        if (scopeView != null) {
            if (kicked) scopeKickAt = now;
            scopeView.postInvalidateOnAnimation();
        }
        if (mk != null) {
            // Milkdrop changes picture when the music changes: a drop (a surge over the last ten
            // seconds), or with a sure beat clock (confidence > 0.5) a lasting rise or fall in energy.
            // No timers: a steady song keeps its picture. With a sure clock the change waits for the
            // next bar line (at most 4 s); without one, only a drop changes the picture, at once.
            // slowE starts at 0 and takes ~20 s to settle; until then every start looks like one big surge.
            boolean drop = frames > 400 && fastE > slowE * 1.45f + 0.05f;
            if (debug) {
                dbgSurge = Math.max(dbgSurge, fastE / Math.max(0.01f, slowE));
                if (drop) dbgDrops.incrementAndGet();
            }
            long since = now - lastMilkAt;
            boolean clockOn = tempo.conf() > 0.5f; // below that the clock is only a guess
            long bar = tempo.bars(now);
            boolean barEdge = clockOn && bar != lastBar;
            lastBar = bar;
            if (waitEvent == null) {
                if (drop && since > 8000) waitEvent = "drop";
                else if (clockOn && secHold >= 20 && since > 12000) waitEvent = change > 0 ? "rise" : "fall";
                if (waitEvent != null) {
                    waitSince = now;
                    longE = secE; // the new level is the baseline from here on
                    secHold = 0;
                }
            }
            if (waitEvent != null && (!clockOn || barEdge || now - waitSince > 4000)) {
                String why = waitEvent;
                waitEvent = null;
                if (debug) {
                    Log.i("dumbtvsync", "switch " + why + (clockOn ? " on bar " + bar + " beatInBar=" + tempo.beatInBar(now)
                            + " edge=" + barEdge : " (no clock)") + " waited=" + (now - waitSince) + "ms change="
                            + Math.round(change * 100) / 100f + " tempo=" + Math.round(tempo.bpm()) + " conf="
                            + Math.round(tempo.conf() * 100) / 100f);
                }
                lastMilkAt = now;
                // tempo, brightness and energy as heard now steer the next pick and the grade
                mk.setMood(clamp(0.8f * intensity() + 0.4f * ener, 0f, 1f));
                mk.next("drop".equals(why) ? 0.6 : 1.0, why);
            }
        }
        postInvalidateOnAnimation();
    }

    // ---- the scope: a thin ring above Milkdrop, drawn from the live spectrum --------------------

    void setScope(View v) {
        scopeView = v;
    }

    private final Paint scopePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] scopeV = new float[BARS];
    private final float[] scopePts = new float[96 * 4];

    /**
     * Called by the Scope view. Faint on purpose (alpha 31%): the music drawn, never the picture.
     * Drawn as line segments: a Path whose shape changes every frame gets its own cached mask
     * texture each time, which filled the GPU texture cache (700+ MB) within a minute.
     */
    void drawScope(Canvas c, int w, int h) {
        float env = (float) Math.exp(-(SystemClock.uptimeMillis() - scopeKickAt) / 160f);
        int n = 96, half = n / 2;
        for (int b = 0; b < BARS; b++) scopeV[b] += (target[b] - scopeV[b]) * 0.35f;
        float cx = w / 2f, cy = h / 2f, r0 = h * 0.30f * (1f + 0.04f * env);
        float px = 0, py = 0;
        for (int i = 0; i <= n; i++) {
            int k = i <= half ? i : n - i; // mirrored, so the left and right halves match
            float r = r0 + h * 0.07f * scopeV[Math.min(BARS - 1, k * (BARS - 1) / half)];
            double a = i * 2 * Math.PI / n - Math.PI / 2;
            float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
            if (i > 0) {
                int o = (i - 1) * 4;
                scopePts[o] = px;
                scopePts[o + 1] = py;
                scopePts[o + 2] = x;
                scopePts[o + 3] = y;
            }
            px = x;
            py = y;
        }
        scopePaint.setStyle(Paint.Style.STROKE);
        scopePaint.setStrokeWidth(2.5f * getResources().getDisplayMetrics().density);
        // the accent washed toward white, at 31% opacity
        scopePaint.setColor(Color.argb(80, (Color.red(accent) + 255) / 2, (Color.green(accent) + 255) / 2,
                (Color.blue(accent) + 255) / 2));
        c.drawLines(scopePts, 0, n * 4, scopePaint);
    }

    /** While Milkdrop is showing it gets the waveform and the beat; this view stops drawing. */
    void setMilk(Milk m) {
        milk = m;
        lastMilkAt = SystemClock.uptimeMillis();
    }

    // ---- composing scenes ------------------------------------------------------------------

    private void reseed(long seed) {
        rng = new Random(seed);
        Arrays.fill(history, 0);
        if (nLayers == 0) newScene();
        else leaving = true;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** How fast and sharp the song feels, 0..1, from tempo and brightness heard so far. */
    private float intensity() {
        float tempo = bpm > 0 ? clamp((bpm - 80f) / 100f, 0f, 1f) : 0.5f;
        float brightness = clamp(bright * 2.2f, 0f, 1f);
        return 0.5f * tempo + 0.5f * brightness;
    }

    private int pick(float inten, int skipMask, boolean mainOnly) {
        float total = 0;
        float[] w = pickW;
        for (int t = 0; t < T; t++) {
            w[t] = 0;
            if (t >= 19 || (skipMask & (1 << t)) != 0 || (mainOnly && !MAIN[t])) continue;
            w[t] = 0.15f + Math.max(0f, 1f - Math.abs(CALM[t] - inten) * 1.6f);
            total += w[t];
        }
        float r = rng.nextFloat() * total;
        for (int t = 0; t < T; t++) {
            r -= w[t];
            if (r <= 0 && w[t] > 0) return t;
        }
        for (int t = 0; t < T; t++) if (w[t] > 0) return t;
        return 0;
    }

    private boolean hasLayer(int t) {
        for (int i = 0; i < nLayers; i++) if (lt[i] == t) return true;
        return false;
    }

    private void addLayer(int t) {
        if (nLayers < 6 && !hasLayer(t)) lt[nLayers++] = t;
    }

    private int pickRecipe(float inten) {
        float total = 0;
        for (int i = 0; i < REC_NAMES.length; i++) {
            pickW[i] = 0.15f + Math.max(0f, 1f - Math.abs(REC_CALM[i] - inten) * 1.6f);
            total += pickW[i];
        }
        float r = rng.nextFloat() * total;
        for (int i = 0; i < REC_NAMES.length; i++) {
            r -= pickW[i];
            if (r <= 0) return i;
        }
        return 0;
    }

    private void newScene() {
        float inten = intensity();
        int forced = -1, forcedRec = -1;
        boolean kaleido = "kaleido".equals(style), scenic = "scenic".equals(style);
        if (kaleido) forced = 15;
        for (int i = 0; i < 19; i++) if (LN[i].equals(style)) forced = i;
        for (int i = 0; i < REC_NAMES.length; i++) if (REC_NAMES[i].equals(style)) forcedRec = i;
        // calm songs lean toward landscapes, fast ones toward abstract scenes
        boolean themed = originOn && theme > 0 && forced < 0 && !kaleido && "auto".equals(style);
        boolean themeRecipe = themed && THEME_REC[theme] != null && rng.nextFloat() < 0.7f;
        boolean nat = scenic || forcedRec >= 0 || themeRecipe
                || (forced < 0 && rng.nextFloat() < 0.25f + 0.45f * (1f - inten));
        int mask = 0, key = 0, recipe = -1;
        float todPick = -1f;
        wxMode = -1;
        for (int attempt = 0; attempt < 10; attempt++) {
            mask = 0;
            if (nat) {
                int[] lay;
                if (themeRecipe) {
                    lay = THEME_REC[theme];
                    pal = 5 + theme;
                    wxMode = THEME_WX[theme];
                    todPick = THEME_TODS[theme][rng.nextInt(THEME_TODS[theme].length)];
                } else {
                    recipe = forcedRec >= 0 ? forcedRec : pickRecipe(inten);
                    lay = REC[recipe];
                    pal = themed ? 5 + theme : (rng.nextFloat() < 0.6f ? REC_PAL[recipe] : rng.nextInt(6));
                    wxMode = REC_WX[recipe];
                    todPick = REC_TOD[recipe];
                }
                nLayers = lay.length;
                for (int i = 0; i < nLayers; i++) {
                    lt[i] = lay[i];
                    mask |= 1 << lt[i];
                }
            } else {
                pal = themed ? 5 + theme : rng.nextInt(6);
                nLayers = 2 + (rng.nextFloat() < 0.45f ? 1 : 0);
                int first = forced >= 0 ? forced : pick(inten, 0, true);
                lt[0] = first;
                mask |= 1 << first;
                for (int i = 1; i < nLayers; i++) {
                    lt[i] = pick(inten, mask, false);
                    mask |= 1 << lt[i];
                }
            }
            // a themed scene that is not a full recipe still gets the theme's woven border now and then
            if (themed && !themeRecipe && THEME_PAT[theme] >= 0 && nLayers < 6 && rng.nextBoolean()) {
                lt[nLayers++] = 25;
                mask |= 1 << 25;
            }
            if (planPal >= 0) pal = planPal; // the song's palette (cover, seed, mood), never the artist's
            key = (mask * 31 + recipe + 1) * 31 + pal + (int) (Math.max(todPick, 0f) * 8f) * 1009;
            if (forced >= 0) break;
            boolean seen = false;
            for (int i = 0; i < history.length; i++) if (history[i] == key) seen = true;
            if (!seen) break;
        }
        // words in the title and tags add matching accent layers
        if (originOn && forced < 0 && !kaleido && kw != 0) {
            if ((kw & Origin.KW_RAIN) != 0) {
                addLayer(24);
                wxMode = 0;
            } else if ((kw & Origin.KW_LOVE) != 0) {
                addLayer(24);
                wxMode = 5;
            } else if ((kw & Origin.KW_FIRE) != 0) {
                addLayer(24);
                wxMode = 4;
            }
            if ((kw & Origin.KW_OCEAN) != 0 && !hasLayer(22) && !hasLayer(23)) addLayer(22);
            if ((kw & Origin.KW_CITY) != 0) addLayer(26);
            if ((kw & Origin.KW_NIGHT) != 0) {
                addLayer(19);
                todPick = 0.5f;
            } else if ((kw & Origin.KW_SUN) != 0) {
                addLayer(19);
                todPick = 0.02f;
            }
        }
        natural = false;
        for (int i = 0; i < nLayers; i++) if (lt[i] >= 19 && lt[i] != 25) natural = true;
        history[historyAt++ % history.length] = key;
        // draw backgrounds first
        for (int i = 1; i < nLayers; i++) {
            int t = lt[i], j = i - 1;
            while (j >= 0 && Z[lt[j]] > Z[t]) {
                lt[j + 1] = lt[j];
                j--;
            }
            lt[j + 1] = t;
        }
        Arrays.fill(has, false);
        for (int i = 0; i < nLayers; i++) {
            has[lt[i]] = true;
            lv[i] = rng.nextInt(3);
            la[i] = SIDES[rng.nextInt(SIDES.length)];
            lb[i] = 2 + rng.nextInt(6);
            lf[i] = rng.nextFloat();
            lh[i] = (i == 0 ? 0f : rng.nextFloat() * 80f - 40f);
            if (lt[i] == 9) {
                la[i] = 1 + rng.nextInt(6);
                lb[i] = la[i] + 1 + rng.nextInt(5);
            }
            if (lt[i] == 24 && wxMode >= 0) lv[i] = wxMode;
            if (lt[i] == 5) fountainVar = lv[i];
            if (lt[i] == 7) rippleVar = lv[i];
        }
        boolean canSym = false;
        for (int i = 0; i < nLayers; i++) if (SYM[lt[i]]) canSym = true;
        symN = canSym ? SYMS[rng.nextInt(SYMS.length)] : 1;
        symMirror = rng.nextBoolean();
        if (kaleido) {
            symN = 6;
            symMirror = true;
        }
        if (milk != null) {
            // no symmetry copies while Milkdrop is the picture
            symN = 1;
            symMirror = false;
        }
        if (colorMode == 2) {
            scheme = 4;
        } else if (soft) {
            scheme = rng.nextFloat() < 0.85f ? 8 + pal : (rng.nextBoolean() ? 0 : 3);
        } else {
            scheme = SCHEMES[rng.nextInt(SCHEMES.length)];
        }
        if (themed && soft && colorMode != 2) scheme = 8 + pal;
        hueBase = rng.nextFloat() * 360f;
        span = SPANS[rng.nextInt(SPANS.length)];
        sat = 0.65f + 0.3f * rng.nextFloat();
        spinDir = rng.nextBoolean() ? 1 : -1;
        sceneAge = 0;
        sinceDrop = 0;
        sceneLen = 20f + rng.nextFloat() * 30f;
        fade = 0f;
        leaving = false;
        if (dropNext) {
            dropNext = false;
            flash = 1f;
        }
        Arrays.fill(rl, 0f);
        Arrays.fill(dlife, 0f);
        Arrays.fill(plife, 0f);
        Arrays.fill(blife, 0f);
        for (int i = 0; i < STARS; i++) resetStar(i, true);
        if (natural) {
            if (todPick >= 0f) p = todPick + (rng.nextFloat() - 0.5f) * 0.06f;
            bodyFx = 0.2f + 0.6f * rng.nextFloat();
            wash = 0f;
            washDir = 0f;
            for (int i = 0; i < NC; i++) {
                cloudX[i] = rng.nextFloat() * 1.4f - 0.2f;
                cloudY[i] = 0.07f + 0.3f * rng.nextFloat();
                cloudS[i] = 0.6f + 0.9f * rng.nextFloat();
                cloudV[i] = 0.5f + rng.nextFloat();
                puff[i] = 0f;
            }
            for (int r = 0; r < RANGES; r++) {
                float k1 = 2 + rng.nextInt(3), k2 = 5 + rng.nextInt(4);
                float p1 = rng.nextFloat() * TWO_PI, p2 = rng.nextFloat() * TWO_PI;
                float wa = themed && theme == 8 ? 0.95f : 0.6f;
                for (int i = 0; i < RN; i++) {
                    float a = TWO_PI * i / RN;
                    ridge[r][i] = 0.4f + 0.6f * (0.5f + 0.5f * (wa * (float) Math.sin(k1 * a + p1)
                            + (1f - wa) * (float) Math.sin(k2 * a + p2)));
                }
                roff[r] = rng.nextFloat();
            }
            for (int r = 0; r < 2; r++) for (int i = 0; i < 24; i++) bld[r][i] = 0.25f + 0.75f * rng.nextFloat();
            winKey = -1;
            for (int i = 0; i < MF; i++) {
                mx[i] = rng.nextFloat();
                my[i] = rng.nextFloat();
                mv[i] = 0.8f + 0.5f * rng.nextFloat();
                mph[i] = rng.nextFloat() * TWO_PI;
            }
            for (int i = 0; i < NS; i++) {
                stx[i] = rng.nextFloat();
                sty[i] = rng.nextFloat();
            }
            for (int i = 0; i < WP; i++) {
                wx[i] = rng.nextFloat();
                wy[i] = rng.nextFloat();
                wv[i] = rng.nextFloat();
                wph[i] = rng.nextFloat() * TWO_PI;
            }
            skyKey = -1;
            peakKey = -1;
        }
    }

    private void resetStar(int i, boolean anywhere) {
        sa[i] = rng.nextFloat() * TWO_PI;
        sr[i] = anywhere ? rng.nextFloat() : 0.02f;
        sv[i] = 0.4f + rng.nextFloat() * 1.1f;
        sh[i] = rng.nextFloat();
    }

    // ---- color -----------------------------------------------------------------------------

    private int color(float h, float s, float v, float alpha) {
        if (soft) {
            // rich but calm: saturation 0.52 to 0.84, brightness at most 0.92 (motion stays gentle)
            s = 0.52f + 0.32f * clamp(s, 0f, 1f);
            v = Math.min(v, 1f) * 0.92f;
        }
        hsv[0] = ((h % 360) + 360) % 360;
        hsv[1] = clamp(s, 0f, 1f);
        hsv[2] = clamp(v, 0f, 1f);
        return Color.HSVToColor((int) clamp(alpha * fade, 0f, 255f), hsv);
    }

    /** Soft cream in place of white (plain white in the vivid look). */
    private int cream(float alpha) {
        int a = (int) clamp(alpha * fade, 0f, 255f);
        return soft ? (a << 24) | CREAM : (a << 24) | 0xFFFFFF;
    }

    private static int lerpC(int a, int b, float t) {
        t = clamp(t, 0f, 1f);
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    private int withA(int c, float alpha) {
        return ((int) clamp(alpha * fade, 0f, 255f) << 24) | (c & 0xFFFFFF);
    }

    /** Picks day, dusk or night from a three-entry table by the time of day. */
    private int stage(int[] t) {
        return tod < 0.5f ? lerpC(t[0], t[1], tod * 2f) : lerpC(t[1], t[2], (tod - 0.5f) * 2f);
    }

    private int accentTone(int pl, float f, float v, float alpha) {
        f -= (float) Math.floor(f);
        float pos = f * 2f;
        int i = (int) pos;
        int c = lerpC(ACC[pl][i], ACC[pl][Math.min(2, i + 1)], pos - i);
        float k = Math.min(v, 1f) * 0.88f;
        return Color.argb((int) clamp(alpha * fade, 0f, 255f), (int) (((c >> 16) & 255) * k),
                (int) (((c >> 8) & 255) * k), (int) ((c & 255) * k));
    }

    /** The scene's color at position f (0..1); off shifts a layer's hue so layers differ. */
    private int tone(float f, float v, float alpha, float off) {
        if (scheme >= 8) return accentTone(scheme - 8, f, v, alpha);
        float hh, drift = hue * 0.3f;
        switch (scheme) {
            case 1:
                hh = hueBase + (f < 0.5f ? 0f : 180f) + f * 40f;
                break;
            case 2:
                hh = hueBase + 120f * (int) (f * 2.99f) + f * 20f;
                break;
            case 3:
                hh = hueBase + f * 18f;
                v *= 0.55f + 0.45f * f;
                break;
            case 4:
                hh = accentHue + f * 30f - 15f;
                drift = 0f;
                break;
            case 5:
                hh = 350f + f * 70f;
                drift = hue * 0.04f;
                break;
            case 6:
                hh = 165f + f * 100f;
                drift = hue * 0.04f;
                break;
            case 7:
                hh = (f < 0.5f ? 315f : 185f) + f * 24f;
                drift = hue * 0.04f;
                break;
            default:
                hh = hueBase + f * span;
        }
        return color(hh + drift + off, sat, v, alpha);
    }

    // ---- drawing ---------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w == 0 || viz == null) return;
        long now = SystemClock.uptimeMillis();
        float dt = lastDraw == 0 ? 0.016f : Math.min(0.1f, (now - lastDraw) / 1000f);
        lastDraw = now;
        if (dt < 0.08f) {
            frameAvg = frameAvg * 0.95f + dt * 0.05f;
            if (frameAvg > 0.026f) lite = true;
            else if (frameAvg < 0.019f) lite = false;
        }
        if (now - lastFrameAt > 500) {
            Arrays.fill(target, 0f);
            bass = mid = high = ener = 0f;
        }
        float peak = 0;
        for (int i = 0; i < BARS; i++) {
            shown[i] += (target[i] - shown[i]) * (target[i] > shown[i] ? 0.7f : 0.2f);
            cap[i] = Math.max(shown[i], cap[i] - dt * 0.5f);
            peak = Math.max(peak, shown[i]);
        }
        boolean noteStyle = style != null && style.startsWith("note_");
        if (peak < 0.03f && !noteStyle) {
            postInvalidateDelayed(250);
            return;
        }
        if (pendKick) {
            pendKick = false;
            kick = 1f;
            hue += 18f;
            wind = Math.min(1.5f, wind + 0.8f);
            if (has[7]) spawnRipple(0, w, h);
            if (has[5]) spawnDots(6, w, h);
            if (has[13]) spawnFirework(w, h);
            if (natural) {
                puff[kickN++ % NC] = 1f;
                if (washDir <= 0f && wash < 0.5f) washDir = 1.6f;
            }
        }
        if (pendSnare) {
            pendSnare = false;
            snare = 1f;
            wind = Math.min(1.5f, wind + 0.3f);
            if (has[7]) spawnRipple(1, w, h);
            if (has[10]) spawnBolt(w, h);
        }
        if (pendHat) {
            pendHat = false;
            hat = 1f;
            hatN++;
            if (has[7] && rng.nextFloat() < 0.5f) spawnRipple(2, w, h);
            if (has[5]) spawnDots(1, w, h);
        }
        kick = Math.max(0f, kick - dt * 5f);
        snare = Math.max(0f, snare - dt * 6f);
        hat = Math.max(0f, hat - dt * 9f);
        if (noteStyle) { // the note-world family draws everything itself
            int sc = 0;
            for (int i = 0; i < NoteWorld.NAMES.length; i++) if (NoteWorld.NAMES[i].equals(style)) sc = i;
            noteWorld.draw(canvas, w, h, dt, sc, notePreset, noteEv, kick, snare, hat, accentHue);
            postInvalidateOnAnimation();
            return;
        }
        hue += dt * (6f + mid * 40f);
        spin += dt * spinDir * (0.18f + bass * 1.3f + kick * 0.8f);
        tpos += dt * (0.12f + bass * 0.6f + kick * 0.5f);
        // scenes fade out, swap while the screen is dark, then fade in
        if (leaving) {
            fade = Math.max(0f, fade - dt * 1.4f);
            if (fade <= 0f) newScene();
        } else {
            fade = Math.min(1f, fade + dt * 0.9f);
        }
        sceneAge += dt;
        sinceDrop += dt;
        for (int i = 0; i < BOLTS; i++) blife[i] = Math.max(0f, blife[i] - dt * 2.6f);
        flash = Math.max(0f, flash - dt * 2.4f);
        if (!leaving) {
            if (sceneAge > 12f && sinceDrop > 12f && fastE > slowE * 1.6f + 0.08f) {
                sinceDrop = 0;
                leaving = true;
                dropNext = true;
            } else if (sceneAge > sceneLen) {
                leaving = true;
            }
        }
        if (natural) updateWorld(dt, w, h);
        float gentle = soft ? 0.55f : 1f;

        // a dark veil keeps the visuals readable on any wallpaper; the beat tint is folded into
        // the same fill so the whole screen is blended once, not twice
        float av = 0x8A * (1f - 0.5f * DayLight.k()) * fade / 255f;
        int tint = tone(0.5f, 0.9f, Math.min(1f, bass * 0.35f + kick * 0.4f) * 70f * gentle, 0f);
        float ta = (tint >>> 24) / 255f * (1f - av);
        float oa = av + ta;
        int veil = ((int) (oa * 255f)) << 24;
        if (ta > 0.004f) {
            float k = ta / oa;
            veil |= ((int) (((tint >> 16) & 0xFF) * k) << 16) | ((int) (((tint >> 8) & 0xFF) * k) << 8)
                    | (int) ((tint & 0xFF) * k);
        }
        if (!(natural && has[19])) canvas.drawColor(veil);
        paint.setStyle(Paint.Style.FILL);
        // punch: the scene swells on kicks and leans on snares (a transform only, no extra drawing)
        canvas.save();
        // a smooth clock locked to the tempo, so the picture keeps breathing between detected kicks,
        // and a wind that surges on every kick and snare
        float period = bpm > 0 ? 60f / bpm : 0.5f;
        float bph = (((now - lastKick) / 1000f) % period) / period;
        beat = Math.max(kick, (1f - bph) * (1f - bph) * (1f - bph) * (bpm > 0 ? 0.7f : 0f));
        wind = Math.max(0f, wind - dt * 1.3f);
        windPos += dt * (0.4f + bass * 1.5f + wind * 2.5f);
        boolean th = originOn && theme > 0;
        float zoom = 1f + (kick * 0.035f + bass * 0.012f) * gentle * (th ? PUNCH[theme] : 1f);
        boolean sway = th && SWAY_AMT[theme] > 0f;
        if (sway) zoom += 0.045f;
        canvas.scale(zoom, zoom, w / 2f, h / 2f);
        if (sway) {
            // the picture rocks in time with the song's tempo: shoulders, hips or a slow roll
            swayPh = (swayPh + dt * TWO_PI * (bpm > 0 ? bpm : 100f) / 120f * SWAY_RATE[theme]) % TWO_PI;
            float sw = (float) (Math.sin(swayPh) + 0.3 * Math.sin(swayPh * 3f)) * SWAY_AMT[theme]
                    * (0.7f + 0.6f * beat);
            canvas.translate(sw * w * 0.005f, Math.abs(sw) * h * 0.003f);
            canvas.rotate(sw * 0.7f * gentle, w / 2f, h / 2f);
        }
        if (snare > 0.05f && !natural) canvas.rotate(snare * 0.7f * gentle * spinDir, w / 2f, h / 2f);
        for (int i = 0; i < nLayers; i++) {
            int t = lt[i];
            if (SYM[t] && symN > 1) {
                float cx = w / 2f, cy = h / 2f;
                int copies = lite ? Math.min(symN, 4) : symN;
                float sc = 0.62f - copies * 0.03f;
                for (int k = 0; k < copies; k++) {
                    canvas.save();
                    canvas.translate(cx, cy);
                    canvas.rotate(k * 360f / copies + (float) Math.toDegrees(spin) * 0.1f);
                    if (symMirror && (k & 1) == 1) canvas.scale(1f, -1f);
                    canvas.translate(h * 0.22f, 0);
                    canvas.scale(sc, sc);
                    canvas.translate(-cx, -cy);
                    drawLayer(canvas, w, h, dt, i);
                    canvas.restore();
                }
            } else {
                drawLayer(canvas, w, h, dt, i);
            }
        }
        canvas.restore();
        if (flash > 0.03f) {
            canvas.drawColor(((int) (flash * flash * (soft ? 40f : 110f) * fade)) << 24
                    | (soft ? CREAM : 0xFFFFFF));
        }
        postInvalidateOnAnimation();
    }

    private void drawLayer(Canvas c, float w, float h, float dt, int i) {
        paint.setStyle(Paint.Style.FILL);
        switch (lt[i]) {
            case 0: drawBars(c, w, h, i); break;
            case 1: drawWave(c, w, h, i); break;
            case 2: drawMountains(c, w, h, i); break;
            case 3: drawRing(c, w, h, i); break;
            case 4: drawOrbit(c, w, h, i); break;
            case 5: drawFountain(c, w, h, dt, i); break;
            case 6: drawWarp(c, w, h, dt, i); break;
            case 7: drawRipples(c, w, h, dt, i); break;
            case 8: drawMatrix(c, w, h, i); break;
            case 9: drawSpiro(c, w, h, i); break;
            case 10: drawLightning(c, i); break;
            case 11: drawTunnel(c, w, h, i); break;
            case 12: drawAurora(c, w, h, i); break;
            case 13: drawFireworks(c, h, dt, i); break;
            case 14: drawHelix(c, w, h, i); break;
            case 16: drawSynth(c, w, h, i); break;
            case 17: drawBeams(c, w, h, i); break;
            case 18: drawRays(c, w, h, i); break;
            case 19: drawSky(c, w, h, i); break;
            case 20: drawPeaks(c, w, h, dt, i); break;
            case 21: drawClouds(c, w, h, dt, i); break;
            case 22: drawSea(c, w, h, i); break;
            case 23: drawShore(c, w, h, i); break;
            case 24: drawWeather(c, w, h, dt, i); break;
            case 25: drawWeave(c, w, h, i); break;
            case 26: drawCity(c, w, h, i); break;
            case 27: drawMeadow(c, w, h, i); break;
            default: drawBlob(c, w, h, i);
        }
    }

    private float dp(float v) {
        return Ui.dp(getContext(), v);
    }

    private void bars(Canvas c, float w, float h, int l) {
        float floor = dp(4), off = lh[l];
        if (lv[l] == 2) {
            float bw = (w / 2f) / BARS, bar = bw * 0.72f, r = bar / 2f, mid = h / 2f;
            for (int i = 0; i < BARS; i++) {
                float f = i / (float) BARS;
                float bh = floor + shown[i] * h * 0.4f;
                paint.setColor(tone(f, 1f, 0xE6, off));
                float x = w / 2f + i * bw + (bw - bar) / 2f;
                rect.set(x, mid - bh, x + bar, mid + bh);
                c.drawRoundRect(rect, r, r, paint);
                float x2 = w / 2f - (i + 1) * bw + (bw - bar) / 2f;
                rect.set(x2, mid - bh, x2 + bar, mid + bh);
                c.drawRoundRect(rect, r, r, paint);
            }
            return;
        }
        float slot = w / BARS, bar = slot * (0.5f + 0.3f * lf[l]), r = bar / 2f;
        for (int i = 0; i < BARS; i++) {
            float f = i / (float) BARS;
            float x = i * slot + (slot - bar) / 2f;
            float bh = floor + shown[i] * h * 0.62f;
            paint.setColor(tone(f, 1f, 0xE6, off));
            rect.set(x, h - bh, x + bar, h);
            c.drawRoundRect(rect, r, r, paint);
            if (lv[l] == 1) {
                float th = floor + shown[i] * h * 0.24f;
                paint.setColor(tone(f, 1f, 0x66, off));
                rect.set(x, 0, x + bar, th);
                c.drawRoundRect(rect, r, r, paint);
            }
            paint.setColor(cream(0xF0));
            float cy = h - floor - cap[i] * h * 0.62f - bar;
            rect.set(x, cy - bar * 0.5f, x + bar, cy);
            c.drawRoundRect(rect, r, r, paint);
        }
    }

    private void drawBars(Canvas c, float w, float h, int l) {
        bars(c, w, h, l);
    }

    /** A glowing ribbon whose thickness is the spectrum. */
    private void drawWave(Canvas c, float w, float h, int l) {
        float y0 = lv[l] == 0 ? h * 0.5f : lv[l] == 1 ? h * 0.7f : h * 0.32f;
        path.reset();
        for (int i = 0; i < BARS; i++) {
            float s = (shown[Math.max(0, i - 1)] + shown[i] * 2f + shown[Math.min(BARS - 1, i + 1)]) / 4f;
            float x = i / (float) (BARS - 1) * w;
            float y = y0 - s * h * 0.3f + (float) Math.sin(i * 0.4f + spin * 3f) * dp(6) * (0.3f + bass);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        for (int i = BARS - 1; i >= 0; i--) {
            float s = (shown[Math.max(0, i - 1)] + shown[i] * 2f + shown[Math.min(BARS - 1, i + 1)]) / 4f;
            float x = i / (float) (BARS - 1) * w;
            float y = y0 + s * h * 0.3f + (float) Math.sin(i * 0.4f + spin * 3f) * dp(6) * (0.3f + bass);
            path.lineTo(x, y);
        }
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(tone(0.3f + lf[l] * 0.4f, 0.9f, 0x70, lh[l]));
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setColor(tone(0.8f, 1f, 0xF0, lh[l]));
        c.drawPath(path, paint);
    }

    private void drawMountains(Canvas c, float w, float h, int l) {
        for (int k = 2; k >= 0; k--) {
            float scale = 1f - k * 0.27f;
            float base = h * (0.9f - k * 0.03f);
            path.reset();
            path.moveTo(0, h);
            for (int i = 0; i < BARS; i++) {
                float a = shown[Math.max(0, i - 1)], b = shown[i], d = shown[Math.min(BARS - 1, i + 1)];
                float s = (a + b * 2f + d) / 4f;
                float x = i / (float) (BARS - 1) * w;
                float y = base - s * h * 0.62f * scale - (float) Math.sin(i * 0.35f + k * 2f + spin) * dp(6);
                path.lineTo(x, y);
            }
            path.lineTo(w, h);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(tone(k / 3f, 0.9f, 0x70 + k * 20, lh[l]));
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setColor(tone(k / 3f + 0.15f, 1f, 0xE0, lh[l]));
            c.drawPath(path, paint);
            if (lv[l] == 1 && k == 0) {
                c.save();
                c.scale(1f, -1f, w / 2f, h / 2f);
                paint.setColor(tone(0.8f, 1f, 0x60, lh[l]));
                c.drawPath(path, paint);
                c.restore();
            }
        }
    }

    private void drawRing(Canvas c, float w, float h, int l) {
        float cx = w / 2f, cy = h / 2f;
        float r0 = h * 0.17f * (1f + bass * 0.3f + kick * 0.14f);
        int n = BARS * 2, rep = 1 + lv[l] + (la[l] > 5 ? 1 : 0);
        float step = TWO_PI / n;
        coreDot(c, cx, cy, h, l);
        paint.setStyle(Paint.Style.STROKE);
        int skip = lite ? 2 : 1;
        paint.setStrokeWidth(Math.max(3f, TWO_PI * r0 / n * 0.62f * skip));
        for (int k = 0; k < n; k += skip) {
            int u = (k * rep) % n;
            int idx = u < BARS ? u : n - 1 - u;
            float a = spin + k * step;
            float len = dp(6) + shown[idx] * h * 0.3f;
            float co = (float) Math.cos(a), sn = (float) Math.sin(a);
            paint.setColor(tone(k / (float) n, 1f, 0xF0, lh[l]));
            c.drawLine(cx + co * r0, cy + sn * r0, cx + co * (r0 + len), cy + sn * (r0 + len), paint);
        }
        paint.setStrokeWidth(dp(3));
        paint.setColor(tone(0.2f, 1f, 0x90, lh[l]));
        c.drawCircle(cx, cy, r0 * 0.98f, paint);
        paint.setColor(tone(0.8f, 1f, 0x40, lh[l]));
        c.drawCircle(cx, cy, r0 * (1.2f + bass * 0.25f), paint);
    }

    /** Dots circling the center on rings, one band of the spectrum per dot. */
    private void drawOrbit(Canvas c, float w, float h, int l) {
        float cx = w / 2f, cy = h / 2f;
        int rings = 4 + lv[l] * 2;
        coreDot(c, cx, cy, h, l);
        paint.setStyle(Paint.Style.FILL);
        for (int g = 0; g < rings; g++) {
            float rad = h * (0.1f + 0.065f * g) * (1f + bass * 0.1f);
            int dots = 6 + g * 3;
            float speed = (1f + g * 0.35f) * (g % 2 == 0 ? 1f : -1f);
            for (int j = 0; j < dots; j++) {
                int band = (g * 9 + j * 3) % BARS;
                float a = spin * speed * 1.4f + j * TWO_PI / dots;
                float s = dp(2f) + shown[band] * dp(3.5f);
                float x = cx + (float) Math.cos(a) * rad, y = cy + (float) Math.sin(a) * rad;
                paint.setColor(tone(g / (float) rings, 1f, 0xE6, lh[l]));
                c.drawCircle(x, y, s, paint);
            }
        }
    }

    private void spawnDots(int count, float w, float h) {
        for (int n = 0; n < count; n++) {
            int i = 0;
            float least = Float.MAX_VALUE;
            for (int j = 0; j < DOTS; j++) {
                if (dlife[j] <= 0) {
                    i = j;
                    break;
                }
                if (dlife[j] < least) {
                    least = dlife[j];
                    i = j;
                }
            }
            if (fountainVar == 0) {
                dx[i] = w * (0.1f + 0.8f * rng.nextFloat());
                dy[i] = h * (0.7f + 0.3f * rng.nextFloat());
                dvx[i] = (rng.nextFloat() - 0.5f) * w * 0.05f;
                dvy[i] = -h * (0.10f + 0.22f * rng.nextFloat());
            } else if (fountainVar == 1) {
                float a = rng.nextFloat() * TWO_PI, sp = h * (0.1f + 0.3f * rng.nextFloat());
                dx[i] = w / 2f;
                dy[i] = h / 2f;
                dvx[i] = (float) Math.cos(a) * sp;
                dvy[i] = (float) Math.sin(a) * sp;
            } else {
                dx[i] = w * rng.nextFloat();
                dy[i] = -dp(10);
                dvx[i] = (rng.nextFloat() - 0.5f) * w * 0.03f;
                dvy[i] = h * (0.12f + 0.2f * rng.nextFloat());
            }
            dsize[i] = dp(2.2f + 2.6f * rng.nextFloat()); // crisp specks: no soft disc bigger than about 1% of the screen height
            dlife[i] = 1f;
            dhue[i] = rng.nextFloat();
        }
    }

    private void drawFountain(Canvas c, float w, float h, float dt, int l) {
        if (rng.nextFloat() < high * 1.2f + mid * 0.4f) spawnDots(1, w, h);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < DOTS; i++) {
            if (dlife[i] <= 0) continue;
            dlife[i] -= dt * 0.38f;
            dx[i] += dvx[i] * dt;
            dy[i] += dvy[i] * dt;
            float a = clamp(dlife[i], 0f, 1f);
            paint.setColor(tone(dhue[i], 0.7f, a * 220, lh[l]));
            c.drawCircle(dx[i], dy[i], dsize[i] * (0.6f + a * 0.4f), paint);
        }
    }

    private void drawWarp(Canvas c, float w, float h, float dt, int l) {
        float cx = w / 2f, cy = h / 2f, maxR = (float) Math.hypot(cx, cy) * 1.05f;
        paint.setStyle(Paint.Style.STROKE);
        float boost = 0.15f + bass * 1.3f + kick * 1.6f;
        int stars = lite ? STARS * 2 / 3 : STARS;
        for (int i = 0; i < stars; i++) {
            float prevR = sr[i];
            sr[i] += dt * sv[i] * boost;
            if (sr[i] > 1.05f) {
                resetStar(i, false);
                continue;
            }
            float trail = Math.max(0.01f, (sr[i] - prevR) * 2.4f + 0.02f * boost);
            float t2 = Math.max(0f, sr[i] - trail);
            float r1 = sr[i] * sr[i] * maxR, r2 = t2 * t2 * maxR;
            float co = (float) Math.cos(sa[i] + spin * 0.15f), sn = (float) Math.sin(sa[i] + spin * 0.15f);
            paint.setStrokeWidth(dp(1f + 3.5f * sr[i]));
            paint.setColor(tone(sh[i], 1f, 60 + 195 * sr[i], lh[l]));
            c.drawLine(cx + co * r2, cy + sn * r2, cx + co * r1, cy + sn * r1, paint);
        }
        coreDot(c, cx, cy, h, l);
    }

    private void spawnRipple(int type, float w, float h) {
        int i = 0;
        float least = Float.MAX_VALUE;
        for (int j = 0; j < RIPPLES; j++) {
            if (rl[j] <= 0) {
                i = j;
                break;
            }
            if (rl[j] < least) {
                least = rl[j];
                i = j;
            }
        }
        rr[i] = h * 0.03f;
        rl[i] = 1f;
        rw[i] = dp(type == 0 ? 9 : type == 1 ? 5 : 2.5f);
        rh[i] = rng.nextFloat();
        if (rippleVar == 0) {
            rx[i] = w / 2f;
            ry[i] = h / 2f;
        } else if (rippleVar == 1) {
            rx[i] = w * (0.15f + 0.7f * rng.nextFloat());
            ry[i] = h * (0.2f + 0.6f * rng.nextFloat());
        } else {
            rippleSide ^= 1;
            rx[i] = rippleSide == 0 ? w * 0.28f : w * 0.72f;
            ry[i] = h / 2f;
        }
    }

    private void drawRipples(Canvas c, float w, float h, float dt, int l) {
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < RIPPLES; i++) {
            if (rl[i] <= 0) continue;
            rr[i] += dt * h * 0.5f;
            rl[i] -= dt * 0.55f;
            float a = Math.max(0f, rl[i]);
            paint.setStrokeWidth(rw[i] * (0.4f + a));
            paint.setColor(tone(rh[i], 1f, a * 230, lh[l]));
            c.drawCircle(rx[i], ry[i], rr[i], paint);
        }
        coreDot(c, w / 2f, h / 2f, h, l);
    }

    /** The hot centre of a layer: a small crisp dot and one thin ring that swells on the kick (never a soft blob). */
    private void coreDot(Canvas c, float cx, float cy, float h, int l) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(tone(0.2f, 1f, 0xE0, lh[l]));
        c.drawCircle(cx, cy, h * 0.007f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, h * 0.002f));
        paint.setColor(tone(0.5f, 1f, 0x60 + (int) (kick * 100), lh[l]));
        c.drawCircle(cx, cy, h * (0.02f + bass * 0.03f + kick * 0.015f), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawMatrix(Canvas c, float w, float h, int l) {
        int cols = lv[l] == 0 ? 32 : lv[l] == 1 ? 24 : 40, rows = lv[l] == 0 ? 16 : lv[l] == 1 ? 10 : 20;
        float cw = w / cols, ch = h * 0.86f / rows, gap = dp(3);
        paint.setStyle(Paint.Style.FILL);
        int mode = la[l] % 3;
        for (int col = 0; col < cols; col++) {
            int band = col * BARS / cols;
            float v = (shown[band] + shown[Math.min(BARS - 1, band + 1)]) * 0.5f;
            int lit = Math.round(v * rows);
            int pk = Math.min(rows - 1, Math.round(cap[band] * rows));
            for (int r = 0; r < rows; r++) {
                float x = col * cw + gap / 2f, y = h - (r + 1) * ch - h * 0.05f;
                float f = mode == 0 ? r / (float) rows : mode == 1 ? col / (float) cols
                        : (r + col) / (float) (rows + cols);
                if (r < lit) paint.setColor(tone(f, 1f, 0xF0, lh[l]));
                else if (r == pk && pk > lit) paint.setColor(cream(0xF0));
                else paint.setColor(tone(f, 0.6f, 0x16, lh[l]));
                rect.set(x, y, x + cw - gap, y + ch - gap);
                c.drawRoundRect(rect, dp(4), dp(4), paint);
            }
        }
    }

    private void drawSpiro(Canvas c, float w, float h, int l) {
        float cx = w / 2f, cy = h / 2f;
        float ax = w * 0.27f * (0.7f + bass * 0.6f + kick * 0.25f);
        float ay = h * 0.36f * (0.7f + mid * 0.6f + snare * 0.2f);
        float ph = spin * 1.4f;
        int n = lite ? 160 : 300;
        paint.setStyle(Paint.Style.STROKE);
        for (int pass = lite ? 1 : 0; pass < 2; pass++) {
            paint.setStrokeWidth(pass == 0 ? dp(10) : dp(3));
            float qx = 0, qy = 0;
            for (int i = 0; i <= n; i++) {
                float t = i / (float) n * TWO_PI;
                float wob = 1f + 0.16f * shown[(i * BARS / (n + 1)) % BARS];
                float x = cx + ax * wob * (float) Math.sin(la[l] * t + ph);
                float y = cy + ay * wob * (float) Math.sin(lb[l] * t);
                if (i > 0) {
                    paint.setColor(tone(i / (float) n, 1f, pass == 0 ? 0x2A : 0xF0, lh[l]));
                    c.drawLine(qx, qy, x, y, paint);
                }
                qx = x;
                qy = y;
            }
        }
    }

    private void spawnBolt(float w, float h) {
        int b = boltAt++ % BOLTS;
        float x0 = w * rng.nextFloat(), y0 = 0, x1 = w * (0.25f + 0.5f * rng.nextFloat()),
                y1 = h * (0.45f + 0.5f * rng.nextFloat());
        for (int i = 0; i < BOLT_PTS; i++) {
            float t = i / (float) (BOLT_PTS - 1);
            float jit = (i == 0 || i == BOLT_PTS - 1) ? 0f : (rng.nextFloat() - 0.5f) * w * 0.09f;
            bx[b * BOLT_PTS + i] = x0 + (x1 - x0) * t + jit;
            by[b * BOLT_PTS + i] = y0 + (y1 - y0) * t;
        }
        blife[b] = 1f;
        bhue[b] = rng.nextFloat();
    }

    private void drawLightning(Canvas c, int l) {
        paint.setStyle(Paint.Style.STROKE);
        for (int b = 0; b < BOLTS; b++) {
            if (blife[b] <= 0) continue;
            float a = blife[b];
            for (int pass = lite ? 1 : 0; pass < 2; pass++) {
                paint.setStrokeWidth(pass == 0 ? dp(10) : dp(2.5f));
                paint.setColor(pass == 0 ? tone(bhue[b], 1f, a * 90, lh[l]) : cream(a * 255f));
                for (int i = 1; i < BOLT_PTS; i++) {
                    c.drawLine(bx[b * BOLT_PTS + i - 1], by[b * BOLT_PTS + i - 1],
                            bx[b * BOLT_PTS + i], by[b * BOLT_PTS + i], paint);
                }
            }
        }
    }

    /** Polygons flying toward you, like a tunnel; kicks throw the speed up. */
    private void drawTunnel(Canvas c, float w, float h, int l) {
        float cx = w / 2f, cy = h / 2f, maxR = (float) Math.hypot(cx, cy) * 1.1f;
        int rings = 12, sides = la[l];
        paint.setStyle(Paint.Style.STROKE);
        for (int k = 0; k < rings; k++) {
            float t = (k / (float) rings + tpos) % 1f;
            float r = t * t * maxR * (1f + bass * 0.25f * (1f - t));
            float rot = spin * (0.5f + 0.3f * lb[l] / 7f) + t * 1.2f * (lv[l] - 1);
            paint.setStrokeWidth(dp(1f + 5f * t));
            paint.setColor(tone(t, 1f, 255f * t, lh[l]));
            path.reset();
            for (int s = 0; s <= sides; s++) {
                float a = rot + s * TWO_PI / sides;
                float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
                if (s == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            c.drawPath(path, paint);
        }
    }

    /** Soft bands of light drifting across the top, swelling with the mids. */
    private void drawAurora(Canvas c, float w, float h, int l) {
        int ribbons = 3 + lv[l];
        paint.setStyle(Paint.Style.FILL);
        for (int r = 0; r < ribbons; r++) {
            float yBase = h * (0.18f + 0.14f * r), amp = h * (0.04f + mid * 0.12f);
            float thick = h * (0.08f + shown[(r * 11) % BARS] * 0.18f);
            float freq = 0.004f + 0.002f * ((r + la[l]) % 3);
            for (int pass = lite ? 1 : 0; pass < 2; pass++) {
                float tk = thick * (pass == 0 ? 1.7f : 1f);
                path.reset();
                for (int x = 0; x <= 24; x++) {
                    float px2 = x / 24f * w;
                    float y = yBase + (float) Math.sin(px2 * freq * 6f + spin * (1.2f + r * 0.3f) + r * 2f) * amp;
                    if (x == 0) path.moveTo(px2, y);
                    else path.lineTo(px2, y);
                }
                for (int x = 24; x >= 0; x--) {
                    float px2 = x / 24f * w;
                    float y = yBase + (float) Math.sin(px2 * freq * 6f + spin * (1.2f + r * 0.3f) + r * 2f) * amp;
                    path.lineTo(px2, y + tk);
                }
                path.close();
                paint.setColor(tone(r / (float) ribbons, 0.9f, pass == 0 ? 0x30 : 0x6A, lh[l]));
                c.drawPath(path, paint);
            }
        }
    }

    private void spawnFirework(float w, float h) {
        float cx = w * (0.15f + 0.7f * rng.nextFloat()), cy = h * (0.15f + 0.4f * rng.nextFloat());
        float hu = rng.nextFloat(), power = h * (0.25f + 0.25f * rng.nextFloat());
        int made = 0;
        for (int i = 0; i < SPARKS && made < 28; i++) {
            if (plife[i] > 0) continue;
            float a = made * TWO_PI / 28f + rng.nextFloat() * 0.2f;
            float sp = power * (0.6f + 0.4f * rng.nextFloat());
            px[i] = cx;
            py[i] = cy;
            pvx[i] = (float) Math.cos(a) * sp;
            pvy[i] = (float) Math.sin(a) * sp;
            plife[i] = 1f;
            phue[i] = hu + rng.nextFloat() * 0.15f;
            made++;
        }
    }

    private void drawFireworks(Canvas c, float h, float dt, int l) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        for (int i = 0; i < SPARKS; i++) {
            if (plife[i] <= 0) continue;
            plife[i] -= dt * 0.7f;
            pvy[i] += h * 0.18f * dt;
            pvx[i] *= 1f - dt * 0.6f;
            pvy[i] *= 1f - dt * 0.4f;
            px[i] += pvx[i] * dt;
            py[i] += pvy[i] * dt;
            float a = clamp(plife[i], 0f, 1f);
            paint.setColor(tone(phue[i], 1f, a * 255, lh[l]));
            c.drawLine(px[i], py[i], px[i] - pvx[i] * 0.05f, py[i] - pvy[i] * 0.05f, paint);
        }
    }

    /** Two twisting strands with rungs, wider where the music is louder. */
    private void drawHelix(Canvas c, float w, float h, int l) {
        float cy = lv[l] == 0 ? h * 0.5f : lv[l] == 1 ? h * 0.32f : h * 0.68f;
        int steps = 48;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < steps; i++) {
            float f = i / (float) (steps - 1), x = f * w;
            float amp = h * (0.06f + shown[i * BARS / steps] * 0.26f);
            float ph = i * 0.33f + spin * 2f;
            float s = (float) Math.sin(ph), d = (float) Math.cos(ph);
            float y1 = cy + s * amp, y2 = cy - s * amp;
            if (i % 3 == 0) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(tone(f, 1f, 0x55, lh[l]));
                c.drawLine(x, y1, x, y2, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            paint.setColor(tone(f, 1f, 0xE6, lh[l]));
            c.drawCircle(x, y1, dp(2.6f + 1.6f * d), paint);
            paint.setColor(tone(f + 0.4f, 1f, 0xE6, lh[l]));
            c.drawCircle(x, y2, dp(2.6f - 1.6f * d), paint);
        }
    }

    /** A pulsing organic shape whose edge is the mirrored spectrum. */
    /** The singer: a silhouette with a neon rim, radial spectrum bars behind it. Without a baked sheet only the bars show. */
    private void drawBlob(Canvas c, float w, float h, int l) {
        float cx = w / 2f, hy = h * 0.5f, headH = h * 0.36f;
        singer.prepare(song.hashCode() * 31 + l);
        float r0 = h * 0.27f;
        int n = BARS * 2;
        float step = TWO_PI / n;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Math.max(3f, TWO_PI * r0 / n * 0.55f));
        for (int k = 0; k < n; k++) {
            int idx = k < BARS ? k : n - 1 - k;
            float a = spin * 0.3f + k * step;
            float len = dp(5) + shown[idx] * h * 0.22f;
            float co = (float) Math.cos(a), sn = (float) Math.sin(a);
            paint.setColor(tone(k / (float) n, 1f, 0xD8, lh[l]));
            c.drawLine(cx + co * r0, hy + sn * r0, cx + co * (r0 + len), hy + sn * (r0 + len), paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        if (singer.ready()) {
            int rim = tone(0.55f, 1f, 255f, lh[l]) | 0xFF000000;
            singer.draw(c, cx, hy, headH, 0.016f, mid, kick, snare, rim, 255);
        }
    }

    /** Retro sunset: a striped sun, the spectrum as a skyline, and a grid rushing toward you. */
    private void drawSynth(Canvas c, float w, float h, int l) {
        float cx = w / 2f, hz = h * 0.58f;
        paint.setStyle(Paint.Style.FILL);
        if (lv[l] != 1) {
            float r = h * 0.2f * (1f + bass * 0.12f + kick * 0.08f), cy = hz - r * 0.55f;
            int bands = 10;
            float bh = (hz - (cy - r)) / bands;
            for (int k = 0; k < bands; k++) {
                float f = k / (float) bands, y0 = cy - r + k * bh, y1 = y0 + bh * (0.95f - 0.6f * f);
                float dy = (y0 + y1) / 2f - cy, hw = (float) Math.sqrt(Math.max(0f, r * r - dy * dy));
                paint.setColor(tone(1f - f, 1f, 0xE0, lh[l]));
                rect.set(cx - hw, y0, cx + hw, y1);
                c.drawRect(rect, paint);
            }
        }
        if (lv[l] != 2) {
            path.reset();
            path.moveTo(0, hz);
            for (int i = 0; i < BARS; i++) {
                float s = (shown[Math.max(0, i - 1)] + shown[i] * 2f + shown[Math.min(BARS - 1, i + 1)]) / 4f;
                path.lineTo(i / (float) (BARS - 1) * w, hz - dp(4) - s * h * 0.3f);
            }
            path.lineTo(w, hz);
            path.close();
            paint.setColor(tone(0.85f, 0.9f, 0xC0, lh[l]));
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setColor(tone(0.3f, 1f, 0xF0, lh[l]));
            c.drawPath(path, paint);
        }
        if (!lite) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x66000000);
            rect.set(0, hz, w, h);
            c.drawRect(rect, paint);
        }
        int rows = 9, cols = 15, n = 0;
        for (int k = 0; k < rows; k++) {
            float t = (k / (float) rows + tpos) % 1f, y = hz + (h - hz) * t * t;
            lines[n++] = 0;
            lines[n++] = y;
            lines[n++] = w;
            lines[n++] = y;
        }
        float spread = w * 0.18f * (1f + kick * 0.08f);
        for (int i = 0; i < cols; i++) {
            lines[n++] = cx + (i - 7) * w * 0.012f;
            lines[n++] = hz;
            lines[n++] = cx + (i - 7) * spread;
            lines[n++] = h;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f + bass * 3f));
        paint.setColor(tone(0.6f, 1f, 0xE0, lh[l]));
        c.drawLines(lines, 0, n, paint);
    }

    /** Stage lights: wide beams from the floor (or ceiling) sweeping with the music. */
    private void drawBeams(Canvas c, float w, float h, int l) {
        int n = 3 + lv[l];
        float dir = lv[l] == 2 ? 1f : -1f, oy = lv[l] == 2 ? -h * 0.03f : h * 1.03f, len = h * 1.7f;
        float half = 0.035f + bass * 0.06f + kick * 0.05f;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            float ox = w * (i + 0.5f) / n;
            float a = (float) Math.sin(spin * (1.1f + 0.25f * i) * 1.5f + i * 1.7f) * (0.55f + 0.25f * mid);
            for (int pass = 0; pass < (lite ? 1 : 2); pass++) {
                float k = pass == 0 ? 1f : 0.3f;
                path.reset();
                path.moveTo(ox, oy);
                path.lineTo(ox + (float) Math.sin(a - half * k) * len, oy + dir * (float) Math.cos(a - half * k) * len);
                path.lineTo(ox + (float) Math.sin(a + half * k) * len, oy + dir * (float) Math.cos(a + half * k) * len);
                path.close();
                paint.setColor(tone(i / (float) n, 1f, pass == 0 ? 0x38 + kick * 0x40 + shown[i * 8] * 0x30 : 0xA0, lh[l]));
                c.drawPath(path, paint);
            }
        }
    }

    /** A sunburst: one spike per band of the mirrored spectrum, three interleaved colors. */
    private void drawRays(Canvas c, float w, float h, int l) {
        float cx = w / 2f, cy = h / 2f;
        int n = 36 + lv[l] * 12;
        float r0 = h * 0.07f * (1f + kick * 0.5f + bass * 0.3f), step = TWO_PI / n;
        float hw = step * (0.28f + 0.12f * lf[l]);
        paint.setStyle(Paint.Style.FILL);
        for (int g = 0; g < 3; g++) {
            path.reset();
            for (int i = g; i < n; i += 3) {
                float u = Math.abs(i / (float) n * 2f - 1f);
                float len = r0 + h * (0.04f + shown[(int) (u * (BARS - 1) * 0.8f)] * 0.5f);
                float a = spin * 0.6f + i * step;
                path.moveTo(cx + (float) Math.cos(a - hw) * r0, cy + (float) Math.sin(a - hw) * r0);
                path.lineTo(cx + (float) Math.cos(a) * len, cy + (float) Math.sin(a) * len);
                path.lineTo(cx + (float) Math.cos(a + hw) * r0, cy + (float) Math.sin(a + hw) * r0);
                path.close();
            }
            paint.setColor(tone(g / 3f, 1f, 0xD0, lh[l]));
            c.drawPath(path, paint);
        }
        coreDot(c, cx, cy, h, l);
    }

    // ---- nature scenes -----------------------------------------------------------------------

    /** Shared sky state: time of day drifts day, dusk, night over about five minutes. */
    private void updateWorld(float dt, float w, float h) {
        p += dt / 300f;
        if (p >= 1f) p -= 1f;
        tod = 0.5f - 0.5f * (float) Math.cos(p * TWO_PI);
        skyTopC = Variety.cap(stage(TOP[pal]) & 0xFFFFFF);
        skyHorC = Variety.cap(stage(HOR[pal]) & 0xFFFFFF);
        sunA = clamp((0.66f - tod) / 0.16f, 0f, 1f);
        moonA = clamp((tod - 0.54f) / 0.16f, 0f, 1f);
        sunC = originOn && theme == 1 ? lerpC(0xFFF2C0, 0xF6C85A, clamp(tod * 2f, 0f, 1f))
                : lerpC(0xF7E7B0, 0xF2B07A, clamp(tod * 2f, 0f, 1f));
        bodyX = w * bodyFx;
        sunY = h * (0.2f + 0.42f * clamp(tod * 1.8f, 0f, 1f));
        // sun and moon keep clear of the now-playing card and its margin
        if (keepOn && bodyX + h * 0.2f > keepL && bodyX - h * 0.2f < keepR && Math.max(sunY, h * 0.22f) - h * 0.2f < keepB) {
            bodyX = Math.max(bodyX, keepR + h * 0.22f);
        }
        moonY = h * 0.22f;
        if (sunA >= moonA) {
            bodyY = sunY;
            bodyC = sunC;
            bodyVis = sunA;
        } else {
            bodyY = moonY;
            bodyC = 0xE6E8F0;
            bodyVis = moonA;
        }
        for (int i = 0; i < NC; i++) puff[i] = Math.max(0f, puff[i] - dt * 2.5f);
        // the tide: a kick sends a wave up the beach, then it slowly draws back
        wash += washDir * dt;
        if (wash >= 1f) {
            wash = 1f;
            washDir = -0.45f;
        } else if (wash <= 0f) {
            wash = 0f;
            if (washDir < 0f) washDir = 0f;
        }
        if (washDir == 0f) {
            washIdle += dt;
            if (washIdle > 4f) {
                washDir = 1f;
                washIdle = 0f;
            }
        } else {
            washIdle = 0f;
        }
    }

    /** Sky gradient, twinkling stars, and a sun or moon that breathes with the bass. */
    private void drawSky(Canvas c, float w, float h, int l) {
        float hz = h * 0.62f;
        int key = (int) (tod * 48f) + pal * 100;
        if (key != skyKey || skyH != h) {
            skyKey = key;
            skyH = h;
            skyPaint.setShader(new LinearGradient(0, 0, 0, hz, skyTopC, skyHorC, Shader.TileMode.CLAMP));
        }
        skyPaint.setAlpha((int) (0xE0 * fade));
        c.drawRect(-dp(24), -dp(24), w + dp(24), h + dp(24), skyPaint);
        if (tod > 0.4f) {
            float sa = clamp((tod - 0.4f) * 3f, 0f, 1f);
            paint.setStyle(Paint.Style.STROKE);
            for (int b = 0; b < 2; b++) {
                int n = 0;
                for (int i = b; i < NS; i += 2) {
                    starPts[n++] = stx[i] * w;
                    starPts[n++] = sty[i] * hz;
                }
                // one half shimmers slowly, the other twinkles on every hi-hat
                float al = b == 0 ? 0.4f + 0.25f * (float) Math.sin(spin * 2f) : 0.25f + hat * 0.75f;
                paint.setStrokeWidth(dp(b == 0 ? 2f : 2f + hat * 2.5f));
                paint.setColor(withA(CREAM, sa * al * 255f));
                c.drawPoints(starPts, 0, n, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        if (sunA > 0.02f) {
            float r = h * 0.07f * (1f + bass * 0.14f + beat * 0.08f);
            paint.setColor(withA(sunC, 0xE0 * sunA));
            c.drawCircle(bodyX, sunY, r, paint);
            int n = 0;
            float r2 = r * (1.7f + bass * 0.9f + kick * 0.4f);
            for (int k = 0; k < 12; k++) {
                float a = spin * 0.25f + k * TWO_PI / 12f, co = (float) Math.cos(a), sn = (float) Math.sin(a);
                lines[n++] = bodyX + co * r * 1.35f;
                lines[n++] = sunY + sn * r * 1.35f;
                lines[n++] = bodyX + co * r2;
                lines[n++] = sunY + sn * r2;
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.5f));
            paint.setColor(withA(sunC, 0x70 * sunA));
            c.drawLines(lines, 0, n, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        if (moonA > 0.02f) {
            float r = h * 0.055f * (1f + bass * 0.1f + kick * 0.04f);
            paint.setColor(withA(0xE6E8F0, 0xE0 * moonA));
            c.drawCircle(bodyX, moonY, r, paint);
            if (lv[l] == 1) {
                // a crescent: cover part of the moon with the sky color
                paint.setColor(withA(lerpC(skyTopC, skyHorC, moonY / hz), 0xFF * moonA));
                c.drawCircle(bodyX + r * 0.45f, moonY - r * 0.15f, r * 0.92f, paint);
            }
        }
    }

    /** Soft clouds that drift with the bass, puff on kicks and are lit from the sun or moon. */
    private void drawClouds(Canvas c, float w, float h, float dt, int l) {
        if (CLOUDS_OFF) return; // round cloud puffs read as bokeh blobs
        int n = lite ? 5 : (lv[l] == 0 ? 5 : lv[l] == 1 ? 7 : 8);
        int base = stage(originOn && theme == 1 ? CLOUD_ETH : CLOUDC);
        int hi = lerpC(base, bodyC, 0.55f);
        float a = 0x98 - tod * 0x30;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            cloudX[i] += dt * (0.006f + bass * 0.02f + wind * 0.02f) * cloudV[i];
            if (cloudX[i] > 1.3f) cloudX[i] = -0.3f;
            float u = h * 0.055f * cloudS[i] * (1f + puff[i] * 0.14f);
            float x = cloudX[i] * w, y = cloudY[i] * h + (float) Math.sin(spin + i) * dp(3);
            paint.setColor(withA(base, a));
            for (int k = 0; k < 5; k++) c.drawCircle(x + CCX[k] * u, y + CCY[k] * u, CCR[k] * u, paint);
            if (!lite) {
                float side = (bodyX > x ? 0.2f : -0.2f) * u;
                paint.setColor(withA(hi, 0x50 * bodyVis));
                for (int k = 0; k < 3; k++) {
                    c.drawCircle(x + CCX[k] * u + side, y + CCY[k] * u - u * 0.1f, CCR[k] * u * 0.7f, paint);
                }
            }
        }
    }

    /** Layered ranges that drift with the bass; far ones fade into the haze. */
    private void drawPeaks(Canvas c, float w, float h, float dt, int l) {
        int rs = lite ? 3 : RANGES;
        float ak = originOn && theme == 8 ? 0.6f : 1f;
        boolean mesa = originOn && theme == 1;
        int key = (int) (tod * 48f) + pal * 100 + lv[l] * 1000 + rs * 10000 + (mesa ? 1 : 0) * 100000;
        if (key != peakKey || peakH != h) {
            peakKey = key;
            peakH = h;
            int deep = lerpC(skyTopC, 0x0F1420, 0.62f);
            float mist = lv[l] == 1 ? 0.9f : 0.72f;
            for (int r = 0; r < rs; r++) {
                float d = r / (float) (rs - 1);
                int top = mesa ? lerpC(lerpC(lerpC(0xB4C490, 0x3F5D30, d), 0x0E1C24, tod * 0.85f), skyHorC, (1f - d) * 0.35f)
                        : lerpC(skyHorC, deep, 0.2f + 0.8f * d);
                int bot = lerpC(top, skyHorC, mist * (1f - 0.75f * d));
                float baseY = h * (0.60f + 0.20f * d), amp = h * (0.15f + 0.13f * d) * ak;
                rangeShader[r] = new LinearGradient(0, baseY - amp, 0, baseY + amp * 0.9f, top, bot,
                        Shader.TileMode.CLAMP);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha((int) (255 * fade));
        for (int r = 0; r < rs; r++) {
            float d = r / (float) (rs - 1), nd = (r + 1) / (float) (rs - 1);
            float baseY = h * (0.60f + 0.20f * d), amp = h * (0.15f + 0.13f * d) * ak;
            float bottom = r == rs - 1 ? h + dp(4)
                    : h * (0.60f + 0.20f * nd) - h * (0.15f + 0.13f * nd) * ak * 0.4f + dp(6);
            roff[r] += dt * (0.004f + 0.012f * d) * (0.5f + bass * 2.2f + wind * 1.5f);
            roff[r] -= (float) Math.floor(roff[r]);
            path.reset();
            path.moveTo(0, bottom);
            for (int i = 0; i <= 24; i++) {
                float m = (i / 24f * (0.9f + 0.35f * r) + roff[r]) * RN;
                int ia = (int) m;
                float fr = m - ia;
                float v = ridge[r][ia % RN] * (1f - fr) + ridge[r][(ia + 1) % RN] * fr;
                // highland plateaus: flat tops with steep sides
                if (mesa) v = clamp((v - 0.35f) * 2f + 0.35f, 0.35f, 0.75f);
                if (r >= rs - 2) v += shown[(i * 2 + r * 7) % BARS] * 0.22f * d;
                path.lineTo(i / 24f * w, baseY - v * amp);
            }
            path.lineTo(w, bottom);
            path.close();
            paint.setShader(rangeShader[r]);
            paint.setAlpha((int) (255 * fade));
            c.drawPath(path, paint);
        }
        paint.setShader(null);
    }

    /** Water whose swell is the spectrum, foam on snares and a shimmering sun or moon path. */
    private void drawSea(Canvas c, float w, float h, int l) {
        float yH = h * (0.50f + 0.05f * lv[l]);
        int bands = lite ? 2 : 3;
        int far = lerpC(skyHorC, skyTopC, 0.35f), deep = lerpC(skyTopC, 0x0C1626, 0.5f);
        float span = h - yH;
        for (int k = 0; k < bands; k++) {
            float y0 = yH + span * k * 0.3f;
            float amp = h * (0.008f + 0.008f * k) * (1f + bass * 1.4f);
            float bottom = k == bands - 1 ? h + dp(4) : yH + span * (k + 1) * 0.3f + h * 0.04f;
            path.reset();
            path2.reset();
            path.moveTo(0, bottom);
            for (int i = 0; i <= 32; i++) {
                float x = i / 32f * w;
                float sp = shown[(i * (BARS - 1) / 32 + k * 5) % BARS];
                float y = y0 + (float) Math.sin(i * (0.55f + 0.2f * k) + spin * (2f + k)) * amp
                        - sp * h * (0.05f + 0.02f * k);
                path.lineTo(x, y);
                if (i == 0) path2.moveTo(x, y);
                else path2.lineTo(x, y);
            }
            path.lineTo(w, bottom);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withA(lerpC(far, deep, 0.15f + 0.75f * k / (float) (bands - 1)), 0xF2));
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2f + snare * 3f));
            paint.setColor(cream(0x28 + snare * 0xA0));
            c.drawPath(path2, paint);
        }
        if (bodyVis > 0.05f) {
            paint.setStyle(Paint.Style.FILL);
            for (int j = 0; j < 14; j++) {
                float t = j / 14f, y = yH + span * t * t * 0.9f + dp(4);
                float hw = (dp(8) + t * dp(80)) * (0.55f + 0.45f * (float) Math.sin(spin * 6f + j * 1.9f + hat * 2.5f));
                rect.set(bodyX - hw, y, bodyX + hw, y + dp(2.5f) + t * dp(4));
                paint.setColor(withA(bodyC, (1f - t) * 0x90 * bodyVis * (0.7f + 0.5f * hat)));
                c.drawRect(rect, paint);
            }
        }
    }

    /** A beach: the surf runs up the sand on each kick, leaving wet sand; gulls by day. */
    private void drawShore(Canvas c, float w, float h, int l) {
        float yH = h * 0.5f, yMin = h * 0.68f, yMax = h * 0.84f, ys = yMin + (yMax - yMin) * wash;
        int sand = stage(SAND), wet = lerpC(sand, skyTopC, 0.35f);
        int water = lerpC(lerpC(skyHorC, skyTopC, 0.4f), 0x0C1626, 0.25f);
        for (int i = 0; i <= 32; i++) {
            edgeY[i] = ys + (float) Math.sin(i * 0.5f + spin * 2f) * dp(5)
                    + shown[(i * (BARS - 1) / 32) % BARS] * h * 0.03f;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withA(sand, 0xFF));
        rect.set(0, yMin - dp(12), w, h + dp(4));
        c.drawRect(rect, paint);
        path.reset();
        path.moveTo(0, yMax + h * 0.02f);
        for (int i = 0; i <= 32; i++) path.lineTo(i / 32f * w, edgeY[i]);
        path.lineTo(w, yMax + h * 0.02f);
        path.close();
        paint.setColor(withA(wet, 0xE0));
        c.drawPath(path, paint);
        path.reset();
        path.moveTo(0, yH);
        path.lineTo(w, yH);
        for (int i = 32; i >= 0; i--) path.lineTo(i / 32f * w, edgeY[i]);
        path.close();
        paint.setColor(withA(water, 0xF0));
        c.drawPath(path, paint);
        path2.reset();
        for (int i = 0; i <= 32; i++) {
            if (i == 0) path2.moveTo(0, edgeY[i]);
            else path2.lineTo(i / 32f * w, edgeY[i]);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3f + snare * 4f));
        paint.setColor(cream(0x70 + snare * 0x80));
        c.drawPath(path2, paint);
        if (!lite) {
            path2.offset(0, -dp(12));
            paint.setStrokeWidth(dp(2f));
            paint.setColor(cream(0x28 + hat * 0x50));
            c.drawPath(path2, paint);
        }
        if (tod < 0.55f) {
            float vis = clamp(1f - tod * 1.6f, 0f, 1f);
            int n = 0;
            for (int g = 0; g < 3; g++) {
                float gx = w * (0.25f + 0.25f * g) + (float) Math.sin(spin * 0.7f + g * 2f) * w * 0.06f;
                float gy = h * (0.16f + 0.07f * g) + (float) Math.sin(spin * 1.1f + g) * dp(10);
                float flap = (float) Math.sin(spin * 9f + g * 1.3f) * (dp(5) + bass * dp(8));
                float half = dp(16);
                lines[n++] = gx - half;
                lines[n++] = gy - flap;
                lines[n++] = gx;
                lines[n++] = gy;
                lines[n++] = gx;
                lines[n++] = gy;
                lines[n++] = gx + half;
                lines[n++] = gy - flap;
            }
            paint.setStrokeWidth(dp(2.5f));
            paint.setColor(cream(0xD0 * vis));
            c.drawLines(lines, 0, n, paint);
        }
    }

    /** From the Stage: how much rain and bokeh to show (bursts on beats and drops), and the now-playing card to keep clear. */
    float wxBurst = 1f, keepL, keepT, keepR, keepB;
    boolean keepOn;

    /** Rain (0), snow (1) or fireflies (2); every particle lives in preallocated arrays. */
    private void drawWeather(Canvas c, float w, float h, float dt, int l) {
        int mode = lv[l];
        if (mode == 0) {
            int n = (int) ((lite ? 55 : 90) * wxBurst), k = 0;
            float fall = 0.9f + bass * 0.5f, slant = 0.05f + mid * 0.04f;
            for (int i = 0; i < n; i++) {
                wy[i] += dt * fall * (0.7f + 0.6f * wv[i]);
                wx[i] -= dt * slant;
                if (wy[i] > 1.05f) {
                    wy[i] = -0.05f;
                    wx[i] = rng.nextFloat() * 1.1f;
                }
                if (wx[i] < -0.05f) wx[i] += 1.1f;
                float x = wx[i] * w, y = wy[i] * h, len = dp(10f + 14f * wv[i]);
                rainLines[k++] = x;
                rainLines[k++] = y;
                rainLines[k++] = x + len * slant * 3f;
                rainLines[k++] = y - len;
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.4f));
            paint.setColor(withA(0xBFD0E0, 0x80 * (0.4f + 0.6f * wxBurst)));
            c.drawLines(rainLines, 0, k, paint);
        } else if (mode == 1) {
            int n = lite ? 50 : 80;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(cream(0xC0));
            for (int i = 0; i < n; i++) {
                wy[i] += dt * (0.05f + 0.09f * wv[i]) * (1f + bass * 0.8f + kick * 0.5f);
                wx[i] += (float) Math.sin(spin * 0.7f + wph[i]) * dt * 0.02f;
                if (wy[i] > 1.03f) {
                    wy[i] = -0.03f;
                    wx[i] = rng.nextFloat();
                }
                if (wx[i] < 0f) wx[i] += 1f;
                else if (wx[i] > 1f) wx[i] -= 1f;
                c.drawCircle(wx[i] * w, wy[i] * h, dp(1.5f + 2.8f * wv[i]), paint);
            }
        } else if (mode == 2) {
            int n = 0; // the bokeh lights were removed (too bright)
            float vis = clamp(0.25f + tod * 1.2f, 0f, 1f) * (0.2f + 0.8f * wxBurst);
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < n; i++) {
                wx[i] += (float) Math.cos(spin * 0.5f + wph[i] * 1.7f) * dt * 0.02f;
                wy[i] += (float) Math.sin(spin * 0.4f + wph[i]) * dt * 0.015f;
                if (wx[i] < 0f) wx[i] += 1f;
                else if (wx[i] > 1f) wx[i] -= 1f;
                if (wy[i] < 0.4f) wy[i] = 0.4f;
                else if (wy[i] > 1f) wy[i] = 1f;
                float blink = clamp(0.5f + 0.5f * (float) Math.sin(spin * 3f + wph[i] * 3f) * 0.6f + hat * 0.5f, 0f, 1f);
                float x = wx[i] * w, y = wy[i] * h;
                if (keepOn && x > keepL - dp(14f) && x < keepR + dp(14f) && y > keepT - dp(14f) && y < keepB + dp(14f)) continue;
                paint.setColor(withA(0xE6EEA8, (0x90 + 0x70 * blink) * vis));
                c.drawCircle(x, y, dp(2.2f), paint);
            }
        } else if (mode == 3) {
            // blossom petals drifting across
            int n = lite ? 30 : 50;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withA(0xEBB9C4, 0xB0));
            for (int i = 0; i < n; i++) {
                wy[i] += dt * (0.04f + 0.06f * wv[i]) * (1f + bass * 0.6f);
                wx[i] += dt * (0.03f + (float) Math.sin(spin * 0.8f + wph[i]) * 0.03f);
                if (wy[i] > 1.03f) {
                    wy[i] = -0.03f;
                    wx[i] = rng.nextFloat() * 1.1f - 0.1f;
                }
                if (wx[i] > 1.05f) wx[i] -= 1.1f;
                float x = wx[i] * w, y = wy[i] * h, sz = dp(4f + 5f * wv[i]);
                rect.set(x - sz, y - sz * 0.55f, x + sz, y + sz * 0.55f);
                c.drawOval(rect, paint);
            }
        } else if (mode == 4) {
            // embers rising and flickering
            int n = lite ? 30 : 50;
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < n; i++) {
                wy[i] -= dt * (0.08f + 0.12f * wv[i]) * (1f + bass + kick * 0.6f);
                wx[i] += (float) Math.sin(spin + wph[i] * 2f) * dt * 0.03f;
                if (wy[i] < -0.03f) {
                    wy[i] = 1.03f;
                    wx[i] = rng.nextFloat();
                }
                float fl = 0.6f + 0.4f * (float) Math.sin(spin * 5f + wph[i] * 3f) + hat * 0.3f;
                float x = wx[i] * w, y = wy[i] * h;
                paint.setColor(withA(0xF2B070, 0xC0 * fl));
                c.drawCircle(x, y, dp(1.5f + 2f * wv[i]), paint);
            }
        } else {
            // hearts floating up, swelling on the kick
            int n = lite ? 7 : 10;
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < n; i++) {
                wy[i] -= dt * (0.05f + 0.05f * wv[i]);
                wx[i] += (float) Math.sin(spin * 0.9f + wph[i]) * dt * 0.02f;
                if (wy[i] < -0.08f) {
                    wy[i] = 1.08f;
                    wx[i] = rng.nextFloat();
                }
                float x = wx[i] * w, y = wy[i] * h, sz = dp(8f + 10f * wv[i]) * (1f + kick * 0.25f);
                path.reset();
                path.moveTo(x, y + sz);
                path.cubicTo(x - sz * 1.3f, y - sz * 0.1f, x - sz * 0.6f, y - sz * 1.1f, x, y - sz * 0.4f);
                path.cubicTo(x + sz * 0.6f, y - sz * 1.1f, x + sz * 1.3f, y - sz * 0.1f, x, y + sz);
                paint.setColor(withA(0xE59AAE, 0xA0 + kick * 0x40));
                c.drawPath(path, paint);
            }
        }
    }

    // ---- origin motifs -------------------------------------------------------------------------

    /** A woven-cloth style border along the bottom and top edges, in the theme's own pattern. */
    private void drawWeave(Canvas c, float w, float h, int l) {
        int pat = theme > 0 ? THEME_PAT[theme] : 0;
        if (pat < 0) pat = 0;
        int n = (36 + lv[l] * 6) * (lite ? 2 : 3) / 3;
        float cw = w / n, sc = (tpos * 3f % 2f) * cw;
        weaveBand(c, w, h, h * 0.075f, -1f, pat, n, cw, sc, l);
        weaveBand(c, w, 0f, h * 0.035f, 1f, pat, n, cw, -sc, l);
    }

    private void weaveBand(Canvas c, float w, float base, float bh, float d, int pat, int n, float cw,
                           float sc, int l) {
        float mid = base + d * bh * 0.5f;
        int ca = tone(0.15f, 1f, 0xB0, 0f), cb = tone(0.7f, 1f, 0xB0, 0f);
        paint.setStyle(Paint.Style.FILL);
        float y1 = base + d * bh * 1.08f, y2 = y1 + d * dp(3), y3 = y2 + d * dp(3);
        paint.setColor(ca);
        rect.set(0, Math.min(y1, y2), w, Math.max(y1, y2));
        c.drawRect(rect, paint);
        paint.setColor(cb);
        rect.set(0, Math.min(y2, y3), w, Math.max(y2, y3));
        c.drawRect(rect, paint);
        path.reset();
        path2.reset();
        for (int i = -1; i <= n; i++) {
            float x0 = i * cw - sc, cx = x0 + cw / 2f;
            float pop = 0.7f + 0.6f * shown[(Math.abs(i) * (BARS - 1) / n) % BARS];
            Path a = (i & 1) == 0 ? path : path2;
            switch (pat) {
                case 0: {
                    float r = cw * 0.55f * pop, hy = bh * 0.5f * pop;
                    a.moveTo(cx - r, mid);
                    a.lineTo(cx, mid - hy);
                    a.lineTo(cx + r, mid);
                    a.lineTo(cx, mid + hy);
                    a.close();
                    break;
                }
                case 1:
                    a.moveTo(x0, base);
                    a.lineTo(x0 + cw, base);
                    a.lineTo(cx, base + d * bh * pop);
                    a.close();
                    break;
                case 2:
                case 5: {
                    float r = cw * (pat == 2 ? 0.75f : 0.95f) * (pat == 5 ? pop : 1f);
                    float cy = pat == 2 ? mid : base;
                    float st = d < 0 ? 180f : 0f;
                    rect.set(cx - r, cy - r, cx + r, cy + r);
                    a.addArc(rect, st, 180f);
                    rect.set(cx - r * 0.62f, cy - r * 0.62f, cx + r * 0.62f, cy + r * 0.62f);
                    a.addArc(rect, st, 180f);
                    break;
                }
                case 3:
                    if ((i & 1) == 0) {
                        float ro = Math.min(cw * 0.95f * pop, bh * 0.52f), ri = ro * 0.55f;
                        for (int k = 0; k < 16; k++) {
                            float rr2 = (k & 1) == 0 ? ro : ri;
                            float px2 = cx + cw / 2f + STAR_C[k] * rr2, py2 = mid + STAR_S[k] * rr2;
                            if (k == 0) a.moveTo(px2, py2);
                            else a.lineTo(px2, py2);
                        }
                        a.close();
                    }
                    break;
                case 4: {
                    float hh = bh * 0.5f;
                    for (int r = 0; r < 2; r++) {
                        if (((i + r) & 1) == 0) {
                            float ya = base + d * r * hh, yb = ya + d * hh;
                            a.addRect(x0, Math.min(ya, yb), x0 + cw, Math.max(ya, yb), Path.Direction.CW);
                        }
                    }
                    break;
                }
                default: {
                    float rad = cw * 0.22f * pop;
                    a.addCircle(cx, base + d * bh * 0.3f, rad, Path.Direction.CW);
                    if (!lite) a.addCircle(cx + cw / 2f, base + d * bh * 0.75f, rad, Path.Direction.CW);
                }
            }
        }
        boolean line = pat == 2 || pat == 5;
        if (line) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.5f));
        }
        paint.setColor(ca);
        c.drawPath(path, paint);
        paint.setColor(cb);
        c.drawPath(path2, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Two rows of buildings; lit windows flicker on the hi-hats. */
    private void drawCity(Canvas c, float w, float h, int l) {
        int back = lerpC(skyHorC, skyTopC, 0.55f), front = lerpC(skyTopC, 0x0A0E18, 0.7f);
        paint.setStyle(Paint.Style.FILL);
        for (int row = 0; row < 2; row++) {
            path.reset();
            path.moveTo(0, h + dp(4));
            for (int i = 0; i < 24; i++) {
                float y = h * (row == 0 ? 0.86f : 0.95f) - bld[row][i] * h * (row == 0 ? 0.42f : 0.30f)
                        - (row == 0 ? shown[(i * 2) % BARS] * h * 0.03f : 0f);
                path.lineTo(i / 24f * w, y);
                path.lineTo((i + 1) / 24f * w, y);
            }
            path.lineTo(w, h + dp(4));
            path.close();
            paint.setColor(withA(row == 0 ? back : front, row == 0 ? 0xE0 : 0xF4));
            c.drawPath(path, paint);
        }
        int key = hatN * 31 + (int) w;
        if (key != winKey) {
            winKey = key;
            winCount = 0;
            float bw = w / 24f, sy = h * 0.035f;
            for (int i = 0; i < 24 && winCount < winPts.length - 1; i++) {
                float top = h * 0.95f - bld[1][i] * h * 0.30f;
                for (int cc = 0; cc < 2; cc++) {
                    for (float y = top + sy; y < h - sy && winCount < winPts.length - 1; y += sy) {
                        int hash = i * 7 + cc * 5 + (int) (y / sy) * 13;
                        boolean lit = hash % 5 != 0 && !(hash % 4 == 1 && ((hatN + hash) & 1) == 1);
                        if (!lit) continue;
                        winPts[winCount++] = i * bw + bw * (0.3f + 0.4f * cc);
                        winPts[winCount++] = y;
                    }
                }
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(4));
        paint.setColor(withA(0xF2D58A, 0xC0));
        c.drawPoints(winPts, 0, winCount, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Highland meadow: rolling hills, flat-topped acacias and yellow daisies swaying in the wind. */
    private void drawMeadow(Canvas c, float w, float h, int l) {
        int green = stage(MEADOW), back = lerpC(green, skyHorC, 0.4f);
        float ph = lf[l] * TWO_PI, night = tod * 0.8f;
        paint.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(0, h + dp(4));
        for (int i = 0; i <= 16; i++) {
            float x01 = i / 16f;
            path.lineTo(x01 * w, h * 0.80f + (float) Math.sin(x01 * 6f + ph) * h * 0.014f);
        }
        path.lineTo(w, h + dp(4));
        path.close();
        paint.setColor(withA(back, 0xFF));
        c.drawPath(path, paint);
        // flat-topped acacias standing on the far hill
        for (int k = 0; k < 3; k++) {
            float x01 = 0.12f + 0.36f * k + 0.1f * ((lf[l] * (k + 1)) % 1f);
            float by = h * 0.80f + (float) Math.sin(x01 * 6f + ph) * h * 0.014f;
            float th = h * (0.12f + 0.03f * k), lean = (k - 1) * dp(6);
            float sw = (float) Math.sin(windPos * 1.2f + k * 2f) * dp(5) * (0.5f + beat);
            float x = x01 * w, cx = x + lean + sw, cy = by - th, cw = th * 1.5f, ch = th * 0.3f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4));
            paint.setColor(withA(lerpC(0x4A3A2A, 0x10141A, night), 0xF0));
            c.drawLine(x, by, x + lean + sw * 0.5f, cy, paint);
            paint.setStyle(Paint.Style.FILL);
            int leaf = lerpC(0x3C5A28, 0x0E1C1A, night);
            paint.setColor(withA(leaf, 0xF0));
            rect.set(cx - cw / 2f, cy - ch * 0.7f, cx + cw / 2f, cy + ch * 0.3f);
            c.drawOval(rect, paint);
            paint.setColor(withA(lerpC(leaf, 0x6F9A48, 0.35f), 0xF0));
            rect.set(cx - cw * 0.3f, cy - ch * 0.95f, cx + cw * 0.3f, cy - ch * 0.2f);
            c.drawOval(rect, paint);
        }
        path.reset();
        path.moveTo(0, h + dp(4));
        for (int i = 0; i <= 24; i++) {
            float x01 = i / 24f;
            path.lineTo(x01 * w, h * 0.875f + (float) Math.sin(x01 * 4.5f + ph * 1.7f) * h * 0.012f
                    - shown[(i * 2) % BARS] * h * 0.02f);
        }
        path.lineTo(w, h + dp(4));
        path.close();
        paint.setColor(withA(green, 0xFF));
        c.drawPath(path, paint);
        // daisies: stems first, then the heads, swaying with the wind and swelling on the beat
        int n = lite ? 24 : MF, nl = 0, np = 0;
        for (int i = 0; i < n; i++) {
            float s = dp(4f + 6f * my[i]) * mv[i];
            float x = mx[i] * w, y = h * (0.85f + 0.065f * my[i]);
            float sway = (float) Math.sin(windPos * 2f + mph[i]) * s * (0.6f + wind * 0.5f);
            lines[nl++] = x;
            lines[nl++] = y;
            lines[nl++] = x + sway;
            lines[nl++] = y - s * 3.2f;
            starPts[np++] = x + sway;
            starPts[np++] = y - s * 3.2f;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.6f));
        paint.setColor(withA(lerpC(0x4E7A34, 0x142A1E, night), 0xD0));
        c.drawLines(lines, 0, nl, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withA(lerpC(0xF2CF4A, 0x8A7A3A, tod * 0.7f), 0xF0));
        for (int i = 0; i < n; i++) {
            float s = dp(4f + 6f * my[i]) * mv[i];
            c.drawCircle(starPts[2 * i], starPts[2 * i + 1], s * (1f + beat * 0.25f), paint);
        }
        paint.setColor(withA(lerpC(0xC27A1E, 0x4A3A1E, tod * 0.6f), 0xF0));
        for (int i = 0; i < n; i++) {
            float s = dp(4f + 6f * my[i]) * mv[i];
            c.drawCircle(starPts[2 * i], starPts[2 * i + 1], s * 0.38f, paint);
        }
    }
}
