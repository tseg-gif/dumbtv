package tv.dumbtv.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;

/**
 * Per-song look table (assets/song_looks.json, keyed by the normalized "artist - title"). An entry says what the
 * song feels like (mood, energy, valence, felt tempo) and what the show should do about it: preferred scene
 * families (weighted 4x by the Director), avoided families (never), the palette (hues and a saturation cap that replace the
 * cover art), the motion weight and the kick style. Lookup order: the user file song_looks.json in the media folder
 * (adb-editable), the shipped asset, then entries derived earlier from live features and tags (cached in prefs).
 * source is "user" (hand-edited, never overwritten), "model" (bootstrapped) or "derived".
 */
final class SongLooks {
    private static final String TAG = "dumbsonglook";

    static final class Entry {
        String key = "", source = "derived", motion = "flowing", kick = "punch", notes = "";
        String[] mood = new String[0], families = new String[0], avoid = new String[0];
        float energy = 0.5f, valence = 0.5f;
        float tempoHint;                 // felt bpm for the pulses, 0 = none
        float[] hues = new float[0];     // degrees, 2-3 of them; empty = keep the cover art palette
        float satCap = 0.55f;
        boolean dark;                    // near-black base: the backdrop's value is held down
        boolean fromTable;               // true when found by lookup (false = live fallback)

        /** speed of the scene clock and the beam sweeps */
        float speed() {
            switch (motion) {
                case "heavy": return 0.6f;
                case "sharp": return 1.25f;
                case "floating": return 0.5f;
                default: return 0.85f;
            }
        }

        /** how big the kick's camera punch is (fraction of the picture) */
        float punch() {
            switch (kick) {
                case "bloom": return 0.02f;
                case "ring": return 0.012f;
                default: return "heavy".equals(motion) ? 0.055f : 0.045f;
            }
        }

        /** the beat envelope's decay rate: a smaller number is a slower ease */
        float decay() {
            if ("bloom".equals(kick)) return 2.6f;
            if ("heavy".equals(motion)) return 3.0f;
            return 4.5f;
        }

        String summary() {
            StringBuilder sb = new StringBuilder();
            for (String s : mood) sb.append(sb.length() == 0 ? "" : ",").append(s);
            return sb.toString();
        }

        JSONObject toJson() throws org.json.JSONException {
            JSONObject o = new JSONObject();
            o.put("source", source);
            o.put("mood", new JSONArray(java.util.Arrays.asList(mood)));
            o.put("energy", energy);
            o.put("valence", valence);
            o.put("tempo_hint", tempoHint > 0 ? (Object) Math.round(tempoHint) : JSONObject.NULL);
            o.put("families", new JSONArray(java.util.Arrays.asList(families)));
            o.put("avoid", new JSONArray(java.util.Arrays.asList(avoid)));
            JSONObject p = new JSONObject();
            JSONArray hs = new JSONArray();
            for (float h : hues) hs.put(Math.round(h));
            p.put("hues", hs);
            p.put("sat_cap", satCap);
            p.put("dark", dark);
            o.put("palette", p);
            o.put("motion", motion);
            o.put("kick", kick);
            o.put("notes", notes);
            return o;
        }

        static Entry fromJson(String key, JSONObject o) {
            Entry e = new Entry();
            e.key = key;
            e.source = o.optString("source", "model");
            e.mood = strings(o.optJSONArray("mood"));
            e.energy = (float) o.optDouble("energy", 0.5);
            e.valence = (float) o.optDouble("valence", 0.5);
            e.tempoHint = o.isNull("tempo_hint") ? 0f : (float) o.optDouble("tempo_hint", 0);
            e.families = strings(o.optJSONArray("families"));
            e.avoid = strings(o.optJSONArray("avoid"));
            JSONObject p = o.optJSONObject("palette");
            if (p != null) {
                JSONArray hs = p.optJSONArray("hues");
                int n = hs == null ? 0 : Math.min(3, hs.length());
                e.hues = new float[n];
                for (int i = 0; i < n; i++) e.hues[i] = (float) hs.optDouble(i, 0);
                e.satCap = Math.max(0.2f, Math.min(0.58f, (float) p.optDouble("sat_cap", 0.55)));
                e.dark = p.optBoolean("dark", false);
            }
            e.motion = o.optString("motion", "flowing");
            e.kick = o.optString("kick", "punch");
            e.notes = o.optString("notes", "");
            e.fromTable = true;
            return e;
        }

        private static String[] strings(JSONArray a) {
            if (a == null) return new String[0];
            String[] r = new String[a.length()];
            for (int i = 0; i < r.length; i++) r[i] = a.optString(i, "");
            return r;
        }
    }

    // ---- normalizing ----

    /** "artist - title", lowercase, feat./remaster/live tags and punctuation removed. */
    static String normalize(String artist, String title) {
        return clean(artist, true) + " - " + clean(title, false);
    }

    private static String clean(String s, boolean artist) {
        if (s == null) return "";
        s = s.toLowerCase(Locale.US);
        s = s.replaceAll("[\\(\\[][^\\)\\]]*[\\)\\]]", " ");                       // (feat. x) [remastered]
        s = s.replaceAll("\\s+-\\s+(\\d{4}\\s+)?(remaster(ed)?|live|mono|stereo|single|radio|album|version|edit|deluxe|explicit).*$", " ");
        s = s.replaceAll("\\b(feat|ft|featuring)\\.?\\s.*$", " ");
        s = s.replaceAll("\\b\\d{4}\\s+remaster(ed)?\\b", " ");
        s = s.replaceAll("[’'`\"/.!?,:;*]", "");                                    // apostrophes and slashes close up: AC/DC -> acdc
        s = s.replaceAll("&", " and ");
        s = s.replaceAll("[^a-z0-9\\u00c0-\\uffff ]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        if (artist && s.startsWith("the ")) s = s.substring(4);
        return s;
    }

    // ---- lookup ----

    private static HashMap<String, Entry> table;
    private static long userStamp = -1;

    private static synchronized void load(Context c) {
        File uf = userFile(c);
        long stamp = uf != null && uf.exists() ? uf.lastModified() : 0;
        if (table != null && stamp == userStamp) return;
        HashMap<String, Entry> t = new HashMap<>();
        try {
            readInto(c.getAssets().open("song_looks.json"), t, false);
        } catch (java.io.IOException | RuntimeException e) {
            android.util.Log.w(TAG, "asset table unreadable", e);
        }
        if (stamp > 0) {
            try {
                readInto(new FileInputStream(uf), t, true);
            } catch (java.io.IOException | RuntimeException e) {
                android.util.Log.w(TAG, "user table unreadable", e);
            }
        }
        table = t;
        userStamp = stamp;
        android.util.Log.i(TAG, "table: " + t.size() + " entries");
    }

    private static File userFile(Context c) {
        try {
            File[] d = c.getExternalMediaDirs();
            return d == null || d.length == 0 || d[0] == null ? null : new File(d[0], "song_looks.json");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void readInto(InputStream in, HashMap<String, Entry> t, boolean user) throws java.io.IOException {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            JSONObject es = new JSONObject(bo.toString("UTF-8")).getJSONObject("entries");
            java.util.Iterator<String> it = es.keys();
            while (it.hasNext()) {
                String k = it.next();
                Entry e = Entry.fromJson(k, es.getJSONObject(k));
                Entry old = t.get(k);
                if (old != null && "user".equals(old.source) && !"user".equals(e.source)) continue; // hand-edited entries stay untouched
                t.put(k, e);
            }
        } catch (org.json.JSONException e) {
            throw new java.io.IOException(e.getMessage());
        } finally {
            in.close();
        }
    }

    /** The entry for this song, or null: use the live fallback. */
    static Entry lookup(Context c, String artist, String title) {
        String key = normalize(artist, title);
        load(c);
        Entry e = table.get(key);
        if (e == null) {
            try {
                String js = c.getApplicationContext().getSharedPreferences("songlooks", Context.MODE_PRIVATE).getString(key, null);
                if (js != null) {
                    e = Entry.fromJson(key, new JSONObject(js));
                    e.source = "derived";
                }
            } catch (org.json.JSONException | RuntimeException ignored) {
                e = null;
            }
        }
        android.util.Log.i(TAG, "lookup \"" + key + "\" -> " + (e == null ? "live fallback" : e.source));
        return e;
    }

    static void cache(Context c, Entry e) {
        try {
            c.getApplicationContext().getSharedPreferences("songlooks", Context.MODE_PRIVATE).edit().putString(e.key, e.toJson().toString()).apply();
        } catch (org.json.JSONException | RuntimeException ignored) {
            // the cache only saves work next time
        }
    }

    // ---- families -> layer values ----

    private static final HashMap<String, String[][]> FAM = new HashMap<>();

    /** a family: backdrops, beams, foregrounds, accents, cameras (null = untouched) */
    private static void fam(String name, String[] b, String[] m, String[] f, String[] a, String[] cm) {
        FAM.put(name, new String[][] {b, m, f, a, cm});
    }

    private static String[] s(String... v) {
        return v;
    }

    static {
        fam("monumental geometry", s("torus_knot", "crystal_tunnel", "plexus"), s("lattice", "pillars"), s("truss", "edge"), null, null);
        fam("pillars", s("crystal_tunnel", "city_drive"), s("pillars"), s("truss"), null, null);
        fam("slow wide beams", null, s("fan", "curtain", "cross"), null, null, s("wide", "drift"));
        fam("ring shockwaves", null, s("rings"), null, s("shock"), null);
        fam("tunnel", s("tunnel", "hyperspace", "crystal_tunnel", "vortex"), null, null, null, null);
        fam("lattice", s("plexus"), s("lattice"), null, null, null);
        fam("sweeping beams", null, s("heads", "wipers", "fan", "cross"), null, null, null);
        fam("low beams", null, s("wipers", "fan"), s("floor", "rail"), null, null);
        fam("call-response beams", null, s("cross", "heads", "curtain", "fan"), null, null, null);
        fam("city", s("city_drive", "synth_road"), null, null, null, null);
        fam("synthwave", s("synth_road", "city_drive"), null, s("floor"), null, null);
        fam("golden hour", s("sunset_rays", "dunes", "firefly_meadow"), null, s("haze", "motes"), null, null);
        fam("galaxy", s("galaxy", "hyperspace"), null, null, null, null);
        fam("ocean", s("night_ocean", "underwater_night"), null, null, null, null);
        fam("aurora", s("aurora_ridge"), null, null, null, null);
        fam("rain", s("rain"), null, null, null, null);
        fam("dot-sheet", s("dot_sheet"), null, null, null, null);
        fam("pastel/soft", s("dot_sheet", "lanterns", "firefly_meadow", "aurora_ridge", "underwater_night"), null, s("motes"), null, null);
        fam("fast confetti", null, null, null, s("confetti"), null);
        fam("bloom", s("mandala_bloom"), null, null, s("fountain"), null);
        fam("note-world", s("aurora_ridge", "galaxy"), null, null, null, null);
        fam("lanterns", s("lanterns"), null, null, null, null);
        fam("hyperspace", s("hyperspace"), null, null, null, null);
        fam("dust", null, null, null, s("dust", "risers"), null);
    }

    /** Backdrops too soft for a heavy or regal look: marked unfit for entries with motion=heavy (they stay in the pool for others). */
    static final String[] UNFIT_HEAVY = {"dot_sheet", "lanterns", "firefly_meadow", "aurora_ridge", "rain", "underwater_night", "mandala_bloom"};

    /** layer 0..4: the ids of every vocabulary value the given families name */
    static boolean[] mask(int layer, String[] families) {
        String[] vocab = vocab(layer);
        boolean[] r = new boolean[vocab.length];
        for (String f : families) {
            String[][] v = FAM.get(f);
            if (v == null || v[layer] == null) continue;
            for (String n : v[layer]) {
                for (int i = 0; i < vocab.length; i++) if (vocab[i].equals(n)) r[i] = true;
            }
        }
        return r;
    }

    static String[] vocab(int layer) {
        switch (layer) {
            case 0: return Look.BACKDROPS;
            case 1: return Look.BEAMS;
            case 2: return Look.FGS;
            case 3: return Look.ACCENTS;
            default: return Look.CAMS;
        }
    }

    // ---- live fallback ----

    /**
     * An entry for a song the table does not know: arousal from energy, tempo and onset rate, valence from brightness and
     * the tonal ratio, then the genre / mood guide (the genre comes from the feel engine, tags from MusicBrainz).
     */
    static Entry derive(String key, String genre, String tags, float ener, float bpm, float onsetsPerSec, float bright, float tonal) {
        float arousal = clamp(0.45f * ener + 0.30f * clamp((bpm - 70f) / 110f) + 0.25f * clamp(onsetsPerSec / 4f));
        float valence = clamp(0.25f + 0.55f * bright + 0.30f * tonal);
        String t = (tags == null ? "" : tags).toLowerCase(Locale.US);
        Entry e = new Entry();
        e.key = key;
        e.source = "derived";
        e.energy = arousal;
        e.valence = valence;
        boolean regal = t.contains("epic") || t.contains("orchestral") || t.contains("cinematic") || t.contains("conscious");
        switch (genre == null ? "pop" : genre) {
            case "hiphop":
                if (regal) {
                    e.mood = new String[] {"epic", "regal"};
                    e.families = new String[] {"monumental geometry", "pillars", "slow wide beams", "ring shockwaves", "tunnel"};
                    e.avoid = new String[] {"pastel/soft", "rain", "aurora", "dot-sheet", "fast confetti"};
                    e.hues = new float[] {45, 355};
                    e.dark = true;
                } else {
                    e.mood = new String[] {"hard", "dark"};
                    e.families = new String[] {"tunnel", "city", "sweeping beams", "pillars"};
                    e.avoid = new String[] {"pastel/soft", "aurora", "dot-sheet", "fast confetti"};
                    e.hues = new float[] {215, 355};
                    e.dark = true;
                }
                e.motion = "heavy";
                e.kick = "punch";
                break;
            case "edm":
                e.mood = new String[] {"driving", "euphoric"};
                e.families = new String[] {"tunnel", "lattice", "hyperspace", "ring shockwaves"};
                e.avoid = new String[] {"rain", "dust"};
                e.hues = new float[] {190, 320};
                e.motion = "sharp";
                e.kick = "ring";
                break;
            case "rock":
                e.mood = new String[] {"driving", "raw"};
                e.families = new String[] {"sweeping beams", "city", "golden hour", "pillars"};
                e.avoid = new String[] {"rain", "dot-sheet", "pastel/soft"};
                e.hues = new float[] {35, 355};
                e.motion = "sharp";
                e.kick = "punch";
                break;
            case "country":
                e.mood = new String[] {"warm", "open"};
                e.families = new String[] {"golden hour", "slow wide beams", "low beams"};
                e.avoid = new String[] {"fast confetti", "lattice", "hyperspace"};
                e.hues = new float[] {38, 25};
                e.motion = "flowing";
                e.kick = "bloom";
                break;
            case "rnb":
                e.mood = new String[] {"slow", "warm", "intimate"};
                e.families = new String[] {"low beams", "golden hour", "synthwave"};
                e.avoid = new String[] {"fast confetti", "hyperspace", "lattice"};
                e.hues = new float[] {20, 330};
                e.motion = "flowing";
                e.kick = "bloom";
                break;
            case "piano":
                e.mood = new String[] {"tender", "spacious"};
                e.families = new String[] {"note-world", "aurora", "galaxy"};
                e.avoid = new String[] {"fast confetti", "ring shockwaves", "lattice"};
                e.hues = new float[] {210, 170};
                e.motion = "floating";
                e.kick = "bloom";
                break;
            case "sad":
                e.mood = new String[] {"sad", "slow"};
                e.families = new String[] {"rain", "ocean", "aurora"};
                e.avoid = new String[] {"fast confetti", "ring shockwaves", "lattice", "hyperspace"};
                e.hues = new float[] {210, 235};
                e.motion = "floating";
                e.kick = "bloom";
                break;
            case "afro":
                e.mood = new String[] {"bright", "bouncy"};
                e.families = new String[] {"call-response beams", "golden hour", "bloom", "city"};
                e.avoid = new String[] {"rain", "dust"};
                e.hues = new float[] {40, 330};
                e.motion = "sharp";
                e.kick = "punch";
                break;
            default:
                e.mood = new String[] {"bright", "pop"};
                e.families = new String[] {"bloom", "city", "synthwave", "call-response beams"};
                e.avoid = new String[] {"rain"};
                e.hues = new float[] {330, 190};
                e.motion = "flowing";
                e.kick = "punch";
        }
        if (arousal < 0.35f && !"heavy".equals(e.motion)) e.motion = "floating";
        else if (arousal > 0.75f && "flowing".equals(e.motion)) e.motion = "sharp";
        e.satCap = valence < 0.35f ? 0.5f : valence > 0.65f ? 0.58f : 0.55f;
        e.dark = e.dark || valence < 0.3f;
        e.notes = "derived from " + (genre == null ? "pop" : genre) + (t.isEmpty() ? "" : " + tags") + " and live features";
        return e;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
