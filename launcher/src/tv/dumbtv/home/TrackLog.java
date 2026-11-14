package tv.dumbtv.home;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Track history next to home.conf (tracks.log in the media folder, adb-readable): one tab-separated line per normalized
 * "artist - title": key, artist, title, play seconds (summed over plays), match ("entry:user|model|derived" or "fallback"),
 * last seen (epoch seconds). `tune-firetv.sh looks` reads it to find songs with no entry. Capped at 5000 lines (oldest dropped).
 */
final class TrackLog {
    private static final int CAP = 5000;
    private static String prevKey = "";
    private static long prevStartMs;

    private TrackLog() {}

    /** A new track started: closes the previous one's play time and records this one. */
    static synchronized void started(Context c, String artist, String title, String match) {
        try {
            File f = file(c);
            if (f == null) return;
            LinkedHashMap<String, String[]> m = read(f);
            long now = System.currentTimeMillis();
            if (!prevKey.isEmpty() && m.containsKey(prevKey)) {
                String[] p = m.get(prevKey);
                p[3] = String.valueOf(parse(p[3]) + Math.max(0, (now - prevStartMs) / 1000));
            }
            String key = SongLooks.normalize(artist, title);
            if (key.length() <= 3) return;
            String[] e = m.remove(key); // re-inserted last: most recent at the end
            if (e == null) e = new String[] {key, artist, title, "0", match, "0"};
            e[4] = match;
            e[5] = String.valueOf(now / 1000);
            m.put(key, e);
            prevKey = key;
            prevStartMs = now;
            write(f, m);
        } catch (java.io.IOException | RuntimeException ignored) {
            // history is a convenience
        }
    }

    /** The playing track's match changed (a derived entry was built). */
    static synchronized void matched(Context c, String artist, String title, String match) {
        try {
            File f = file(c);
            if (f == null) return;
            LinkedHashMap<String, String[]> m = read(f);
            String[] e = m.get(SongLooks.normalize(artist, title));
            if (e == null) return;
            e[4] = match;
            write(f, m);
        } catch (java.io.IOException | RuntimeException ignored) {
            // history is a convenience
        }
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static File file(Context c) {
        File[] d = c.getExternalMediaDirs();
        return d == null || d.length == 0 || d[0] == null ? null : new File(d[0], "tracks.log");
    }

    private static LinkedHashMap<String, String[]> read(File f) throws java.io.IOException {
        LinkedHashMap<String, String[]> m = new LinkedHashMap<>();
        if (!f.exists()) return m;
        BufferedReader r = new BufferedReader(new FileReader(f));
        try {
            String l;
            while ((l = r.readLine()) != null) {
                String[] p = l.split("\t", -1);
                if (p.length >= 6) m.put(p[0], p);
            }
        } finally {
            r.close();
        }
        return m;
    }

    private static void write(File f, LinkedHashMap<String, String[]> m) throws java.io.IOException {
        Iterator<Map.Entry<String, String[]>> it = m.entrySet().iterator();
        for (int drop = m.size() - CAP; drop > 0 && it.hasNext(); drop--) {
            it.next();
            it.remove();
        }
        FileWriter w = new FileWriter(f, false);
        try {
            for (String[] p : m.values()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < p.length; i++) sb.append(i == 0 ? "" : "\t").append(p[i].replace('\t', ' ').replace('\n', ' '));
                w.write(sb.append('\n').toString());
            }
        } finally {
            w.close();
        }
    }
}
