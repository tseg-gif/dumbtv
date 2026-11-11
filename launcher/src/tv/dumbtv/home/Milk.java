package tv.dumbtv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Butterchurn (the WebGL Milkdrop engine, MIT) in a transparent WebView behind the tiles, drawn at
 * 960x540 and scaled up. Everything is bundled in assets, so no network is needed. The audio
 * is not captured by the WebView at all: the TV's own Visualizer waveform is passed in through
 * evaluateJavascript. If frames take longer than 30 ms the offending preset is dropped, and if
 * that keeps happening the listener is told to fall back to the Canvas engine.
 *
 * Presets live in assets/presets (an index with an energy score per preset, one JSON file each)
 * and are chosen here: every song seeds its own shuffle of the whole pool, nothing used in the
 * last 40 picks comes back, and the energy of the pick follows the mood of the music.
 */
final class Milk {
    interface Listener {
        /** Milkdrop cannot keep up (or cannot start): go back to the Canvas visuals. */
        void onFallback(String why);
    }

    private static final String PREFS = "milk";
    private static final String BAD = "bad", RECENT = "recent", LAST_FIRST = "lastFirst";
    private static final int RECENT_MAX = 40;
    private static final double SLOW_MS = 30.0;
    private static final long WARMUP_MS = 6000; // shader compiles stall the first frames of a preset
    private static final int MAX_STRIKES = 6; // six slow presets in a row: fall back to the Canvas engine
    private static final long STRIKE_TTL_MS = 7L * 24 * 3600 * 1000;
    private static final int BLOCK_SONGS = 3, MIN_POOL = 150;

    private final Context ctx;
    private final WebView web;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean loaded, wantRun, running;
    private int reloads;
    private int strikes;
    private long warmupUntil;
    private long frames;

    // preset pool
    private String[] names = new String[0];
    private int[] energy = new int[0], organic = new int[0];
    private boolean[] excluded = new boolean[0];
    private int banned;
    private static final String[] BANNED = {"kaleid", "mandala", "mirror", "symmetr", "fractal", "tunnel", "spiral"};
    /** react_comfort: high (default), normal or vivid. */
    private String comfort = "high";
    private final Set<String> bad = new HashSet<>();
    private final ArrayList<Integer> recent = new ArrayList<>();
    private int lastFirst = -1, curId = -1;
    private boolean presetLoaded;
    /** A scene (scenes.js) instead of a Milkdrop preset: a theme from the song's words. */
    private boolean cosmos;
    private String scene = "", sceneArg = "";
    private String artistName = "", titleName = "", lyrics = "";
    private Origin.Info info;
    /** The shared keyword map: scene name, then the words (title, artist, tags, lyrics) that call for it. */
    private static final String[][] THEMES = {
        {"cosmos", "stars?|starry|starlight|stardust|galax(y|ies)|cosmic|cosmos|space|universe|planets?|moon(light)?|lunar|orbit|astro(naut|world)?|nebula|milky way|comet|supernova|saturn|jupiter|mars|venus|neptune|uranus|pluto|eclipse|constellation|interstellar|stellar|solar"},
        {"rain", "rain(y|ing|drops?)?|storm|thunder|drizzle|umbrella|flood|downpour|cloudy"},
        {"snow", "snow(y|ing|fall|flakes?)?|winter|frozen|frost|blizzard|christmas|icy|ice"},
        {"fire", "fire|flames?|burn(s|ing|ed)?|blaze|inferno|embers?|smoke|wildfire"},
        {"ocean", "ocean|sea|waves?|beach|shore|tides?|sail(ing|or)?|surf|island|lagoon|mermaid|harbou?r|water"},
        {"desert", "desert|sand|sahara|dunes?|canyon|oasis|mirage|cactus|dust"},
        {"forest", "forest|woods?|trees?|jungle|leaf|leaves|garden|pines?|wilderness|firefl(y|ies)"},
        {"city", "city|cities|streets?|downtown|skyline|boulevard|avenue|highway|taxi|subway|urban|concrete"},
        {"night", "nights?|midnight|tonight|nocturne|after dark|evening|dark"}};
    private static final java.util.regex.Pattern LETTER = java.util.regex.Pattern.compile("^([a-z])('s|[.])(?![a-z])");
    private static final java.util.regex.Pattern[] THEME_RE = new java.util.regex.Pattern[THEMES.length];
    static {
        for (int i = 0; i < THEMES.length; i++) {
            THEME_RE[i] = java.util.regex.Pattern.compile("(?<![a-z])(" + THEMES[i][1] + ")(?![a-z])");
        }
    }
    // the song now playing
    private String songKey = "";
    private long songSeed = 1;
    private int[] order = new int[0];
    private int cursor;
    private boolean firstPending = true;
    // identity and mood
    private String themeId = "";
    private float mood = 0.5f, tagBias;
    private String palette = "null", artistKey = "";
    private final ArrayList<Integer> history = new ArrayList<>();
    /** Too slow for the song now playing; forgotten at the next song. */
    private final Set<Integer> songSkip = new HashSet<>();
    /** Presets the last songs used (react variety): picked last. */
    private final Set<Integer> avoid = new HashSet<>();
    /** How much each preset follows the music (NaN: not measured yet), and the score below which it is not used. */
    private float[] resp = new float[0];
    private long lastPickAt;
    private double lastMs;

    Milk(Context c, Listener l) {
        ctx = c;
        listener = l;
        loadPool();
        web = new WebView(c);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                Log.w("dumbtv", "milkdrop js " + m.messageLevel() + ": " + m.message() + " @" + m.lineNumber());
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                Log.i("dumbtv", "milkdrop page finished " + url);
                // the first launch after an install sometimes reports a finished page that is still
                // empty: check that the script is there, and reload if it is not
                v.evaluateJavascript("typeof window.start", new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String r) {
                        if (r != null && r.contains("function")) {
                            loaded = true;
                            if (debug) js("setDebug(true)", true);
                            if (wantRun) start();
                        } else if (++reloads <= 5) {
                            Log.w("dumbtv", "milkdrop page empty (" + r + "), reload " + reloads);
                            ui.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    web.loadUrl("file:///android_asset/milk.html");
                                }
                            }, 800);
                        } else {
                            listener.onFallback("error:page did not load");
                        }
                    }
                });
            }
        });
        web.loadUrl("file:///android_asset/milk.html");
        Log.i("dumbtv", "milkdrop loading page");
    }

    View view() {
        return web;
    }

    // ---- the pool ---------------------------------------------------------------------------

    private void loadPool() {
        try {
            JSONArray a = new JSONArray(readAsset("pre_index.json"));
            names = new String[a.length()];
            energy = new int[a.length()];
            organic = new int[a.length()];
            excluded = new boolean[a.length()];
            resp = new float[a.length()];
            Arrays.fill(resp, Float.NaN);
            for (int i = 0; i < names.length; i++) {
                JSONObject o = a.getJSONObject(i);
                names[i] = o.getString("n");
                energy[i] = o.optInt("e", 50);
                organic[i] = o.optInt("o", 50);
                // kaleidoscope looks: never shown, whatever the comfort level
                String low = names[i].toLowerCase(Locale.US);
                for (String w : BANNED) {
                    if (low.contains(w)) {
                        excluded[i] = true;
                        banned++;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.w("dumbtv", "milkdrop preset index missing", e);
        }
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        bad.addAll(p.getStringSet(BAD, new HashSet<String>()));
        // earlier builds blocked a preset on its first slow frame; those blocks start over, and the
        // pool is never allowed below MIN_POOL
        if (p.getInt("rules", 0) < 2 || names.length - bad.size() < MIN_POOL) {
            bad.clear();
            p.edit().putStringSet(BAD, new HashSet<String>()).putInt("rules", 2).apply();
        }
        for (String id : p.getString(RECENT, "").split(",")) {
            try {
                recent.add(Integer.valueOf(id));
            } catch (NumberFormatException ignored) {
                // empty list
            }
        }
        lastFirst = p.getInt(LAST_FIRST, -1);
        for (String e : p.getString("resp2", "").split(",")) { // "resp" held the old, noisier scores
            String[] kv = e.split(":");
            try {
                int id = Integer.parseInt(kv[0]);
                if (id < resp.length) resp[id] = Float.parseFloat(kv[1]);
            } catch (RuntimeException ignored) {
                // empty or damaged entry
            }
        }
        shuffle(System.nanoTime());
    }

    private String readAsset(String path) throws Exception {
        InputStream in = ctx.getAssets().open(path);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private void shuffle(long seed) {
        songSeed = seed;
        order = new int[names.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Random r = new Random(seed);
        for (int i = order.length - 1; i > 0; i--) {
            int j = r.nextInt(i + 1), t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        cursor = 0;
    }

    /** FNV-1a: the same artist and title always give the same shuffle. */
    private static long fnv(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** A new song gets its own shuffle and a fresh first picture. */
    synchronized void setSong(String artist, String title) {
        String key = (artist == null ? "" : artist.trim().toLowerCase(Locale.US)) + "|"
                + (title == null ? "" : title.trim().toLowerCase(Locale.US));
        if (key.equals(songKey)) return;
        songKey = key;
        songSkip.clear();
        shuffle(key.length() <= 1 ? System.nanoTime() : fnv(key));
        firstPending = true;
        artistName = artist == null ? "" : artist;
        titleName = title == null ? "" : title;
        lyrics = "";
        info = null;
        updateScene();
        // the last song's place and cover colors must not leak into this one: neutral until resolved
        artistKey = artist == null ? "" : Artists.key(Origin.primary(artist));
        themeId = "";
        tagBias = 0f;
        palette = "null";
        pushTheme();
        if (running && (presetLoaded || !cosmos)) loadNext(1.5, "song");
    }

    /** Where the artist is from (null: unknown): theme for the grade and silhouettes, plus mood words. */
    synchronized void setIdentity(Origin.Info info) {
        themeId = info == null ? "" : info.id;
        float bias = 0f;
        if (info != null && !info.spec.isEmpty()) {
            js("setSpec('" + info.id + "','" + info.spec + "')", true);
            bias = info.bias;
        }
        if (info != null) {
            for (String t : info.tags) {
                if (hasAny(t, "sad", "chill", "ambient", "downtempo", "slow", "melanchol", "sleep", "acoustic",
                        "lo-fi", "lofi", "relax", "dark", "ballad", "soft", "mellow")) {
                    bias -= 0.12f;
                } else if (hasAny(t, "aggressive", "hard", "metal", "punk", "dance", "party", "edm", "techno",
                        "drum and bass", "hardcore", "trap", "drill", "upbeat", "energetic", "house")) {
                    bias += 0.12f;
                }
            }
        }
        tagBias = Math.max(-0.25f, Math.min(0.25f, bias));
        this.info = info;
        updateScene();
        pushTheme();
    }

    private static boolean hasAny(String tag, String... words) {
        for (String w : words) if (tag.contains(w)) return true;
        return false;
    }

    /** 0 slow and dark .. 1 fast and bright, from tempo, brightness and energy; re-read on each pick. */
    synchronized void setMood(float m) {
        float before = mood;
        mood = Math.max(0f, Math.min(1f, m));
        if (Math.abs(mood - before) > 0.05f) js("setMood(" + Math.round(Math.max(0f, Math.min(1f, mood + tagBias)) * 100) / 100f + ")", true);
    }

    /** Lyrics, when something supplies them: the same keyword map reads them. */
    synchronized void setLyrics(String text) {
        lyrics = text == null ? "" : text.toLowerCase(Locale.US);
        updateScene();
    }

    private static int hits(java.util.regex.Pattern re, String text) {
        int n = 0;
        java.util.regex.Matcher m = re.matcher(text);
        while (m.find()) n++;
        return n;
    }

    /**
     * Picks the scene for the song: the theme its words call for (title, artist and tags count three, a
     * lyric once), else the normal Milkdrop engine.
     */
    private void updateScene() {
        String name = "", arg = "";
        // a title that starts with a spelled-out letter ("B's on the Table") gets that letter, giant
        java.util.regex.Matcher lm = LETTER.matcher(titleName.toLowerCase(Locale.US).replace("\u2019", "'"));
        if (lm.find()) {
            name = "letter";
            arg = lm.group(1).toUpperCase(Locale.US);
        }
        if (name.isEmpty()) {
            StringBuilder words = new StringBuilder(artistName.toLowerCase(Locale.US)).append(' ').append(titleName.toLowerCase(Locale.US));
            if (info != null) for (String t : info.tags) words.append(' ').append(t);
            String w = words.toString();
            int best = 0;
            for (int i = 0; i < THEMES.length; i++) {
                int score = 3 * hits(THEME_RE[i], w) + hits(THEME_RE[i], lyrics);
                if (score > best && (score >= 3 || hits(THEME_RE[i], lyrics) >= 4)) {
                    best = score;
                    name = THEMES[i][0];
                }
            }
        }
        if (name.equals(scene) && arg.equals(sceneArg)) return;
        boolean was = cosmos;
        scene = name;
        sceneArg = arg;
        cosmos = !name.isEmpty();
        Log.i("dumbtv", "milkdrop scene " + (cosmos ? name : "none"));
        js("setScene('" + name + "'," + (arg.isEmpty() ? "''" : "'" + arg.replace("'", "") + "'") + ")", true);
        if (was && !cosmos && running) loadNext(1.5, "scene");
    }

    private void pushTheme() {
        float m = Math.max(0f, Math.min(1f, mood + tagBias));
        js("setTheme('" + themeId + "'," + (songSeed & 0x7fffffffL) + "," + Math.round(m * 100) / 100f + ")", true);
        js("setPalette(" + palette + ")", true);
    }

    /** Dominant cover colors (RGB ints, already softened) or null; blended into the grade. */
    synchronized void setPalette(int[] colors) {
        StringBuilder sb = new StringBuilder("[");
        if (colors != null) {
            for (int i = 0; i < colors.length; i++) {
                sb.append(i == 0 ? "" : ",").append('[').append(colors[i] >> 16 & 255).append(',')
                        .append(colors[i] >> 8 & 255).append(',').append(colors[i] & 255).append(']');
            }
        }
        palette = colors == null || colors.length == 0 ? "null" : sb.append(']').toString();
        js("setPalette(" + palette + ")", true);
    }

    /** Theme nudges the energy window: calm places pick calmer pictures. */
    private int themeBias() {
        switch (themeId) {
            case "ethiopia": case "jamaica": case "mist": case "bluenight": case "nashville": case "cairo":
                return -12;
            case "grid": case "nightcity": case "la": case "nyc": case "tokyo": case "seoul": case "miami":
                return 10;
            default:
                return 0;
        }
    }

    /**
     * Presets that follow the music less are downweighted, never excluded: a score of 0 still plays
     * about a third as often as one of 0.4 or more. Unmeasured presets play as often as the best.
     * The draw is a hash of the song and the preset, so a song keeps its order.
     */
    private boolean acceptByResp(int id) {
        float r = resp[id];
        if (Float.isNaN(r)) return true;
        float p = 0.3f + 0.7f * Math.min(1f, r / 0.4f);
        long h = songSeed * 0x9E3779B97F4A7C15L + id * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return ((h >>> 11) & 0xFFFFFF) / (float) 0x1000000 < p;
    }

    /** One score per preset: the correlation of loudness with frame change over 10+ s of play, 0..1. */
    private void onCal(String id, String score, String samples) {
        try {
            int i = Integer.parseInt(id);
            int n = Integer.parseInt(samples);
            if (n < 100) return; // too few samples to mean anything
            resp[i] = Math.max(0f, Math.min(1f, Float.parseFloat(score)));
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < resp.length; k++) if (!Float.isNaN(resp[k])) sb.append(k).append(':').append(resp[k]).append(',');
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("resp2", sb.toString()).apply();
            if (debug) Log.i("dumbtvresp", "id=" + i + " score=" + resp[i] + " samples=" + n + " " + names[i]);
        } catch (RuntimeException ignored) {
            // not a number: ignore
        }
    }

    /** The next preset of this song: unused in the last 40, not blocked, close to the mood's energy. */
    private int pick() {
        int n = names.length;
        if (n == 0) return -1;
        if (firstPending) {
            int fav = liked();
            if (fav >= 0) return fav;
        }
        int target = Math.round(Math.max(0f, Math.min(1f, mood + tagBias)) * 99) + themeBias();
        // flowing, wide-field pictures first; geometric ones only when nothing else fits
        for (int minO : new int[] {50, 25, 0}) {
            for (int width = 30; width <= 130; width += 25) {
                for (int step = 0; step < n; step++) {
                    int id = order[(cursor + step) % n];
                    if (excluded[id] || recent.contains(id) || avoid.contains(id) || bad.contains(names[id]) || songSkip.contains(id) || !acceptByResp(id)) continue;
                    if (organic[id] < minO || Math.abs(energy[id] - target) > width) continue;
                    if (firstPending && id == lastFirst) continue;
                    cursor = (cursor + step + 1) % n;
                    return id;
                }
            }
        }
        // everything is recent or blocked: take the oldest recent one
        for (int step = 0; step < n; step++) {
            int id = order[(cursor + step) % n];
            if (!excluded[id] && !bad.contains(names[id]) && !songSkip.contains(id) && !(firstPending && id == lastFirst)) {
                cursor = (cursor + step + 1) % n;
                return id;
            }
        }
        return -1;
    }

    /** Picks, remembers and sends one preset to the page (held like the audio by react_sync_ms). */
    private synchronized void loadNext(double blend, String why) {
        if (cosmos) return;
        for (int attempt = 0; attempt < 3; attempt++) {
            int id = pick();
            if (id < 0 || present(id, blend, why)) return;
        }
    }

    /** Comfort floors for how long one picture takes to melt into the next. */
    private double comfortBlend(double blend, String why) {
        if ("vivid".equals(comfort) || "first".equals(why)) return blend;
        boolean high = "high".equals(comfort);
        if (why.startsWith("remote")) return Math.max(blend, high ? 1.5 : 0.6);
        if ("slow".equals(why) || "bad".equals(why) || "mirror".equals(why)) return Math.max(blend, high ? 2.0 : 1.0);
        return Math.max(blend, high ? 4.0 : 2.5);
    }

    /** react_comfort=high|normal|vivid: brightness, flash and motion limits live in the page. */
    synchronized void setComfort(String level) {
        String l = "normal".equals(level) || "vivid".equals(level) ? level : "high";
        if (l.equals(comfort)) return;
        comfort = l;
        js("setComfort('" + comfort + "')", true);
    }

    private boolean present(int id, double blend, String why) {
        blend = comfortBlend(blend, why);
        String json;
        try {
            json = readAsset("pre_" + id + ".json");
        } catch (Exception e) {
            Log.w("dumbtv", "milkdrop preset file " + id, e);
            markBad(names[id]);
            return false;
        }
        curId = id;
        lastPickAt = SystemClock.uptimeMillis();
        presetLoaded = true;
        recent.add(id);
        while (recent.size() > RECENT_MAX) recent.remove(0);
        history.add(id);
        while (history.size() > RECENT_MAX) history.remove(0);
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        StringBuilder sb = new StringBuilder();
        for (int r : recent) sb.append(r).append(',');
        ed.putString(RECENT, sb.toString());
        if (firstPending) {
            lastFirst = id;
            ed.putInt(LAST_FIRST, id);
            firstPending = false;
        }
        ed.apply();
        if (debug) {
            Log.i("dumbtvsync", "preset pick id=" + id + " energy=" + energy[id] + " resp=" + (Float.isNaN(resp[id]) ? "-" : String.valueOf(resp[id])) + " mood="
                    + Math.round(mood * 100) / 100f + " theme=" + (themeId.isEmpty() ? "-" : themeId)
                    + " reason=" + why + " blend=" + blend + "s hold=" + syncMs + "ms " + names[id]);
        }
        final String code = "loadB64('" + Base64.encodeToString(json.getBytes(java.nio.charset.Charset.forName("UTF-8")),
                Base64.NO_WRAP) + "'," + blend + ",'" + id + "')";
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (wantRun) web.evaluateJavascript(code, null);
            }
        }, syncMs);
        return true;
    }

    /** Remote: +1 next preset, -1 the one before. */
    synchronized void skip(int dir) {
        if (!running) return;
        if (dir > 0) {
            loadNext(0.6, "remote");
        } else if (history.size() >= 2) {
            history.remove(history.size() - 1);
            present(history.remove(history.size() - 1), 0.6, "remote-back");
        }
    }

    /** Remote up: remember this picture for this artist; returns its name (null: nothing showing). */
    synchronized void avoidPresets(int[] ids) {
        avoid.clear();
        for (int id : ids) avoid.add(id);
    }

    synchronized int currentId() {
        return curId;
    }

    /** Down on the remote: this preset is never shown again, and the next one comes in. */
    synchronized String banCurrent() {
        if (curId < 0) return null;
        String n = names[curId];
        markBad(n);
        loadNext(0.6, "bad");
        return n;
    }

    synchronized String likeCurrent() {
        if (curId < 0 || artistKey.isEmpty()) return curId < 0 ? null : names[curId];
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String list = p.getString("like:" + artistKey, "");
        if (!(',' + list).contains("," + curId + ",")) list = list + curId + ",";
        p.edit().putString("like:" + artistKey, list).apply();
        return names[curId];
    }

    private int liked() {
        if (artistKey.isEmpty()) return -1;
        for (String s : ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("like:" + artistKey, "").split(",")) {
            try {
                int id = Integer.parseInt(s);
                if (id < names.length && !excluded[id] && !recent.contains(id) && !bad.contains(names[id]) && id != lastFirst) return id;
            } catch (NumberFormatException ignored) {
                // empty
            }
        }
        return -1;
    }

    // ---- running ----------------------------------------------------------------------------

    /** react_debug=on: one "dumbtvsync" line a second from here and from the page; off by default. */
    void setDebug(boolean on) {
        if (on == debug) return;
        debug = on;
        js("setDebug(" + on + ")", true);
        ui.removeCallbacks(dbgTick);
        if (on) {
            dbgAt = SystemClock.uptimeMillis();
            ui.postDelayed(dbgTick, 1000);
        }
    }

    /**
     * react_sync_ms, -300..300: positive holds the picture back so it lines up with a soundbar or
     * Bluetooth speaker that plays later than the TV's audio mix the Visualizer taps. A negative
     * value cannot show a beat before it was captured, so it only removes the hold (0).
     */
    void setSyncMs(int ms) {
        syncMs = Math.max(0, Math.min(300, ms));
    }

    private final Runnable dbgTick = new Runnable() {
        @Override
        public void run() {
            if (!debug) return;
            long now = SystemClock.uptimeMillis();
            float secs = Math.max(0.001f, (now - dbgAt) / 1000f);
            dbgAt = now;
            Log.i("dumbtvsync", String.format(java.util.Locale.US,
                    "bridge waves_in=%.0f/s evaluateJs=%.0f/s beats_sent=%.0f/s hold=%dms running=%s",
                    dbgIn.getAndSet(0) / secs, dbgOut.getAndSet(0) / secs, dbgBeats.getAndSet(0) / secs,
                    syncMs, running));
            ui.postDelayed(this, 1000);
        }
    };

    private boolean debug;
    private long dbgAt;
    private volatile int syncMs;
    private final AtomicInteger dbgIn = new AtomicInteger(), dbgOut = new AtomicInteger(),
            dbgBeats = new AtomicInteger();

    /** Runs or pauses the renderer; a paused WebView costs the GPU nothing. */
    void setRunning(boolean on) {
        if (on == wantRun) return;
        wantRun = on;
        if (on) {
            web.onResume();
            if (loaded) start();
        } else {
            running = false;
            js("stop()", false);
            web.onPause();
        }
    }

    private void start() {
        if (names.length == 0) {
            listener.onFallback("error:no presets");
            return;
        }
        warmupUntil = SystemClock.uptimeMillis() + WARMUP_MS;
        strikes = 0;
        frames = 0;
        running = true;
        js("start()", false);
        js("setScene('" + scene + "'," + (sceneArg.isEmpty() ? "''" : "'" + sceneArg.replace("'", "") + "'") + ")", true);
        js("setComfort('" + comfort + "')", true);
        pushTheme();
        if (!presetLoaded) loadNext(0, "first");
    }

    /** The waveform from the TV's Visualizer (unsigned bytes, 128 is silence); any thread. */
    void feed(byte[] wave) {
        if (!running) return;
        final String b64 = Base64.encodeToString(wave, 0, Math.min(wave.length, 1024), Base64.NO_WRAP);
        final long captured = System.currentTimeMillis() + syncMs;
        if (debug) dbgIn.incrementAndGet();
        // the page gets the moment this audio is due on screen and reports how late it arrived
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                if (debug) dbgOut.incrementAndGet();
                web.evaluateJavascript("feed('" + b64 + "'," + captured + ")", null);
            }
        }, syncMs);
    }

    /** A kick (0) or snare (1) just heard, strength 0.5..1; held like the waveform; any thread. */
    void beat(final int kind, float strength) {
        if (!running) return;
        final String code = "beat(" + kind + "," + Math.round(strength * 100) / 100f + ")";
        if (debug) dbgBeats.incrementAndGet();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (running) web.evaluateJavascript(code, null);
            }
        }, syncMs);
    }

    /**
     * The beat clock: tempo, how sure it is, the beat period and the epoch time of a beat. The hold
     * is added to the anchor, so the predicted beats arrive as late as the audio does; any thread.
     */
    void clock(float bpm, float conf, double periodMs, long anchorEpoch, float strength) {
        if (!running || periodMs <= 0) return;
        final String code = "clock(" + Math.round(bpm * 10) / 10f + "," + Math.round(conf * 100) / 100f + ","
                + Math.round(periodMs) + "," + (anchorEpoch + syncMs) + "," + Math.round(strength * 100) / 100f + ")";
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (running) web.evaluateJavascript(code, null);
            }
        });
    }

    /** A drop, a kick after a long stretch, or the timer: blend to the next preset; any thread. */
    void next(double blendSeconds, String why) {
        loadNext(blendSeconds, why);
    }

    void destroy() {
        running = false;
        wantRun = false;
        ui.removeCallbacks(dbgTick);
        web.destroy();
    }

    private void js(final String code, boolean post) {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(code, null);
            }
        };
        if (post || Looper.myLooper() != Looper.getMainLooper()) ui.post(r);
        else r.run();
    }

    private synchronized void markBad(String name) {
        bad.add(name);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(BAD, new HashSet<>(bad)).apply();
    }

    private void onStatus(String s) {
        Log.i("dumbtv", "milkdrop " + s);
        if (s.startsWith("error:")) {
            running = false;
            listener.onFallback(s);
        } else if (s.startsWith("mirror:")) {
            // the output is mirror-symmetric (a kaleidoscope): skip it for the rest of this song
            if (curId >= 0) songSkip.add(curId);
            loadNext(0, "mirror");
        } else if (s.startsWith("badpreset:")) {
            try {
                markBad(names[Integer.parseInt(s.substring(10))]);
            } catch (RuntimeException ignored) {
                // not an id we know
            }
            loadNext(0, "bad");
        }
    }

    private void onFrameMs(double ms) {
        if (!running) return;
        lastMs = ms;
        if (++frames % 10 == 1) Log.i("dumbtv", "milkdrop frame " + Math.round(ms * 10) / 10.0 + " ms");
        if (SystemClock.uptimeMillis() < warmupUntil) return;
        if (ms <= SLOW_MS) {
            strikes = 0;
            return;
        }
        if (++strikes >= MAX_STRIKES) {
            running = false;
            listener.onFallback("frames took " + Math.round(ms) + " ms");
            return;
        }
        // one heavy preset should not end the mode: skip it for this song and try the next
        warmupUntil = SystemClock.uptimeMillis() + WARMUP_MS;
        if (curId >= 0) slowStrike(curId);
        loadNext(0, "slow");
    }

    /**
     * A preset too slow even at low resolution: skipped for the rest of this song, and one strike
     * on its record. Strikes older than 7 days are forgotten; three strikes from three different
     * songs block it for good, but never so far that fewer than 150 presets are left.
     */
    private synchronized void slowStrike(int id) {
        songSkip.add(id);
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis(), cutoff = now - STRIKE_TTL_MS;
        int song = songKey.hashCode();
        Set<Integer> songs = new HashSet<>();
        StringBuilder keep = new StringBuilder();
        for (String s : p.getString("strike:" + id, "").split(",")) {
            String[] hv = s.split(":");
            if (hv.length != 2) continue;
            try {
                if (Long.parseLong(hv[1]) < cutoff) continue;
                songs.add(Integer.valueOf(hv[0]));
                keep.append(s).append(',');
            } catch (NumberFormatException ignored) {
                // damaged entry: forgotten
            }
        }
        if (songs.add(song)) keep.append(song).append(':').append(now).append(',');
        p.edit().putString("strike:" + id, keep.toString()).apply();
        boolean block = songs.size() >= BLOCK_SONGS && names.length - banned - bad.size() - 1 >= MIN_POOL;
        Log.i("dumbtv", "milkdrop slowpreset:" + names[id] + " songs=" + songs.size() + (block ? " BLOCKED" : ""));
        if (block) markBad(names[id]);
    }

    /** Called from the page; runs on a WebView thread, so everything hops to the main thread. */
    public final class Bridge {
        @JavascriptInterface
        public void status(final String s) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    onStatus(s);
                }
            });
        }

        @JavascriptInterface
        public void cal(final String id, final String score, final String samples) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    onCal(id, score, samples);
                }
            });
        }

        @JavascriptInterface
        public void comfort(String line) {
            Log.i("dumbtvcomfort", line);
        }

        @JavascriptInterface
        public void sync(String line) {
            Log.i("dumbtvsync", "web " + line);
        }

        @JavascriptInterface
        public void frameMs(final double ms) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    onFrameMs(ms);
                }
            });
        }
    }
}
