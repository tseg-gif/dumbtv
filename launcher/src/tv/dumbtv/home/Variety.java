package tv.dumbtv.home;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;

/**
 * Makes every song look different from the last eight. A Plan is everything the stage decides once per
 * song: palette (cover color + song seed + mood, never the artist), time of day, weather, camera route,
 * landmark order and which theme packs the words and genre call for. The last songs' choices are kept
 * (palette hue bucket, scene types, landmark order, Milkdrop preset ids) and avoided. Pure Java, so the
 * debug harness runs the same code the TV does. Logcat tag: dumbtvvar.
 */
final class Variety {
    static final String TAG = "dumbtvvar";

    interface Sink {
        void log(String line);
    }

    interface Store {
        String load();

        void save(String s);
    }

    static Sink sink = new Sink() {
        @Override
        public void log(String line) {
            android.util.Log.i(TAG, line);
        }
    };

    static final String[] PACKS = {"money", "love", "street", "party", "grief", "faith", "ocean", "fire",
        "city", "space"};
    static final int[] PACK_N = {6, 4, 4, 4, 4, 4, 4, 4, 5, 4};
    /** Scene ids are pack * 8 + index; this one is the camera route through the artist's city. */
    static final int JOURNEY = 80;
    /** 0 calm, 1 medium, 2 high: how much energy each scene wants. */
    private static final int[][] SCENE_E = {{1, 0, 0, 1, 2, 2}, {0, 0, 1, 2}, {1, 1, 0, 2}, {2, 1, 2, 2},
        {0, 0, 0, 1}, {0, 1, 1, 2}, {0, 0, 1, 2}, {0, 2, 1, 2}, {1, 0, 2, 2, 1}, {2, 0, 0, 1}};
    static final String[] TODS = {"day", "golden", "dusk", "night"};
    static final String[] WX = {"clear", "rain", "fog", "haze"};

    // Stems ending in '!' must be the whole word; the others match the start of a word.
    private static final String[][] WORDS = {
        {"money", "cash", "band!", "bands!", "rich", "paid", "gold", "chain", "jet!", "jets!", "check", "cheque",
            "million", "bag!", "bags!", "bread", "price", "hustl", "deal", "flex", "roll", "dollar", "bank", "racks!"},
        {"love", "heart", "kiss", "romanc", "amor", "baby", "lover", "darling", "sweet", "desire", "forever"},
        {"street", "drive", "driving", "ride", "car!", "cars!", "highway", "road", "midnight", "cruis", "lane!",
            "block!", "hood!"},
        {"party", "club", "danc", "lit!", "tonight", "shots!", "bounce", "wine", "dj!", "weekend", "friday"},
        {"rain", "cry", "tear", "miss", "goodbye", "pain", "alone", "grief", "lonely", "broken", "sorrow", "hurt",
            "gone!"},
        {"god", "jesus", "pray", "heaven", "faith", "lord", "church", "gospel", "bless", "angel", "sunrise",
            "hallelujah", "amen"},
        {"ocean", "sea!", "seas!", "wave", "beach", "island", "sail", "shore", "tide", "coast", "surf", "blue!"},
        {"fire", "flame", "burn", "blaze", "ember", "inferno", "smoke", "hot!", "heat"},
        {"city", "downtown", "lights!", "skyline", "urban", "town!", "neon", "avenue", "tokyo", "york"},
        {"space", "star!", "stars!", "galaxy", "planet", "rocket", "cosmic", "moon", "universe", "orbit", "alien",
            "astro"}};

    /** Scenes the viewer liked or banned with the remote (persisted by Stage). */
    static final java.util.Set<Integer> liked = new java.util.HashSet<>(), banned = new java.util.HashSet<>();
    private static final int KEEP = 8, KEEP_HUE = 10;
    private static final ArrayList<Rec> HIST = new ArrayList<>();
    private static Store store;
    private static boolean loaded;

    private Variety() {
    }

    static final class Rec {
        int hue = -1, tod = -1;
        final ArrayList<Integer> scenes = new ArrayList<>(), presets = new ArrayList<>();
        String landmarks = "";
    }

    static final class Plan {
        String key = "";
        long seed;
        Random rng;
        int pal, hueBucket, tod, wx, skySeed;
        float hueShift, coverMix, mood = 0.5f, camDir = 1, camSpeed = 1, satBoost = 1f;
        int cover;
        int[] packs = new int[3];
        boolean hiphop, rnb, afro, halfTime, fixedRoute, forceJourney, strong;
        int forcedPack = -1;
        String cityId = "", cityName = "";
        String[] order = new String[0];
        float journeyShare;
        final Rec rec = new Rec();

        /** Landmark order as one string, for the log and the history. */
        String route() {
            return Journey.join(order);
        }

        String signature() {
            StringBuilder sb = new StringBuilder();
            sb.append("hue=").append(hueBucket).append(" pal=").append(pal).append(" tod=").append(TODS[tod])
                    .append(" wx=").append(WX[wx]).append(" scenes=").append(rec.scenes)
                    .append(" city=").append(cityId.isEmpty() ? "-" : cityId).append(" route=").append(route())
                    .append(" presets=").append(rec.presets);
            return sb.toString();
        }
    }

    // ---- history -------------------------------------------------------------------------------

    static synchronized void init(Store s) {
        store = s;
        HIST.clear();
        loaded = true;
        String raw = s == null ? "" : s.load();
        if (raw == null) raw = "";
        for (String line : raw.split("\n")) {
            String[] f = line.split("\\|", -1);
            if (f.length < 5) continue;
            try {
                Rec r = new Rec();
                r.hue = Integer.parseInt(f[0]);
                r.tod = Integer.parseInt(f[1]);
                fill(r.scenes, f[2]);
                r.landmarks = f[3];
                fill(r.presets, f[4]);
                HIST.add(r);
            } catch (NumberFormatException ignored) {
                // a damaged line is dropped
            }
        }
    }

    /** Harness: an empty history that is never saved. */
    static synchronized void initMemory() {
        store = null;
        HIST.clear();
        loaded = true;
    }

    private static void fill(ArrayList<Integer> out, String csv) {
        for (String x : csv.split(",")) {
            if (!x.isEmpty()) out.add(Integer.parseInt(x));
        }
    }

    private static String csv(ArrayList<Integer> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) sb.append(i > 0 ? "," : "").append(l.get(i));
        return sb.toString();
    }

    /** The song is over (or replaced): its choices are remembered. */
    static synchronized void commit(Plan p) {
        if (p == null || p.rec.scenes.isEmpty()) return;
        HIST.add(p.rec);
        while (HIST.size() > KEEP_HUE) HIST.remove(0);
        if (store != null) {
            StringBuilder sb = new StringBuilder();
            for (Rec r : HIST) {
                sb.append(r.hue).append('|').append(r.tod).append('|').append(csv(r.scenes)).append('|')
                        .append(r.landmarks).append('|').append(csv(r.presets)).append('\n');
            }
            store.save(sb.toString());
        }
    }

    private static boolean recentScene(int id) {
        for (int i = Math.max(0, HIST.size() - KEEP); i < HIST.size(); i++) {
            if (HIST.get(i).scenes.contains(id)) return true;
        }
        return false;
    }

    private static boolean recentHue(int b) {
        for (Rec r : HIST) if (r.hue == b) return true;
        return false;
    }

    private static String[] recentRoutes() {
        int n = Math.min(KEEP, HIST.size());
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = HIST.get(HIST.size() - n + i).landmarks;
        return out;
    }

    /** Milkdrop preset ids used in the last songs; the page should not pick them first. */
    static synchronized int[] avoidPresets() {
        ArrayList<Integer> l = new ArrayList<>();
        for (int i = Math.max(0, HIST.size() - KEEP); i < HIST.size(); i++) l.addAll(HIST.get(i).presets);
        int[] out = new int[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i);
        return out;
    }

    // ---- colors --------------------------------------------------------------------------------

    static float hueOf(int rgb) {
        float r = (rgb >> 16 & 255) / 255f, g = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f;
        float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), d = mx - mn;
        if (d < 1e-4f) return 0f;
        float h = mx == r ? ((g - b) / d) % 6f : mx == g ? (b - r) / d + 2f : (r - g) / d + 4f;
        h *= 60f;
        return h < 0 ? h + 360f : h;
    }

    static float satOf(int rgb) {
        int mx = Math.max(rgb >> 16 & 255, Math.max(rgb >> 8 & 255, rgb & 255));
        int mn = Math.min(rgb >> 16 & 255, Math.min(rgb >> 8 & 255, rgb & 255));
        return mx == 0 ? 0f : (mx - mn) / (float) mx;
    }

    static float valOf(int rgb) {
        return Math.max(rgb >> 16 & 255, Math.max(rgb >> 8 & 255, rgb & 255)) / 255f;
    }

    static int hsv(float h, float s, float v) {
        h = ((h % 360f) + 360f) % 360f / 60f;
        s = Math.max(0f, Math.min(1f, s));
        v = Math.max(0f, Math.min(1f, v));
        float c = v * s, x = c * (1f - Math.abs(h % 2f - 1f)), m = v - c, r, g, b;
        int k = (int) h;
        switch (k) {
            case 0: r = c; g = x; b = 0; break;
            case 1: r = x; g = c; b = 0; break;
            case 2: r = 0; g = c; b = x; break;
            case 3: r = 0; g = x; b = c; break;
            case 4: r = x; g = 0; b = c; break;
            default: r = c; g = 0; b = x; break;
        }
        return 0xFF000000 | ((int) ((r + m) * 255f + 0.5f) << 16) | ((int) ((g + m) * 255f + 0.5f) << 8)
                | (int) ((b + m) * 255f + 0.5f);
    }

    /** The same color turned around the hue wheel. */
    static int rotate(int rgb, float deg) {
        return hsv(hueOf(rgb) + deg, satOf(rgb), valOf(rgb));
    }

    /** The colour limiter for large areas: saturation at most 0.55 and brightness at most 0.8, hue kept. */
    static int cap(int rgb) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        if (mx == 0) return rgb | 0xFF000000;
        float s = (mx - mn) / (float) mx;
        if (s > 0.55f) {
            float k = 0.55f / s;
            r = Math.round(mx - (mx - r) * k);
            g = Math.round(mx - (mx - g) * k);
            b = Math.round(mx - (mx - b) * k);
        }
        float v = mx / 255f;
        if (v > 0.8f) {
            float k = 0.8f / v;
            r = Math.round(r * k);
            g = Math.round(g * k);
            b = Math.round(b * k);
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static float hueDist(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    // ---- planning ------------------------------------------------------------------------------

    private static int hits(String text, int pack) {
        if (text.isEmpty()) return 0;
        int n = 0, from = -1, len = text.length();
        for (int i = 0; i <= len; i++) {
            boolean letter = i < len && text.charAt(i) >= 'a' && text.charAt(i) <= 'z';
            if (letter) {
                if (from < 0) from = i;
            } else if (from >= 0) {
                String w = text.substring(from, i);
                from = -1;
                for (String stem : WORDS[pack]) {
                    boolean exact = stem.endsWith("!");
                    if (exact ? w.equals(stem.substring(0, stem.length() - 1)) : w.startsWith(stem)) {
                        n++;
                        break;
                    }
                }
            }
        }
        return n;
    }

    private static boolean tagHas(String tags, String... words) {
        for (String w : words) if (tags.contains(w)) return true;
        return false;
    }

    /**
     * @param tags     MusicBrainz genre tags, comma separated, lower case
     * @param theme    Origin theme number (12 hip-hop, 3 afrobeats ...), 0 when unknown
     * @param place    where the artist is from, free text ("compton california"), may be empty
     * @param cover    dominant cover color (RGB) or 0
     * @param style    react_style: "auto" or a pack / "journey"
     */
    static synchronized Plan make(String artist, String title, String tags, String lyrics, int theme, String place,
                                  int cover, String style) {
        if (!loaded) initMemory();
        Plan p = new Plan();
        String a = artist == null ? "" : artist.toLowerCase(Locale.US).trim();
        String t = title == null ? "" : title.toLowerCase(Locale.US).trim();
        p.key = a + "|" + t;
        long h = 1125899906842597L;
        for (int i = 0; i < p.key.length(); i++) h = 31 * h + p.key.charAt(i);
        p.seed = h ^ (h >>> 29);
        p.rng = new Random(p.seed);
        Random r = p.rng;
        tags = tags == null ? "" : tags.toLowerCase(Locale.US);
        lyrics = lyrics == null ? "" : lyrics.toLowerCase(Locale.US);
        p.hiphop = theme == 12 || tagHas(tags, "hip hop", "hip-hop", "rap", "trap", "drill", "grime");
        p.afro = theme == 3 || tagHas(tags, "afro", "amapiano", "highlife", "naija");
        p.rnb = !p.hiphop && !p.afro && tagHas(tags, "r&b", "rnb", "soul", "quiet storm", "neo soul");
        p.halfTime = p.hiphop;
        p.mood = p.afro ? 0.65f : p.rnb ? 0.3f : p.hiphop ? 0.55f : 0.5f;
        p.cover = cover;

        // theme packs: the words in title, artist and tags weigh 3, lyrics 1, genre adds a lean
        float[] score = new float[PACKS.length];
        String head = t + " " + a + " " + tags.replace(',', ' ');
        for (int k = 0; k < score.length; k++) {
            score[k] = 3f * Math.min(3, hits(head, k)) + Math.min(4, hits(lyrics, k)) + r.nextFloat() * 1.2f;
        }
        if (p.hiphop) {
            score[8] += 2.2f;
            score[0] += 1.8f;
            score[2] += 1.2f;
        }
        if (p.rnb) {
            score[1] += 2.2f;
            score[6] += 1.2f;
            score[2] += 1.0f;
            score[5] += 0.8f;
        }
        if (p.afro) {
            score[3] += 2.2f;
            score[6] += 1.4f;
            score[8] += 1.0f;
            score[7] += 0.8f;
        }
        // packs used by the last two songs wait their turn
        for (int i = Math.max(0, HIST.size() - 2); i < HIST.size(); i++) {
            for (int sc : HIST.get(i).scenes) if (sc / 8 < score.length) score[sc / 8] -= 0.6f;
        }
        for (int k = 0; k < 3; k++) {
            int best = 0;
            for (int j = 1; j < score.length; j++) if (score[j] > score[best]) best = j;
            if (k == 0) p.strong = score[best] >= 3.5f; // the song's own words call for this pack
            p.packs[k] = best;
            score[best] = -999f;
        }
        for (int k = 0; k < PACKS.length; k++) if (PACKS[k].equals(style)) p.forcedPack = k;
        if (p.forcedPack >= 0) p.packs[0] = p.forcedPack;
        p.forceJourney = "journey".equals(style);

        // time of day and weather: not the last song's
        int lastTod = HIST.isEmpty() ? -1 : HIST.get(HIST.size() - 1).tod;
        int[] todW = p.rnb ? new int[] {1, 3, 2, 1} : p.afro ? new int[] {4, 3, 1, 1} : new int[] {2, 2, 3, 3};
        for (int k = 0; k < 8; k++) {
            p.tod = pickWeighted(r, todW);
            if (p.tod != lastTod) break;
        }
        boolean wet = hits(head + " " + lyrics, 4) > 1 || r.nextFloat() < 0.12f;
        p.wx = wet ? 1 : r.nextFloat() < 0.2f ? 2 : r.nextFloat() < 0.2f ? 3 : 0;
        p.skySeed = r.nextInt();
        p.camDir = r.nextBoolean() ? 1f : -1f;
        p.camSpeed = 0.7f + 0.8f * r.nextFloat();
        p.satBoost = p.afro ? 1.2f : p.rnb ? 0.85f : 1f;
        palette(p, place);
        route(p, a, t, place, r);
        float cityKnown = p.cityId.isEmpty() ? 0f : 1f;
        p.journeyShare = p.fixedRoute ? 0.4f : cityKnown * (p.hiphop ? 0.18f : 0.1f);
        if (p.forceJourney) p.journeyShare = 1f;
        p.rec.hue = p.hueBucket;
        p.rec.tod = p.tod;
        p.rec.landmarks = p.route();
        return p;
    }

    private static int pickWeighted(Random r, int[] w) {
        int sum = 0;
        for (int x : w) sum += x;
        int v = r.nextInt(Math.max(1, sum));
        for (int i = 0; i < w.length; i++) {
            v -= w[i];
            if (v < 0) return i;
        }
        return 0;
    }

    /** Cover color picks the neighbourhood, the song's seed picks within it, the last songs' hues are out. */
    private static void palette(Plan p, String place) {
        Random r = p.rng;
        float coverHue = p.cover != 0 ? hueOf(p.cover) : r.nextFloat() * 360f;
        float best = Float.MAX_VALUE;
        int bucket = 0;
        for (int b = 0; b < 12; b++) {
            if (recentHue(b)) continue;
            float d = hueDist(b * 30f + 15f, coverHue) / 180f + r.nextFloat() * (p.cover != 0 ? 0.45f : 1.5f);
            if (d < best) {
                best = d;
                bucket = b;
            }
        }
        if (best == Float.MAX_VALUE) bucket = (int) (coverHue / 30f) % 12; // every bucket used: cover wins
        float target = bucket * 30f + 5f + r.nextFloat() * 20f;
        // base palette whose lead accent is nearest in hue; the rest is turned around by the difference
        int pal = 0;
        float near = Float.MAX_VALUE;
        for (int k = 0; k < 6; k++) {
            float d = hueDist(hueOf(Pulse.ACC[k][0]), target) + r.nextFloat() * 25f;
            if (d < near) {
                near = d;
                pal = k;
            }
        }
        p.pal = pal;
        p.hueBucket = bucket;
        p.hueShift = target - hueOf(Pulse.ACC[pal][0]);
        p.coverMix = p.cover != 0 ? 0.18f + 0.12f * r.nextFloat() : 0f;
    }

    private static void route(Plan p, String artist, String title, String place, Random r) {
        String[] ov = Journey.override(artist, title);
        String id = null;
        if (ov != null) {
            id = ov[0];
            p.order = ov[1].split(",");
            p.fixedRoute = true;
        } else {
            id = Journey.cityIdOf(place);
            if (id == null && p.forceJourney) id = Journey.CITIES[r.nextInt(Journey.CITIES.length)].id;
        }
        Journey.City c = id == null ? null : Journey.city(id);
        if (c == null) return;
        p.cityId = c.id;
        p.cityName = c.name;
        if (!p.fixedRoute) p.order = Journey.order(c, r, recentRoutes());
    }

    /** Wikidata answered after the song started: a generic city for the same plan. */
    static synchronized void setCity(Plan p, Journey.City c) {
        if (p == null || c == null || !p.cityId.isEmpty()) return;
        p.cityId = c.id;
        p.cityName = c.name;
        p.order = Journey.order(c, p.rng, recentRoutes());
        p.journeyShare = p.hiphop ? 0.18f : 0.1f;
        p.rec.landmarks = p.route();
    }

    /**
     * The scene for one energy section: 0 calm intro or verse, 1 build, 2 drop or chorus, 3 outro or
     * break. Unused scenes of the song's packs that fit the energy win, recently seen ones lose.
     */
    static synchronized int pickScene(Plan p, int section, float mood) {
        Random r = p.rng;
        int want = section == 2 ? 2 : section == 1 ? 1 : 0;
        float m = Math.max(0f, Math.min(1f, 0.6f * mood + 0.4f * p.mood));
        if (m > 0.7f && want < 2) want++;
        if (m < 0.3f && want > 0) want--;
        if (!p.cityId.isEmpty() && (section == 0 || section == 2) && r.nextFloat() < p.journeyShare
                && !p.rec.scenes.contains(JOURNEY)) {
            return use(p, JOURNEY);
        }
        if (p.forceJourney && !p.cityId.isEmpty()) return use(p, JOURNEY);
        int bestId = 0;
        float bestW = -1f;
        for (int rank = 0; rank < 3; rank++) {
            int pack = p.packs[rank];
            if (p.forcedPack >= 0 && pack != p.forcedPack) continue;
            for (int i = 0; i < PACK_N[pack]; i++) {
                int id = pack * 8 + i;
                if (id == 0 || id == 2 || id == 3 || id == 50) continue; // jet, contract, chain and the jumping dolphins are retired
                int gap = Math.abs(SCENE_E[pack][i] - want);
                float w = (gap == 0 ? 3f : gap == 1 ? 1.2f : 0.2f) * (rank == 0 ? (p.strong ? 3.2f : 1.6f) : rank == 1 ? 1.2f : 0.9f);
                if (p.rec.scenes.contains(id)) w *= p.forcedPack >= 0 ? 0.5f : 0.05f;
                if (recentScene(id)) w *= 0.5f;
                if (id == 64 || id == 65 || id == 68) w *= 0.45f; // skyline scenes, kept occasional
                if (banned.contains(id)) w *= 0.001f;
                if (liked.contains(id)) w *= 2.2f;
                w *= 0.6f + r.nextFloat();
                if (w > bestW) {
                    bestW = w;
                    bestId = id;
                }
            }
        }
        return use(p, bestId);
    }

    /** A scene of one given pack (the genre playlist's family) that fits the section's energy. */
    static synchronized int pickIn(Plan p, int pack, int section, float mood) {
        Random r = p.rng;
        int want = section == 2 ? 2 : section == 1 ? 1 : 0;
        float m = Math.max(0f, Math.min(1f, 0.6f * mood + 0.4f * p.mood));
        if (m > 0.7f && want < 2) want++;
        if (m < 0.3f && want > 0) want--;
        int bestId = pack * 8 + 1;
        float bestW = -1f;
        for (int i = 0; i < PACK_N[pack]; i++) {
            int id = pack * 8 + i;
            if (id == 0 || id == 2 || id == 3 || id == 50) continue;
            int gap = Math.abs(SCENE_E[pack][i] - want);
            float w = gap == 0 ? 3f : gap == 1 ? 1.2f : 0.2f;
            if (p.rec.scenes.contains(id)) w *= 0.12f;
            if (recentScene(id)) w *= 0.5f;
            if (id == 64 || id == 65 || id == 68) w *= 0.45f;
            if (banned.contains(id)) w *= 0.001f;
            if (liked.contains(id)) w *= 2.2f;
            w *= 0.6f + r.nextFloat();
            if (w > bestW) {
                bestW = w;
                bestId = id;
            }
        }
        return use(p, bestId);
    }

    private static int use(Plan p, int id) {
        if (!p.rec.scenes.contains(id)) p.rec.scenes.add(id);
        return id;
    }

    static synchronized void notePreset(Plan p, int id) {
        if (p != null && id >= 0 && !p.rec.presets.contains(id)) p.rec.presets.add(id);
    }

    static void log(Plan p) {
        if (p != null) sink.log("song=" + p.key + " " + p.signature());
    }

    // ---- debug harness -------------------------------------------------------------------------

    private static final String[][] SAMPLES = {
        {"Kendrick Lamar", "Dodger Blue", "hip hop,rap,west coast", "", "12", "compton california"},
        {"Drake", "Money In The Grave", "hip hop,rap,trap", "bands cash money", "12", "toronto"},
        {"SZA", "Good Days", "r&b,soul", "love good days", "0", "st. louis"},
        {"Burna Boy", "Last Last", "afrobeats,afro-fusion", "", "3", "lagos"},
        {"Billie Eilish", "Ocean Eyes", "pop,dream pop", "ocean waves", "0", "los angeles"},
        {"The Weeknd", "Blinding Lights", "synth-pop,r&b", "city lights driving", "0", "toronto"},
        {"Kirk Franklin", "Stomp", "gospel", "god praise", "0", "fort worth texas"},
        {"Daft Punk", "Get Lucky", "electronic,disco", "dance tonight", "11", "paris"},
        {"Adele", "Someone Like You", "soul,pop", "cry rain miss", "0", "london"},
        {"Bob Marley", "Three Little Birds", "reggae", "sun rise", "2", "kingston jamaica"}};
    /** Cover hues that deliberately collide (all blue), to prove the avoidance works. */
    private static final int[] COVERS = {0x2060C0, 0x2870C8, 0x1E5AB0, 0x3070D0, 0x2468B8, 0x2C6CC4, 0x1F62BC,
        0x2A66CC, 0x2264C2, 0x2E6AC6};

    /** Ten sample songs through the real planner; prints each signature and a uniqueness report. Returns the number of repeats. */
    static synchronized int harness() {
        Store was = store;
        ArrayList<Rec> keep = new ArrayList<>(HIST);
        initMemory();
        Plan[] plans = new Plan[SAMPLES.length];
        for (int i = 0; i < SAMPLES.length; i++) {
            String[] s = SAMPLES[i];
            Plan p = make(s[0], s[1], s[2], s[3], Integer.parseInt(s[4]), s[5], COVERS[i], "auto");
            for (int sec = 0; sec < 4; sec++) pickScene(p, sec, 0.2f + 0.25f * sec);
            for (int sec = 0; sec < 4; sec++) pickScene(p, sec, 0.5f);
            plans[i] = p;
            sink.log("sim " + (i + 1) + " " + s[0] + " - " + s[1] + " " + p.signature());
            commit(p);
        }
        int repeats = 0;
        for (int i = 0; i < plans.length; i++) {
            if (plans[i].rec.scenes.size() < 4) {
                sink.log("REPEAT song " + (i + 1) + " has only " + plans[i].rec.scenes.size() + " sub-scenes");
                repeats++;
            }
            for (int j = i + 1; j < plans.length; j++) {
                if (plans[i].hueBucket == plans[j].hueBucket) {
                    sink.log("REPEAT hue bucket " + plans[i].hueBucket + " shared by " + (i + 1) + " and " + (j + 1));
                    repeats++;
                }
                if (plans[i].rec.scenes.equals(plans[j].rec.scenes)) {
                    sink.log("REPEAT scene order shared by " + (i + 1) + " and " + (j + 1));
                    repeats++;
                }
                if (!plans[i].route().isEmpty() && plans[i].route().equals(plans[j].route())) {
                    sink.log("REPEAT landmark order shared by " + (i + 1) + " and " + (j + 1));
                    repeats++;
                }
            }
        }
        sink.log("uniqueness report: " + SAMPLES.length + " songs, " + repeats + " repeats");
        HIST.clear();
        HIST.addAll(keep);
        store = was;
        return repeats;
    }
}
