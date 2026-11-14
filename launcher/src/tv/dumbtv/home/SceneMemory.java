package tv.dumbtv.home;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;

/**
 * What the visualizer has already shown, kept across songs and launcher restarts (the store is SharedPreferences on
 * the TV, a plain map in the offline test). The hard rules live here so Glow's own playlist and the Director's
 * backdrop picks obey the same ones:
 *   - never the same scene twice in a row, never the same scene within the last 6 scene changes;
 *   - never the same scene + preset within 15 minutes (a scene with a single variant is one look, so the scene itself);
 *   - the family changes on every scene change (family first, then the scene);
 *   - per song, the last 8 scenes are avoided; globally the last 30 are remembered.
 * Times are wall-clock milliseconds, so they survive a restart.
 */
final class SceneMemory {
    interface Store {
        String load(String key);

        void save(String key, String value);
    }

    static final int GLOBAL = 30, PER_SONG = 8, NO_REPEAT = 6, KEEP = 200;
    static final long PRESET_MS = 15L * 60 * 1000;
    private static final String KEY = "history";

    static final class E {
        final int id, var, family, songHash;
        final long t;

        E(int id, int var, int family, int songHash, long t) {
            this.id = id;
            this.var = var;
            this.family = family;
            this.songHash = songHash;
            this.t = t;
        }
    }

    private final Store store;
    private final ArrayList<E> hist = new ArrayList<>(); // newest first
    private final HashMap<Integer, Integer> session = new HashMap<>();
    private int sessionChanges, sessionRepeats;

    SceneMemory(Store store) {
        this.store = store;
        String s = store == null ? null : store.load(KEY);
        if (s != null && s.length() > 0) {
            for (String part : s.split(";")) {
                String[] f = part.split(",");
                if (f.length != 5) continue;
                try {
                    hist.add(new E(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), Long.parseLong(f[4])));
                } catch (NumberFormatException e) {
                    // a damaged entry is just forgotten
                }
            }
        }
    }

    synchronized void record(int id, int var, int family, String song, long now) {
        hist.add(0, new E(id, var, family, song == null ? 0 : song.hashCode(), now));
        while (hist.size() > KEEP) hist.remove(hist.size() - 1);
        sessionChanges++;
        Integer c = session.get(id);
        if (c != null) sessionRepeats++;
        session.put(id, c == null ? 1 : c + 1);
        if (store != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hist.size(); i++) {
                E e = hist.get(i);
                if (i > 0) sb.append(';');
                sb.append(e.id).append(',').append(e.var).append(',').append(e.family).append(',').append(e.songHash).append(',').append(e.t);
            }
            store.save(KEY, sb.toString());
        }
    }

    /** the scene shown just before, or -1 */
    synchronized int lastId() {
        return hist.isEmpty() ? -1 : hist.get(0).id;
    }

    synchronized int lastFamily() {
        return hist.isEmpty() ? -1 : hist.get(0).family;
    }

    /** true when the scene is among the last {@code n} scene changes */
    synchronized boolean inLast(int id, int n) {
        for (int i = 0; i < Math.min(n, hist.size()); i++) if (hist.get(i).id == id) return true;
        return false;
    }

    /** how many different presets of this scene were shown in the last 15 minutes */
    private int presetsUsed(int id, long now) {
        HashSet<Integer> v = new HashSet<>();
        for (E e : hist) {
            if (now - e.t > PRESET_MS) break;
            if (e.id == id) v.add(e.var);
        }
        return v.size();
    }

    /**
     * The hard rules for a scene about to be chosen. nVar is how many structurally different presets it has.
     * {@code relaxed} keeps only "not the same as the last one" (used when nothing else is left).
     */
    synchronized boolean blocked(int id, int nVar, long now, boolean relaxed) {
        if (!hist.isEmpty() && hist.get(0).id == id) return true;
        if (relaxed) return false;
        if (inLast(id, NO_REPEAT)) return true;
        return presetsUsed(id, now) >= Math.max(1, nVar);
    }

    /** a preset of this scene that was not shown in the last 15 minutes, else the one shown longest ago */
    synchronized int pickVariant(int id, int nVar, Random r, long now) {
        if (nVar <= 1) return 0;
        boolean[] used = new boolean[nVar];
        for (E e : hist) {
            if (now - e.t > PRESET_MS) break;
            if (e.id == id && e.var >= 0 && e.var < nVar) used[e.var] = true;
        }
        int free = 0;
        for (boolean u : used) if (!u) free++;
        if (free > 0) {
            int k = r.nextInt(free);
            for (int i = 0; i < nVar; i++) if (!used[i] && k-- == 0) return i;
        }
        long oldest = Long.MAX_VALUE;
        int pick = 0;
        for (int v = 0; v < nVar; v++) {
            long last = -1;
            for (E e : hist) if (e.id == id && e.var == v) { last = e.t; break; }
            if (last < oldest) { oldest = last; pick = v; }
        }
        return pick;
    }

    /** the scene was among this song's last 8 scenes */
    synchronized boolean songRecent(int id, String song) {
        int h = song == null ? 0 : song.hashCode(), n = 0;
        for (E e : hist) {
            if (e.songHash != h) continue;
            if (e.id == id) return true;
            if (++n >= PER_SONG) break;
        }
        return false;
    }

    synchronized int sessionDistinct() {
        return session.size();
    }

    synchronized int sessionRepeats() {
        return sessionRepeats;
    }

    synchronized String stats() {
        return "played=" + sessionChanges + " distinct=" + session.size() + " repeats=" + sessionRepeats;
    }
}
