package tv.dumbtv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One short line about the song, from the Wikipedia REST summary of "title (artist song)" or
 * "title (song)". Accepted only for a standard article whose extract names the artist; disambiguation
 * pages and anything else are ignored. Asked once per song on a background thread, answers (and
 * misses) are cached, so the same song is instant and costs no request. Off unless react_facts=on.
 */
final class Facts {
    interface Callback {
        /** Main thread; only called when a line was found. */
        void got(String line);
    }

    private static final String PREFS = "facts", UA = "dumbtv-home/1.6 (https://github.com/humblemuzzu/dumbtv)";
    private static final int MAX = 90;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Facts() {
    }

    static void fetch(Context c, final String artist, final String title, final Callback cb) {
        final Context app = c.getApplicationContext();
        final String a = Origin.primary(artist), t = cleanTitle(title);
        if (a.isEmpty() || t.isEmpty()) return;
        final String key = Artists.key(a) + "|" + Artists.key(t);
        final SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String cached = p.getString(key, null);
        if (cached != null) {
            if (!cached.isEmpty()) cb.got(cached);
            return;
        }
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                String line = "";
                try {
                    String[] pages = {t + " (" + a + " song)", t + " (song)"};
                    for (String page : pages) {
                        line = summary(page, a);
                        if (!line.isEmpty()) break;
                    }
                    p.edit().putString(key, line).apply(); // a miss is kept too
                } catch (Exception e) {
                    Log.i("dumbtv", "facts: " + e); // offline: try again next time
                    return;
                }
                if (line.isEmpty()) return;
                final String out = line;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.got(out);
                    }
                });
            }
        });
    }

    /** "Song (with SZA) - From ..." -> "Song". */
    static String cleanTitle(String title) {
        if (title == null) return "";
        String s = title;
        int i = s.indexOf(" - ");
        if (i > 0) s = s.substring(0, i);
        i = s.indexOf('(');
        if (i > 0) s = s.substring(0, i);
        i = s.indexOf('[');
        if (i > 0) s = s.substring(0, i);
        return s.trim();
    }

    private static String summary(String page, String artist) throws Exception {
        URL url = new URL("https://en.wikipedia.org/api/rest_v1/page/summary/"
                + URLEncoder.encode(page.replace(' ', '_'), "UTF-8").replace("+", "%20"));
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(6000);
        con.setReadTimeout(6000);
        con.setRequestProperty("User-Agent", UA);
        con.setRequestProperty("Accept", "application/json");
        try {
            if (con.getResponseCode() != 200) return "";
            InputStream in = con.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            JSONObject o = new JSONObject(out.toString("UTF-8"));
            if (!"standard".equals(o.optString("type"))) return ""; // disambiguation, redirects, errors
            String extract = o.optString("extract", "");
            if (!extract.toLowerCase(Locale.US).contains(artist.toLowerCase(Locale.US))) return "";
            return shorten(extract);
        } finally {
            con.disconnect();
        }
    }

    /** The first sentence, at most 90 characters, cut at a word. */
    static String shorten(String extract) {
        String s = extract.trim();
        int end = s.indexOf(". ");
        if (end > 0) s = s.substring(0, end + 1);
        if (s.length() > MAX) {
            s = s.substring(0, MAX - 1);
            int sp = s.lastIndexOf(' ');
            if (sp > 40) s = s.substring(0, sp);
            s = s.replaceAll("[ ,;:]+$", "") + "…";
        }
        return s;
    }
}
