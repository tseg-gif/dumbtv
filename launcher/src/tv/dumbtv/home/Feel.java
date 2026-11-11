package tv.dumbtv.home;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.Log;

import java.util.Locale;
import java.util.Random;

/**
 * Genre feel. Decides in the first ~20 s what kind of music is playing (tags first, then tempo, onset density, band
 * balance, tonality and dynamics from the live audio) and keeps refining it. The class sets:
 * <ul>
 * <li>which scene families the song's playlist draws from (4-7 scenes, no family twice within 3, never the last song's first),
 * <li>how hard each layer answers kicks, snares and hats (attack, decay, gain) and how fast things move,
 * <li>a few extra layers (shock rings, edge lights, hat sparks, confetti, laser sweeps, fireflies, waves, rays...),
 * <li>how long a scene stays (20-60 s, moved on at section and phrase boundaries by the stage).
 * </ul>
 * Quiet moments thin the picture out and slow it down. Drops and builds are remembered per song, so a replay starts
 * them a beat or two early. Every layer keeps the comfort limits: saturation 0.6, brightness 0.8, at most two bright
 * jumps a second, never a full-screen flash. Logcat tag: dumbfeel.
 */
final class Feel {
    static final String TAG = "dumbfeel";

    static final int PIANO = 0, HIPHOP = 1, RNB = 2, POP = 3, ROCK = 4, COUNTRY = 5, EDM = 6, JAZZ = 7, REGGAE = 8, GOSPEL = 9, UNKNOWN = 10, NC = 11;
    static final String[] NAMES = {"piano", "hiphop", "rnb", "pop", "rock", "country", "edm", "jazz", "reggae", "gospel", "unknown"};
    /** Scene families: the ten theme packs, then the piano scene, the LED disk and the city journey. */
    static final String[] FAMS = {"money", "love", "street", "party", "grief", "faith", "ocean", "fire", "city", "space", "piano", "disk", "journey"};
    static final int FAM_PIANO = 10, FAM_DISK = 11, FAM_JOURNEY = 12, NF = 13;

    private static final String[][] WORDS = {
        {"piano", "classical", "ambient", "instrumental", "orchestra", "soundtrack", "neoclassical", "symphony", "sonata", "concerto", "chopin", "beethoven", "mozart", "debussy"},
        {"hip hop", "hip-hop", "rap", "trap", "drill", "grime", "boom bap", "gangsta"},
        {"r&b", "rnb", "soul", "neo soul", "quiet storm", "slow jam"},
        {"pop", "dance pop", "synth-pop", "synthpop", "k-pop", "teen pop"},
        {"rock", "metal", "punk", "grunge", "hardcore", "alternative", "emo", "indie rock"},
        {"country", "folk", "bluegrass", "americana", "singer-songwriter", "acoustic"},
        {"edm", "house", "techno", "trance", "electronic", "dubstep", "electro", "disco", "dance"},
        {"jazz", "blues", "bebop", "swing", "bossa"},
        {"reggae", "afro", "afrobeats", "amapiano", "latin", "reggaeton", "salsa", "dancehall", "soca", "highlife"},
        {"gospel", "worship", "christian", "praise", "hymn"},
        {}};

    /** Well-known artists, for when no tags arrive: a name here counts like three tag hits. */
    private static final String[][] ARTISTS = {
        {"ludovico einaudi", "yiruma", "max richter", "olafur arnalds", "brian crain", "the piano guys", "ludwig van"},
        {"drake", "kendrick", "j. cole", "kanye", "ye ", "travis scott", "future", "21 savage", "lil ", "jay-z", "nas ", "eminem", "50 cent", "nicki minaj", "cardi b", "megan thee",
            "tyler, the creator", "a$ap", "young thug", "migos", "metro boomin", "gunna", "roddy ricch", "playboi carti", "dababy", "snoop dogg", "dr. dre", "ice cube", "2pac", "notorious",
            "wiz khalifa", "post malone", "juice wrld", "xxxtentacion", "bas,", "joyner lucas", "polo g", "rod wave", "doja cat", "ice spice", "nba youngboy", "kodak black"},
        {"sza", "frank ocean", "h.e.r.", "usher", "beyonc", "alicia keys", "daniel caesar", "giveon", "summer walker", "jhene", "brent faiyaz", "miguel", "the weeknd", "d'angelo", "erykah badu", "lauryn hill", "marvin gaye", "stevie wonder", "al green", "sade", "aaliyah", "mary j", "luther vandross", "toni braxton", "babyface", "chris brown", "tank", "bryson tiller", "6lack"},
        {"taylor swift", "ariana grande", "dua lipa", "harry styles", "ed sheeran", "justin bieber", "olivia rodrigo", "billie eilish", "bruno mars", "katy perry", "lady gaga", "rihanna", "selena gomez", "miley cyrus", "sabrina carpenter", "charli xcx", "michael jackson", "prince", "madonna", "whitney houston", "elton john", "abba", "bee gees", "backstreet boys", "nsync", "britney spears", "justin timberlake", "maroon 5", "adele", "lizzo", "the chainsmokers", },
        {"metallica", "nirvana", "foo fighters", "linkin park", "ac/dc", "guns n' roses", "green day", "red hot chili", "system of a down", "slipknot", "rage against", "pearl jam", "queen", "led zeppelin", "imagine dragons", "paramore", "the beatles", "rolling stones", "u2", "coldplay", "eagles", "fleetwood mac", "bon jovi", "aerosmith", "kiss", "van halen", "the killers", "arctic monkeys", "oasis", "blink-182", "my chemical romance", "twenty one pilots"},
        {"luke combs", "morgan wallen", "zach bryan", "chris stapleton", "johnny cash", "dolly parton", "willie nelson", "tyler childers", "kacey musgraves", "luke bryan", "blake shelton", "bon iver", "mumford", "the lumineers"},
        {"dimitri vegas", "like mike", "steve aoki", "afrojack", "armin van buuren", "alan walker", "r3hab", "meduza", "gryffin", "illenium", "porter robinson", "rufus du sol", "eric prydz", "kaskade", "the prodigy", "chemical brothers", "alesso", "axwell", "calvin harris", "david guetta", "avicii", "marshmello", "skrillex", "deadmau5", "daft punk", "tiesto", "swedish house", "zedd", "kygo", "martin garrix", "diplo", "fred again", "odesza", "fisher"},
        {"miles davis", "john coltrane", "norah jones", "billie holiday", "ella fitzgerald", "louis armstrong", "dave brubeck", "nina simone", "b.b. king", "muddy waters", "stevie ray", "gregory porter"},
        {"bob marley", "burna boy", "wizkid", "davido", "tems", "rema", "asake", "ayra starr", "bad bunny", "j balvin", "shakira", "daddy yankee", "karol g", "sean paul", "shaggy", "buju banton", "damian marley", "peter tosh"},
        {"kirk franklin", "hillsong", "elevation worship", "bethel music", "maverick city", "tasha cobbs", "cece winans", "yolanda adams", "tye tribbett", "jonathan mcreynolds", "chandler moore", "travis greene"},
        {}};

    //                       money love street party grief faith ocean fire city space
    private static final float[][] PACK_W = {
        /* piano   */ {0f, 2f, 0f, 0f, 2.5f, 1.5f, 3f, 0f, 1f, 3f},
        /* hiphop  */ {3f, 0.8f, 3f, 1.5f, 0.5f, 0f, 0f, 2f, 3f, 0.3f},
        /* rnb     */ {1f, 3f, 1.5f, 1f, 1.5f, 1f, 2.5f, 0.5f, 2f, 0.8f},
        /* pop     */ {1f, 2f, 1f, 3f, 0.5f, 0.5f, 2f, 1.5f, 2.5f, 1.5f},
        /* rock    */ {0.5f, 0.5f, 2f, 1.5f, 1.5f, 0f, 0.3f, 3f, 2f, 1.5f},
        /* country */ {0f, 1.5f, 2f, 0.5f, 1f, 2f, 2f, 0.5f, 0.3f, 0.5f},
        /* edm     */ {0.5f, 0.3f, 1f, 3f, 0f, 0f, 0.5f, 1.5f, 2.5f, 3f},
        /* jazz    */ {1f, 2f, 1.5f, 0.5f, 1.5f, 0.3f, 1f, 0f, 3f, 0.5f},
        /* reggae  */ {0.5f, 1.5f, 0.5f, 3f, 0f, 0.5f, 3f, 2f, 0.3f, 0.3f},
        /* gospel  */ {0f, 1f, 0f, 0.5f, 1f, 3f, 0.5f, 0.5f, 0.5f, 2f},
        /* unknown */ {1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f}};
    //                                   piano disk journey
    private static final float[][] SPECIAL_W = {
        {4f, 0f, 0.2f}, {0f, 1.5f, 2f}, {0f, 0.3f, 1f}, {0f, 1f, 1f}, {0f, 0.6f, 1f}, {0f, 0f, 1f}, {0f, 1.5f, 1f}, {0.5f, 0.3f, 1f},
        {0f, 0.5f, 1f}, {0f, 0f, 0.6f}, {0f, 0.3f, 1f}};

    // beat response per class: decay rates per second, gain on the pop, speed, the camera push (fraction), max dwell
    private static final float[] KICK_DECAY = {3f, 7f, 3.5f, 5.5f, 6.5f, 4f, 6f, 3f, 5f, 3f, 5.5f};
    private static final float[] SNARE_DECAY = {4f, 7.5f, 4f, 6.5f, 7.5f, 5f, 7f, 4f, 6f, 4f, 6f};
    private static final float[] HAT_DECAY = {6f, 11f, 6f, 9f, 10f, 7f, 10f, 6f, 9f, 6f, 9f};
    private static final float[] KICK_GAIN = {0.35f, 1.2f, 0.7f, 1f, 1.1f, 0.6f, 1.1f, 0.5f, 0.9f, 0.5f, 1f};
    private static final float[] SPEED = {0.7f, 1f, 0.75f, 1f, 1.1f, 0.8f, 1.15f, 0.8f, 0.95f, 0.8f, 1f};
    private static final float[] THUMP = {0f, 0.03f, 0f, 0.012f, 0.02f, 0f, 0.02f, 0f, 0.015f, 0f, 0f};
    private static final float[] MAX_DWELL = {60f, 40f, 55f, 45f, 40f, 60f, 40f, 60f, 50f, 60f, 45f};
    private static final boolean[] MELODIC = {true, false, true, false, false, true, false, true, false, true, false};

    private static final int FX_RING = 1, FX_EDGE = 2, FX_HATS = 4, FX_CONFETTI = 8, FX_LASER = 16, FX_FLIES = 32, FX_WAVES = 64,
            FX_LINES = 128, FX_POLY = 256, FX_RAYS = 512, FX_BURST = 1024;
    private static final int[] FX = {0, FX_RING | FX_EDGE | FX_HATS | FX_BURST, FX_WAVES, FX_CONFETTI | FX_BURST, FX_RING | FX_LASER, FX_FLIES,
        FX_RING | FX_HATS | FX_BURST, FX_LINES, FX_POLY, FX_RAYS, FX_RING};

    private static int lastFirst = -1;

    boolean debug;
    boolean early; // a remembered drop is about to land: the stage may switch before the minimum dwell is up
    int cls = UNKNOWN;
    float conf;

    // ---- inputs, set by the stage every frame -------------------------------------------------------
    float bass, mid, high, ener, harm, bpm, tempoConf, kickEnv, sub, progress = -1f, fps = 60f;
    boolean kickNow, snareNow, hatNow, kickPop, barEdge, clockOn;
    int sec, barCount;
    float phase;

    // ---- state -------------------------------------------------------------------------------------
    private final Random rng = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private SharedPreferences prefs;
    private String song = "", key = "", forced = "auto";
    private long seed;
    private float since, candT, statusT, logSecT;
    private int cand = UNKNOWN;
    private final float[] prior = new float[NC], total = new float[NC];
    private float rBass = 0.4f, rHigh = 0.3f, rCent = 0.5f, rOnset, rKick, rSnare, rHat, eMax, eMin = 1f, eFast, eSlow, bpmAvg, harmAvg, dyn;
    private int[] list = new int[0];
    private int idx;
    private boolean rebuild;
    private int forcedPack = -1;
    private boolean forceJourney, hasCity;
    private String lastSection = "";

    // anticipation
    private final int[] evType = new int[8], evPos = new int[8];
    private final boolean[] evFired = new boolean[8];
    private int evN;
    private long lastPos;

    // layers
    private float quiet, tension, burstT, burstAt = -99f, thumpEnv, barPush, edgeEnv, clock, bounce;
    private int ca = 0xFFC08040, cb = 0xFF6090C0;
    private final float[] ringT = {-1f, -1f, -1f, -1f}, ripT = {-1f, -1f, -1f, -1f}, ripX = new float[4], ripY = new float[4];
    private static final int NP = 40;
    private final float[] px = new float[NP], py = new float[NP], pvx = new float[NP], pvy = new float[NP], pl = new float[NP];
    private final int[] pc = new int[NP];
    private float lastEdge = -9f, lastRip = -9f, confT;
    private final float[] fx0 = new float[18], fy0 = new float[18];

    Feel() {
        for (int i = 0; i < fx0.length; i++) {
            fx0[i] = (i * 0.618f) % 1f;
            fy0[i] = 0.25f + 0.7f * ((i * 0.381f + 0.17f) % 1f);
        }
    }

    void attach(SharedPreferences p) {
        prefs = p;
    }

    void setForced(String g) {
        forced = g == null ? "auto" : g.toLowerCase(Locale.US);
        int f = indexOf(forced);
        if (f >= 0 && f != cls) {
            cls = f;
            conf = 1f;
            rebuild = true;
        }
    }

    private static int indexOf(String n) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(n)) return i;
        return -1;
    }

    // ---- per song ------------------------------------------------------------------------------------

    void newSong(String name) {
        song = name;
        since = 0f;
        cls = UNKNOWN;
        cand = UNKNOWN;
        conf = 0f;
        candT = 0f;
        rBass = 0.4f;
        rHigh = 0.3f;
        rCent = 0.5f;
        rOnset = rKick = rSnare = rHat = 0f;
        eMax = 0f;
        eMin = 1f;
        bpmAvg = harmAvg = dyn = 0f;
        tension = 0f;
        burstT = 0f;
        statusT = 0f;
        lastSection = "";
        evN = 0;
        early = false;
        java.util.Arrays.fill(total, 0f);
        list = new int[0];
        idx = 0;
    }

    /** The plan exists: read the tags, make the first guess, build the playlist, load this song's remembered builds and drops. */
    void begin(Variety.Plan plan, String text, boolean tagHard, boolean cityKnown) {
        key = plan.key;
        seed = plan.seed;
        forcedPack = plan.forcedPack;
        forceJourney = plan.forceJourney;
        hasCity = cityKnown;
        for (int c = 0; c < NC; c++) {
            int hits = 0;
            for (String w : WORDS[c]) if (text.contains(w)) hits++;
            prior[c] = Math.min(3, hits) * 1.5f;
            for (String n : ARTISTS[c]) if (text.contains(n)) prior[c] += 3f;
        }
        if (plan.hiphop) prior[HIPHOP] += 3f;
        if (plan.rnb) prior[RNB] += 3f;
        if (plan.afro) prior[REGGAE] += 3f;
        if (tagHard) prior[ROCK] += 1f;
        loadMemory();
        int f = indexOf(forced);
        if (f >= 0) {
            cls = f;
            conf = 1f;
        } else {
            score();
            pick(true);
        }
        buildList();
        log("song=\"" + song + "\" genre=" + NAMES[cls] + " conf=" + pct(conf) + " source=" + (maxPrior() > 0f ? "tags" : "audio-pending")
                + " playlist=" + listString() + " memory=" + evN);
    }

    /** Tags that arrived after the plan (the artist's origin lookup): fold them into the guess. */
    void refine(String text, boolean hiphop) {
        boolean changed = false;
        for (int c = 0; c < NC; c++) {
            int hits = 0;
            for (String w : WORDS[c]) if (text.contains(w)) hits++;
            float v = Math.min(3, hits) * 1.5f;
            for (String n : ARTISTS[c]) if (text.contains(n)) v += 3f;
            if (c == HIPHOP && hiphop) v += 3f;
            if (v > prior[c]) {
                prior[c] = v;
                changed = true;
            }
        }
        if (changed) {
            log("tags arrived: " + text.trim());
            confT = 1f; // re-score at once
            score();
            pick(true);
        }
    }

    private float maxPrior() {
        float m = 0f;
        for (float p : prior) m = Math.max(m, p);
        return m;
    }

    // ---- classification ----------------------------------------------------------------------------

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** One step per frame: smooth the features, re-score the genre four times a second, run the layers. */
    void update(float dt) {
        since += dt;
        clock += dt;
        float k = Math.min(1f, dt / 6f);
        float sum = bass + mid + high + 0.05f;
        rBass += (bass / sum - rBass) * k;
        rHigh += (high / sum - rHigh) * k;
        rCent += ((0.5f * mid + high) / sum - rCent) * k;
        float inst = Math.min(1f, dt / 5f);
        rKick += ((kickNow ? 1f / Math.max(dt, 0.008f) : 0f) - rKick) * inst;
        rSnare += ((snareNow ? 1f / Math.max(dt, 0.008f) : 0f) - rSnare) * inst;
        rHat += ((hatNow ? 1f / Math.max(dt, 0.008f) : 0f) - rHat) * inst;
        rOnset = rKick + rSnare + rHat;
        if (bpm > 40f) bpmAvg = bpmAvg == 0f ? bpm : bpmAvg + (bpm - bpmAvg) * Math.min(1f, dt / 4f);
        harmAvg += (harm - harmAvg) * k;
        eMax = Math.max(ener, eMax - dt * 0.02f);
        eMin = Math.min(ener, eMin + dt * 0.02f);
        dyn = (eMax - eMin) / (eMax + 0.05f);
        eFast += (ener - eFast) * Math.min(1f, dt / 1.5f);
        eSlow += (ener - eSlow) * Math.min(1f, dt / 14f);
        float ratio = eFast / (eSlow + 0.03f);
        float q = (ratio < 0.7f && eFast < 0.3f) ? 1f : 0f;
        quiet += (q - quiet) * Math.min(1f, dt * 0.8f);

        confT += dt;
        if (confT >= 0.25f && indexOf(forced) < 0) {
            confT = 0f;
            score();
            pick(since < 20f);
        }
        runLayers(dt);
        statusT += dt;
        if (debug && statusT >= 10f) {
            statusT = 0f;
            log("status genre=" + NAMES[cls] + " conf=" + pct(conf) + " section=" + lastSection + " bpm=" + Math.round(bpmAvg) + " onset=" + String.format(Locale.US, "%.1f", rOnset)
                    + " bass=" + pct(rBass) + " harm=" + pct(harmAvg) + " dyn=" + pct(dyn) + " quiet=" + pct(quiet) + " fps=" + Math.round(fps));
        }
    }

    private static float band(float v, float lo, float hi) { // 1 inside [lo, hi], falling off outside
        if (v >= lo && v <= hi) return 1f;
        float d = v < lo ? lo - v : v - hi;
        return Math.max(0f, 1f - d / Math.max(1f, (hi - lo) * 0.5f));
    }

    private void score() {
        float[] l = new float[NC];
        float b = bpmAvg;
        boolean haveBpm = b > 40f;
        float slow = clamp(1f - rOnset / 6f, 0f, 1f);
        l[PIANO] = 1.1f * harmAvg + 0.6f * (rOnset < 1.5f ? 1f : 0f) + 0.3f * dyn;
        l[HIPHOP] = 0.8f * clamp((rBass - 0.28f) * 3.5f, 0f, 1f) + 0.6f * clamp(rHat / 4f, 0f, 1f) + 0.3f * clamp(rOnset / 6f, 0f, 1f) + (haveBpm ? 0.5f * Math.max(band(b, 62f, 100f), band(b, 125f, 165f)) : 0f);
        l[RNB] = (haveBpm ? 0.6f * band(b, 55f, 98f) : 0f) + 0.5f * band(rBass, 0.35f, 0.6f) + 0.4f * slow + 0.3f * harmAvg;
        l[POP] = (haveBpm ? 0.7f * band(b, 100f, 132f) : 0f) + 0.5f * band(rCent, 0.35f, 0.6f) + 0.4f * band(rOnset, 2f, 7f);
        l[ROCK] = 0.9f * clamp((rHigh - 0.25f) * 4f, 0f, 1f) + 0.5f * clamp(rSnare / 2f, 0f, 1f) + 0.4f * dyn + 0.3f * clamp(rOnset / 8f, 0f, 1f);
        l[COUNTRY] = (haveBpm ? 0.4f * band(b, 80f, 130f) : 0f) + 0.5f * band(harmAvg, 0.25f, 0.6f) + 0.5f * clamp((0.4f - rBass) * 4f, 0f, 1f) + 0.2f * slow;
        l[EDM] = (haveBpm ? 0.7f * band(b, 118f, 136f) : 0f) + 0.7f * clamp((rKick - 1.4f) / 1.2f, 0f, 1f) * (tempoConf > 0.5f ? 1f : 0.4f) + 0.4f * clamp((rBass - 0.35f) * 3f, 0f, 1f);
        l[JAZZ] = 0.6f * band(harmAvg, 0.3f, 0.7f) + 0.5f * slow + 0.3f * clamp((0.45f - rBass) * 4f, 0f, 1f);
        l[REGGAE] = (haveBpm ? 0.5f * band(b, 62f, 112f) : 0f) + 0.5f * band(rBass, 0.45f, 0.65f) + 0.3f * band(rOnset, 1.5f, 5f);
        l[GOSPEL] = 0.35f * dyn + 0.25f * band(harmAvg, 0.3f, 0.7f);
        float liveW = clamp(since / 20f, 0f, 1f);
        for (int c = 0; c < UNKNOWN; c++) total[c] = prior[c] + liveW * l[c] * 2f;
        total[UNKNOWN] = 0.9f;
    }

    private void pick(boolean free) {
        int top = UNKNOWN, second = UNKNOWN;
        float t1 = 0f, t2 = 0f;
        for (int c = 0; c < NC; c++) {
            float v = c == UNKNOWN ? 0.9f : (since < 0.01f ? prior[c] : total[c]);
            if (v > t1) {
                second = top;
                t2 = t1;
                top = c;
                t1 = v;
            } else if (v > t2) {
                second = c;
                t2 = v;
            }
        }
        float cf = clamp((t1 - t2) / (t1 + 0.5f), 0f, 1f);
        if (top != UNKNOWN && (t1 < 1.2f || cf < 0.15f)) {
            top = UNKNOWN;
            cf = clamp(0.3f * t1 / 1.2f, 0f, 0.3f);
        }
        if (top == UNKNOWN) cf = clamp(0.3f * t1 / 1.2f, 0f, 0.3f);
        conf = cf;
        if (top == UNKNOWN && cls != UNKNOWN && free) top = cls; // the first guess is not given up for a shrug
        if (top == cls) {
            candT = 0f;
            return;
        }
        if (top == cand) candT += 0.25f;
        else {
            cand = top;
            candT = 0f;
        }
        boolean accept = free || (candT > 6f && t1 > total[cls] * 1.2f);
        if (accept) {
            int was = cls;
            cls = top;
            candT = 0f;
            rebuild = true;
            log("genre " + NAMES[was] + " -> " + NAMES[cls] + " conf=" + pct(conf) + " since=" + Math.round(since) + "s bpm=" + Math.round(bpmAvg)
                    + " bass=" + pct(rBass) + " high=" + pct(rHigh) + " harm=" + pct(harmAvg) + " onset=" + String.format(Locale.US, "%.1f", rOnset));
        }
    }

    // ---- playlist ----------------------------------------------------------------------------------

    private float famWeight(int c, int f) {
        if (f < 10) return PACK_W[c][f];
        float w = SPECIAL_W[c][f - 10];
        if (f == FAM_PIANO && harmAvg > 0.55f && since > 6f) w = Math.max(w, 1.5f);
        if (f == FAM_JOURNEY && !hasCity) return 0f;
        return w;
    }

    private void buildList() {
        rebuild = false;
        if (forceJourney && hasCity) {
            list = new int[] {FAM_JOURNEY};
            idx = 0;
            return;
        }
        if (forcedPack >= 0) {
            list = new int[] {forcedPack};
            idx = 0;
            return;
        }
        int n = 4 + (int) ((seed >>> 3 & 0xFF) % 4);
        int[] best = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            Random r = new Random(seed * 31L + cls * 977L + attempt * 7919L + (idx > 0 ? idx : 0));
            int[] cur = new int[n];
            boolean ok = true;
            for (int i = 0; i < n && ok; i++) {
                float sum = 0f;
                float[] w = new float[NF];
                for (int f = 0; f < NF; f++) {
                    w[f] = famWeight(cls, f);
                    for (int back = 1; back <= 3; back++) {
                        int j = i - back;
                        if (j >= 0 && cur[j] == f) w[f] = 0f;
                    }
                    if (i == 0 && f == lastFirst) w[f] = 0f;
                    sum += w[f];
                }
                if (sum <= 0f) {
                    ok = false;
                    break;
                }
                float v = r.nextFloat() * sum;
                int pickF = 0;
                for (int f = 0; f < NF; f++) {
                    v -= w[f];
                    if (v <= 0f && w[f] > 0f) {
                        pickF = f;
                        break;
                    }
                }
                cur[i] = pickF;
            }
            if (!ok) continue;
            // cyclic check: the loop back to the start must not repeat a family within 3
            boolean cyc = true;
            for (int i = 0; i < n && cyc; i++) {
                for (int back = 1; back <= 3; back++) {
                    int j = ((i - back) % n + n) % n;
                    if (j != i && cur[j] == cur[i] && n > 3) cyc = false;
                }
            }
            best = cur;
            if (cyc) break;
        }
        if (best == null) {
            best = new int[] {topPack()};
        }
        list = best;
        if (idx >= list.length) idx = 0;
        if (list.length > 0 && since < 3f) lastFirst = list[0];
    }

    int topPack() {
        int bf = 0;
        float bw = -1f;
        for (int f = 0; f < 10; f++) {
            float w = PACK_W[cls][f];
            if (w > bw) {
                bw = w;
                bf = f;
            }
        }
        return bf;
    }

    /** The next family on the playlist; the playlist is rebuilt first when the genre has changed. */
    int nextFamily() {
        if (rebuild) {
            buildList();
            log("playlist " + NAMES[cls] + " -> " + listString());
        }
        if (list.length == 0) return topPack();
        int f = list[idx % list.length];
        idx++;
        return f;
    }

    /** The section for the scene's energy: 0 calm, 1 medium, 2 full, 3 break. Intro and outro stay calm. */
    int energySection(int secNow) {
        String s = sectionName(secNow);
        return "intro".equals(s) || "outro".equals(s) ? 0 : "verse".equals(s) ? 1 : "chorus".equals(s) ? 2 : 3;
    }

    String sectionName(int secNow) {
        if (since < 15f || (progress >= 0f && progress < 0.06f)) return "intro";
        if (progress > 0.92f) return "outro";
        return secNow == 2 ? "chorus" : secNow == 3 ? "bridge" : "verse";
    }

    void noteSection(int secNow) {
        String s = sectionName(secNow);
        if (!s.equals(lastSection)) {
            lastSection = s;
            log("section=" + s + " genre=" + NAMES[cls] + " fps=" + Math.round(fps));
        }
    }

    void noteScene(int family, int sceneId, String sceneName) {
        log("family -> " + FAMS[Math.max(0, Math.min(NF - 1, family))] + " scene=" + sceneName + " section=" + lastSection + " genre=" + NAMES[cls]
                + " list=" + listString() + " at=" + (idx % Math.max(1, list.length)) + " fps=" + Math.round(fps));
    }

    /** The first scene waits a few seconds so the genre is known when the playlist is used. */
    boolean ready() {
        return indexOf(forced) >= 0 || (since >= 4f && (cls != UNKNOWN || since >= 8f));
    }

    float minDwell() {
        return 20f;
    }

    float maxDwell() {
        return MAX_DWELL[cls];
    }

    float kickDecay() {
        return KICK_DECAY[cls];
    }

    float snareDecay() {
        return SNARE_DECAY[cls];
    }

    float hatDecay() {
        return HAT_DECAY[cls];
    }

    float kickGain() {
        return KICK_GAIN[cls] * (1f - 0.35f * quiet);
    }

    boolean melodic() {
        return MELODIC[cls];
    }

    /** Speed of the scenes: the genre's, slower in quiet moments, faster in a build. */
    float speedMul() {
        return SPEED[cls] * (1f - 0.45f * quiet) * (1f + 0.3f * tension);
    }

    /** Camera push, as a fraction (at most 0.03): kicks, bar starts, the 808, the off-beat bounce and a building tension. */
    float zoom() {
        return Math.min(0.03f, THUMP[cls] * (0.7f * thumpEnv + 0.3f * barPush + 0.4f * Math.min(1f, sub)) * (1f - 0.5f * quiet)
                + (cls == REGGAE ? 0.014f * bounce : 0f) + (cls == EDM ? 0.03f * tension : 0f));
    }

    private String listString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.length; i++) sb.append(i == 0 ? "" : ",").append(FAMS[list[i]]);
        return sb.append("]").toString();
    }

    private static String pct(float v) {
        return Math.round(v * 100f) + "%";
    }

    private void log(String s) {
        if (debug) Log.i(TAG, s);
    }

    // ---- remembered builds and drops ------------------------------------------------------------------

    private void loadMemory() {
        evN = 0;
        if (prefs == null) return;
        try {
            String s = prefs.getString("feel_" + key, "");
            for (String part : s.split(",")) {
                int at = part.indexOf('@');
                if (at < 1 || evN >= evType.length) continue;
                evType[evN] = Integer.parseInt(part.substring(0, at));
                evPos[evN] = Integer.parseInt(part.substring(at + 1));
                evFired[evN++] = false;
            }
        } catch (RuntimeException ignored) {
            evN = 0;
        }
    }

    /** A build (1) or a drop (2) at this playback position: remembered for the next play. */
    void record(int type, long posMs) {
        if (posMs < 4000 || prefs == null || key.isEmpty()) return;
        for (int i = 0; i < evN; i++) if (evType[i] == type && Math.abs(evPos[i] - posMs) < 8000) return;
        if (evN == evType.length) {
            System.arraycopy(evType, 1, evType, 0, evN - 1);
            System.arraycopy(evPos, 1, evPos, 0, evN - 1);
            evN--;
        }
        evType[evN] = type;
        evPos[evN] = (int) posMs;
        evFired[evN++] = true; // it has just happened this play
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < evN; i++) sb.append(i == 0 ? "" : ",").append(evType[i]).append('@').append(evPos[i]);
        try {
            prefs.edit().putString("feel_" + key, sb.toString()).apply();
        } catch (RuntimeException ignored) {
            // storage unavailable: the song has no memory
        }
        log("remember " + (type == 2 ? "drop" : "build") + " at " + posMs / 1000 + "s");
    }

    /** 0 nothing, 1 a remembered build is 1-2 beats away, 2 a remembered drop is. */
    int anticipate(long posMs, float beatMs) {
        if (posMs < lastPos - 5000) for (int i = 0; i < evN; i++) evFired[i] = false; // a seek or a replay
        lastPos = posMs;
        for (int i = 0; i < evN; i++) {
            long lead = evPos[i] - posMs;
            if (!evFired[i] && lead >= 0 && lead <= 2f * beatMs + 250f) {
                evFired[i] = true;
                log("anticipate " + (evType[i] == 2 ? "drop" : "build") + " in " + lead + " ms");
                return evType[i];
            }
        }
        return 0;
    }

    /** One big soft bloom for a drop, at most every 8 s. */
    void burst() {
        if (clock - burstAt < 8f || (FX[cls] & FX_BURST) == 0) return;
        burstAt = clock;
        burstT = 1f;
    }

    // ---- layers --------------------------------------------------------------------------------------

    void setAccents(int a, int b) {
        ca = soft(a);
        cb = soft(b);
    }

    /** Comfort: saturation at most 0.6, brightness at most 0.8. */
    private static int soft(int c) {
        float s = Math.min(0.6f, Variety.satOf(c)), v = Math.min(0.8f, Variety.valOf(c));
        return Variety.hsv(Variety.hueOf(c), s, v) | 0xFF000000;
    }

    private static int withAlpha(int c, float a) {
        return (Math.max(0, Math.min(255, (int) (a * 255f))) << 24) | (c & 0xFFFFFF);
    }

    private void spawn(float x, float y, float vx, float vy, int col) {
        for (int i = 0; i < NP; i++) {
            if (pl[i] <= 0f) {
                px[i] = x;
                py[i] = y;
                pvx[i] = vx;
                pvy[i] = vy;
                pl[i] = 1f;
                pc[i] = col;
                return;
            }
        }
    }

    private void runLayers(float dt) {
        int fx = FX[cls];
        thumpEnv = Math.max(0f, thumpEnv - dt * 6f);
        barPush = Math.max(0f, barPush - dt * 3f);
        edgeEnv = Math.max(0f, edgeEnv - dt * 7f);
        if (kickPop) {
            thumpEnv = 1f;
            for (int i = 0; i < 4; i++) {
                if ((fx & FX_RING) != 0 && ringT[i] < 0f) {
                    ringT[i] = 0f;
                    break;
                }
            }
        }
        if (barEdge) barPush = 1f;
        if (snareNow && (fx & FX_EDGE) != 0 && clock - lastEdge > 0.5f) { // crisp, but never more than twice a second
            lastEdge = clock;
            edgeEnv = 1f;
        }
        for (int i = 0; i < 4; i++) {
            if (ringT[i] >= 0f) {
                ringT[i] += dt;
                if (ringT[i] > 0.9f) ringT[i] = -1f;
            }
            if (ripT[i] >= 0f) {
                ripT[i] += dt;
                if (ripT[i] > 1.2f) ripT[i] = -1f;
            }
        }
        if (hatNow && (fx & FX_HATS) != 0 && quiet < 0.6f) {
            for (int j = 0; j < 2; j++) spawn(rng.nextFloat(), 0.9f, (rng.nextFloat() - 0.5f) * 0.25f, -(0.35f + 0.5f * rng.nextFloat()), rng.nextBoolean() ? ca : cb);
        }
        if ((fx & FX_FLIES) != 0 && (hatNow || snareNow) && clock - lastRip > 0.5f) { // a string pluck: a small ring
            lastRip = clock;
            for (int i = 0; i < 4; i++) {
                if (ripT[i] < 0f) {
                    ripT[i] = 0f;
                    ripX[i] = 0.15f + 0.7f * rng.nextFloat();
                    ripY[i] = 0.3f + 0.4f * rng.nextFloat();
                    break;
                }
            }
        }
        if ((fx & FX_CONFETTI) != 0 && sec == 2 && quiet < 0.5f && rng.nextFloat() < dt * 6f) { // the chorus only
            spawn(rng.nextFloat(), -0.04f, (rng.nextFloat() - 0.5f) * 0.05f, 0.08f + 0.1f * rng.nextFloat(), rng.nextBoolean() ? ca : cb);
        }
        for (int i = 0; i < NP; i++) {
            if (pl[i] <= 0f) continue;
            px[i] += pvx[i] * dt;
            py[i] += pvy[i] * dt;
            pl[i] -= dt * ((fx & FX_CONFETTI) != 0 ? 0.12f : 1.3f);
        }
        // tension builds in a build section and falls away after the drop
        float tt = sec == 1 && cls == EDM ? 1f : 0f;
        tension += (tt - tension) * Math.min(1f, dt * (tt > tension ? 0.35f : 1.5f));
        if (burstT > 0f) burstT = Math.max(0f, burstT - dt / 1.6f);
        // reggae: the bounce lands on the off-beat
        if (cls == REGGAE && clockOn) {
            float d = Math.abs(phase - 0.5f);
            bounce = Math.max(0f, 1f - d * 6f);
        } else {
            bounce = 0f;
        }
    }

    void drawFx(Canvas c, float w, float h, float a) {
        if (a <= 0.01f) return;
        int fx = FX[cls];
        float k = a * (1f - 0.6f * quiet);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setStrokeCap(Paint.Cap.ROUND);
        if ((fx & FX_WAVES) != 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(h * 0.012f);
            for (int i = 0; i < 3; i++) {
                paint.setColor(withAlpha(i == 1 ? cb : ca, 0.14f * k));
                path.reset();
                for (int s = 0; s <= 24; s++) {
                    float x = w * s / 24f, y = h * (0.72f + 0.07f * i) + (float) Math.sin(s * 0.55f + clock * (0.5f + 0.15f * i) + i) * h * (0.02f + 0.03f * Math.min(1f, bass));
                    if (s == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                c.drawPath(path, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
        if ((fx & FX_LINES) != 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, h * 0.003f));
            for (int i = 0; i < 3; i++) {
                paint.setColor(withAlpha(i == 1 ? cb : ca, 0.2f * k));
                path.reset();
                for (int s = 0; s <= 30; s++) {
                    float x = w * s / 30f, y = h * (0.3f + 0.18f * i) + (float) Math.sin(s * 0.3f + clock * 0.22f + i * 2f) * h * 0.06f + (float) Math.sin(s * 0.9f - clock * 0.17f) * h * 0.02f;
                    if (s == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                c.drawPath(path, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
        if ((fx & FX_POLY) != 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, h * 0.004f));
            float hz = Math.max(0.6f, bpm / 60f);
            for (int i = 0; i < 3; i++) {
                float p = (float) Math.abs(Math.sin(clock * Math.PI * hz * (0.5f + 0.25f * i)));
                paint.setColor(withAlpha(i == 1 ? cb : ca, (0.08f + 0.1f * p) * k));
                c.drawCircle(w * 0.5f, h * 0.55f, h * (0.12f + 0.07f * i) * (1f + 0.12f * p), paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
        if ((fx & FX_RAYS) != 0) {
            float open = clamp(eFast * 1.6f, 0f, 1f);
            for (int i = 0; i < 7; i++) {
                float ang = -1.5708f + (i - 3) * (0.1f + 0.1f * open) + (float) Math.sin(clock * 0.15f + i) * 0.03f, len = h * 1.3f, hw = h * (0.025f + 0.02f * open);
                float ex = w * 0.5f + (float) Math.cos(ang) * len, ey = h * 1.05f + (float) Math.sin(ang) * len;
                float nx = -(float) Math.sin(ang), ny = (float) Math.cos(ang);
                path.reset();
                path.moveTo(w * 0.5f, h * 1.05f);
                path.lineTo(ex + nx * hw, ey + ny * hw);
                path.lineTo(ex - nx * hw, ey - ny * hw);
                path.close();
                paint.setColor(withAlpha(i % 2 == 0 ? ca : cb, (0.05f + 0.1f * open) * k));
                c.drawPath(path, paint);
            }
        }
        if ((fx & FX_LASER) != 0) {
            float drive = clamp(0.5f * (mid + high) + 0.4f * ener, 0f, 1f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, h * 0.004f));
            for (int i = 0; i < 3; i++) {
                float ang = -1.5708f + (float) Math.sin(clock * 0.45f + i * 2.1f) * 0.75f, x0 = w * (0.15f + 0.35f * i);
                paint.setColor(withAlpha(i == 1 ? cb : ca, (0.1f + 0.2f * drive) * k));
                c.drawLine(x0, h, x0 + (float) Math.cos(ang) * h * 1.2f, h + (float) Math.sin(ang) * h * 1.2f, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
        if ((fx & FX_FLIES) != 0) {
            for (int i = 0; i < fx0.length; i++) {
                float x = fx0[i] * w + (float) Math.sin(clock * 0.2f + i * 1.9f) * w * 0.03f, y = fy0[i] * h + (float) Math.sin(clock * 0.17f + i * 2.7f) * h * 0.02f;
                float p = Math.max(0f, (float) Math.sin(clock * 0.5f + i * 1.3f));
                paint.setColor(withAlpha(0xFFE0A0, 0.12f * p * k));
                c.drawCircle(x, y, h * 0.012f, paint);
                paint.setColor(withAlpha(0xFFE8B0, 0.7f * p * k));
                c.drawCircle(x, y, h * 0.0035f, paint);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, h * 0.003f));
            for (int i = 0; i < 4; i++) {
                if (ripT[i] < 0f) continue;
                float f = ripT[i] / 1.2f;
                paint.setColor(withAlpha(ca, 0.22f * (1f - f) * k));
                c.drawCircle(ripX[i] * w, ripY[i] * h, h * (0.01f + 0.07f * f), paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
        if ((fx & FX_RING) != 0) {
            paint.setStyle(Paint.Style.STROKE);
            for (int i = 0; i < 4; i++) {
                if (ringT[i] < 0f) continue;
                float f = ringT[i] / 0.9f;
                paint.setStrokeWidth(Math.max(2f, h * 0.007f * (1f - f)));
                paint.setColor(withAlpha(ca, 0.3f * (1f - f) * k));
                c.drawCircle(w * 0.5f, h * 0.45f, h * (0.08f + 0.55f * f), paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
        if (edgeEnv > 0.01f) { // the snare lights only the edges of the picture
            for (int i = 0; i < 6; i++) {
                float f = 1f - i / 6f, bw = w * 0.012f;
                paint.setColor(withAlpha(cb, 0.2f * edgeEnv * f * k));
                c.drawRect(i * bw, 0, (i + 1) * bw, h, paint);
                c.drawRect(w - (i + 1) * bw, 0, w - i * bw, h, paint);
            }
        }
        if ((fx & (FX_HATS | FX_CONFETTI)) != 0) {
            boolean conf = (fx & FX_CONFETTI) != 0;
            for (int i = 0; i < NP; i++) {
                if (pl[i] <= 0f) continue;
                float x = px[i] * w, y = py[i] * h;
                if (conf) {
                    int col = blendWhite(pc[i], 0.35f);
                    paint.setColor(withAlpha(col, 0.5f * Math.min(1f, pl[i] * 3f) * k));
                    rectAt(c, x, y, h * 0.011f, h * 0.005f, clock * 2f + i);
                } else {
                    paint.setColor(withAlpha(pc[i], 0.6f * Math.min(1f, pl[i]) * k));
                    c.drawCircle(x, y, h * 0.004f, paint);
                }
            }
        }
        if (burstT > 0.01f) { // the drop: one thin expanding ring (no soft bloom)
            float f = 1f - burstT, r = h * (0.08f + 0.7f * f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, h * 0.003f));
            paint.setColor(withAlpha(ca, 0.45f * burstT * a));
            c.drawCircle(w * 0.5f, h * 0.5f, r, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void rectAt(Canvas c, float x, float y, float hw, float hh, float rot) {
        c.save();
        c.rotate(rot * 57f, x, y);
        c.drawRect(x - hw, y - hh, x + hw, y + hh, paint);
        c.restore();
    }

    private static int blendWhite(int col, float t) {
        int r = (int) ((col >> 16 & 255) * (1 - t) + 255 * t), g = (int) ((col >> 8 & 255) * (1 - t) + 255 * t), b = (int) ((col & 255) * (1 - t) + 255 * t);
        return soft(0xFF000000 | (r << 16) | (g << 8) | b);
    }
}
