package tv.dumbtv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Camera routes through an artist's city. A city is a list of landmark ids (see Landmarks); a route is
 * some of them in an order, and the order is shuffled per song unless a song override fixes it.
 * Unknown places are looked up on Wikidata (CC0): the city, then its best-known tourist attractions,
 * each mapped to a generic silhouette from its label. Answers are cached in SharedPreferences.
 */
final class Journey {
    static final class City {
        final String id, name;
        final String[] lm;

        City(String id, String name, String... lm) {
            this.id = id;
            this.name = name;
            this.lm = lm;
        }
    }

    interface TextCallback {
        /** Main thread; the plain lyrics, lower case. */
        void got(String text);
    }

    interface Callback {
        /** Main thread; null when nothing was found. */
        void done(City city);
    }

    static final City[] CITIES = {
        new City("la", "Los Angeles", "compton", "watts", "downtown", "griffith", "hollywood", "pier", "dodger"),
        new City("ny", "New York", "brooklyn", "empire", "liberty", "wtc", "chrysler", "houses"),
        new City("toronto", "Toronto", "cn", "skydome", "downtown", "houses", "ship"),
        new City("chicago", "Chicago", "willis", "hancock", "bean", "el", "downtown", "bridge"),
        new City("atlanta", "Atlanta", "spire", "cyl", "downtown", "stadium", "overpass"),
        new City("london", "London", "bigben", "eye", "towerbridge", "shard", "stpauls"),
        new City("lagos", "Lagos", "lekki", "theatre", "downtown", "longbridge", "market"),
        new City("kingston", "Kingston", "bluemtn", "harbour", "palms", "lighthouse", "houses"),
        new City("addis", "Addis Ababa", "entoto", "meskel", "au", "dome", "downtown"),
        new City("miami", "Miami", "deco", "palms", "downtown", "ship", "lifeguard")};

    /** Words in a place name (MusicBrainz area, artist table) that mean one of the cities above. */
    private static final String[][] ALIAS = {
        {"la", "los angeles", "compton", "inglewood", "long beach", "watts", "hollywood", "pasadena",
            "santa monica", "california", "oakland", "san francisco"},
        {"ny", "new york", "brooklyn", "bronx", "harlem", "queens", "manhattan", "staten island", "philadelphia",
            "new jersey"},
        {"toronto", "toronto", "ontario", "scarborough", "mississauga", "canada"},
        {"chicago", "chicago", "illinois"}, {"atlanta", "atlanta", "georgia", "houston", "memphis", "new orleans"},
        {"london", "london", "england", "manchester", "birmingham", "united kingdom"},
        {"lagos", "lagos", "abuja", "ibadan", "accra", "nigeria", "ghana"},
        {"kingston", "kingston", "jamaica", "trinidad", "barbados", "bahamas"},
        {"addis", "addis", "ethiopia", "eritrea", "asmara"},
        {"miami", "miami", "florida", "puerto rico", "cuba", "dominican"}};

    /** artist | title | city | landmarks in order (the last one is where the song ends). */
    private static final String[][] OVERRIDE = {
        {"kendrick lamar", "dodger blue", "la", "compton,watts,downtown,griffith,hollywood,pier,dodger"},
        {"kendrick lamar", "m.a.a.d city", "la", "compton,watts,pier,downtown,hollywood,griffith"},
        {"kendrick lamar", "money trees", "la", "compton,houses,watts,downtown,pier"},
        {"jay-z", "empire state of mind", "ny", "brooklyn,wtc,liberty,chrysler,empire"},
        {"kanye west", "homecoming", "chicago", "el,bridge,bean,hancock,willis"},
        {"drake", "started from the bottom", "toronto", "houses,downtown,skydome,ship,cn"},
        {"burna boy", "ye", "lagos", "market,longbridge,theatre,downtown,lekki"},
        {"stormzy", "big for your boots", "london", "stpauls,shard,eye,towerbridge,bigben"}};

    private static final String UA = "dumbtv-home/1.6 (https://github.com/humblemuzzu/dumbtv)";
    private static Handler main;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    private Journey() {
    }

    private static synchronized Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    /** First artist of "A feat. B", "A, B", "A & B". */
    private static String first(String artist) {
        if (artist == null) return "";
        return artist.toLowerCase(Locale.US).split("\\s*(,|;|&|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bwith\\b|\\sx\\s)\\s*")[0].trim();
    }

    static City city(String id) {
        for (City c : CITIES) if (c.id.equals(id)) return c;
        return null;
    }

    /** City id for a free-text place ("Compton California"), or null. */
    static String cityIdOf(String text) {
        if (text == null) return null;
        String s = text.toLowerCase(Locale.US);
        for (String[] a : ALIAS) {
            for (int i = 1; i < a.length; i++) if (s.contains(a[i])) return a[0];
        }
        return null;
    }

    /** The override row {city, order} for this artist and song, or null. */
    static String[] override(String artist, String title) {
        String a = norm(first(artist)), t = norm(title);
        if (a.isEmpty() || t.isEmpty()) return null;
        for (String[] o : OVERRIDE) {
            if (a.equals(o[0]) && t.startsWith(o[1])) return new String[] {o[2], o[3]};
        }
        return null;
    }

    static String norm(String s) {
        if (s == null) return "";
        String x = s.toLowerCase(Locale.US);
        int p = x.indexOf('(');
        if (p > 0) x = x.substring(0, p);
        p = x.indexOf(" - ");
        if (p > 0) x = x.substring(0, p);
        return x.trim();
    }

    /**
     * Five (or all, if fewer) landmarks in a song-specific order. The order is reshuffled until it is
     * not one of {@code avoid} (a "a,b,c" string each), up to 12 times.
     */
    static String[] order(City c, Random rng, String[] avoid) {
        int n = Math.min(5, c.lm.length);
        String[] out = new String[n];
        for (int attempt = 0; attempt < 12; attempt++) {
            String[] pool = c.lm.clone();
            for (int i = pool.length - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                String t = pool[i];
                pool[i] = pool[j];
                pool[j] = t;
            }
            System.arraycopy(pool, 0, out, 0, n);
            if (!contains(avoid, join(out))) break;
        }
        return out;
    }

    static String join(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) sb.append(i > 0 ? "," : "").append(a[i]);
        return sb.toString();
    }

    private static boolean contains(String[] a, String s) {
        if (a == null) return false;
        for (String x : a) if (s.equals(x)) return true;
        return false;
    }

    /** Plain lyrics from LRCLIB (free, no key) for the keyword packs; kept 24 h, never shown. */
    /** Synced LRC lyrics (timed lines) for the singer's mouth; same cache rules, nothing is shown. */
    static void synced(Context c, final String artist, final String title, final TextCallback cb) {
        if (artist == null || title == null || artist.isEmpty() || title.isEmpty()) return;
        final SharedPreferences p = c.getApplicationContext().getSharedPreferences("journey", Context.MODE_PRIVATE);
        final String key = "sy:" + norm(artist) + "|" + norm(title);
        if (System.currentTimeMillis() - p.getLong("syfail:" + key, 0) < 24L * 3600 * 1000) return;
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject o = new JSONObject(get("https://lrclib.net/api/get?artist_name="
                            + URLEncoder.encode(first(artist), "UTF-8") + "&track_name="
                            + URLEncoder.encode(norm(title), "UTF-8")));
                    final String text = o.optString("syncedLyrics", "");
                    if (text.isEmpty()) throw new Exception("no synced lyrics");
                    main().post(new Runnable() {
                        @Override
                        public void run() {
                            cb.got(text);
                        }
                    });
                } catch (Exception e) {
                    p.edit().putLong("syfail:" + key, System.currentTimeMillis()).apply();
                }
            }
        });
    }

    static void lyrics(Context c, final String artist, final String title, final TextCallback cb) {
        if (artist == null || title == null || artist.isEmpty() || title.isEmpty()) return;
        final SharedPreferences p = c.getApplicationContext().getSharedPreferences("journey", Context.MODE_PRIVATE);
        final String key = "ly:" + norm(artist) + "|" + norm(title);
        if (System.currentTimeMillis() - p.getLong("lyfail:" + key, 0) < 24L * 3600 * 1000) return;
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject o = new JSONObject(get("https://lrclib.net/api/get?artist_name="
                            + URLEncoder.encode(first(artist), "UTF-8") + "&track_name="
                            + URLEncoder.encode(norm(title), "UTF-8")));
                    final String text = o.optString("plainLyrics", "").toLowerCase(Locale.US);
                    if (text.isEmpty()) throw new Exception("no lyrics");
                    main().post(new Runnable() {
                        @Override
                        public void run() {
                            cb.got(text);
                        }
                    });
                } catch (Exception e) {
                    p.edit().putLong("lyfail:" + key, System.currentTimeMillis()).apply();
                }
            }
        });
    }

    // ---- Wikidata fallback ---------------------------------------------------------------------

    /** Generic silhouette for a landmark's English label. */
    static String generic(String label) {
        String s = label.toLowerCase(Locale.US);
        if (s.contains("tower") || s.contains("needle") || s.contains("spire")) return "tower";
        if (s.contains("bridge")) return "bridge";
        if (s.contains("cathedral") || s.contains("church") || s.contains("mosque") || s.contains("temple")
                || s.contains("basilica") || s.contains("shrine") || s.contains("synagogue")) return "dome";
        if (s.contains("stadium") || s.contains("arena") || s.contains("coliseum")) return "stadium";
        if (s.contains("castle") || s.contains("palace") || s.contains("fort")) return "castle";
        if (s.contains("mount") || s.contains("hill") || s.contains("park") || s.contains("garden")) return "hills";
        if (s.contains("light")) return "lighthouse";
        if (s.contains("museum") || s.contains("theatre") || s.contains("theater") || s.contains("hall")
                || s.contains("library") || s.contains("square")) return "hall";
        if (s.contains("wheel")) return "eye";
        return "tower";
    }

    /** Looks the place up in the cache, then on Wikidata (background); calls back on the main thread. */
    static void fetch(Context c, final String place, final Callback cb) {
        final Context app = c.getApplicationContext();
        final String q = norm(place);
        if (q.isEmpty()) return;
        final SharedPreferences p = app.getSharedPreferences("journey", Context.MODE_PRIVATE);
        String hit = p.getString("wd:" + q, null);
        if (hit != null) {
            City cached = parse(q, hit);
            if (cached != null) cb.done(cached);
            return;
        }
        if (System.currentTimeMillis() - p.getLong("fail:" + q, 0) < 24L * 3600 * 1000) return;
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                String v = null;
                try {
                    v = lookup(q);
                } catch (Exception e) {
                    Log.w("dumbtv", "wikidata lookup failed: " + e);
                }
                if (v == null) {
                    p.edit().putLong("fail:" + q, System.currentTimeMillis()).apply();
                    return;
                }
                p.edit().putString("wd:" + q, v).apply();
                final City city = parse(q, v);
                if (city != null) {
                    main().post(new Runnable() {
                        @Override
                        public void run() {
                            cb.done(city);
                        }
                    });
                }
            }
        });
    }

    /** Cache value: "Label|id1,id2,id3". */
    private static City parse(String q, String v) {
        String[] parts = v.split("\\|", -1);
        if (parts.length < 2 || parts[1].isEmpty()) return null;
        return new City("wd:" + q, parts[0], parts[1].split(","));
    }

    private static String lookup(String q) throws Exception {
        JSONObject found = new JSONObject(get("https://www.wikidata.org/w/api.php?action=wbsearchentities&language=en"
                + "&type=item&limit=1&format=json&search=" + URLEncoder.encode(q, "UTF-8")));
        JSONArray hits = found.optJSONArray("search");
        if (hits == null || hits.length() == 0) return null;
        String qid = hits.getJSONObject(0).optString("id");
        String label = hits.getJSONObject(0).optString("label", q).replace('|', ' ');
        if (!qid.matches("Q\\d+")) return null;
        String sparql = "SELECT ?lLabel ?s WHERE { ?l wdt:P131* wd:" + qid
                + "; wdt:P31/wdt:P279* wd:Q570116; wikibase:sitelinks ?s. "
                + "SERVICE wikibase:label { bd:serviceParam wikibase:language \"en\". } } ORDER BY DESC(?s) LIMIT 8";
        StringBuilder ids = new StringBuilder();
        try {
            JSONArray rows = new JSONObject(get("https://query.wikidata.org/sparql?format=json&query="
                    + URLEncoder.encode(sparql, "UTF-8"))).getJSONObject("results").getJSONArray("bindings");
            for (int i = 0; i < rows.length() && ids.length() < 80; i++) {
                String g = generic(rows.getJSONObject(i).getJSONObject("lLabel").optString("value"));
                if (("," + ids).contains("," + g + ",") || ids.toString().equals(g)) continue;
                ids.append(ids.length() == 0 ? "" : ",").append(g);
            }
        } catch (Exception e) {
            Log.w("dumbtv", "wikidata attractions failed: " + e);
        }
        // a place with no listed attractions still gets a plausible skyline
        if (ids.length() == 0) ids.append("downtown,tower,bridge,hills,dome");
        else ids.append(",downtown");
        return label + "|" + ids;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setConnectTimeout(8000);
            con.setReadTimeout(9000);
            con.setRequestProperty("User-Agent", UA);
            con.setRequestProperty("Accept", "application/json");
            if (con.getResponseCode() != 200) throw new Exception("http " + con.getResponseCode());
            InputStream in = con.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 256 * 1024) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }
}
