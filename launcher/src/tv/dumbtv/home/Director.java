package tv.dumbtv.home;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

/**
 * Lighting designer and DJ brain. Pure Java (no Android), so the offline harness runs the same code as the TV.
 *
 * At each song start it builds a SHOW PLAN of six looks (intro, build, drop, chorus, breakdown, outro) from the
 * genre guidance. A look changes only on a phrase boundary (every 8 bars) after at least two phrases, or on the
 * downbeat after a drop. Novelty memory: no layer value repeats within the last 3 looks (so adjacent looks differ in
 * all five layers), a full signature does not return within 15 minutes, and a per-song (8) and global (30)
 * history down-weights values seen recently. Palette is not part of a signature and never counts as a change.
 * Sections come from loudness (and Glow's own surge/sag detector): intro, build, drop, chorus, breakdown, outro.
 */
final class Director {
    static final int INTRO = 0, BUILD = 1, DROP = 2, CHORUS = 3, BREAK = 4, OUTRO = 5;
    static final String[] KIND = {"intro", "build", "drop", "chorus", "breakdown", "outro"};
    static final int PHRASE_BARS = 8, MIN_HOLD_BARS = 16;
    static final long SIG_BLOCK_MS = 15L * 60 * 1000;
    private static final int[] PLAN_KINDS = {INTRO, BUILD, DROP, CHORUS, BREAK, OUTRO};

    static boolean DBG;
    private final Random rnd;
    private String genre = "pop";
    private boolean active = true;
    private SongLooks.Entry entry;           // the song's mood-table entry (or a derived one); null = genre guidance only
    private boolean[][] prefMask, avoidMask; // per layer: values the entry prefers (4x) and never allows

    private final ArrayList<Look> songHist = new ArrayList<>(), globalHist = new ArrayList<>();
    private final HashMap<String, Long> sigSeen = new HashMap<>();
    private final ArrayList<Look> plan = new ArrayList<>();
    private int planIdx = -1;
    private Look cur, prev;
    private long curStartMs, curStartBar, fadeStartMs, songStartMs, lastMs;
    private long lastBars = Long.MIN_VALUE;
    private boolean pendingDrop;
    private long dropWaitBar;
    int violations; // layer repeats that had to be allowed because the genre left no other choice

    // section detector
    private int kind = INTRO;
    private int candKind = INTRO;
    private long candSinceMs, kindSinceMs, dropAtMs = -1;
    private float eFast, eSlow, peak, eAgo;
    private long agoAtMs;
    private float chorusHold, songMean, meanT;
    private int glowPrev;
    private boolean seeded;
    private long glowChangeMs, lastDetectMs;

    Director(long seed) {
        rnd = new Random(seed);
    }

    // ---- genre guidance ----

    static String canon(String g) {
        if (g == null) return "pop";
        String s = g.toLowerCase();
        if (s.contains("sleep") || s.contains("meditat") || s.contains("calm") || s.contains("white noise")) return "calm";
        if (s.contains("hip") || s.contains("rap") || s.contains("trap") || s.contains("drill")) return "hiphop";
        if (s.contains("edm") || s.contains("house") || s.contains("dance") || s.contains("electro") || s.contains("techno") || s.contains("trance")) return "edm";
        if (s.contains("rock") || s.contains("metal") || s.contains("punk") || s.contains("alt")) return "rock";
        if (s.contains("country") || s.contains("folk") || s.contains("bluegrass")) return "country";
        if (s.contains("r&b") || s.contains("rnb") || s.contains("soul") || s.contains("romantic")) return "rnb";
        if (s.contains("sad") || s.contains("melanch")) return "sad";
        if (s.contains("piano") || s.contains("classical") || s.contains("ambient") || s.contains("instrumental")) return "piano";
        if (s.contains("afro") || s.contains("latin") || s.contains("reggae") || s.contains("salsa") || s.contains("dancehall")) return "afro";
        return "pop";
    }

    private static int[] ids(String[] vocab, String... names) {
        int[] r = new int[names.length];
        for (int i = 0; i < names.length; i++) r[i] = Look.idx(vocab, names[i]);
        return r;
    }

    /** allowed layer values for a genre: [backdrop, beam, fg, accent, cam] */
    static int[][] profile(String genre) {
        String[] B = Look.BACKDROPS, M = Look.BEAMS, F = Look.FGS, A = Look.ACCENTS, C = Look.CAMS;
        int[] allCam = ids(C, "push", "drift", "tilt", "wide");
        switch (genre) {
            case "hiphop":
                return new int[][] {ids(B, "tunnel", "city_drive", "plexus", "vortex", "crystal_tunnel", "galaxy", "synth_road"),
                    ids(M, "fan", "heads", "lattice", "wipers", "cross", "curtain", "pillars"),
                    ids(F, "edge", "truss", "haze", "rail", "rise"), ids(A, "sparks", "shock", "fountain", "risers", "dust"), allCam};
            case "edm":
                return new int[][] {ids(B, "tunnel", "mandala_bloom", "hyperspace", "crystal_tunnel", "vortex", "plexus", "synth_road"),
                    ids(M, "fan", "lattice", "rings", "curtain", "cross", "heads", "pillars", "wipers"),
                    ids(F, "floor", "truss", "rise", "haze", "edge"), ids(A, "fountain", "risers", "shock", "confetti", "sparks"), allCam};
            case "rock":
                return new int[][] {ids(B, "city_drive", "vortex", "galaxy", "tunnel", "plexus", "sunset_rays"),
                    ids(M, "heads", "wipers", "fan", "cross", "pillars", "curtain"),
                    ids(F, "truss", "edge", "haze", "rail", "rise"), ids(A, "sparks", "fountain", "shock", "risers"), allCam};
            case "country":
                return new int[][] {ids(B, "firefly_meadow", "sunset_rays", "dunes", "aurora_ridge", "lanterns", "night_ocean"),
                    ids(M, "none", "wipers", "pillars", "fan"),
                    ids(F, "motes", "haze", "rail", "edge"), ids(A, "dust", "risers", "shock", "sparks"), allCam};
            case "rnb":
                return new int[][] {ids(B, "dot_sheet", "sunset_rays", "night_ocean", "aurora_ridge", "lanterns", "synth_road", "underwater_night"),
                    ids(M, "none", "pillars", "wipers", "fan"),
                    ids(F, "floor", "rail", "haze", "motes"), ids(A, "dust", "risers", "shock", "sparks"), allCam};
            case "piano":
                return new int[][] {ids(B, "aurora_ridge", "galaxy", "night_ocean", "dunes", "lanterns", "firefly_meadow", "torus_knot"),
                    ids(M, "none", "pillars", "wipers", "fan"),
                    ids(F, "floor", "rail", "motes", "haze"), ids(A, "dust", "risers", "shock", "sparks"), allCam};
            case "sad":
                return new int[][] {ids(B, "rain", "night_ocean", "underwater_night", "aurora_ridge", "dunes", "galaxy"),
                    ids(M, "none", "pillars", "wipers", "fan"),
                    ids(F, "floor", "rail", "haze", "motes"), ids(A, "dust", "risers", "shock", "sparks"),
                    ids(C, "wide", "drift", "tilt", "push")};
            case "afro":
                return new int[][] {ids(B, "sunset_rays", "mandala_bloom", "lanterns", "city_drive", "firefly_meadow", "synth_road", "dot_sheet"),
                    ids(M, "fan", "heads", "curtain", "cross", "rings", "wipers"),
                    ids(F, "rise", "floor", "truss", "edge", "haze"), ids(A, "confetti", "sparks", "shock", "fountain", "risers"), allCam};
            default: // pop
                return new int[][] {ids(B, "sunset_rays", "synth_road", "mandala_bloom", "aurora_ridge", "city_drive", "dot_sheet", "lanterns", "galaxy"),
                    ids(M, "fan", "heads", "rings", "cross", "curtain", "wipers"),
                    ids(F, "edge", "rise", "truss", "rail", "floor"), ids(A, "confetti", "shock", "sparks", "fountain", "risers"), allCam};
        }
    }

    /** what each section wants from a layer (weights multiply the base 1.0) */
    private float bias(int layer, int value, int k) {
        String n;
        switch (layer) {
            case 1:
                n = Look.BEAMS[value];
                if (k == DROP || k == CHORUS) return "none".equals(n) ? 0.05f : 2.5f;
                if (k == BUILD) return "none".equals(n) ? 0.2f : ("fan".equals(n) || "heads".equals(n) || "lattice".equals(n) ? 2f : 1f);
                if (k == INTRO || k == BREAK || k == OUTRO) return "none".equals(n) ? 3f : ("pillars".equals(n) || "wipers".equals(n) ? 1.5f : 0.6f);
                return 1f;
            case 2:
                n = Look.FGS[value];
                if (k == DROP || k == CHORUS) return "rise".equals(n) || "edge".equals(n) || "truss".equals(n) ? 2f : 1f;
                if (k == INTRO || k == BREAK) return "rail".equals(n) || "floor".equals(n) || "haze".equals(n) || "motes".equals(n) ? 1.8f : 0.8f;
                return 1f;
            case 3:
                n = Look.ACCENTS[value];
                if (k == DROP) return "fountain".equals(n) || "shock".equals(n) || "confetti".equals(n) ? 3f : 0.7f;
                if (k == CHORUS) return "confetti".equals(n) || "fountain".equals(n) || "shock".equals(n) ? 2f : 1f;
                if (k == BUILD) return "risers".equals(n) ? 4f : 0.6f;
                if (k == INTRO || k == BREAK || k == OUTRO) return "dust".equals(n) || "risers".equals(n) ? 2.5f : 0.4f;
                return 1f;
            default:
                return 1f;
        }
    }

    // ---- picking with novelty memory ----

    /** The scene history shared with Glow (persistent on the TV). Names are Look.BACKDROPS entries. */
    interface Memory {
        boolean blocked(String scene, long now);

        int family(String scene);
    }

    /**
     * The backdrop to pin: the look's own choice if the scene history allows it, else the first scene (in random order)
     * that is not blocked and not in the family just shown. A backdrop is held at least 25 s, so two pinned scenes can
     * come from looks that were not neighbours in the plan and the plan's own filter does not cover them.
     */
    static int safeBackdrop(int wanted, Memory m, long now, int lastFamily, Random r, boolean[] banned) {
        if (m == null) return wanted;
        String w = Look.BACKDROPS[wanted];
        if (!m.blocked(w, now) && m.family(w) != lastFamily) return wanted;
        int n = Look.BACKDROPS.length, off = r.nextInt(n);
        for (int i = 0; i < n; i++) {
            int a = (off + i) % n;
            String nm = Look.BACKDROPS[a];
            if (a != wanted && !(banned != null && banned[a]) && !m.blocked(nm, now) && m.family(nm) != lastFamily) return a;
        }
        return wanted;
    }

    private Memory mem;

    void setMemory(Memory m) {
        mem = m;
    }

    Memory memory() {
        return mem;
    }

    private static final int[] ALL_BACKDROPS = new int[Look.BACKDROPS.length];

    static {
        for (int i = 0; i < ALL_BACKDROPS.length; i++) ALL_BACKDROPS[i] = i;
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    private boolean inLast(List<Look> window, int layer, int value, int n) {
        for (int i = Math.max(0, window.size() - n); i < window.size(); i++) if (window.get(i).layer(layer) == value) return true;
        return false;
    }

    private Look pick(int k, List<Look> window, long now) {
        int[][] prof = profile(genre);
        Look best = null;
        for (int tries = 0; tries < 30; tries++) {
            int[] v = new int[Look.LAYERS];
            for (int layer = 0; layer < Look.LAYERS; layer++) {
                // backdrops: every scene is in the pool for every genre (the genre's own list weighs 2x), and a backdrop does
                // not return within 6 looks; the other layers stay genre-restricted and avoid the last 3 looks
                int[] allowed = layer == 0 ? ALL_BACKDROPS : prof[layer];
                if (entry != null && layer > 0) allowed = withPreferred(allowed, layer);
                if (entry != null) allowed = withoutAvoided(allowed, layer);
                int nWin = layer == 0 ? 6 : 3;
                ArrayList<Integer> cands = new ArrayList<>();
                for (int a : allowed) if (!inLast(window, layer, a, nWin)) cands.add(a);
                if (cands.isEmpty()) { // nothing outside the window: avoid at least the previous one
                    violations++;
                    for (int a : allowed) if (!inLast(window, layer, a, 1)) cands.add(a);
                }
                if (cands.isEmpty()) for (int a : allowed) cands.add(a);
                if (cands.isEmpty()) for (int a : prof[layer]) cands.add(a); // an entry that avoids everything a genre offers: keep the show alive
                if (layer == 0 && mem != null) { // the persistent scene memory: rules across songs and restarts, and the family rotates
                    int lastFam = window.isEmpty() ? -1 : mem.family(Look.BACKDROPS[window.get(window.size() - 1).backdrop]);
                    ArrayList<Integer> ok = new ArrayList<>();
                    for (int a : cands) {
                        String nm = Look.BACKDROPS[a];
                        if (!mem.blocked(nm, now) && mem.family(nm) != lastFam) ok.add(a);
                    }
                    if (!ok.isEmpty()) cands = ok;
                }
                float[] w = new float[cands.size()];
                float sum = 0;
                for (int i = 0; i < w.length; i++) {
                    int a = cands.get(i);
                    float x = bias(layer, a, k);
                    if (layer == 0 && !contains(prof[0], a)) x *= 0.5f; // genre-matched backdrops weigh 2x the rest
                    if (prefMask != null && prefMask[layer][a]) x *= 4f; // the entry's preferred families
                    if (inLast(songHist, layer, a, 8)) x *= 0.5f;
                    if (inLast(globalHist, layer, a, 30)) x *= 0.75f;
                    w[i] = x;
                    sum += x;
                }
                float r = rnd.nextFloat() * sum;
                int pickI = 0;
                for (int i = 0; i < w.length; i++) {
                    r -= w[i];
                    if (r <= 0) {
                        pickI = i;
                        break;
                    }
                }
                v[layer] = cands.get(pickI);
            }
            Look l = new Look(v[0], v[1], v[2], v[3], v[4], rnd.nextInt(3), rnd.nextInt(4), k);
            best = l;
            Long seen = sigSeen.get(l.signature());
            if (seen == null || now - seen > SIG_BLOCK_MS) break;
        }
        return best;
    }

    private int[] withPreferred(int[] base, int layer) {
        boolean[] m = prefMask[layer];
        boolean[] in = new boolean[m.length];
        int n = 0;
        for (int a : base) if (!in[a]) {
            in[a] = true;
            n++;
        }
        for (int a = 0; a < m.length; a++) if (m[a] && !in[a]) {
            in[a] = true;
            n++;
        }
        int[] r = new int[n];
        for (int a = 0, i = 0; a < in.length; a++) if (in[a]) r[i++] = a;
        return r;
    }

    private int[] withoutAvoided(int[] base, int layer) {
        boolean[] m = avoidMask[layer];
        int n = 0;
        for (int a : base) if (!m[a]) n++;
        int[] r = new int[n];
        int i = 0;
        for (int a : base) if (!m[a]) r[i++] = a;
        return r;
    }

    /** The backdrops this song never gets (avoided families, and the soft scenes when the motion is heavy). */
    boolean[] bannedBackdrops() {
        return avoidMask == null ? null : avoidMask[0];
    }

    SongLooks.Entry entry() {
        return entry;
    }

    /** Sets the song's entry (null clears it) and builds the masks the picker uses. Call before newSong/rebuild. */
    private void applyEntry(SongLooks.Entry e) {
        entry = e;
        prefMask = avoidMask = null;
        if (e == null) return;
        prefMask = new boolean[Look.LAYERS][];
        avoidMask = new boolean[Look.LAYERS][];
        for (int l = 0; l < Look.LAYERS; l++) {
            prefMask[l] = SongLooks.mask(l, e.families);
            avoidMask[l] = SongLooks.mask(l, e.avoid);
        }
        if ("heavy".equals(e.motion)) {
            for (String n : SongLooks.UNFIT_HEAVY) avoidMask[0][Look.idx(Look.BACKDROPS, n)] = true;
        }
        // an avoided family never loses a backdrop the entry also prefers by name
        for (int l = 0; l < Look.LAYERS; l++) for (int a = 0; a < prefMask[l].length; a++) if (avoidMask[l][a]) prefMask[l][a] = false;
    }

    /** A new entry for the playing song (live fallback finished, or tags arrived): the looks still to come are built again. */
    void setEntry(SongLooks.Entry e, long now, long bars) {
        applyEntry(e);
        if (!active) return;
        ArrayList<Look> w = new ArrayList<>(globalHist);
        boolean early = now - songStartMs < 20000 || cur == null;
        int first = early ? 0 : Math.min(planIdx + 1, PLAN_KINDS.length - 1);
        ArrayList<Look> built = new ArrayList<>();
        for (int i = first; i < PLAN_KINDS.length; i++) {
            Look l = pick(PLAN_KINDS[i], w, now);
            built.add(l);
            w.add(l);
        }
        while (plan.size() > (early ? 0 : planIdx + 1)) plan.remove(plan.size() - 1);
        plan.addAll(built);
        if (early) {
            planIdx = 0;
            switchTo(plan.get(0), now, bars);
        }
    }

    private ArrayList<Look> windowWith(List<Look> extra) {
        ArrayList<Look> w = new ArrayList<>(globalHist);
        w.addAll(extra);
        return w;
    }

    // ---- song and time ----

    boolean active() {
        return active;
    }

    void newSong(String key, String genreIn, long now, long bars, SongLooks.Entry e) {
        applyEntry(e);
        genre = canon(genreIn);
        active = !"calm".equals(genre); // sleep/rain playlists keep the Meditation family and no beat reactions
        songHist.clear();
        songStartMs = now;
        kind = INTRO;
        candKind = INTRO;
        kindSinceMs = candSinceMs = now;
        eFast = eSlow = peak = eAgo = 0f;
        seeded = false;
        agoAtMs = now;
        dropAtMs = -1;
        pendingDrop = false;
        chorusHold = 0f;
        songMean = meanT = 0f;
        glowPrev = 0;
        glowChangeMs = lastDetectMs = now;
        plan.clear();
        planIdx = -1;
        if (!active) {
            cur = null;
            return;
        }
        ArrayList<Look> built = new ArrayList<>();
        for (int k : PLAN_KINDS) {
            ArrayList<Look> w = windowWith(built);
            built.add(pick(k, w, now));
        }
        plan.addAll(built);
        planIdx = 0;
        switchTo(plan.get(0), now, bars);
    }

    /**
     * The genre often arrives a few seconds into a song. If it differs from the one the plan was built for, build
     * the plan again; inside the first 20 s the opening look changes too, later only the looks still to come.
     */
    void setGenre(String genreIn, long now, long bars) {
        String g = canon(genreIn);
        if (g.equals(genre) && cur != null) return;
        genre = g;
        active = !"calm".equals(genre);
        if (!active) {
            cur = null;
            plan.clear();
            planIdx = -1;
            return;
        }
        ArrayList<Look> w = new ArrayList<>(globalHist);
        boolean early = now - songStartMs < 20000 || cur == null;
        int first = early ? 0 : Math.min(planIdx + 1, PLAN_KINDS.length - 1);
        ArrayList<Look> built = new ArrayList<>();
        for (int i = first; i < PLAN_KINDS.length; i++) {
            Look l = pick(PLAN_KINDS[i], w, now);
            built.add(l);
            w.add(l);
        }
        while (plan.size() > (early ? 0 : planIdx + 1)) plan.remove(plan.size() - 1);
        plan.addAll(built);
        if (early) {
            planIdx = 0;
            switchTo(plan.get(0), now, bars);
        }
    }

    private void switchTo(Look l, long now, long bars) {
        prev = cur;
        cur = l;
        curStartMs = now;
        curStartBar = bars;
        fadeStartMs = now;
        songHist.add(l);
        if (songHist.size() > 8) songHist.remove(0);
        globalHist.add(l);
        if (globalHist.size() > 30) globalHist.remove(0);
        sigSeen.put(l.signature(), now);
    }

    /**
     * Call a few times a second. bars and beatInBar come from the beat clock (or a time-based stand-in when
     * the clock is not confident), ener is the smoothed 0..1 energy, glowSection Glow's own detector (2 = surge, 3 = sag).
     */
    void update(long now, long bars, int beatInBar, float ener, int glowSection) {
        if (!active || cur == null) return;
        float dt = Math.max(0f, Math.min(1f, (now - lastMs) / 1000f));
        lastMs = now;
        if (!seeded) { // start the averages at the first reading, or the slow one lags far below and every ramp reads as a surge
            seeded = true;
            eFast = eSlow = eAgo = songMean = peak = ener;
            agoAtMs = now;
        }
        eFast += (ener - eFast) * Math.min(1f, dt / 1.5f);
        eSlow += (ener - eSlow) * Math.min(1f, dt / 12f);
        peak = Math.max(peak * (float) Math.exp(-dt / 90f), eFast);
        if (now - agoAtMs >= 6000) {
            eAgo = eFast;
            agoAtMs = now;
        }
        detectKind(now, glowSection);

        boolean barEdge = bars != lastBars;
        lastBars = bars;
        long held = bars - curStartBar;
        if (pendingDrop) {
            if (dropWaitBar == Long.MIN_VALUE) dropWaitBar = bars;
            boolean downbeat = barEdge && beatInBar == 0;
            // a drop right after a change waits until the look has had 4 bars, and is dropped if that takes over 8 more
            if (held >= 4 && (downbeat || bars - dropWaitBar >= 2)) {
                pendingDrop = false;
                replan(DROP, now);
                switchTo(plan.get(planIdx), now, bars);
                return;
            }
            if (bars - dropWaitBar >= 8 + 4) pendingDrop = false;
        }
        // a look (and so its backdrop scene) never lasts longer than 45 s, or 30 s in a drop or chorus: it changes on the
        // first bar line after the cap, even on a slow song where 16 bars are longer than that
        long cap = kind == DROP || kind == CHORUS ? 30000L : 45000L;
        boolean capped = barEdge && held >= 4 && now - curStartMs >= cap;
        if (capped || (barEdge && bars % PHRASE_BARS == 0 && held >= MIN_HOLD_BARS)) {
            planIdx++;
            if (planIdx >= plan.size()) { // the plan ran out: every look so far has been shown, so the history is the window
                plan.add(pick(kind == DROP ? CHORUS : kind, new ArrayList<>(globalHist), now));
            }
            switchTo(plan.get(planIdx), now, bars);
        }
    }

    /** A section event disagrees with the plan: rebuild what is left of it starting with a look for this section. */
    private void replan(int k, long now) {
        while (plan.size() > planIdx + 1) plan.remove(plan.size() - 1);
        // window = what has really been shown, then each look picked here in order (the current look is already shown)
        ArrayList<Look> w = new ArrayList<>(globalHist);
        Look first = pick(k, w, now);
        plan.add(first);
        planIdx = plan.size() - 1;
        w.add(first);
        int[] after = k == DROP ? new int[] {CHORUS, BREAK, OUTRO} : new int[] {CHORUS, BREAK};
        for (int a : after) {
            Look l = pick(a, w, now);
            plan.add(l);
            w.add(l);
        }
    }

    private void detectKind(long now, int glowSection) {
        float age = (now - songStartMs) / 1000f;
        int cand = kind;
        float pk = Math.max(peak, 0.05f);
        // Glow's surge/sag flag is a level that stays latched; only a fresh change (within 3 s) is an event
        if (glowSection != glowPrev) {
            glowPrev = glowSection;
            glowChangeMs = now;
        }
        // a surge flag at the start of a ramp is a build, not a drop: the energy must really be well above the song so far
        boolean surge = glowSection == 2 && now - glowChangeMs < 3000 && age >= 10f && eFast > 1.25f * songMean && eFast - eAgo > 0.12f;
        boolean sag = glowSection == 3 && now - glowChangeMs < 3000 && age >= 10f;
        float dtS = Math.max(0f, (now - lastDetectMs) / 1000f);
        lastDetectMs = now;
        meanT += dtS;
        songMean += (eFast - songMean) * (meanT > 0.001f ? Math.min(1f, dtS / meanT) : 1f);
        boolean highState = kind == DROP || kind == CHORUS;
        if (DBG && age > 28f && age < 34f) {
            System.out.printf("    age=%.2f kind=%s eFast=%.3f eSlow=%.3f eAgo=%.3f mean=%.3f pk=%.3f glow=%d surge=%b%n", age, KIND[kind], eFast, eSlow, eAgo, songMean, pk, glowSection, surge);
        }
        if (age < 10f) {
            cand = INTRO;
        } else if (surge || (!highState && eFast > eSlow * 1.35f + 0.03f && eFast > 0.55f * pk && eFast > 1.25f * songMean
                && eFast - eAgo > 0.12f)) {
            cand = DROP;
        } else if (sag || (age > 20f && eFast < 0.55f * pk)) {
            cand = age > 120f && eFast < 0.5f * pk && eFast < eAgo ? OUTRO : BREAK;
        } else if (!highState && eFast - eAgo > 0.06f) {
            cand = BUILD;
        } else if (eFast >= 0.8f * pk && eFast >= 1.1f * songMean) {
            chorusHold += 0.25f;
            if (kind == DROP && (now - dropAtMs) < 8000) cand = DROP;
            else if (chorusHold >= 6f || kind == CHORUS) cand = CHORUS;
        } else if (kind == DROP && (now - dropAtMs) > 8000) {
            cand = CHORUS;
        } else if (kind == INTRO && age >= 10f) {
            cand = INTRO; // still the quiet opening: stay sparse until something happens
        }
        if (cand != DROP && cand != CHORUS) chorusHold = 0f;
        if (cand != candKind) {
            candKind = cand;
            candSinceMs = now;
        }
        long need = cand == DROP ? 0 : 500;
        if (cand != kind && now - candSinceMs >= need) {
            kind = cand;
            kindSinceMs = now;
            if (kind == DROP) {
                dropAtMs = now;
                pendingDrop = true;
                dropWaitBar = Long.MIN_VALUE;
            } else if (planIdx + 1 < plan.size() && plan.get(planIdx + 1).kind != kind && kind != OUTRO) {
                // the next planned look was meant for another section: pick again, and chain the rest after it so
                // the no-repeat window still holds between neighbours
                int n = plan.size() - (planIdx + 1);
                int[] kinds = new int[n];
                for (int i = 0; i < n; i++) kinds[i] = i == 0 ? kind : plan.get(planIdx + 1 + i).kind;
                while (plan.size() > planIdx + 1) plan.remove(plan.size() - 1);
                ArrayList<Look> w = new ArrayList<>(globalHist); // the shown looks, then the ones picked here in order
                for (int i = 0; i < n; i++) {
                    Look l = pick(kinds[i], w, now);
                    plan.add(l);
                    w.add(l);
                }
            }
        }
    }

    // ---- accessors for the rig and the overlay ----

    Look look() {
        return cur;
    }

    Look previous() {
        return prev;
    }

    /** 0..1 crossfade from the previous look to the current one over two bars. */
    float fade(long now, long barMs) {
        long d = Math.max(1500, 2 * barMs);
        return Math.max(0f, Math.min(1f, (now - fadeStartMs) / (float) d));
    }

    int kind() {
        return kind;
    }

    /** 0..1 progress through a build (12 s) or an outro (20 s); 0 otherwise */
    float kindProgress(long now) {
        float s = (now - kindSinceMs) / 1000f;
        return kind == BUILD ? Math.min(1f, s / 12f) : kind == OUTRO ? Math.min(1f, s / 20f) : 0f;
    }

    long dropAt() {
        return dropAtMs;
    }

    long lookAgeMs(long now) {
        return now - curStartMs;
    }

    String genre() {
        return genre;
    }

    List<Look> upcoming(int n) {
        ArrayList<Look> r = new ArrayList<>();
        for (int i = planIdx + 1; i < plan.size() && r.size() < n; i++) r.add(plan.get(i));
        return r;
    }

    /** the matched entry (or the live fallback), its mood, felt tempo and the families the picker is weighting */
    String entryDebug() {
        if (entry == null) return "entry: live fallback";
        StringBuilder sb = new StringBuilder("entry: ").append(entry.key.isEmpty() ? "?" : entry.key).append(" [").append(entry.source).append("] mood=")
            .append(entry.summary()).append(" tempo_hint=").append(entry.tempoHint > 0 ? String.valueOf(Math.round(entry.tempoHint)) : "-")
            .append("\nfamilies:");
        for (String f : entry.families) sb.append(' ').append(f).append(',');
        return sb.append(" motion=").append(entry.motion).append(" kick=").append(entry.kick).toString();
    }

    String debug(long now) {
        StringBuilder sb = new StringBuilder();
        if (!active || cur == null) return "director off (" + genre + ")";
        sb.append(entryDebug()).append('\n');
        sb.append("genre=").append(genre).append(" section=").append(KIND[kind])
            .append(" look#").append(globalHist.size()).append(" age=").append(lookAgeMs(now) / 1000).append("s\n")
            .append("look: ").append(cur.name()).append("\nnext:");
        for (Look l : upcoming(3)) sb.append("\n  ").append(KIND[l.kind]).append(": ").append(l.name());
        return sb.toString();
    }
}
