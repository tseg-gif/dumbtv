package tv.dumbtv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The Cinema clip library: pre-rendered 1080p clips in {@code files/cinema} (and {@code Android/media/<pkg>/cinema}, which
 * Fire OS 8 lets adb reach). An optional sidecar {@code <name>.json} holds {"tags":["calm","piano"],"bpm":90}; a clip
 * without one is untagged. Picking avoids the song's last 6 clips, the last 20 anywhere, and anything shown in the last
 * 10 minutes (relaxed only when the library is too small to honour that).
 */
final class CinemaLib {
    private CinemaLib() {
    }

    static final class Clip {
        final String id, path;
        final String[] tags;
        final float bpm;

        Clip(String id, String path, String[] tags, float bpm) {
            this.id = id;
            this.path = path;
            this.tags = tags;
            this.bpm = bpm;
        }

        boolean has(String t) {
            for (String x : tags) if (x.equals(t)) return true;
            return false;
        }

        String tagText() {
            return tags.length == 0 ? "untagged" : Arrays.toString(tags);
        }
    }

    private static final long TEN_MIN = 10L * 60 * 1000;
    private static List<Clip> cache = new ArrayList<Clip>();
    private static long scannedAt;
    private static final LinkedList<String> global = new LinkedList<String>();
    private static final HashMap<String, LinkedList<String>> perSong = new HashMap<String, LinkedList<String>>();
    private static final HashMap<String, Long> usedAt = new HashMap<String, Long>();

    static File[] dirs(Context c) {
        File a = c.getExternalFilesDir("cinema"); // /sdcard/Android/data/<pkg>/files/cinema
        File m = new File("/sdcard/Android/media/" + c.getPackageName() + "/cinema");
        if (a != null) a.mkdirs();
        m.mkdirs();
        return new File[] {a, m};
    }

    /** All clips found, rescanned at most every 20 s. */
    static synchronized List<Clip> scan(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (scannedAt != 0 && now - scannedAt < 20000) return cache;
        scannedAt = now;
        ArrayList<Clip> out = new ArrayList<Clip>();
        for (File d : dirs(c)) {
            if (d == null) continue;
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                String n = f.getName();
                if (!n.toLowerCase(Locale.US).endsWith(".mp4") || f.length() < 50000) continue;
                String id = n.substring(0, n.length() - 4);
                boolean dup = false;
                for (Clip o : out) if (o.id.equals(id)) dup = true;
                if (dup) continue;
                String[] tags = new String[0];
                float bpm = 0f;
                File j = new File(d, id + ".json");
                if (j.exists() && j.length() < 20000) {
                    try {
                        FileInputStream in = new FileInputStream(j);
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] buf = new byte[2048];
                        int k;
                        while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
                        in.close();
                        JSONObject o = new JSONObject(bo.toString("UTF-8"));
                        JSONArray ta = o.optJSONArray("tags");
                        if (ta != null) {
                            tags = new String[ta.length()];
                            for (int i = 0; i < tags.length; i++) tags[i] = ta.optString(i, "").toLowerCase(Locale.US);
                        }
                        bpm = (float) o.optDouble("bpm", 0.0);
                    } catch (Exception e) {
                        // an unreadable sidecar just means the clip is untagged
                    }
                }
                out.add(new Clip(id, f.getAbsolutePath(), tags, bpm));
            }
        }
        cache = out;
        return out;
    }

    static int count(Context c) {
        return scan(c).size();
    }

    static synchronized void load(Context c) {
        SharedPreferences p = c.getSharedPreferences("cinema", Context.MODE_PRIVATE);
        if (!usedAt.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(p.getString("usedAt", "{}"));
            long wall = System.currentTimeMillis(), up = SystemClock.elapsedRealtime();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                usedAt.put(k, up - (wall - o.optLong(k)));
            }
        } catch (Exception e) {
            // start with an empty history
        }
    }

    private static void save(Context c) {
        try {
            JSONObject o = new JSONObject();
            long wall = System.currentTimeMillis(), up = SystemClock.elapsedRealtime();
            for (String k : usedAt.keySet()) if (up - usedAt.get(k) < 3600000L) o.put(k, wall - (up - usedAt.get(k)));
            c.getSharedPreferences("cinema", Context.MODE_PRIVATE).edit().putString("usedAt", o.toString()).apply();
        } catch (Exception e) {
            // history is a nicety
        }
    }

    /**
     * Picks a clip for the song. Genre and mood tags raise a clip's score; calm songs (piano, jazz, country, reggae, or a
     * slow tempo) prefer clips tagged calm, others prefer energetic ones. Returns null when there are no clips.
     */
    static synchronized Clip pick(Context c, String songKey, String genre, float bpm, Random r) {
        List<Clip> all = scan(c);
        if (all.isEmpty()) return null;
        load(c);
        long now = SystemClock.elapsedRealtime();
        LinkedList<String> mine = perSong.get(songKey);
        boolean calm = "piano".equals(genre) || "jazz".equals(genre) || "country".equals(genre) || "reggae".equals(genre)
            || (bpm > 0 && bpm < 92);
        Clip best = null;
        double bestScore = -1e9;
        for (int pass = 0; pass < 3 && best == null; pass++) {
            for (Clip k : all) {
                Long at = usedAt.get(k.id);
                boolean recentTen = at != null && now - at < TEN_MIN;
                boolean inSong = mine != null && mine.contains(k.id), inGlobal = global.contains(k.id);
                if (pass == 0 && (recentTen || inSong || inGlobal)) continue;
                if (pass == 1 && (recentTen || inSong)) continue; // the library is small: only the last 20 rule is relaxed
                double s = r.nextDouble() * 1.5;
                if (genre != null && k.has(genre)) s += 3;
                if (k.has(calm ? "calm" : "energetic")) s += 2;
                if (k.has(calm ? "energetic" : "calm")) s -= 1;
                if (k.bpm > 0 && bpm > 0 && Math.abs(k.bpm / bpm - 1) < 0.1) s += 1;
                if (pass == 2) s -= at == null ? 0 : (now - at) < 0 ? 0 : -((now - at) / 1000.0) * 0.001; // the oldest wins
                if (s > bestScore) {
                    bestScore = s;
                    best = k;
                }
            }
        }
        if (best != null) {
            usedAt.put(best.id, now);
            global.addFirst(best.id);
            while (global.size() > 20) global.removeLast();
            if (mine == null) {
                mine = new LinkedList<String>();
                perSong.put(songKey, mine);
            }
            mine.addFirst(best.id);
            while (mine.size() > 6) mine.removeLast();
            save(c);
        }
        return best;
    }
}
