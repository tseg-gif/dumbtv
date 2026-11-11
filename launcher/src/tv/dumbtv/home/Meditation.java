package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The Meditation folder: a grid of calm scenes (rain, candle, aurora ...) drawn by CalmGl, optionally over
 * a looping muted clip pushed with `tune-firetv.sh meditation`. Menu or OK inside a scene opens its
 * settings (sleep timer, thunder glow, clock, ...); any other key wakes it from a sleep fade; Back leaves.
 */
public final class Meditation extends Activity {
    private static final String TAG = "dumbcalm";
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final String DIR = "/sdcard/Movies/meditation";
    static final String EXTRA_SCENE = "scene";
    static final String EXTRA_SLEEP = "sleep";

    /** id, title, what it is, gradient top, gradient bottom, backdrop file name (null = generated only), "hide" = hidden without it. */
    private static final String[][] SCENES = {
        {"canopy", "Canopy Rain", "looking up through birch and maple, rain falling toward you", "#4a6a4a", "#0a140c", null, null},
        {"autumn", "Autumn Canopy", "the same sky patch in autumn leaves", "#8a5a2a", "#140c06", null, null},
        {"puddle", "Night Puddle", "low over a wet street at night", "#6a4a38", "#0c0d14", "night_street", null},
        {"garden", "Green Garden Rain", "foliage through wet glass", "#4f7f58", "#10281a", "green_garden", null},
        {"leaf", "Leaf Downpour", "macro of wet leaves in heavy rain", "#3f7a3c", "#0d2410", "leaf_closeup", "hide"},
        {"avenue", "Green Avenue", "tree-lined wet road", "#58805a", "#0f1d15", "green_avenue", "hide"},
        {"roof", "Roof Downpour", "water pouring off a roof edge", "#3a4a63", "#10141c", "roof_porch", null},
        {"gold", "Golden Cloud", "a glowing cloud shedding gold rain", "#c8923a", "#150f0a", null, null},
        {"orb", "Breathing Orb", "4-7-8 breathing pace", "#d98a55", "#1b1015", null, null},
        {"candle", "Candle Glow", "a single flame", "#b0652a", "#120b08", null, null},
        {"aurora", "Slow Aurora", "northern lights over a ridge", "#2f9e70", "#0a1424", null, null},
        {"ocean", "Night Ocean", "moonlight on dark water", "#5b6d94", "#0b1424", null, null},
        {"fireflies", "Fireflies", "slow lights in the dark", "#52703a", "#0a1618", null, null},
    };

    private static String titleOf(String id) {
        for (String[] s : SCENES) if (s[0].equals(id)) return s[1];
        return id;
    }

    /** Scenes shown in the grid: the two photo-only scenes only appear once their photo is on the TV. */
    private static java.util.List<String[]> visible() {
        java.util.ArrayList<String[]> out = new java.util.ArrayList<String[]>();
        for (String[] s : SCENES) if (s[6] == null || clipFor(s[0]) != null) out.add(s);
        return out;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Config cfg;
    private FrameLayout root, sceneBox;
    private LinearLayout grid;
    private GLSurfaceView gl;
    private CalmGl renderer;
    private CalmAudio audio;
    private TextView clock, cue;
    private String current;
    private boolean direct, dialogOpen;
    private int sleepMinutes;
    private String lastClockText = "";
    private boolean gallery;
    private int galIx;
    private TextView galLabel;

    // ---- entry points ------------------------------------------------------------------------

    static void open(Context c) {
        open(c, null);
    }

    static void open(Context c, String scene) {
        Intent i = new Intent(c, Meditation.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (scene != null) i.putExtra(EXTRA_SCENE, scene);
        c.startActivity(i);
    }

    private static long lastGallery;

    /** react_style=calm_gallery: opens the calm gallery once a minute at most, only from Home. */
    static void galleryOnce(Home home) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastGallery != 0 && now - lastGallery < 60000) return;
        lastGallery = now;
        open(home, "gallery");
    }

    // ---- Spotify auto-start --------------------------------------------------------------------

    private static String lastAuto = "";

    /**
     * Called by Home (only while Home itself is on screen, so no other app is ever interrupted) when the
     * song changes. With med_auto=offer it asks; with med_auto=start it just opens the fitting scene.
     */
    static void auto(final Home home, Config cfg, String text, String pkg) {
        String mode = cfg.get("med_auto", "off");
        if ("off".equals(mode) || !"com.spotify.tv.android".equals(pkg)) return;
        String t = text.toLowerCase(Locale.US);
        if (!Pattern.compile("rain|thunder|storm|sleep|nature|ambient|forest").matcher(t).find()) return;
        if (t.equals(lastAuto)) return;
        lastAuto = t;
        final String id = Pattern.compile("thunder|storm").matcher(t).find() ? "roof" : "garden";
        Log.i(TAG, "auto-start: " + mode + " " + id + " for \"" + text.trim() + "\"");
        if ("start".equals(mode)) {
            open(home, id);
            return;
        }
        final AlertDialog d = new AlertDialog.Builder(home, Ui.DIALOG)
                .setTitle("Calming sounds playing")
                .setMessage("Start the " + (id.equals("roof") ? "Roof Downpour" : "Rain Garden") + " meditation scene?")
                .setPositiveButton("Start", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface x, int w) {
                        open(home, id);
                    }
                })
                .setNegativeButton("Not now", null)
                .create();
        d.show();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (d.isShowing()) d.dismiss();
            }
        }, 20000);
    }

    // ---- clips -------------------------------------------------------------------------------

    /** The photo (.jpg/.png) or clip (.mp4 ...) named like the scene's backdrop key (night_street, green_garden ...). */
    static String clipFor(String id) {
        for (String[] s : SCENES) {
            if (!s[0].equals(id) || s[5] == null) continue;
            File[] files = new File(DIR).listFiles();
            if (files == null) return null;
            String best = null;
            boolean bestVid = false;
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.US);
                if (!n.startsWith(s[5]) || !n.matches(".*\\.(jpg|jpeg|png|mp4|mkv|webm|mov)$")) continue;
                boolean vid = n.matches(".*\\.(mp4|mkv|webm|mov)$");
                if (best == null || (vid && !bestVid)) {
                    best = f.getAbsolutePath();
                    bestVid = vid;
                }
            }
            return best;
        }
        return null;
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = Config.get(this);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        audio = new CalmAudio();
        buildGrid();
        String s = getIntent().getStringExtra(EXTRA_SCENE);
        if (s != null) {
            direct = true;
            showScene(s);
            int m = getIntent().getIntExtra(EXTRA_SLEEP, 0);
            if (m > 0) setSleep(m);
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        String s = i.getStringExtra(EXTRA_SCENE);
        if (s != null) {
            direct = true;
            showScene(s);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gl != null) {
            gl.onResume();
            startAudio();
            ui.post(tick);
            ui.removeCallbacks(galTick);
            if (gallery) ui.postDelayed(galTick, 10000);
        }
    }

    @Override
    protected void onPause() {
        Lights.get(this).setMaster(1f);
        super.onPause();
        ui.removeCallbacks(tick);
        ui.removeCallbacks(galTick);
        ui.removeCallbacks(cycleTick);
        if (gl != null) {
            renderer.release();
            gl.onPause();
        }
        audio.stop();
    }

    // ---- grid --------------------------------------------------------------------------------

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private void buildGrid() {
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(60), dp(40), dp(60), dp(30));
        TextView title = Ui.text(this, 30, Ui.TEXT, Ui.LIGHT);
        title.setText("Meditation");
        grid.addView(title);
        TextView sub = Ui.text(this, 14, Ui.MUTED, Ui.LIGHT);
        sub.setText("Calm scenes. OK opens one. In a scene, Menu or OK for the sleep timer and settings.");
        sub.setPadding(0, dp(6), 0, dp(24));
        grid.addView(sub);
        int per = 4;
        LinearLayout row = null;
        final java.util.List<String[]> vis = visible();
        for (int i = 0; i <= vis.size(); i++) {
            if (i % per == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(MATCH, 0, 1f);
                rp.bottomMargin = dp(16);
                grid.addView(row, rp);
            }
            View tile = i < vis.size() ? tile(vis.get(i)) : settingsTile();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, MATCH, 1f);
            lp.rightMargin = dp(16);
            row.addView(tile, lp);
        }
        root.addView(grid, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    private View tile(final String[] s) {
        String clip = clipFor(s[0]);
        return makeTile(s[1], s[2] + (s[5] == null ? "" : clip != null ? "\nyour file: " + new File(clip).getName() : "\ngenerated backdrop (add " + s[5] + ".jpg)"),
                Color.parseColor(s[3]), Color.parseColor(s[4]), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showScene(s[0]);
                    }
                });
    }

    private View settingsTile() {
        return makeTile("Settings", "sleep timer, rain intensity, clock,\nthunder glow, lite mode, auto-start", 0xFF3a3a42, 0xFF16161a, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settings();
            }
        });
    }

    private View makeTile(String name, String about, int top, int bottom, View.OnClickListener click) {
        final LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.BOTTOM);
        t.setPadding(dp(18), dp(14), dp(18), dp(16));
        final GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bottom});
        bg.setCornerRadius(dp(14));
        t.setBackground(bg);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(click);
        TextView n = Ui.text(this, 20, Ui.TEXT, Ui.MEDIUM);
        n.setText(name);
        TextView a = Ui.text(this, 12, Ui.MUTED, Ui.LIGHT);
        a.setText(about);
        a.setPadding(0, dp(4), 0, 0);
        t.addView(n);
        t.addView(a);
        final int accent = cfg.accent();
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(120).start();
                bg.setStroke(has ? dp(3) : 0, accent);
            }
        });
        return t;
    }

    // ---- scene -------------------------------------------------------------------------------

    private void showScene(String id) {
        gallery = "gallery".equals(id);
        if (gallery) {
            java.util.List<String[]> v = visible();
            id = v.get(0)[0];
            galIx = 1;
        }
        current = id;
        grid.setVisibility(View.GONE);
        if (sceneBox == null) {
            sceneBox = new FrameLayout(this);
            gl = new GLSurfaceView(this);
            gl.setEGLContextClientVersion(2);
            renderer = new CalmGl(this, audio, new CalmGl.Listener() {
                @Override
                public void onStats(String scene, float fps, int w, int h) {
                    stats(scene, fps, w, h);
                }
            });
            gl.setRenderer(renderer);
            gl.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
            sceneBox.addView(gl, new FrameLayout.LayoutParams(MATCH, MATCH));
            clock = Ui.text(this, 20, 0x66FFFFFF, Ui.LIGHT);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END);
            cp.setMargins(0, dp(28), dp(40), 0);
            sceneBox.addView(clock, cp);
            cue = Ui.text(this, 18, 0x55FFFFFF, Ui.LIGHT);
            FrameLayout.LayoutParams qp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            qp.bottomMargin = dp(48);
            sceneBox.addView(cue, qp);
            galLabel = Ui.text(this, 18, 0x99FFFFFF, Ui.LIGHT);
            FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
            gp.setMargins(dp(40), dp(28), 0, 0);
            sceneBox.addView(galLabel, gp);
            root.addView(sceneBox, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        galLabel.setVisibility(gallery ? View.VISIBLE : View.GONE);
        galLabel.setText(titleOf(id));
        ui.removeCallbacks(galTick);
        if (gallery) ui.postDelayed(galTick, 10000);
        ui.removeCallbacks(cycleTick);
        if (!gallery) ui.postDelayed(cycleTick, CYCLE_MS);
        renderer.setIntensity(cfg.get("med_int", "steady"));
        sceneBox.setVisibility(View.VISIBLE);
        lite(cfg.get("med_lite", "off").equals("on"));
        renderer.setThunder("on".equals(cfg.get("med_thunder", "off")));
        renderer.setScene(id, clipFor(id));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        gl.onResume();
        startAudio();
        ui.removeCallbacks(tick);
        ui.post(tick);
        Log.i(TAG, "scene start: " + id + " clip=" + clipFor(id) + " lite=" + cfg.get("med_lite", "off")
                + " thunder=" + cfg.get("med_thunder", "off") + " sleep=off");
    }

    /** react_style=calm_gallery: every scene for 10 s, its name in the corner. */
    private final Runnable galTick = new Runnable() {
        @Override
        public void run() {
            if (!gallery || renderer == null) return;
            java.util.List<String[]> v = visible();
            String id = v.get(galIx++ % v.size())[0];
            current = id;
            renderer.setScene(id, clipFor(id));
            galLabel.setText(titleOf(id));
            Log.i(TAG, "gallery scene: " + id + " asset=" + clipFor(id));
            ui.postDelayed(this, 10000);
        }
    };

    private static final String[] MAIN = {"canopy", "garden", "roof", "autumn"};
    private static final long CYCLE_MS = 10L * 60 * 1000;

    /** Every 10 minutes the four main rain scenes hand over to the next one through a 5 s dip to dark. */
    private final Runnable cycleTick = new Runnable() {
        @Override
        public void run() {
            if (gallery || renderer == null || current == null) return;
            int at = -1;
            for (int i = 0; i < MAIN.length; i++) if (MAIN[i].equals(current)) at = i;
            if (at < 0 || !"on".equals(cfg.get("med_cycle", "on"))) return;
            final String next = MAIN[(at + 1) % MAIN.length];
            renderer.dip(true);
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (renderer == null || gallery) return;
                    current = next;
                    renderer.setScene(next, clipFor(next));
                    renderer.dip(false);
                    Log.i(TAG, "scene change: " + next);
                    ui.postDelayed(cycleTick, CYCLE_MS);
                }
            }, 2500);
        }
    };

    private void startAudio() {
        boolean ok = audio.start(this);
        Log.i(TAG, "audio capture " + (ok ? "on" : "unavailable (steady default rain)"));
    }

    private void leaveScene() {
        if (direct) {
            finish();
            return;
        }
        current = null;
        gallery = false;
        ui.removeCallbacks(galTick);
        setSleep(0);
        ui.removeCallbacks(tick);
        renderer.release();
        gl.onPause();
        audio.stop();
        sceneBox.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        grid.setVisibility(View.VISIBLE);
        View first = ((ViewGroup) grid.getChildAt(2)).getChildAt(0);
        if (first != null) first.requestFocus();
        Log.i(TAG, "scene stop");
    }

    private void lite(boolean on) {
        renderer.setLite(on);
    }

    /** Logged every ~5 s (the renderer itself steps its resolution down when the frame rate falls under 45). */
    private void stats(final String scene, final float fps, final int w, final int h) {
        Log.i(TAG, String.format(Locale.US, "scene=%s fps=%.1f bg=%dx%d audio=%s sleep=%s lite=%s", scene, fps, w, h,
                audio.heard() ? "heard" : "none(default rain)", renderer.sleepState(), cfg.get("med_lite", "off")));
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (current == null || renderer == null) return;
            float s = renderer.sleepFactor();
            Lights.get(Meditation.this).setContext(LightCore.CTX_MEDITATION);
            Lights.get(Meditation.this).setMaster(s);
            boolean showClock = "on".equals(cfg.get("med_clock", "off"));
            clock.setVisibility(showClock ? View.VISIBLE : View.GONE);
            if (showClock) {
                String t = new SimpleDateFormat("h:mm", Locale.US).format(new Date());
                if (!t.equals(lastClockText)) {
                    lastClockText = t;
                    clock.setText(t);
                }
                clock.setAlpha(0.1f + 0.9f * s);
            }
            boolean showCue = "orb".equals(current) && "on".equals(cfg.get("med_cue", "off"));
            cue.setVisibility(showCue ? View.VISIBLE : View.GONE);
            if (showCue) {
                int[] k = new int[1];
                CalmGl.orbPhase((android.os.SystemClock.elapsedRealtime() % 3600000L) / 1000f, k);
                cue.setText(k[0] == 0 ? "breathe in" : k[0] == 1 ? "hold" : "breathe out");
                cue.setAlpha(s);
            }
            ui.postDelayed(this, 500);
        }
    };

    // ---- remote keys ---------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (current == null || dialogOpen || sceneBox == null || sceneBox.getVisibility() != View.VISIBLE) {
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (renderer.sleepActive() && renderer.sleepFactor() < 0.999f) {
            setSleep(0);
            Log.i(TAG, "woken by remote, sleep timer off");
            return true;
        }
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK) {
            leaveScene();
        } else if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            settings();
        }
        return true;
    }

    // ---- settings ----------------------------------------------------------------------------

    private void setSleep(int minutes) {
        sleepMinutes = minutes;
        if (renderer != null) renderer.setSleep(minutes);
        Log.i(TAG, "sleep timer: " + (minutes == 0 ? "off" : minutes + " min"));
    }

    private String onOff(String key) {
        return cfg.get(key, "off");
    }

    private void toggle(String key) {
        cfg.set(key, "on".equals(onOff(key)) ? "off" : "on");
    }

    private void settings() {
        final String auto = cfg.get("med_auto", "off");
        final String[] items = {
            "Sleep timer: " + (sleepMinutes == 0 ? "off" : sleepMinutes + " min"),
            "Rain intensity: " + cfg.get("med_int", "steady"),
            "Soft distant thunder glow: " + onOff("med_thunder"),
            "Clock: " + onOff("med_clock"),
            "Breathing text cue (orb): " + onOff("med_cue"),
            "Lite mode: " + onOff("med_lite"),
            "Auto-start from Spotify: " + auto,
        };
        dialogOpen = true;
        AlertDialog dlg = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Meditation settings")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                sleepPicker();
                                return;
                            case 1:
                                String it = cfg.get("med_int", "steady");
                                it = "drizzle".equals(it) ? "steady" : "steady".equals(it) ? "heavy" : "drizzle";
                                cfg.set("med_int", it);
                                if (renderer != null) renderer.setIntensity(it);
                                break;
                            case 2:
                                toggle("med_thunder");
                                if (renderer != null) renderer.setThunder("on".equals(onOff("med_thunder")));
                                break;
                            case 3:
                                toggle("med_clock");
                                break;
                            case 4:
                                toggle("med_cue");
                                break;
                            case 5:
                                toggle("med_lite");
                                if (renderer != null) lite("on".equals(onOff("med_lite")));
                                break;
                            default:
                                cfg.set("med_auto", "off".equals(auto) ? "offer" : "offer".equals(auto) ? "start" : "off");
                        }
                        settings();
                    }
                })
                .setNegativeButton("Close", null)
                .create();
        track(dlg);
        dlg.show();
    }

    private AlertDialog openDlg;

    /** Clears dialogOpen only when the dialog that closes is the latest one shown (a menu re-opens itself after a toggle). */
    private void track(final AlertDialog d) {
        openDlg = d;
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface x) {
                if (openDlg == d) {
                    openDlg = null;
                    dialogOpen = false;
                }
            }
        });
    }

    private void sleepPicker() {
        final int[] mins = {0, 15, 30, 60, 90};
        String[] names = {"Off", "15 minutes", "30 minutes", "60 minutes", "90 minutes"};
        int cur = 0;
        for (int i = 0; i < mins.length; i++) if (mins[i] == sleepMinutes) cur = i;
        dialogOpen = true;
        AlertDialog dlg = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Sleep timer")
                .setSingleChoiceItems(names, cur, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        setSleep(mins[which]);
                        d.dismiss();
                    }
                })
                .create();
        track(dlg);
        dlg.show();
    }
}
