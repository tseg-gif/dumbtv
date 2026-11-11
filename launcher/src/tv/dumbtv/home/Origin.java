package tv.dumbtv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Where the music comes from, so the visuals can look like it. The artist is looked up once on
 * MusicBrainz (country and genre tags) on a background thread, at most one request a second, and
 * the answer is kept in SharedPreferences so the same artist is instant and works offline.
 * Words in the song title and tags (rain, fire, ocean, love, night, sun, city) add accent layers.
 * A failed lookup just means the normal composer is used.
 */
final class Origin {
    static final int KW_RAIN = 1, KW_FIRE = 2, KW_OCEAN = 4, KW_LOVE = 8, KW_NIGHT = 16, KW_SUN = 32,
            KW_CITY = 64;

    static final int ETHIOPIA = 1, CARIBBEAN = 2, AFRO = 3, LATIN = 4, JAPAN = 5, KOREA = 6, INDIA = 7,
            MIDEAST = 8, COUNTRY = 9, JAZZ = 10, ELECTRONIC = 11, HIPHOP = 12, ROCK = 13, CLASSICAL = 14;

    static final class Info {
        final int theme, kw;
        /** True when the artist was found in the cache or on MusicBrainz, city included. */
        final boolean resolved;
        /** Milkdrop identity (la, ethiopia, tokyo, grid ...), "" when nothing matched. */
        final String id;
        /** MusicBrainz genre tags, lower case, most voted first. */
        final String[] tags;

        /** Theme JSON for a curated artist ("" otherwise) and the mood nudge that goes with it. */
        final String spec;
        final float bias;

        Info(int theme, int kw, boolean resolved, String id, String[] tags) {
            this(theme, kw, resolved, id, tags, "", 0f);
        }

        Info(int theme, int kw, boolean resolved, String id, String[] tags, String spec, float bias) {
            this.theme = theme;
            this.kw = kw;
            this.resolved = resolved;
            this.id = id;
            this.tags = tags;
            this.spec = spec;
            this.bias = bias;
        }
    }

    interface Callback {
        /** Runs on the main thread. */
        void done(Info info);
    }

    private static final String UA = "dumbtv-home/1.6 (https://github.com/humblemuzzu/dumbtv)";
    private static final String PREFS = "origin";
    private static final long GAP_MS = 1100, RETRY_MS = 10 * 60 * 1000;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "dumbtv-origin");
            t.setDaemon(true);
            return t;
        }
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Long> failedAt = new HashMap<>();
    private static long lastRequest;

    private Origin() {
    }

    // ---- public ------------------------------------------------------------------------------

    /** What is already known without any network: the cache, plus the words of the title. */
    static Info cached(Context c, String artist, String title) {
        String a = primary(artist);
        Artists.Entry e = a.isEmpty() ? null : Artists.find(a);
        if (e != null) return new Info(0, keywords(title), true, e.id, new String[0], e.spec(), e.bias);
        String v = a.isEmpty() ? null : prefs(c).getString(norm(a), null);
        return build(v, title);
    }

    /** The artist's area and begin-area from the cache ("compton california"), or "". */
    static String place(Context c, String artist) {
        String a = primary(artist);
        String v = a.isEmpty() ? null : prefs(c).getString(norm(a), null);
        if (v == null) return "";
        String[] p = v.split("\\|", -1);
        return p.length >= 4 ? (p[2] + " " + p[3]).toLowerCase(Locale.US).trim() : "";
    }

    /** Looks the artist up in the background and calls back on the main thread when found. */
    static void fetch(Context c, final String artist, final String title, final Callback cb) {
        final Context app = c.getApplicationContext();
        final String a = primary(artist);
        if (a.isEmpty()) return;
        final String key = norm(a);
        synchronized (failedAt) {
            Long t = failedAt.get(key);
            if (t != null && SystemClock.elapsedRealtime() - t < RETRY_MS) return;
        }
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                String v = null;
                try {
                    v = query(a);
                } catch (Exception e) {
                    Log.w("dumbtv", "origin lookup failed: " + e);
                }
                if (v == null) {
                    synchronized (failedAt) {
                        failedAt.put(key, SystemClock.elapsedRealtime());
                    }
                    return;
                }
                prefs(app).edit().putString(key, v).apply();
                final Info info = build(v, title);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(info);
                    }
                });
            }
        });
    }

    // ---- lookup --------------------------------------------------------------------------------

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** "Daft Punk feat. X" and "A, B" and "A & B" all look up the first artist. */
    static String primary(String artist) {
        if (artist == null) return "";
        String a = artist.trim();
        // names that contain the separators themselves
        for (String whole : new String[] {"tyler, the creator", "earth, wind & fire", "simon & garfunkel",
                "hall & oates", "florence + the machine"}) {
            if (a.toLowerCase(Locale.US).startsWith(whole)) return a.substring(0, whole.length());
        }
        String[] parts = a.split("(?i)\\s*(,|;|&|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bwith\\b|\\sx\\s|\\+)\\s*");
        a = parts.length > 0 ? parts[0].trim() : a;
        if (a.toLowerCase(Locale.US).endsWith(" - topic")) a = a.substring(0, a.length() - 8);
        return a;
    }

    private static String norm(String a) {
        return a.toLowerCase(Locale.US).trim();
    }

    /** One request, rate limited. Returns "COUNTRY|tag;tag;tag" ("|" when not found), null on error. */
    private static String query(String name) throws Exception {
        synchronized (Origin.class) {
            long wait = lastRequest + GAP_MS - SystemClock.elapsedRealtime();
            if (wait > 0) Thread.sleep(wait);
            lastRequest = SystemClock.elapsedRealtime();
        }
        String q = URLEncoder.encode("artist:\"" + name.replace("\"", " ") + "\"", "UTF-8");
        URL url = new URL("https://musicbrainz.org/ws/2/artist/?query=" + q + "&limit=3&fmt=json");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        try {
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            con.setRequestProperty("User-Agent", UA);
            con.setRequestProperty("Accept", "application/json");
            if (con.getResponseCode() != 200) throw new Exception("http " + con.getResponseCode());
            InputStream in = con.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 256 * 1024) out.write(buf, 0, n);
            in.close();
            JSONArray list = new JSONObject(out.toString("UTF-8")).optJSONArray("artists");
            JSONObject best = null;
            if (list != null) {
                for (int i = 0; i < list.length() && best == null; i++) {
                    JSONObject o = list.getJSONObject(i);
                    if (name.equalsIgnoreCase(o.optString("name"))) best = o;
                }
                if (best == null && list.length() > 0 && list.getJSONObject(0).optInt("score") >= 90) {
                    best = list.getJSONObject(0);
                }
            }
            if (best == null) return "|||";
            String country = best.optString("country", "");
            String places = placeName(best.optJSONObject("area")) + "|" + placeName(best.optJSONObject("begin-area"));
            if (country.isEmpty()) {
                JSONObject area = best.optJSONObject("area");
                JSONArray codes = area == null ? null : area.optJSONArray("iso-3166-1-codes");
                if (codes != null && codes.length() > 0) country = codes.optString(0, "");
            }
            JSONArray tags = best.optJSONArray("tags");
            StringBuilder sb = new StringBuilder(country).append('|');
            boolean[] used = new boolean[tags == null ? 0 : tags.length()];
            for (int k = 0; k < 8 && tags != null; k++) {
                int pick = -1, top = -1;
                for (int i = 0; i < used.length; i++) {
                    int cnt = tags.getJSONObject(i).optInt("count");
                    if (!used[i] && cnt > top) {
                        top = cnt;
                        pick = i;
                    }
                }
                if (pick < 0) break;
                used[pick] = true;
                if (k > 0) sb.append(';');
                sb.append(tags.getJSONObject(pick).optString("name").replace(';', ' ').replace('|', ' '));
            }
            return sb.append('|').append(places).toString();
        } finally {
            con.disconnect();
        }
    }

    private static String placeName(JSONObject area) {
        return area == null ? "" : area.optString("name", "").replace('|', ' ').replace(';', ' ');
    }

    /** Cache value: "CC|tag;tag|area|begin-area". Older two-part values still work but ask again. */
    private static Info build(String v, String title) {
        String country = "", place = "";
        String[] tags = new String[0];
        boolean full = false;
        if (v != null) {
            String[] p = v.split("\\|", -1);
            country = p[0];
            if (p.length > 1 && !p[1].isEmpty()) tags = p[1].toLowerCase(Locale.US).split(";");
            if (p.length >= 4) {
                full = true;
                place = (p[2] + " " + p[3]).toLowerCase(Locale.US);
            }
        }
        int kw = keywords(title);
        for (String t : tags) kw |= keywords(t);
        int theme = v == null ? 0 : themeOf(country, tags);
        return new Info(theme, kw, full, v == null ? "" : identity(country, place, theme), tags);
    }

    // City (or state) words first, then the country, then the genre.
    private static final String[][] CITIES = {
        {"la", "los angeles", "compton", "inglewood", "long beach", "watts", "hollywood", "pasadena",
            "santa monica", "california"},
        {"nyc", "new york", "brooklyn", "bronx", "harlem", "queens", "manhattan", "staten island"},
        {"miami", "miami", "florida"},
        {"nashville", "nashville", "tennessee", "kentucky", "texas", "oklahoma"},
        {"toronto", "toronto", "ontario"}, {"atlanta", "atlanta"}, {"chicago", "chicago", "illinois"},
        {"london", "london"}, {"paris", "paris"}, {"berlin", "berlin"},
        {"tokyo", "tokyo", "osaka", "kyoto"}, {"seoul", "seoul"},
        {"lagos", "lagos", "abuja", "ibadan", "accra"},
        {"jamaica", "kingston", "jamaica"}, {"ethiopia", "addis", "ethiopia"},
        {"rio", "rio de janeiro", "sao paulo", "são paulo"},
        {"mumbai", "mumbai", "delhi", "bombay", "punjab"},
        {"cairo", "cairo", "istanbul", "dubai", "beirut"},
        {"nairobi", "nairobi", "johannesburg", "kampala"}};

    private static String identity(String country, String place, int theme) {
        for (String[] c : CITIES) {
            for (int i = 1; i < c.length; i++) if (place.contains(c[i])) return c[0];
        }
        switch (country) {
            case "GB": return "london";
            case "FR": return "paris";
            case "DE": return "berlin";
            case "JP": return "tokyo";
            case "KR": return "seoul";
            case "NG": case "GH": return "lagos";
            case "JM": case "TT": case "BB": case "BS": return "jamaica";
            case "ET": case "ER": return "ethiopia";
            case "BR": case "MX": case "CO": case "AR": case "PR": case "CU": case "DO": case "VE": case "PE":
            case "CL": return "rio";
            case "IN": case "PK": case "BD": return "mumbai";
            case "EG": case "LB": case "SA": case "AE": case "IR": case "IQ": case "SY": case "JO": case "TR":
            case "IL": case "MA": case "DZ": case "TN": return "cairo";
            case "KE": case "TZ": case "UG": case "ZA": return "nairobi";
            default: break;
        }
        switch (theme) {
            case ETHIOPIA: return "ethiopia";
            case CARIBBEAN: return "jamaica";
            case AFRO: return "lagos";
            case LATIN: return "rio";
            case JAPAN: return "tokyo";
            case KOREA: return "seoul";
            case INDIA: return "mumbai";
            case MIDEAST: return "cairo";
            case COUNTRY: return "nashville";
            case HIPHOP: return "nightcity";
            case ELECTRONIC: return "grid";
            case ROCK: return "dusk";
            case JAZZ: return "bluenight";
            case CLASSICAL: return "mist";
            default: return "";
        }
    }

    // ---- mapping -------------------------------------------------------------------------------

    private static final String[][] TAGS = new String[15][];

    static {
        TAGS[ETHIOPIA] = new String[] {"ethiopia", "ethio", "eritrea", "tigrinya", "amharic", "oromo", "habesha"};
        TAGS[CARIBBEAN] = new String[] {"reggae", "dancehall", "ska", "dub", "calypso", "soca", "caribbean",
            "rocksteady", "roots"};
        TAGS[AFRO] = new String[] {"afrobeat", "afropop", "afro", "amapiano", "highlife", "afroswing", "naija",
            "african"};
        TAGS[LATIN] = new String[] {"latin", "salsa", "reggaeton", "bachata", "cumbia", "bossa", "merengue",
            "tango", "flamenco", "mariachi", "samba", "tropical", "urbano", "latino"};
        TAGS[JAPAN] = new String[] {"j-pop", "jpop", "japan", "city pop", "enka", "anime", "j-rock", "visual kei"};
        TAGS[KOREA] = new String[] {"k-pop", "kpop", "korean", "k-hip", "k-rock"};
        TAGS[INDIA] = new String[] {"bollywood", "hindi", "indian", "hindustani", "carnatic", "bhangra", "tamil",
            "punjabi", "telugu", "filmi", "qawwali", "desi"};
        TAGS[MIDEAST] = new String[] {"arab", "turkish", "persian", "middle east", "oriental", "mizrahi",
            "khaleeji", "maghrebi", "rai"};
        TAGS[COUNTRY] = new String[] {"country", "bluegrass", "americana", "honky", "outlaw", "nashville"};
        TAGS[JAZZ] = new String[] {"jazz", "blues", "bebop", "swing", "big band"};
        TAGS[ELECTRONIC] = new String[] {"electronic", "house", "techno", "edm", "trance", "dubstep",
            "drum and bass", "electro", "synth", "ambient", "downtempo", "idm"};
        TAGS[HIPHOP] = new String[] {"hip hop", "hip-hop", "rap", "trap", "grime", "drill", "gangsta"};
        TAGS[ROCK] = new String[] {"rock", "metal", "punk", "grunge", "alternative", "emo", "hardcore"};
        TAGS[CLASSICAL] = new String[] {"classical", "orchestra", "opera", "baroque", "symphon", "chamber",
            "composer", "piano"};
    }

    /** Short words must be the whole tag ("ska", "rap"); longer ones may sit inside a tag. */
    private static boolean tagHas(String tag, String kw) {
        if (kw.length() > 3) return tag.contains(kw);
        // "christian rap" has the word rap; "grapes" does not
        for (String w : tag.split("[^a-z]+")) if (w.equals(kw)) return true;
        return false;
    }

    static int themeOf(String country, String[] tags) {
        float[] s = new float[15];
        switch (country) {
            case "ET": case "ER":
                s[ETHIOPIA] += 3f;
                break;
            case "JM": case "TT": case "BB": case "BS": case "LC": case "VC": case "AG": case "DM":
            case "GD": case "KN":
                s[CARIBBEAN] += 2.5f;
                break;
            case "NG": case "GH": case "SN": case "CI": case "CM": case "ZA": case "KE": case "TZ": case "UG":
                s[AFRO] += 2f;
                break;
            case "MX": case "CO": case "AR": case "BR": case "PR": case "CU": case "DO": case "VE": case "PE":
            case "CL": case "EC": case "UY": case "PA": case "CR":
                s[LATIN] += 2.5f;
                break;
            case "ES":
                s[LATIN] += 1.5f;
                break;
            case "JP":
                s[JAPAN] += 3f;
                break;
            case "KR":
                s[KOREA] += 3f;
                break;
            case "IN": case "PK": case "BD": case "LK": case "NP":
                s[INDIA] += 2.5f;
                break;
            case "EG": case "LB": case "SA": case "AE": case "IR": case "IQ": case "SY": case "JO": case "TR":
            case "IL": case "MA": case "DZ": case "TN": case "KW": case "QA": case "OM": case "YE": case "LY":
            case "PS":
                s[MIDEAST] += 2.5f;
                break;
            default:
                break;
        }
        for (int i = 0; i < tags.length && i < 8; i++) {
            String tag = tags[i].toLowerCase(Locale.US);
            float weight = i < 3 ? 1.5f : 1f;
            for (int t = 1; t < 15; t++) {
                for (String kw : TAGS[t]) {
                    if (tagHas(tag, kw)) {
                        s[t] += weight;
                        break;
                    }
                }
            }
        }
        int best = 0;
        float top = 1.4f;
        for (int t = 1; t < 15; t++) {
            if (s[t] > top) {
                top = s[t];
                best = t;
            }
        }
        return best;
    }

    // Stems ending in '!' must be the whole word; the others match the start of a word.
    private static final String[][] KW = {
        {"rain", "storm", "thunder", "drizzl", "shower"},
        {"fire", "flame", "burn", "blaze", "ember", "inferno", "hot!"},
        {"ocean", "sea!", "seas!", "wave", "beach", "island", "sail", "shore", "tide", "coast"},
        {"love", "heart", "kiss", "romanc", "amor", "amour", "valentine"},
        {"night", "midnight", "moon", "dark", "star!", "stars!", "dream", "noche", "nocturn"},
        {"sun", "summer", "morning", "golden", "sol!", "verano"},
        {"city", "street", "downtown", "urban", "town!", "neon", "lights!"}};

    static int keywords(String text) {
        if (text == null || text.isEmpty()) return 0;
        int mask = 0;
        String s = text.toLowerCase(Locale.US);
        int n = s.length(), from = -1;
        for (int i = 0; i <= n; i++) {
            boolean letter = i < n && s.charAt(i) >= 'a' && s.charAt(i) <= 'z';
            if (letter) {
                if (from < 0) from = i;
            } else if (from >= 0) {
                String w = s.substring(from, i);
                from = -1;
                for (int k = 0; k < KW.length; k++) {
                    for (String stem : KW[k]) {
                        boolean exact = stem.endsWith("!");
                        if (exact ? w.equals(stem.substring(0, stem.length() - 1)) : w.startsWith(stem)) {
                            mask |= 1 << k;
                        }
                    }
                }
            }
        }
        return mask;
    }
}
