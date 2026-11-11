package tv.dumbtv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.util.Locale;

/**
 * The stage above the visualizer: side speakers, theme vignettes and city journeys.
 *
 * Director: each song gets a Variety plan (palette, time of day, weather, packs, route). The song is
 * cut into four energy sections (calm, build, drop, break); a change of section picks that section's
 * vignette and switches on the next bar line (a drop switches at once), with a 2 s crossfade, never a cut.
 * With a beat confidence under 0.5 only onsets and energy drive it. A motion floor adds foreground
 * motion, and later a new scene, when the picture has hardly changed for 6 s.
 *
 * Comfort: no full-screen flashes, brightness pops are rate limited (2 a second at most) and smoothed,
 * every color is capped in luminance. Performance: under 45 fps the stage first lowers its resolution,
 * then drops layers. Static shapes drift slowly so nothing burns in.
 */
final class Stage extends View {
    private static final String TAG = "dumbtvvar";
    private static final float TAU = (float) (Math.PI * 2);
    private static boolean harnessRan;

    private final Pulse pulse;
    private final Scenes[] slot = {new Scenes(), new Scenes()};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private Milk milk;

    // settings
    private boolean speakers = true, debug;
    private String style = "auto";
    private float lumCap = 0.55f;
    private int syncMs, dim; // dim: 0 full, 1 dim, 2 off
    private float density = 1f;

    // song
    private Variety.Plan plan;
    private String artist = "", title = "", tags = "", place = "", lyrics = "";
    private int theme, cover;
    private boolean originDone, coverDone;
    private long songAt, planDue;
    private long trackStamp;
    private long trackPos, trackDur;
    private float trackSpeed = 1f;

    // time
    private long lastDraw, lastPop;
    private float vis, clock;

    // audio, as the stage sees it
    private int seenK, seenS, seenH;
    private final long[] evAt = new long[24];
    private final byte[] evKind = new byte[24];
    private int evN;
    private float kickEnv, snareEnv, hatEnv, sub, popV, popTarget, beat;
    private float eFast, eSlow, eOld, eOldAt;
    private int secNow = 0, secCand = -1;
    private float secCandT, secDwell, sinceSwitch;
    private long lastBar = Long.MIN_VALUE;
    private boolean barEdge, clockOn;
    private long barCount;
    private int sceneNo;
    private final PianoScene piano = new PianoScene();
    private final Feel feel = new Feel();
    private float noteEnv;
    private int seenNotes;
    private final LedDisk disk = new LedDisk();
    private float diskA;
    private boolean diskWant;
    private long diskUntil, diskForceAt;

    private void maybeDisk(long now) {
        if (diskWant || pianoWant || pianoSwap || level >= 3 || prng.nextFloat() > 0.2f) return;
        startDisk(now);
    }

    private void startDisk(long now) {
        diskWant = true;
        diskUntil = now + 30000 + prng.nextInt(15000);
        disk.begin(plan == null ? 1 : plan.key.hashCode(), hardMix > 0.5f || tagHard);
    }

    private void diskTick(float dt, long now, float va) {
        if (debug && !diskWant && now - diskForceAt > 2000) { // test hook: touch .../tv.dumbtv.home/disk_force while music plays
            diskForceAt = now;
            if (new java.io.File("/sdcard/Android/media/tv.dumbtv.home/disk_force").exists()) startDisk(now);
        }
        if (diskWant && (now > diskUntil || pianoWant)) diskWant = false;
        float to = diskWant ? 1f : 0f;
        diskA += Math.signum(to - diskA) * Math.min(Math.abs(to - diskA), dt / 2f);
        disk.alpha = diskA * va;
        if (diskA > 0.005f) {
            disk.update(dt, barEdge, switchFast, evSnareNow, evKickNow, pulse, kickEnv, pulse.tempo().phase(now), level);
        }
    }
    private final java.util.Random prng = new java.util.Random();
    private float pianoA;
    private boolean pianoSong; // piano scene up or a piano song playing: no speakers, lights or sky movers
    private boolean pianoWant, pianoSwap;
    private long pianoUntil;
    private int pianoStep;
    private long forceAt, pianoCheckAt;
    private int forceN;

    private String noteKey(String a, String t, long dur) {
        return lower(a) + "|" + lower(t) + "|" + (dur / 1000);
    }

    private android.content.SharedPreferences noteStore() {
        return getContext().getSharedPreferences("dumbtv_notes", 0);
    }

    /** Title, artist or tags say "piano": trust that over the FFT score, which a soft solo piano rarely lifts. */
    private boolean tagPiano() {
        return lower(title).contains("piano") || lower(artist).contains("piano") || lower(tags).contains("piano");
    }

    /** Piano scenes: after 8+ s of piano-like sound, or at once when the song is tagged piano. */
    private void maybePiano(long now) {
        boolean tagged = tagPiano();
        piano.pianoRollOnly = tagged;
        if (pianoWant || pianoSwap) return;
        if (!tagged && (!pulse.notes.pianoLikely(now, 8000) || prng.nextFloat() > 0.5f)) return;
        pianoWant = true;
        pianoStep = 0;
        pianoUntil = now + (tagged ? 30000 + prng.nextInt(10000) : 12000 + prng.nextInt(4000));
        piano.calm = 1f - hardMix;
        piano.begin(plan == null ? 1 : plan.key.hashCode(), pianoStep, pulse.notes, songAt);
        if (debug) Log.i("dumbtvnotes", "piano scene on: look " + piano.look + " view " + piano.view + " backdrop " + piano.backdrop + " score "
                + Math.round(pulse.notes.score * 100) + (tagged ? " tagged" : ""));
    }

    private void pianoTick(float dt, long now, float va, boolean fine) {
        if (debug && !pianoWant && now - forceAt > 2000) { // test hook: touch .../tv.dumbtv.home/piano_force while music plays
            forceAt = now;
            if (new java.io.File("/sdcard/Android/media/tv.dumbtv.home/piano_force").exists()) {
                pianoWant = true;
                pianoStep = 0;
                pianoUntil = now + 14000;
                piano.calm = 1f - hardMix;
        piano.begin(plan == null ? 1 : plan.key.hashCode(), forceN++, pulse.notes, songAt);
                Log.i("dumbtvnotes", "piano scene forced: view " + piano.view + " backdrop " + piano.backdrop);
            }
        }
        if (!pianoWant && !pianoSwap && tagPiano() && now - pianoCheckAt > 1500) { // piano songs: keep returning to the piano scene
            pianoCheckAt = now;
            if (songAt != 0 && now - songAt > 1500) maybePiano(now);
        }
        if (pianoWant && now > pianoUntil) { // 16 s at most: fade to a related variant, or out
            if (tagPiano() || (pianoStep < 2 && pulse.notes.pianoLikely(now, 3000))) {
                pianoSwap = true;
            } else {
                pianoWant = false;
            }
            pianoUntil = Long.MAX_VALUE;
        }
        float to = pianoWant && !pianoSwap ? 1f : 0f;
        pianoA += Math.signum(to - pianoA) * Math.min(Math.abs(to - pianoA), dt / 2f);
        if (pianoSwap && pianoA <= 0.01f) {
            pianoSwap = false;
            pianoStep++;
            pianoUntil = now + 12000 + prng.nextInt(4000);
            piano.calm = 1f - hardMix;
        piano.begin(plan == null ? 1 : plan.key.hashCode(), pianoStep, pulse.notes, songAt);
        }
        long pos = trackStamp == 0 ? 0 : trackPos + (long) ((SystemClock.elapsedRealtime() - trackStamp) * trackSpeed);
        piano.alpha = pianoA * va * pianoDim;
        if (pianoA > 0.005f) piano.update(dt, now, pulse.notes, pos, fine);
    }
    private final Choreo cho = new Choreo();
    private final int[] secScene = {-1, -1, -1, -1};
    private float motion, lowMotionT, flux;
    private boolean forceSwitch;

    // scenes
    private int cur = 0;
    private float xfade = 1f; // 0..1, how far the incoming scene has come
    private boolean fading;
    private int curId = -1, nextId = -1;
    private final int[] lastScenes = new int[4];
    private int lastN;
    private boolean fgOn;
    private float fgT;
    private final float[] fgx = new float[24], fgy = new float[24], fgs = new float[24];

    // governor
    private int level;
    private float frameAvg = 0.0167f, evalT, goodT;
    private float res = 1f;

    Stage(Context c, Pulse pulse) {
        super(c);
        this.pulse = pulse;
        setWillNotDraw(false);
        setFocusable(false);
        density = c.getResources().getDisplayMetrics().density;
        prefs = c.getApplicationContext().getSharedPreferences("stage", Context.MODE_PRIVATE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        feel.attach(prefs);
        Variety.init(new Variety.Store() {
            @Override
            public String load() {
                return prefs.getString("hist", "");
            }

            @Override
            public void save(String s) {
                prefs.edit().putString("hist", s).apply();
            }
        });
        for (String s : prefs.getString("like", "").split(",")) if (!s.isEmpty()) Variety.liked.add(Integer.parseInt(s));
        for (String s : prefs.getString("ban", "").split(",")) if (!s.isEmpty()) Variety.banned.add(Integer.parseInt(s));
        for (int i = 0; i < fgx.length; i++) {
            fgx[i] = (i * 0.618f) % 1f;
            fgy[i] = (i * 0.381f + 0.2f) % 1f;
            fgs[i] = 0.5f + (i % 5) * 0.2f;
        }
    }

    // ---- settings ------------------------------------------------------------------------------

    /** "on" always, "off" never, "auto" (default) only for hip-hop, bass-heavy music or when the tags ask. */
    void setSpeakerMode(String m) {
        speakerMode = "on".equals(m) ? 1 : "off".equals(m) ? 0 : 2;
        speakers = speakerMode != 0;
    }

    private int speakerMode = 2;
    private float gv, subShare, wxImp, wxBurst = 1f, hueTarget, hueCur;
    private boolean tagBass, tagHard, hardApplied;
    private float snareRate, hardT, hardMix, hardPal;
    private long palAt;

    /** Dark, tense scenes: street, city and rain packs come first. */
    private void applyHard() {
        hardApplied = true;
        plan.packs[0] = 2;
        plan.packs[1] = 8;
        plan.packs[2] = 4;
    }

    /** Speaker gate, rain and bokeh bursts, and the slow hue drift; once per frame. */
    private void ambience(float dt, boolean live) {
        subShare += ((sub > 0.5f ? 1f : 0f) - subShare) * Math.min(1f, dt / 15f);
        boolean heavy = plan != null && (plan.hiphop || tagBass) || subShare > 0.25f;
        float t = speakerMode == 1 ? 1f : speakerMode == 0 || !live ? 0f : heavy ? 1f : 0f;
        gv += (t - gv) * Math.min(1f, dt / 1.5f);
        // rain and bokeh come in bursts on beats and drops, and are gone in quiet sections
        wxImp = Math.max(0f, wxImp - dt * 0.35f);
        float q = secNow == 3 ? 0f : secNow == 0 ? 0.35f : 1f;
        wxBurst += (wxImp * q - wxBurst) * Math.min(1f, dt * 3f);
        pulse.wxBurst = wxBurst;
        // aggressive music: hard tags, or a long stretch of heavy bass, high energy, fast tempo and busy snares
        snareRate += ((evSnareNow ? 1f / Math.max(dt, 0.008f) : 0f) - snareRate) * Math.min(1f, dt / 5f);
        boolean run = live && eSlow > 0.35f && subShare > 0.25f && pulse.tempo().bpm() > 90 && snareRate > 1f;
        hardT = Math.max(0f, Math.min(30f, hardT + (run ? dt : tagHard ? 0f : -2f * dt)));
        boolean hardOn = tagHard || hardT > 12f;
        if (hardOn && !hardApplied && plan != null) applyHard();
        hardMix += ((hardOn ? 1f : 0f) - hardMix) * Math.min(1f, dt / 2.5f);
        if (Math.abs(hardMix - hardPal) > 0.04f) {
            long tn = SystemClock.uptimeMillis();
            if (tn - palAt > 250) {
                palAt = tn;
                hardPal = hardMix;
                palette();
            }
        }
        hueCur += (hueTarget - hueCur) * Math.min(1f, dt * 0.12f);
        long now = SystemClock.uptimeMillis();
        if (Math.abs(hueTarget - hueCur) > 0.6f && now - palAt > 250) {
            palAt = now;
            palette();
        }
    }

    void setStyle(String s) {
        if (s == null) s = "auto";
        if (!s.equals(style)) {
            style = s;
            if (plan != null) makePlan();
        }
    }

    /** True for react_style values that pick a theme pack or the journey. */
    static boolean isPack(String s) {
        if ("journey".equals(s)) return true;
        for (String p : Variety.PACKS) if (p.equals(s)) return true;
        return false;
    }

    /** piano_palette=auto|warm and piano_dim=bright|normal|dim|night: how bright the piano scenes are, and whether they avoid blue. */
    void setPiano(String palette, String dim) {
        piano.warm = "warm".equals(palette);
        pianoDim = "bright".equals(dim) ? 1f : "normal".equals(dim) ? 0.78f : "night".equals(dim) ? 0.4f : 0.6f;
    }

    private float pianoDim = 0.6f;

    /** react_genre=<class> forces a genre profile for testing; auto (default) decides per song. */
    void setGenre(String g) {
        feel.setForced(g);
    }

    /** The glow rooms hide this view, so it never draws; build the song's plan (and its genre) once the tags had time to arrive. */
    void primeGenre() {
        if (plan == null && SystemClock.uptimeMillis() >= planDue) makePlan();
    }

    /** The genre class the feel engine settled on for this song, for the glow rooms' palette. */
    String genre() {
        int c = feel.cls;
        return c >= 0 && c < Feel.NAMES.length ? Feel.NAMES[c] : "unknown";
    }

    void setComfort(String level) {
        lumCap = "high".equals(level) ? 0.55f : 0.72f;
    }

    void setSyncMs(int ms) {
        syncMs = ms;
    }

    void setMilk(Milk m) {
        milk = m;
        if (m != null && plan != null) m.avoidPresets(Variety.avoidPresets());
    }

    void setDebug(boolean on) {
        debug = on;
        feel.debug = on;
        if (on && !harnessRan) {
            harnessRan = true;
            Variety.sink = new Variety.Sink() {
                @Override
                public void log(String line) {
                    Log.i(TAG, line);
                }
            };
            new Thread(new Runnable() {
                @Override
                public void run() {
                    int repeats = Variety.harness();
                    Log.i(TAG, "harness done, repeats=" + repeats);
                }
            }, "dumbtv-harness").start();
        }
    }

    // ---- song ----------------------------------------------------------------------------------

    void setSong(String a, String t) {
        a = a == null ? "" : a;
        t = t == null ? "" : t;
        if (a.equals(artist) && t.equals(title)) return;
        if (pulse.notes.evCount >= 20 && !artist.isEmpty()) { // keep this song's notes for the next play
            try {
                noteStore().edit().putString(noteKey(artist, title, trackDur), pulse.notes.pack(songAt)).apply();
            } catch (RuntimeException ignored) {
                // storage unavailable: the song simply has no memory
            }
        }
        pulse.notes.reset();
        pianoWant = false;
        pianoSwap = false;
        diskWant = false;
        Variety.commit(plan);
        plan = null;
        feel.newSong(a + " - " + t);
        artist = a;
        title = t;
        tags = "";
        place = "";
        lyrics = "";
        theme = 0;
        originDone = false;
        coverDone = false;
        cover = 0;
        songAt = SystemClock.uptimeMillis();
        planDue = songAt + 4500;
        curId = nextId = -1;
        fading = false;
        slot[0].reset(-1, 0);
        slot[1].reset(-1, 0);
        lastN = 0;
        sinceSwitch = 99f;
        secNow = 0;
        pulse.tempo().setHalfTime(false);
        Artists.Entry known = Artists.find(Origin.primary(a));
        place = known != null ? known.city : "";
    }

    void setOrigin(Origin.Info info, String originPlace) {
        originDone = true;
        if (info != null) {
            theme = info.theme;
            StringBuilder sb = new StringBuilder();
            for (String s : info.tags) sb.append(sb.length() == 0 ? "" : ",").append(s);
            tags = sb.toString();
        }
        if (originPlace != null && !originPlace.isEmpty()) place = place + " " + originPlace;
        if (plan != null && info != null) feel.refine(lower(artist) + " " + lower(title) + " " + lower(tags), info.theme == 12);
        if (plan != null && plan.cityId.isEmpty() && !place.trim().isEmpty() && Journey.cityIdOf(place) == null) {
            lookupCity(plan);
        }
    }

    void setCover(int[] palette) {
        coverDone = true;
        cover = 0;
        if (palette != null && palette.length > 0) {
            float best = -1f;
            for (int c : palette) {
                float sc = Variety.satOf(c) * (0.4f + Variety.valOf(c));
                if (sc > best) {
                    best = sc;
                    cover = c | 0xFF000000;
                }
            }
        }
    }

    void setLyrics(String text) {
        lyrics = text == null ? "" : text;
    }

    /** Playback position from the media session, for routes that must end with the song. */
    void setTrack(long posMs, long durMs, float speed) {
        trackPos = posMs;
        trackDur = durMs;
        trackSpeed = speed;
        trackStamp = SystemClock.elapsedRealtime();
    }

    private void makePlan() {
        Variety.commit(plan);
        plan = Variety.make(artist, title, tags, lyrics, theme, place, cover, style);
        String tl = lower(tags);
        String tt = tl + " " + lower(title);
        tagHard = tt.contains("diss") || tt.contains("battle") || tt.contains("gangsta") || tt.contains("gangster")
                || tt.contains("drill") || tt.contains("grime") || tt.contains("hardcore") || tt.contains("metal");
        hardApplied = false;
        hardT = tagHard ? 30f : 0f;
        if (tagHard) applyHard();
        feel.begin(plan, lower(artist) + " " + lower(title) + " " + tt, tagHard, !plan.cityId.isEmpty());
        try {
            piano.setStored(noteStore().getString(noteKey(artist, title, trackDur), null));
        } catch (RuntimeException e) {
            piano.setStored(null);
        }
        sceneNo = 0;
        cho.seed(plan.key.hashCode(), debug);
        secScene[0] = secScene[1] = secScene[2] = secScene[3] = -1;
        spk.newSong(plan.key.hashCode(), plan.packs[0], tagHard);
        tagBass = tl.contains("trap") || tl.contains("bass") || tl.contains("drill") || tl.contains("dubstep")
                || tl.contains("hip") || tl.contains("rap") || tl.contains("808") || tl.contains("reggaeton") || tl.contains("grime");
        pulse.setPlanPal(plan.pal);
        pulse.tempo().setHalfTime(plan.halfTime);
        if (milk != null) milk.avoidPresets(Variety.avoidPresets());
        palette();
        Variety.log(plan);
        if (plan.cityId.isEmpty() && !place.trim().isEmpty()) lookupCity(plan);
        curId = -1;
        sinceSwitch = 99f;
    }

    private void lookupCity(final Variety.Plan forPlan) {
        Journey.fetch(getContext(), place, new Journey.Callback() {
            @Override
            public void done(Journey.City city) {
                if (forPlan == plan) {
                    Variety.setCity(plan, city);
                    Variety.log(plan);
                }
            }
        });
    }

    /** Colors for the current plan and time of day: base rows from the pulse tables, turned to the plan's hue. */
    private void palette() {
        if (plan == null) return;
        int col = plan.tod == 0 ? 0 : plan.tod == 3 ? 2 : 1;
        int top = Pulse.TOP[plan.pal][col], hor = Pulse.HOR[plan.pal][col];
        if (plan.tod == 1) {
            top = lerp(Pulse.TOP[plan.pal][0], Pulse.TOP[plan.pal][1], 0.5f);
            hor = lerp(Pulse.HOR[plan.pal][0], Pulse.HOR[plan.pal][1], 0.5f);
        }
        float sh = plan.hueShift + hueCur;
        top = tint(Variety.rotate(top | 0xFF000000, sh), plan.satBoost);
        hor = tint(Variety.rotate(hor | 0xFF000000, sh), plan.satBoost);
        if (plan.cover != 0) hor = lerp(hor, plan.cover, plan.coverMix);
        int a0 = tint(Variety.rotate(Pulse.ACC[plan.pal][0] | 0xFF000000, sh), plan.satBoost);
        int a1 = tint(Variety.rotate(Pulse.ACC[plan.pal][1] | 0xFF000000, sh), plan.satBoost);
        int a2 = tint(Variety.rotate(Pulse.ACC[plan.pal][2] | 0xFF000000, sh), plan.satBoost);
        int dark = lerp(Variety.rotate(Pulse.TOP[plan.pal][2] | 0xFF000000, sh), 0xFF050508, 0.55f);
        // a complementary accent from the cover art, so the scene is never a single tone
        int comp = Variety.rotate((plan.cover != 0 ? plan.cover : a0) | 0xFF000000, 180f + hueCur);
        a2 = lerp(a2, tint(comp, plan.satBoost), 0.65f);
        if (hardMix > 0.01f) { // aggressive music: near-black sky, red, amber and steel
            top = lerp(top, 0xFF050507, 0.88f * hardMix);
            hor = lerp(hor, 0xFF1C1012, 0.6f * hardMix);
            a0 = lerp(a0, 0xFFE0861C, 0.85f * hardMix);
            a1 = lerp(a1, 0xFFB82424, 0.85f * hardMix);
            a2 = lerp(a2, 0xFF7C90A8, 0.85f * hardMix);
            dark = lerp(dark, 0xFF030304, hardMix);
        }
        spk.setPalette(a0, a1, a2, hor, dark);
        for (Scenes s : slot) {
            s.pc[0] = top;
            s.pc[1] = lerp(top, hor, 0.5f);
            s.pc[2] = hor;
            s.pc[3] = a0;
            s.pc[4] = a1;
            s.pc[5] = a2;
            s.pc[6] = dark;
            s.pc[7] = lerp(0xFFF6EEE0, hor, 0.15f);
            s.night = plan.tod == 3 || hardMix > 0.5f;
            s.order = plan.order;
            s.camDir = plan.camDir;
            s.camSpeed = plan.camSpeed;
            s.tod = hardMix > 0.5f ? 3 : plan.tod;
            s.wx = hardMix > 0.5f ? 1 : plan.wx;
        }
    }

    private static int tint(int c, float sat) {
        if (sat == 1f) return c;
        return Variety.hsv(Variety.hueOf(c), Variety.satOf(c) * sat, Variety.valOf(c));
    }

    private static int lerp(int a, int b, float t) {
        int r = (int) ((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t);
        int g = (int) ((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // ---- remote --------------------------------------------------------------------------------

    /** Left (-1) or right (1): the previous or another scene. */
    void skip(int dir) {
        if (plan == null) return;
        if (dir < 0 && lastN >= 2) {
            lastN--;
            beginScene(lastScenes[lastN - 1], false);
        } else {
            beginScene(pickNext(SystemClock.uptimeMillis()), true);
        }
    }

    String like() {
        if (curId < 0) return null;
        Variety.liked.add(curId);
        Variety.banned.remove(curId);
        save();
        return name(curId);
    }

    String ban() {
        if (curId < 0) return null;
        Variety.banned.add(curId);
        Variety.liked.remove(curId);
        save();
        String n = name(curId);
        skip(1);
        return n;
    }

    /** Long press on OK: full, dim, off. */
    String cycleDim() {
        dim = (dim + 1) % 3;
        return dim == 0 ? "Visuals: full" : dim == 1 ? "Visuals: dim" : "Visuals: off";
    }

    boolean isOff() {
        return dim == 2;
    }

    private void save() {
        prefs.edit().putString("like", join(Variety.liked)).putString("ban", join(Variety.banned)).apply();
    }

    private static String join(java.util.Set<Integer> s) {
        StringBuilder sb = new StringBuilder();
        for (int i : s) sb.append(sb.length() == 0 ? "" : ",").append(i);
        return sb.toString();
    }

    private static String name(int id) {
        return id == Variety.JOURNEY ? "journey" : Variety.PACKS[id / 8] + " " + (id % 8 + 1);
    }

    // ---- director ------------------------------------------------------------------------------

    private void beginScene(int id, boolean remember) {
        if (id == curId) return;
        int incoming = fading ? 1 - cur : 1 - cur;
        Scenes s = slot[incoming];
        s.reset(id, plan == null ? 0 : plan.skySeed + id * 7919);
        nextId = id;
        sceneNo++;
        if (sceneNo > 1) hueTarget += 22f;
        spk.newScene(sceneNo, secNow, id);
        fading = true;
        xfade = 0f;
        sinceSwitch = 0f;
        lowMotionT = 0f;
        fgOn = false;
        if (remember) {
            if (lastN == lastScenes.length) {
                System.arraycopy(lastScenes, 1, lastScenes, 0, lastN - 1);
                lastN--;
            }
            lastScenes[lastN++] = id;
        }
        if (plan != null) {
            Log.i(TAG, "scene " + name(id) + " section=" + secNow + " bar=" + barCount + " clock=" + clockOn
                    + " hue=" + plan.hueBucket);
        }
    }

    private void direct(long now, float dt) {
        if (milk != null && plan != null && now - presetPollAt > 3000) {
            presetPollAt = now;
            Variety.notePreset(plan, milk.currentId());
        }
        if (plan == null) {
            // wait (up to 4.5 s) for the artist's origin and the cover colors, so the plan can use them
            if (!((originDone && coverDone) || now >= planDue) || now < songAt + 1500) return;
            makePlan();
        }
        Tempo tp = pulse.tempo();
        clockOn = tp.conf() > 0.5f;
        long bar = tp.bars(now);
        barEdge = clockOn && lastBar != Long.MIN_VALUE && bar != lastBar;
        if (barEdge) barCount++;
        lastBar = clockOn ? bar : Long.MIN_VALUE;

        float e = pulse.ener();
        eFast += (e - eFast) * Math.min(1f, dt / 1.5f);
        eSlow += (e - eSlow) * Math.min(1f, dt / 14f);
        eOldAt += dt;
        if (eOldAt > 3f) {
            eOldAt = 0f;
            eOld = eFast;
        }
        float ratio = eFast / (eSlow + 0.03f);
        int cand;
        if (ratio > 1.22f && eFast > 0.2f) cand = 2;
        else if (eFast - eOld > 0.05f && ratio > 0.95f) cand = 1;
        else if (ratio < 0.8f) cand = 3;
        else cand = eFast > 0.5f ? 2 : 0;
        if (cand == secCand) secCandT += dt;
        else {
            secCand = cand;
            secCandT = 0f;
        }
        secDwell += dt;
        sinceSwitch += dt;
        boolean dropNow = cand == 2 && ratio > 1.3f && secNow != 2 && secCandT > 0.4f && secDwell > 4f;
        if ((cand != secNow && secCandT > 1.5f && secDwell > 7f) || dropNow) {
            secNow = cand;
            secDwell = 0f;
            if (secNow == 2 || secNow == 1) feel.record(secNow, posNow());
            if (secNow == 2) feel.burst();
            hueTarget += 22f; // the palette drifts on at every section
            if (dropNow) wxImp = 1f;
            forceSwitch = true;
            switchFast = dropNow;
        }
        {
            float beatMs = clockOn ? 60000f / Math.max(60f, (float) tp.bpm()) : 600f;
            int ant = trackStamp == 0 ? 0 : feel.anticipate(posNow(), beatMs);
            if (ant == 2) { // a remembered drop: start it a beat or two early
                secNow = 2;
                secDwell = 0f;
                forceSwitch = true;
                switchFast = true;
                feel.early = true;
                wxImp = 1f;
                feel.burst();
            } else if (ant == 1 && sinceSwitch > 10f) {
                forceSwitch = true;
                feel.early = true;
            }
        }
        // an unchanging section still moves on after a while
        if (!fading && sinceSwitch > feel.maxDwell()) forceSwitch = true;

        // motion floor: the picture has hardly changed for 6 s
        float m = 0.6f * Math.min(1f, flux * 6f) + 0.4f * Math.min(1f, (kickEnv + snareEnv + hatEnv) * 0.5f);
        motion += (m - motion) * Math.min(1f, dt / 0.8f);
        if (motion < 0.12f) lowMotionT += dt;
        else lowMotionT = Math.max(0f, lowMotionT - dt * 2f);
        if (lowMotionT > 6f && !fgOn) {
            fgOn = true;
            fgT = 0f;
            Log.i(TAG, "motion floor: foreground motion on");
        }
        if (fgOn) {
            fgT += dt;
            if (lowMotionT > 12f && !fading) {
                forceSwitch = true;
                Log.i(TAG, "motion floor: switching scene");
            }
        }

        if (fading) {
            xfade += dt / 2f;
            if (xfade >= 1f) {
                fading = false;
                cur = 1 - cur;
                curId = nextId;
                xfade = 1f;
            }
            return;
        }
        if (curId < 0 && nextId < 0) {
            if (!feel.ready()) return;
            beginScene(pickNext(now), true);
            return;
        }
        if (forceSwitch) {
            if (sinceSwitch < feel.minDwell() && !feel.early) return; // a scene stays at least 20 s
            if (waitFrom == 0) waitFrom = now;
            boolean ok = barEdge && (barCount % 8 == 0 || now - waitFrom > 2000); // phrase boundaries first
            boolean wait = clockOn && !switchFast && !feel.early && !ok && now - waitFrom < 6000;
            if (wait) return;
            feel.early = false;
            forceSwitch = false;
            switchFast = false;
            waitFrom = 0;
            int id = secNow == 2 && secScene[2] >= 0 && secScene[2] != curId ? secScene[2] : pickNext(now);
            if (id == curId) id = pickNext(now);
            if (secNow == 2 && secScene[2] < 0) secScene[2] = id;
            maybePiano(now);
            maybeDisk(now);
            beginScene(id, true);
        }
    }

    private long posNow() {
        return trackStamp == 0 ? 0 : trackPos + (long) ((SystemClock.elapsedRealtime() - trackStamp) * trackSpeed);
    }

    /** Hands the stage's view of the audio to the genre feel, once per frame. */
    private void feelStep(float dt, long now) {
        Tempo tp = pulse.tempo();
        feel.bass = pulse.bass();
        feel.mid = pulse.mid();
        feel.high = pulse.high();
        feel.ener = pulse.ener();
        feel.harm = pulse.notes.score;
        feel.bpm = (float) tp.bpm();
        feel.tempoConf = tp.conf();
        feel.kickEnv = kickEnv;
        feel.sub = sub;
        feel.kickNow = evKickNow;
        feel.snareNow = evSnareNow;
        feel.hatNow = evHatNow;
        feel.kickPop = evKickNow && popTarget > 0.95f;
        feel.barEdge = barEdge;
        feel.clockOn = clockOn;
        feel.phase = tp.phase(now);
        feel.sec = secNow;
        feel.barCount = (int) barCount;
        feel.progress = progress(now);
        feel.fps = 1f / Math.max(0.004f, frameAvg);
        feel.setAccents(spk.accentA(), spk.accentB());
        feel.update(dt);
        feel.noteSection(secNow);
    }

    /** The next scene from the genre's playlist; the piano scene and the LED disk are families of it too. */
    private int pickNext(long now) {
        int fam = feel.nextFamily();
        int sec = feel.energySection(secNow);
        if (fam == Feel.FAM_PIANO) {
            feelPiano(now);
            fam = feel.topPack();
        } else if (fam == Feel.FAM_DISK) {
            if (!diskWant && !pianoWant && level < 3) startDisk(now);
            fam = feel.topPack();
        } else if (fam == Feel.FAM_JOURNEY) {
            if (plan != null && !plan.cityId.isEmpty()) {
                feel.noteScene(fam, Variety.JOURNEY, "journey");
                return Variety.JOURNEY;
            }
            fam = feel.topPack();
        }
        int id = Variety.pickIn(plan, fam, sec, pulse.mood());
        feel.noteScene(fam, id, name(id));
        return id;
    }

    /** The piano scene as a family of the playlist: it plays for one dwell, then the rotation moves on. */
    private void feelPiano(long now) {
        if (pianoWant || pianoSwap || level >= 3) return;
        pianoWant = true;
        pianoStep = 0;
        pianoUntil = now + 30000 + prng.nextInt(10000);
        piano.pianoRollOnly = tagPiano();
        piano.calm = 1f - hardMix;
        piano.begin(plan == null ? 1 : plan.key.hashCode(), pianoStep, pulse.notes, songAt);
    }

    private long presetPollAt;
    private boolean switchFast;
    private long waitFrom;

    // ---- drawing -------------------------------------------------------------------------------

    private long trimAt;
    private int memFloor; // 0 normally; 2 (half resolution) while the TV is short of RAM, so Android keeps Spotify alive

    /** Spotify's service is the first thing Android kills when RAM runs low, and Connect then drops the TV from the phone. */
    private void checkMemory() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long availMb = mi.availMem / (1024 * 1024);
            int floor = availMb < 600 ? 2 : availMb > 800 ? 0 : memFloor;
            if (floor != memFloor) {
                memFloor = floor;
                if (debug) Log.i(TAG, "memory: " + availMb + " MB available, floor " + floor);
                if (level < memFloor) {
                    level = memFloor;
                    applyLevel();
                }
            }
        } catch (RuntimeException ignored) {
            // no memory info: the frame-rate governor alone decides
        }
    }

    /** Path masks pile up in Skia's cache (about 370 MB of a 1.7 GB TV); Spotify gets killed for it. */
    private static void trimGpu() {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object global = wmg.getMethod("getInstance").invoke(null);
            wmg.getMethod("trimMemory", int.class).invoke(global, android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not reachable on this build: the cache simply stays
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w == 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = lastDraw == 0 ? 0.016f : Math.min(0.1f, (now - lastDraw) / 1000f);
        lastDraw = now;
        clock += dt;
        if (now - trimAt > (memFloor > 0 ? 6000 : 25000)) {
            trimAt = now;
            trimGpu();
            checkMemory();
        }
        boolean live = pulse.isActive() && dim < 2;
        vis = live ? Math.min(1f, vis + dt * 0.7f) : Math.max(0f, vis - dt * 1.2f);
        if (vis <= 0.005f) {
            if (!live) {
                postInvalidateDelayed(300);
                return;
            }
        }
        govern(dt);
        events(now, dt);
        flux += (pulse.flux() - flux) * Math.min(1f, dt * 4f);
        if (live) direct(now, dt);
        {
            Tempo tpc = pulse.tempo();
            int kc = cho.kicksCounted();
            cho.update(dt, clockOn ? tpc.bars(now) : kc >> 2, clockOn ? tpc.beatInBar(now) : kc & 3,
                    clockOn ? tpc.phase(now) : 0f, evKickNow, evSnareNow, evHatNow, pulse.bass(), pulse.mid(), w, h);
            spk.lightSwing = cho.lightSwing;
            spk.lightMix = cho.lightMix;
        }
        float dimMul = dim == 1 ? 0.5f : 1f;
        float va = vis * dimMul;

        canvas.save();
        if (!pianoSong) cho.camera(canvas, w, h); // a keyboard that tilts and zooms looks broken
        if (!pianoSong && feel.zoom() > 0.0005f) canvas.scale(1f + feel.zoom(), 1f + feel.zoom(), w * 0.5f, h * 0.5f); // at most 3%
        boolean scenesOn = plan != null;
        boolean covered = pianoA > 0.97f || diskA > 0.97f; // a full-screen family hides everything under it: do not draw it
        pulse.setAlpha(covered ? 0f : 1f);
        if (scenesOn && vis > 0f && !covered) {
            Scenes a = slot[cur], b = slot[1 - cur];
            feed(a, w, h, dt, now);
            float br = (0.82f + 0.18f * beat) * (1f - 0.92f * pianoA) * (1f - 0.92f * diskA);
            float aAlpha = (fading ? 1f - xfade : 1f) * va * br, bAlpha = fading ? xfade * va * br : 0f;
            if (curId >= 0) {
                a.alpha = aAlpha;
                a.draw(canvas);
            }
            if (fading) {
                feed(b, w, h, dt, now);
                b.alpha = bAlpha;
                b.draw(canvas);
            }
            if (fgOn) foreground(canvas, w, h, dt, va);
        }
        pianoTick(dt, now, va, level < 3);
        diskTick(dt, now, va);
        if (live) feelStep(dt, now);
        if (diskA > 0.01f) disk.draw(canvas, w, h, spk.hasCard, spk.cl, spk.ct, spk.cr, spk.cb);
        if (pianoA > 0.01f) {
            long ppos = trackStamp == 0 ? 0 : trackPos + (long) ((SystemClock.elapsedRealtime() - trackStamp) * trackSpeed);
            piano.draw(canvas, w, h, ppos, level < 3);
        }
        if (!pianoSong && va > 0.01f && plan != null) feel.drawFx(canvas, w, h, va);
        ambience(dt, live);
        // the stage (setting, floor, speakers) follows the speaker gate; sky movers are always on
        pianoSong = pianoA > 0.01f || tagPiano() || pulse.notes.pianoLikely(now, 4000);
        drawSpeakers(canvas, w, h, dt, va, pianoSong ? 0f : (speakers ? gv : 0f) * (1f - diskA));
        if (!pianoSong) cho.draw(canvas, paint, w, h, spk.accentA(), spk.accentB(), va, spk.hasCard, spk.cl, spk.ct, spk.cr, spk.cb);
        canvas.restore();
        // last step, the colour limiter: a darker top and a slight cap over the whole picture
        if (va > 0.01f) {
            paint.setStyle(Paint.Style.FILL);
            float lv = Math.min(1f, va * 1.5f);
            for (int i = 0; i < 6; i++) {
                float k = 1f - i / 5f;
                paint.setColor(((int) ((0.06f + 0.24f * k * k) * 255f * lv)) << 24);
                canvas.drawRect(0, h * 0.5f * i / 6f, w, h * 0.5f * (i + 1) / 6f + 1f, paint);
            }
            paint.setColor(((int) (0.06f * 255f * lv)) << 24);
            canvas.drawRect(0, h * 0.5f, w, h, paint);
        }
        postInvalidateOnAnimation();
    }

    private void feed(Scenes s, float w, float h, float dt, long now) {
        s.w = w;
        s.h = h;
        s.dt = dt;
        s.age += dt;
        s.cap = lumCap;
        s.kick = Math.min(1f, popV * feel.kickGain());
        s.snare = snareEnv;
        s.hat = hatEnv;
        s.bass = pulse.bass();
        s.ener = pulse.ener();
        s.beat = beat;
        s.burst = wxBurst;
        s.speed = (0.8f + 0.7f * Math.min(1f, pulse.ener() * 1.4f) + (clockOn ? 0f : 0.1f)) * feel.speedMul();
        s.kicked = evKickNow;
        s.snared = evSnareNow;
        s.hatted = evHatNow;
        s.barEdge = barEdge;
        s.clockOn = clockOn;
        s.beatN = barCount * 8 + (seenK & 7);
        s.progress = progress(now);
    }

    private float progress(long now) {
        if (trackDur <= 0 || trackStamp == 0) return -1f;
        long pos = trackPos + (long) ((SystemClock.elapsedRealtime() - trackStamp) * trackSpeed);
        return Math.max(0f, Math.min(1f, pos / (float) trackDur));
    }

    private boolean evKickNow, evSnareNow, evHatNow;

    /** Audio events, held back by react_sync_ms, turned into smooth envelopes. */
    private void events(long now, float dt) {
        int k = pulse.kicks(), s = pulse.snares(), hh = pulse.hats();
        for (int i = 0; i < Math.min(3, k - seenK) && evN < evAt.length; i++) {
            evAt[evN] = now + Math.max(0, syncMs);
            evKind[evN++] = 0;
        }
        for (int i = 0; i < Math.min(2, s - seenS) && evN < evAt.length; i++) {
            evAt[evN] = now + Math.max(0, syncMs);
            evKind[evN++] = 1;
        }
        for (int i = 0; i < Math.min(2, hh - seenH) && evN < evAt.length; i++) {
            evAt[evN] = now + Math.max(0, syncMs);
            evKind[evN++] = 2;
        }
        seenK = k;
        seenS = s;
        seenH = hh;
        evKickNow = evSnareNow = evHatNow = false;
        for (int i = 0; i < evN; ) {
            if (evAt[i] <= now) {
                byte kind = evKind[i];
                evN--;
                evAt[i] = evAt[evN];
                evKind[i] = evKind[evN];
                if (kind == 0) {
                    evKickNow = true;
                    kickEnv = 1f;
                    wxImp = Math.min(1f, wxImp + 0.2f);
                    if (now - lastPop >= 500) { // at most two bright pops a second
                        lastPop = now;
                        popTarget = 1f;
                    }
                } else if (kind == 1) {
                    evSnareNow = true;
                    snareEnv = 1f;
                } else {
                    evHatNow = true;
                    hatEnv = 1f;
                }
            } else {
                i++;
            }
        }
        kickEnv = Math.max(0f, kickEnv - dt * feel.kickDecay());
        snareEnv = Math.max(0f, snareEnv - dt * feel.snareDecay());
        hatEnv = Math.max(0f, hatEnv - dt * feel.hatDecay());
        popTarget = Math.max(0f, popTarget - dt * 3f);
        popV += (popTarget - popV) * Math.min(1f, dt * 12f);
        // 808: a deep note keeps ringing after the kick, so the cone is still moving a second later
        float b = pulse.bass();
        sub = Math.max(sub - dt * 0.95f, b > 0.55f ? b : 0f);
        float ph = pulse.tempo().phase(now);
        float pulseBeat = (1f - ph) * (1f - ph) * (1f - ph);
        beat = clockOn ? pulseBeat : kickEnv;
        int nn = pulse.notes.evCount; // melodic music follows its notes, bass music its kick
        if (nn != seenNotes) {
            if (nn > seenNotes) noteEnv = 1f;
            seenNotes = nn;
        }
        noteEnv = Math.max(0f, noteEnv - dt * 3f);
        if (feel.melodic()) beat = Math.max(beat * 0.5f, noteEnv * 0.8f);
    }

    private void govern(float dt) {
        if (dt < 0.09f) frameAvg += (dt - frameAvg) * 0.05f;
        evalT += dt;
        if (evalT < 1.5f) return;
        evalT = 0f;
        float fps = 1f / frameAvg;
        if (fps < 45f) {
            goodT = 0f;
            if (level < 3) {
                level++;
                Log.i(TAG, "governor: " + Math.round(fps) + " fps, level " + level);
                applyLevel();
            }
        } else if (fps > 56f && level > memFloor) {
            goodT += 1.5f;
            if (goodT > 20f) {
                goodT = 0f;
                level--;
                Log.i(TAG, "governor: " + Math.round(fps) + " fps, level " + level);
                applyLevel();
            }
        } else {
            goodT = 0f;
        }
    }

    /** Level 1 and 2 lower the resolution (75%, 50%), 3 drops speaker shading, 4 the vignettes, 5 the speakers. */
    private void applyLevel() {
        float r = level == 0 ? 1f : level == 1 ? 0.75f : 0.5f;
        if (r != res) {
            res = r;
            resize(pulse, r);
        }
    }

    private static void resize(View v, float r) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        View parent = (View) v.getParent();
        if (lp == null || parent == null) return;
        lp.width = r >= 1f ? ViewGroup.LayoutParams.MATCH_PARENT : Math.round(parent.getWidth() * r);
        lp.height = r >= 1f ? ViewGroup.LayoutParams.MATCH_PARENT : Math.round(parent.getHeight() * r);
        v.setPivotX(0f);
        v.setPivotY(0f);
        v.setScaleX(r >= 1f ? 1f : 1f / r);
        v.setScaleY(r >= 1f ? 1f : 1f / r);
        v.setLayoutParams(lp);
    }

    /** Slow motes and a soft light drift: foreground movement for a picture that has gone still. */
    private void foreground(Canvas c, float w, float h, float dt, float a) {
        float k = Math.min(1f, fgT / 2f) * a;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < fgx.length; i++) {
            fgx[i] = (fgx[i] + dt * 0.012f * fgs[i] + 1f) % 1f;
            fgy[i] = (fgy[i] - dt * 0.02f * fgs[i] + 1f) % 1f;
            float r = h * 0.004f * (1f + fgs[i]);
            paint.setColor(((int) (70 * k * (0.5f + 0.5f * (float) Math.sin(clock * fgs[i] + i))) << 24) | 0xE8E0D0);
            c.drawCircle(fgx[i] * w, fgy[i] * h, r, paint);
        }
    }

    // ---- speakers ------------------------------------------------------------------------------

    private final Speakers spk = new Speakers();

    private void drawSpeakers(Canvas c, float w, float h, float dt, float a, float stageVis) {
        spk.stageVis = stageVis;
        spk.moversOff = pianoSong ? 1f : diskA;
        spk.hard = hardMix;
        spk.kickNow = evKickNow;
        spk.snareNow = evSnareNow;
        spk.hatNow = evHatNow;
        spk.debug = debug;
        spk.bass = pulse.bass();
        spk.flux = pulse.flux();
        spk.ener = pulse.ener();
        spk.sub = sub;
        spk.kickEnv = kickEnv;
        spk.snareEnv = snareEnv;
        spk.hatEnv = hatEnv;
        spk.bpm = (float) pulse.tempo().bpm();
        spk.cap = lumCap;
        spk.alpha = a;
        spk.level = level;
        spk.section = secNow;
        long now = SystemClock.uptimeMillis();
        if (now - cardAt > 400) { // the now-playing card (plus 3%) stays clear of every effect
            cardAt = now;
            spk.hasCard = false;
            if (card != null && card.getVisibility() == View.VISIBLE && card.getWidth() > 0) {
                card.getLocationOnScreen(cardLoc);
                int sx0 = cardLoc[0], sy0 = cardLoc[1];
                getLocationOnScreen(cardLoc);
                spk.cl = card.getLeft() * 0f + sx0 - cardLoc[0] - w * 0.03f;
                spk.ct = sy0 - cardLoc[1] - h * 0.03f;
                spk.cr = spk.cl + card.getWidth() + w * 0.06f;
                spk.cb = spk.ct + card.getHeight() + h * 0.06f;
                spk.hasCard = true;
            }
            pulse.keepOn = spk.hasCard;
            pulse.keepL = spk.cl;
            pulse.keepT = spk.ct;
            pulse.keepR = spk.cr;
            pulse.keepB = spk.cb;
        }
        for (Scenes s : slot) {
            s.keepOn = spk.hasCard;
            s.keepL = spk.cl;
            s.keepT = spk.ct;
            s.keepR = spk.cr;
            s.keepB = spk.cb;
        }
        spk.draw(c, w, h, dt);
    }

    private View card;
    private long cardAt;
    private final int[] cardLoc = new int[2];

    void setCard(View v) {
        card = v;
    }

    // ---- used by Variety.harness on the TV -------------------------------------------------------

    String describe() {
        return plan == null ? "no plan" : plan.signature();
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }
}
