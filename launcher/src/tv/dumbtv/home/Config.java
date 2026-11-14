package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * All settings live in one plain {@code key=value} file in the app's external files dir,
 * so {@code adb pull}/{@code adb push} is the whole export/import story and a hand edit is
 * picked up on the next return to the home screen. Where adb cannot reach that folder
 * (Fire OS 8), the same file can be pushed into the app's media folder instead.
 */
final class Config {
    static final String FILE = "home.conf";

    static final class Choice {
        final String key, title;
        final String[] values, names;
        final String fallback;

        Choice(String key, String title, String fallback, String[] values, String[] names) {
            this.key = key;
            this.title = title;
            this.fallback = fallback;
            this.values = values;
            this.names = names;
        }

        String name(String value) {
            int i = Arrays.asList(values).indexOf(value);
            return i < 0 ? value : names[i];
        }
    }

    static final Choice ACCENT = new Choice("accent", "Accent color", "cyan",
            new String[] {"white", "blue", "cyan", "green", "amber", "red", "pink", "violet", "neon"},
            new String[] {"White", "Blue", "Cyan", "Green", "Amber", "Red", "Pink", "Violet", "Neon green"});
    static final Choice TILES = new Choice("tiles", "Tile size", "medium",
            new String[] {"small", "medium", "large"},
            new String[] {"Small", "Medium", "Large"});
    static final Choice LAYOUT = new Choice("layout", "Layout", "rows",
            new String[] {"rows", "grid"},
            new String[] {"Rows", "Grid"});
    static final Choice ORDER = new Choice("order", "App order", "custom",
            new String[] {"custom", "recent"},
            new String[] {"Your order", "Recently used"});
    static final Choice LABELS = new Choice("labels", "App names", "focus",
            new String[] {"focus", "always", "never"},
            new String[] {"On focus", "Always", "Never"});
    static final Choice CLOCK = new Choice("clock", "Clock", "auto",
            new String[] {"auto", "24h", "12h"},
            new String[] {"System", "24-hour", "12-hour"});
    /** Free memory under the date, for TVs that are short of it. Shows only; frees nothing. */
    static final Choice MEMORY = new Choice("memory", "Memory meter", "off",
            new String[] {"off", "on"},
            new String[] {"Hidden", "Shown"});
    static final Choice INPUTS = new Choice("inputs", "Inputs row", "on",
            new String[] {"on", "off"},
            new String[] {"Shown", "Hidden"});
    static final Choice SWITCH = new Choice("switch", "Open inputs with", "auto",
            new String[] {"auto", "tv", "home"},
            new String[] {"Automatic", "The TV's app", "dumbtv home"});
    static final Choice BACKGROUND = new Choice("background", "Background", "nebula",
            new String[] {"black", "graphite", "midnight", "image", "adaptive", "adaptive-tint", "nebula", "wp_la", "wp_synth", "wp_ocean", "wp_aurora", "wp_tokyo", "wp_dunes", "wp_peaks", "wp_space", "wp_la_blue", "wp_la_rose", "wp_la_night", "wp_la_teal"},
            new String[] {"Black", "Graphite", "Midnight", "Image file", "Adaptive solid", "Adaptive wallpaper tint", "Nebula (animated)", "Los Angeles sunset", "Synthwave", "Deep ocean", "Northern lights", "Tokyo neon rain", "Desert dusk", "Mountain lake", "Ringed planet", "LA blue hour", "LA rose haze", "LA night freeway", "LA teal and gold"});
    static final Choice DIM = new Choice("dim", "Image dim", "40",
            new String[] {"0", "20", "40", "60", "80"},
            new String[] {"0%", "20%", "40%", "60%", "80%"});

    static final Choice ROTATE = new Choice("rotate", "Wallpaper rotation", "off",
            new String[] {"off", "visit", "hourly", "daily", "daytime"},
            new String[] {"Off", "Every return home", "Every hour", "Every day", "By time of day"});

    /**
     * For a TV children use: hides Options and Settings and turns off the tile menu, so the home
     * screen only opens apps and inputs. Holding Menu for {@link Home#UNLOCK_MS} opens Options.
     */
    static final Choice LOCK = new Choice("lock", "Child lock", "off",
            new String[] {"off", "on"},
            new String[] {"Off", "On"});

    static final Choice TILE_STYLE = new Choice("tile_style", "App tile style", "original",
            new String[] {"original", "afterglow"}, new String[] {"Original", "Afterglow"});
    /** The pills under the clock: Appstore, Spotify and whatever else Options picks. */
    static final Choice QUICK = new Choice("quick_row", "Quick launch row", "on",
            new String[] {"on", "off"}, new String[] {"Shown", "Hidden"});
    /** A chip beside Options that shows whether a VPN is up, and opens the VPN app. */
    static final Choice VPN_CHIP = new Choice("vpn_chip", "VPN indicator", "on",
            new String[] {"on", "off"}, new String[] {"Shown", "Hidden"});
    /** Bars and a bass pulse behind the tiles, driven by the audio playing (needs RECORD_AUDIO via adb). */
    static final Choice REACT = new Choice("react", "Music reactive", "on",
            new String[] {"on", "off"}, new String[] {"On", "Off"});
    static final Choice REACT_STYLE = new Choice("react_style", "Visualizer style", "auto",
            new String[] {"auto", "bars", "wave", "mountains", "ring", "orbit", "fountain", "warp", "ripples",
                "matrix", "spiro", "lightning", "tunnel", "aurora", "fireworks", "helix", "blob", "kaleido",
                "synth", "beams", "rays", "scenic", "beach", "misty", "ocean", "snow", "rain",
                "fireflies", "coast", "moonlit", "milkdrop", "money", "journey", "love", "street", "party", "grief",
                "faith", "fire", "city", "space", "glowroom", "glowroom_dunes", "glowroom_tunnel", "glowroom_planet",
                "glowroom_rain", "glowroom_galaxy", "glowroom_beam", "gallery", "calm_gallery",
                "glow_firefly_meadow", "glow_aurora_ridge", "glow_night_ocean", "glow_sunset_rays", "glow_synth_road", "glow_hyperspace", "glow_helix", "glow_mandala_bloom", "glow_lanterns", "glow_embers", "glow_plexus", "glow_city_drive", "glow_vortex", "glow_ink_flow", "glow_eclipse", "glow_crystal_tunnel", "glow_pond_ripples", "glow_lissajous_ribbons", "glow_snowfall", "glow_bubbles", "glow_pendulum_wave", "glow_torus_knot", "glow_supernova", "glow_sound_lines", "glow_atom_orbits", "glow_jellyfish"},
            new String[] {"Different every song", "Bars", "Wave", "Mountains", "Ring", "Orbits", "Fountain",
                "Warp", "Ripples", "LED matrix", "Spirograph", "Lightning", "Tunnel", "Aurora",
                "Fireworks", "Helix", "Blob", "Kaleidoscope", "Synthwave", "Laser beams", "Sunburst", "Nature scenes only", "Sunset beach",
                "Misty mountains", "Night ocean", "Snowy peaks", "Rainy hills", "Firefly forest",
                "Golden coast", "Moonlit shore", "Milkdrop (experimental)", "Money", "City journey", "Love", "Street / night drive",
                "Party / club", "Rain / grief", "Faith / sunrise", "Fire", "City lights", "Space",
                "Glow rooms (GL particles)", "Glow: dune glide", "Glow: tunnel rush", "Glow: planet",
                "Glow: light rain", "Glow: galaxy", "Glow: beam hall", "Gallery (every scene)", "Meditation gallery (every calm scene)",
                "Glow: firefly meadow", "Glow: aurora ridge", "Glow: night ocean", "Glow: sunset rays", "Glow: synth road", "Glow: hyperspace", "Glow: helix", "Glow: mandala bloom", "Glow: lanterns", "Glow: embers", "Glow: plexus", "Glow: city drive", "Glow: vortex", "Glow: ink flow", "Glow: eclipse", "Glow: crystal tunnel", "Glow: pond ripples", "Glow: lissajous ribbons", "Glow: snowfall", "Glow: bubbles", "Glow: pendulum wave", "Glow: torus knot", "Glow: supernova", "Glow: sound lines", "Glow: atom orbits", "Glow: jellyfish"});
    /** Looks the artist up on MusicBrainz (country, genre) and themes the visuals to match. */
    /** Woofer and tweeter cones on the left and right edges that move with the kicks and hats. */
    static final Choice REACT_SPEAKERS = new Choice("react_speakers", "Side speakers", "auto",
            new String[] {"auto", "on", "off"}, new String[] {"Auto (hip-hop, bass)", "On", "Off"});
    static final Choice REACT_ORIGIN = new Choice("react_origin", "Visuals match the artist's origin", "on",
            new String[] {"on", "off"}, new String[] {"On", "Off"});
    /** One line about the song from Wikipedia when it starts (uses the internet). */
    static final Choice REACT_FACTS = new Choice("react_facts", "Song fact captions", "off",
            new String[] {"off", "on"}, new String[] {"Off", "On"});
    /** Human figures in the visuals (the singer silhouette, the Beat It dancer). Off: only shapes, lights and particles. */
    static final Choice SHOW_PEOPLE = new Choice("show_people", "Show people", "off",
            new String[] {"off", "on"}, new String[] {"Off", "On"});
    static final Choice REACT_COLOR = new Choice("react_color", "Visualizer colors", "soft",
            new String[] {"soft", "vivid", "accent"},
            new String[] {"Soft (easy on the eyes)", "Vivid", "Accent only"});
    static final Choice REACT_CINEMA = new Choice("react_cinema", "Cinema clips in the music rotation", "on",
            new String[] {"on", "off"}, new String[] {"On (when clips are on the TV)", "Off"});
    /** Piano scenes: varied colors, or only warm ones (rose to amber, no blue light). */
    static final Choice PIANO_PALETTE = new Choice("piano_palette", "Piano colors", "auto",
            new String[] {"auto", "warm"}, new String[] {"Varied", "Warm, low blue (easy on the eyes)"});
    static final Choice PIANO_DIM = new Choice("piano_dim", "Piano brightness", "dim",
            new String[] {"bright", "normal", "dim", "night"}, new String[] {"Bright", "Normal", "Dim", "Night (darkest)"});
    /** While music plays: hide the apps, show the song and the visualizer. Any key brings them back. */
    static final Choice MUSIC_MODE = new Choice("music_mode", "Hide apps while music plays", "on",
            new String[] {"on", "off"}, new String[] {"On", "Off"});
    /** The sound while moving along a row: soft marimba notes, a short buzz, or nothing. */
    static final Choice SCROLL_SOUND = new Choice("scroll_sound", "Scroll sound", "tune",
            new String[] {"tune", "pop", "boing", "bloop", "arcade", "bongo", "notes", "glass", "marimba", "kalimba", "bubble", "synth", "buzz", "off"},
            new String[] {"Melody", "Pop", "Boing", "Bloop", "Arcade", "Bongo", "Harmony chimes", "Glass pluck", "Marimba", "Kalimba", "Water drops", "Synth arpeggio", "Buzz", "Off"});
    static final Choice[] CHOICES = {ACCENT, TILES, LAYOUT, ORDER, LABELS, CLOCK, MEMORY, INPUTS, SWITCH, BACKGROUND, DIM, ROTATE, LOCK, TILE_STYLE, QUICK, VPN_CHIP, REACT, REACT_STYLE, REACT_SPEAKERS, SCROLL_SOUND, REACT_COLOR, REACT_CINEMA, PIANO_PALETTE, PIANO_DIM, REACT_ORIGIN, REACT_FACTS, SHOW_PEOPLE, MUSIC_MODE};

    /** Folder of wallpapers, next to home.conf, that Background › Image file rotates through. */
    static final String WALLPAPERS = "wallpapers";
    private static final String OFFSET = "wallpaper.offset";

    private static final int[] ACCENTS = {
        0xFFFFFFFF, 0xFF3D8BFF, 0xFF2BC4E8, 0xFF34C759, 0xFFFFB340, 0xFFFF5147, 0xFFFF5C9D, 0xFFA07CFF, 0xFF39FF14,
    };
    private static final int[] BACKGROUNDS = {0xFF000000, 0xFF141518, 0xFF0B1220, 0xFF000000, 0xFF141518, 0xFF141518, 0xFF05060F, 0xFF05060F, 0xFF05060F, 0xFF05060F, 0xFF05060F, 0xFF05060F, 0xFF05060F, 0xFF05060F, 0xFF05060F, 0xFF05060F, 0xFF05060F, 0xFF05060F, 0xFF05060F};

    private static Config instance;

    final File dir;
    private final File file;
    /** The app's media folder, /sdcard/Android/media/tv.dumbtv.home: adb can write there. */
    private final File shared;
    private final File sharedFile;
    /** What was last applied from the media folder, so the same push is not applied twice. */
    private final File appliedFile;
    private final Map<String, String> values = new LinkedHashMap<>();
    private long signature = Long.MIN_VALUE;
    private int version;
    private long visits;

    static Config get(Context c) {
        if (instance == null) instance = new Config(c.getApplicationContext());
        return instance;
    }

    private Config(Context c) {
        File external = c.getExternalFilesDir(null);
        dir = external != null ? external : c.getFilesDir();
        file = new File(dir, FILE);
        // Fire OS 8 (Android 11) hides /sdcard/Android/data/<pkg> from adb, so nothing can be
        // pushed into the app's own folder. The app's media folder is still writable by adb,
        // so it is used as a second source: wallpapers are read from it, and a settings file
        // pushed there is merged in once.
        File[] media = c.getExternalMediaDirs();
        shared = (media != null && media.length > 0 && media[0] != null) ? media[0] : null;
        sharedFile = shared == null ? null : new File(shared, FILE);
        appliedFile = new File(dir, FILE + ".applied");
        refresh();
    }

    /** Changes every time a setting changes, from the UI or from the file on disk. */
    int version() {
        return version;
    }

    void refresh() {
        long sig = signature();
        if (sig == signature) return;
        signature = sig;
        values.clear();
        if (file.isFile()) read();
        if (mergeShared()) save();
        version++;
    }

    /** Both files count: a settings file pushed into the media folder has to be noticed too. */
    private long signature() {
        long sig = file.lastModified() * 31 + file.length();
        if (sharedFile != null) sig = sig * 31 + hash(sharedFile);
        return sig;
    }

    /** Content hash, so a push is noticed even when it carries an old timestamp. */
    private static long hash(File f) {
        if (!f.isFile()) return 0;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return Arrays.hashCode(out.toByteArray());
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Takes the settings from a file pushed into the media folder, the one folder adb can still
     * write on Fire OS 8. The push is applied once: what was applied is remembered, so the same
     * file is not applied again and a later change on the TV is not overwritten by it.
     */
    private boolean mergeShared() {
        if (sharedFile == null || !sharedFile.isFile()) return false;
        Map<String, String> incoming = parse(sharedFile);
        if (incoming.isEmpty()) return false;
        if (incoming.equals(parse(appliedFile))) return false;
        values.putAll(incoming);
        writeSettings(appliedFile, incoming);
        return true;
    }

    private void read() {
        values.putAll(parse(file));
    }

    private static Map<String, String> parse(File f) {
        Map<String, String> out = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (line.startsWith("#") || eq <= 0) continue;
                out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            Log.w("dumbtv", "could not read " + f, e);
        }
        return out;
    }

    private void save() {
        writeSettings(file, values);
        signature = signature();
    }

    private static void writeSettings(File target, Map<String, String> settings) {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write("# dumbtv home. Edit on the TV (Options) or here; unknown keys are kept.\n");
            for (Map.Entry<String, String> e : settings.entrySet()) {
                w.write(e.getKey() + "=" + e.getValue() + "\n");
            }
        } catch (IOException e) {
            Log.w("dumbtv", "could not write " + tmp, e);
            return;
        }
        if (!tmp.renameTo(target)) Log.w("dumbtv", "could not replace " + target);
    }

    String get(String key, String fallback) {
        String v = values.get(key);
        return v == null || v.isEmpty() ? fallback : v;
    }

    String get(Choice c) {
        String v = values.get(c.key);
        return v != null && Arrays.asList(c.values).contains(v) ? v : c.fallback;
    }

    void set(String key, String value) {
        if (value == null || value.isEmpty()) values.remove(key);
        else values.put(key, value);
        version++;
        save();
    }

    List<String> list(String key) {
        List<String> out = new ArrayList<>();
        for (String s : get(key, "").split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    void setList(String key, List<String> items) {
        // String.join is Android 8+; TextUtils.join keeps Fire OS 6 (Android 7.1) working.
        set(key, TextUtils.join(",", items));
    }

    void removePrefix(String... prefixes) {
        for (Iterator<String> it = values.keySet().iterator(); it.hasNext(); ) {
            String k = it.next();
            for (String p : prefixes) {
                if (k.startsWith(p)) {
                    it.remove();
                    break;
                }
            }
        }
        version++;
        save();
    }

    int accent() {
        String v = values.get(ACCENT.key);
        if (v != null && v.startsWith("#")) {
            try {
                return Color.parseColor(v) | 0xFF000000;
            } catch (IllegalArgumentException ignored) {
                // falls through to the named palette
            }
        }
        return ACCENTS[Arrays.asList(ACCENT.values).indexOf(get(ACCENT))];
    }

    int background() {
        return BACKGROUNDS[Arrays.asList(BACKGROUND.values).indexOf(get(BACKGROUND))];
    }

    /** Counts returns to the home screen, for the "visit" rotation. */
    void visited() {
        visits++;
    }

    /** Steps the rotation forward by one, so the next wallpaper shows now. */
    void nextWallpaper() {
        set(OFFSET, String.valueOf(offset() + 1));
    }

    private int offset() {
        try {
            return Integer.parseInt(get(OFFSET, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 0 morning (6-13 h), 1 afternoon (13-20 h), 2 night. */
    static int period() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 6 && h < 13 ? 0 : h >= 13 && h < 20 ? 1 : 2;
    }

    private static final String[][] PERIOD_WORDS = {
        {"morning", "day"},
        {"afternoon", "evening"},
        {"night", "midnight"},
    };

    /**
     * Files whose name carries a word for {@code period}; empty when others are tagged but not
     * this one, null when no file is tagged at all (then the period just indexes the pool).
     */
    private static List<File> forPeriod(List<File> pool, int period) {
        List<File> fit = new ArrayList<>();
        boolean tagged = false;
        for (File f : pool) {
            // Whole words only, so "sunday.jpg" or "holiday.jpg" is not a morning picture.
            List<String> words = Arrays.asList(f.getName().toLowerCase(Locale.ROOT).split("[^a-z]+"));
            for (int p = 0; p < PERIOD_WORDS.length; p++) {
                for (String w : PERIOD_WORDS[p]) {
                    if (words.contains(w)) {
                        tagged = true;
                        if (p == period && !fit.contains(f)) fit.add(f);
                    }
                }
            }
        }
        return tagged ? fit : null;
    }

    List<File> wallpapers() {
        List<File> out = new ArrayList<>();
        addPictures(new File(dir, WALLPAPERS), out);
        if (shared != null) addPictures(new File(shared, WALLPAPERS), out);
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                int byName = a.getName().compareToIgnoreCase(b.getName());
                return byName != 0 ? byName : a.getPath().compareTo(b.getPath());
            }
        });
        return out;
    }

    private static void addPictures(File folder, List<File> out) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                    || n.endsWith(".webp"))) {
                out.add(f);
            }
        }
    }

    File wallpaper() {
        List<File> pool = wallpapers();
        if (!pool.isEmpty()) {
            long now = System.currentTimeMillis();
            long step;
            String mode = get(ROTATE);
            if ("daytime".equals(mode)) {
                int period = period();
                List<File> fit = forPeriod(pool, period);
                if (fit == null) {
                    // Keep Options › Next wallpaper working here too.
                    int n = pool.size();
                    return pool.get(((period + offset()) % n + n) % n);
                }
                if (!fit.isEmpty()) pool = fit;
                mode = "visit";
            }
            switch (mode) {
                case "visit": step = visits; break;
                case "hourly": step = now / 3_600_000L; break;
                case "daily": step = (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L; break;
                default: step = 0;
            }
            int n = pool.size();
            return pool.get((int) (((step + offset()) % n + n) % n));
        }
        for (String name : new String[] {"wallpaper.jpg", "wallpaper.jpeg", "wallpaper.png", "wallpaper.webp"}) {
            File f = new File(dir, name);
            if (f.isFile()) return f;
            if (shared != null) {
                File s = new File(shared, name);
                if (s.isFile()) return s;
            }
        }
        return null;
    }
}
