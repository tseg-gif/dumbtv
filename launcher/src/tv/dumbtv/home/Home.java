package tv.dumbtv.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class Home extends Activity {
    /** How long Menu is held to open Options while the child lock is on. */
    static final long UNLOCK_MS = 3000;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService colorIo = Executors.newSingleThreadExecutor();
    private final List<TextView> chips = new ArrayList<>();
    private Config cfg;
    private TvInputManager tv;
    private int applied = -1, appsGeneration;
    private boolean resumed, landed, unlockOpened;
    private String lastFocusedKind;
    private String lastAppId;
    private String lastInputId;
    private FrameLayout root;
    private ImageView wall;
    private View shade;
    private View tint;
    private TextView clock, date;
    private Meter meter;
    private Row apps, inputs;
    private Map<String, Long> recent = new HashMap<>();
    private Tile moving;
    private String wallKey;
    private String backgroundMode;
    private ValueAnimator colorAnimation;
    private int displayedColor, targetColor;
    private boolean tintWallpaper;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Uri data = i.getData();
            loadApps(data == null ? null : data.getSchemeSpecificPart());
        }
    };

    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };

    private final TvInputManager.TvInputCallback inputWatch = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String id, int state) {
            loadInputs();
        }

        @Override
        public void onInputAdded(String id) {
            loadInputs();
        }

        @Override
        public void onInputRemoved(String id) {
            loadInputs();
        }

        public void onInputUpdated(String id) {
            loadInputs();
        }
    };

    private final class Row {
        final String kind;
        final TextView title;
        final HorizontalScrollView scroll;
        final LinearLayout strip;
        final Grid grid;
        List<Item> all = new ArrayList<>();

        Row(String kind, String name, LinearLayout parent) {
            this.kind = kind;
            int edge = dp(56), bleed = dp(14);
            title = Ui.text(Home.this, 12, Ui.FAINT, Ui.MEDIUM);
            title.setText(name.toUpperCase(Locale.ROOT));
            title.setLetterSpacing(0.14f);
            title.setPadding(edge, dp(20), edge, dp(2));
            strip = new LinearLayout(Home.this);
            strip.setClipChildren(false);
            strip.setClipToPadding(false);
            strip.setPadding(bleed, dp(12), bleed, dp(8));
            scroll = new HorizontalScrollView(Home.this) {
                /** Keeps the focused tile centered, except near either end of the row. */
                @Override
                protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                    if (getChildCount() == 0) return 0;
                    int range = Math.max(0, getChildAt(0).getWidth() - (getWidth() - getPaddingLeft() - getPaddingRight()));
                    int target = Math.max(0, Math.min(range, r.centerX() - getWidth() / 2));
                    return target - getScrollX();
                }
            };
            scroll.setFocusable(false);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.setClipChildren(false);
            scroll.setClipToPadding(false);
            scroll.setPadding(edge - bleed, 0, edge - bleed, 0);
            scroll.addView(strip, new FrameLayout.LayoutParams(WRAP, WRAP));
            grid = new Grid(Home.this);
            grid.setPadding(edge, dp(12), edge, dp(8));
            parent.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));
            parent.addView(scroll, new LinearLayout.LayoutParams(MATCH, WRAP));
            parent.addView(grid, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        /** Whichever container currently holds the tiles, following Options › Layout. */
        ViewGroup tiles() {
            return "grid".equals(cfg.get(Config.LAYOUT)) ? grid : strip;
        }

        void setVisible(boolean on) {
            boolean gridMode = "grid".equals(cfg.get(Config.LAYOUT));
            title.setVisibility(on ? View.VISIBLE : View.GONE);
            scroll.setVisibility(on && !gridMode ? View.VISIBLE : View.GONE);
            grid.setVisibility(on && gridMode ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Future<List<Item>> catalog = io.submit(new java.util.concurrent.Callable<List<Item>>() {
            @Override
            public List<Item> call() {
                return Catalog.apps(Home.this);
            }
        });
        cfg = Config.get(this);
        Future<Map<String, Long>> times = recentFor(Catalog.APPS) == null ? null
                : io.submit(new java.util.concurrent.Callable<Map<String, Long>>() {
                    @Override
                    public Map<String, Long> call() {
                        return Recent.times(Home.this);
                    }
                });
        tv = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        setContentView(build());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        Keeper.start(this);
        SelfHeal.start(this);
        if (tv != null) tv.registerCallback(inputWatch, ui);
        firstApps(catalog, times);
        loadInputs();
    }

    /**
     * Waits for the app list and loads the art of the tiles that fit on screen, so the first
     * frame is complete. After a cold start that frame is what people see first: loading in the
     * background drew the Inputs row alone, focused HDMI 1 for ~0.3 s, then popped the apps and
     * their banners in.
     */
    private void firstApps(Future<List<Item>> catalog, Future<Map<String, Long>> times) {
        try {
            apps.all = catalog.get();
            if (times != null) recent = times.get();
        } catch (ExecutionException e) {
            loadApps(null);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            loadApps(null);
            return;
        }
        int fit = getResources().getDisplayMetrics().widthPixels / (cardSize()[0] + dp(18)) + 1;
        for (Item it : Catalog.visible(cfg, Catalog.APPS, apps.all, recentFor(Catalog.APPS))) {
            if (fit-- <= 0) break;
            Catalog.art(getPackageManager(), it, "afterglow".equals(cfg.get(Config.TILE_STYLE)));
            it.artRequested = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (warm != null) warm.schedule();
        Guard.arrived(this);
        cfg.refresh();
        cfg.visited();
        if (cfg.version() != applied) apply();
        else background();
        refreshRecent();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(time, f);
        tick();
        updatePulse();
        Lights lt = Lights.get(this);
        lt.setMaster(1f);
        lt.setContext(LightCore.CTX_MUSIC);
        lt.start(pulse);
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        Lights.get(this).setContext(LightCore.CTX_AWAY); // a video app or another screen is in front: no beat lights
        if (warm != null) warm.cancel();
        if (pulse != null) pulse.stop();
        playing = false;
        syncMilk();
        ui.removeCallbacks(rehide);
        ui.removeCallbacks(nowTick);
        playing = false;
        musicHidden = false;
        if (page != null) page.setAlpha(1f);
        if (nowView != null) {
            nowView.setAlpha(0f);
            nowView.setVisibility(View.GONE);
        }
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        stopColorAnimation();
        targetColor = 0;
        unregisterReceiver(time);
        if (moving != null) stopMoving();
    }

    @Override
    protected void onDestroy() {
        Lights.get(this).stop();
        unregisterReceiver(packages);
        if (milk != null) {
            root.removeView(milk.view());
            if (scope != null) {
                root.removeView(scope);
                scope = null;
                pulse.setScope(null);
            }
            milk.destroy();
            milk = null;
        }
        if (glow != null) {
            glow.destroy();
            glow = null;
        }
        if (tv != null) tv.unregisterCallback(inputWatch);
        io.shutdownNow();
        colorIo.shutdownNow();
        if (blips != null) blips.release();
        super.onDestroy();
    }

    /**
     * Android 9 keeps a hidden window's GPU context (about 35 MB on this TV) unless memory is
     * critical. Dropping it while an app plays costs one context rebuild on the way back.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_COMPLETE) return;
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object global = wmg.getMethod("getInstance").invoke(null);
            wmg.getMethod("trimMemory", int.class).invoke(global, TRIM_MEMORY_COMPLETE);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not reachable on this build: the context simply stays
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (moving != null) stopMoving();
        if (resumed) focusFirst();
    }

    @Override
    public void onBackPressed() {
        focusFirst();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if ((e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || e.getKeyCode() == KeyEvent.KEYCODE_ENTER)
                && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() > 0 && !debugToggled
                && e.getEventTime() - e.getDownTime() >= 3000) {
            debugToggled = true;
            if (debugView != null) VizLab.toggleTrack(this); // second hold: the Viz Lab test track starts or stops
            else toggleDebug();
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            debugToggled = false;
        }
        if (musicKey(e)) return true;
        if (moving == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    shift(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    shift(1);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // In a grid, up and down move the tile a whole line; a strip has no lines.
                    if (moving.getParent() instanceof Grid) {
                        int cols = ((Grid) moving.getParent()).columns();
                        shift(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -cols : cols);
                    }
                    break;
                default:
                    stopMoving();
            }
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU && locked()) {
            // Repeats keep arriving while the button is held; the first one past the hold opens it.
            if (e.getRepeatCount() == 0) {
                unlockOpened = false;
            } else if (!unlockOpened && e.getEventTime() - e.getDownTime() >= UNLOCK_MS) {
                unlockOpened = true;
                startActivity(new Intent(this, Options.class));
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f instanceof Tile) menu((Tile) f);
            else startActivity(new Intent(this, Options.class));
            return true;
        }
        return super.onKeyDown(code, e);
    }

    /**
     * The scroll position for a focused rect {@code r}: the earliest of the top, the apps row or
     * the inputs row that still shows the row holding {@code r} whole.
     */
    private int rowTarget(ScrollView page, Rect r) {
        View content = page.getChildAt(0);
        List<int[]> rows = rowBlocks(content);
        int[] at = null;
        for (int[] b : rows) {
            if (r.centerY() >= b[0]) at = b;
        }
        if (at == null) return 0;
        int range = Math.max(0, content.getHeight() - page.getHeight());
        return Math.max(0, Math.min(range, stopFor(at, rows, page.getHeight())));
    }

    /**
     * Title top and strip bottom of each visible row, in {@code content}'s coordinates. In grid
     * mode every line of tiles is its own block (the first one starting at the row title), so
     * a grid taller than the screen still scrolls a whole line at a time.
     */
    private List<int[]> rowBlocks(View content) {
        List<int[]> rows = new ArrayList<>();
        for (Row row : new Row[] {apps, inputs}) {
            if (row.scroll.getVisibility() == View.VISIBLE) {
                rows.add(new int[] {topIn(row.title, content), topIn(row.scroll, content) + row.scroll.getHeight()});
            } else if (row.grid.getVisibility() == View.VISIBLE) {
                int gridTop = topIn(row.grid, content), lastTop = Integer.MIN_VALUE;
                for (int i = 0; i < row.grid.getChildCount(); i++) {
                    View t = row.grid.getChildAt(i);
                    if (t.getTop() == lastTop) continue;
                    int start = lastTop == Integer.MIN_VALUE ? topIn(row.title, content) : gridTop + t.getTop() - dp(12);
                    lastTop = t.getTop();
                    rows.add(new int[] {start, gridTop + t.getBottom() + dp(8)});
                }
            }
        }
        return rows;
    }

    /** The earliest of the top or a row title that shows the whole of {@code at}. */
    private static int stopFor(int[] at, List<int[]> rows, int height) {
        if (height >= at[1]) return 0;
        for (int[] b : rows) {
            if (b[0] <= at[0] && b[0] + height >= at[1]) return b[0];
        }
        return at[0];
    }

    /**
     * When the page is taller than the screen, pads its bottom just enough that every row's stop
     * is reachable. Otherwise the last stop is clamped and the clock above it shows half cut.
     */
    private void fitBottom(ScrollView page) {
        View content = page.getChildAt(0);
        if (content == null || page.getHeight() == 0) return;
        int base = dp(32), height = page.getHeight();
        int natural = content.getHeight() - content.getPaddingBottom() + base;
        int want = base;
        if (natural > height) {
            int last = 0;
            List<int[]> rows = rowBlocks(content);
            for (int[] b : rows) last = Math.max(last, stopFor(b, rows, height));
            want = base + Math.max(0, last + height - natural);
        }
        if (content.getPaddingBottom() != want) {
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(), content.getPaddingRight(), want);
        }
    }

    /** {@code v}'s top edge in {@code content}'s coordinates. */
    private static int topIn(View v, View content) {
        int y = 0;
        while (v != null && v != content) {
            y += v.getTop();
            v = (View) v.getParent();
        }
        return y;
    }

    private Pulse pulse;
    private Stage stage;
    private Milk milk;
    private Glow glow;
    private volatile String glowGenre = "unknown";
    private boolean genrePoll, glowCanvas;
    private android.widget.TextView glowLabel;
    private Scope scope;
    private boolean milkFailed;
    private String curArtist = "", curTitle = "";
    private Origin.Info curInfo;
    private int[] curPalette;

    /**
     * react_style=milkdrop: Butterchurn in a WebView replaces the Canvas engine for as long as it
     * keeps up (Milk reports slow frames and asks for the fallback).
     */
    private void syncMilk() {
        if (pulse == null) return;
        String style = cfg.get(Config.REACT_STYLE);
        if (!"milkdrop".equals(style)) milkFailed = false;
        boolean want = !milkFailed && "milkdrop".equals(style) && "on".equals(cfg.get(Config.REACT));
        if (want && milk == null) {
            try {
                milk = new Milk(this, new Milk.Listener() {
                    @Override
                    public void onFallback(String why) {
                        android.util.Log.w("dumbtv", "milkdrop fallback: " + why);
                        milkFailed = true;
                        syncMilk();
                    }
                });
                root.addView(milk.view(), root.indexOfChild(pulse) + 1, new FrameLayout.LayoutParams(MATCH, MATCH));
                milk.setSong(curArtist, curTitle);
                milk.setIdentity(curInfo);
                milk.setPalette(curPalette);
                // the song card always sits above the visuals, on a solid backing
                nowView.bringToFront();
                nowView.setElevation(dp(12));
            } catch (RuntimeException e) {
                android.util.Log.w("dumbtv", "milkdrop unavailable", e);
                milk = null;
                milkFailed = true;
                want = false;
            }
        }
        pulse.setMilk(want ? milk : null);
        stage.setMilk(want ? milk : null);
        pulse.setVisibility(want || (glow != null && !glowCanvas) ? View.INVISIBLE : View.VISIBLE);
        if (milk != null) {
            milk.setDebug("on".equals(cfg.get("react_debug", "off")));
            milk.setSyncMs(syncMs());
            milk.setComfort(cfg.get("react_comfort", "high"));
            boolean on = want && resumed && playing;
            milk.view().setVisibility(on ? View.VISIBLE : View.GONE);
            if (scope != null) scope.setVisibility(on ? View.VISIBLE : View.GONE);
            milk.setRunning(on);
        }
        if (!want) pulse.invalidate();
        syncGlow();
    }

    /** react_style=glowroom*: native GL particle rooms replace the Canvas engine while music plays. */
    private void syncGlow() {
        String style = cfg.get(Config.REACT_STYLE);
        boolean want = (style.startsWith("glow") || "gallery".equals(style)) && "on".equals(cfg.get(Config.REACT)) && milk == null;
        if (want && glow == null) {
            try {
                glow = new Glow(this, pulse);
                VizLab.glow = glow;
                root.addView(glow.view(), root.indexOfChild(pulse), new FrameLayout.LayoutParams(MATCH, MATCH));
                glow.setSong(curArtist, curTitle);
                glow.setCanvasListener(new Glow.CanvasListener() {
                    @Override
                    public void style(final String st) {
                        root.post(new Runnable() {
                            @Override
                            public void run() {
                                glowCanvas = st != null;
                                if (st != null) pulse.setStyle(st);
                                pulse.setVisibility(glowCanvas ? View.VISIBLE : View.INVISIBLE);
                            }
                        });
                    }
                });
                glow.setLabelListener(new Glow.LabelListener() {
                    @Override
                    public void label(final String t) {
                        root.post(new Runnable() {
                            @Override
                            public void run() {
                                if (t == null) {
                                    if (glowLabel != null) glowLabel.setVisibility(View.GONE);
                                    return;
                                }
                                if (glowLabel == null) {
                                    glowLabel = new android.widget.TextView(Home.this);
                                    glowLabel.setTextColor(0xCCFFFFFF);
                                    glowLabel.setTextSize(13f);
                                    glowLabel.setPadding(dp(10), dp(4), dp(10), dp(4));
                                    glowLabel.setBackgroundColor(0x66000000);
                                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM | android.view.Gravity.END);
                                    lp.setMargins(0, 0, dp(24), dp(24));
                                    root.addView(glowLabel, lp);
                                }
                                glowLabel.setText(t);
                                glowLabel.setVisibility(View.VISIBLE);
                                glowLabel.bringToFront();
                            }
                        });
                    }
                });
                nowView.bringToFront();
                nowView.setElevation(dp(12));
            } catch (RuntimeException e) {
                android.util.Log.w("dumbglow", "glow unavailable", e);
                glow = null;
                want = false;
            }
        }
        if (!want && glow != null) {
            glow.destroy();
            root.removeView(glow.view());
            glow = null;
            VizLab.glow = null;
        }
        if (glow != null) {
            glow.setStyle(style);
            float gs = 8f;
            int gv = 4;
            try {
                gs = Float.parseFloat(cfg.get("react_gallery_secs", "8"));
                gv = Integer.parseInt(cfg.get("react_gallery_variants", "4"));
            } catch (NumberFormatException e) {
                gs = 8f;
                gv = 4;
            }
            glow.setGallery(gs, gv);
            glow.setDancerForce("on".equals(cfg.get("react_dancer", "off")));
            glow.setDancerSongs(cfg.get("react_dancer_songs", "Michael Jackson|Beat It"));
            glow.setBaseSync(syncMs());
            int delayMs = 0;
            try {
                delayMs = Integer.parseInt(cfg.get("react_delay_ms", "0"));
            } catch (NumberFormatException e) {
                delayMs = 0;
            }
            glow.setDelay(delayMs);
            glow.setCalibrate("on".equals(cfg.get("react_calibrate", "off")));
            glow.setGenreSource(new Glow.GenreSource() {
                @Override
                public String genre() {
                    return glowGenre;
                }
            });
            if (!genrePoll) {
                genrePoll = true;
                root.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (glow == null) {
                            genrePoll = false;
                            return;
                        }
                        stage.primeGenre();
                        glowGenre = stage.genre();
                        root.postDelayed(this, 1000);
                    }
                }, 1000);
            }
            boolean on = resumed && playing;
            glow.view().setVisibility(on ? View.VISIBLE : View.GONE);
            glow.setRunning(on);
        }
        pulse.setVisibility((want && !glowCanvas) || milk != null ? View.INVISIBLE : View.VISIBLE);
        stage.setVisibility(want ? View.INVISIBLE : View.VISIBLE);
    }

    /** Fades the song fact in, holds it 8 s and fades it out. */
    private void showFact(String line) {
        factView.animate().cancel();
        factView.setText(line);
        factView.animate().alpha(0.8f).setDuration(700).withEndAction(new Runnable() {
            @Override
            public void run() {
                factView.animate().alpha(0f).setStartDelay(8000).setDuration(700).start();
            }
        }).start();
    }

    /** react_sync_ms in home.conf, -300..300: visuals later (+) for a soundbar or Bluetooth speaker. */
    private int syncMs() {
        try {
            return Math.max(-300, Math.min(300, Integer.parseInt(cfg.get("react_sync_ms", "0"))));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Every 12 s, while the Canvas engine is what shows, empties the GPU cache its paths fill. */
    private final Runnable gpuTrim = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            if (pulse != null && pulse.getVisibility() == View.VISIBLE) Gpu.trim();
            ui.postDelayed(this, 12000);
        }
    };

    /** Listens to the audio while the home screen is showing, when Music reactive is on. */
    private void updatePulse() {
        if (pulse == null) return;
        pulse.setAccent(cfg.accent());
        String reactStyle = cfg.get(Config.REACT_STYLE);
        // theme packs and the journey belong to the stage; the Canvas engine keeps composing its own scenes
        pulse.setStyle(Stage.isPack(reactStyle) && !"ocean".equals(reactStyle) ? "auto" : reactStyle);
        stage.setStyle(reactStyle);
        stage.setGenre(cfg.get("react_genre", "auto"));
        stage.setSpeakerMode(cfg.get(Config.REACT_SPEAKERS));
        stage.setComfort(cfg.get("react_comfort", "high"));
        stage.setPiano(cfg.get(Config.PIANO_PALETTE), cfg.get(Config.PIANO_DIM));
        stage.setSyncMs(syncMs());
        stage.setDebug("on".equals(cfg.get("react_debug", "off")));
        pulse.setColorMode(cfg.get(Config.REACT_COLOR));
        pulse.setOriginEnabled("on".equals(cfg.get(Config.REACT_ORIGIN)));
        pulse.setDebug("on".equals(cfg.get("react_debug", "off")));
        ui.removeCallbacks(gpuTrim);
        ui.postDelayed(gpuTrim, 12000);
        if (resumed && "on".equals(cfg.get(Config.REACT))) pulse.start();
        else pulse.stop();
        syncMilk();
    }

    // ---- music mode: apps out of the way, song and visualizer in front ----
    private ScrollView page;
    private LinearLayout nowView;
    private ImageView nowArt;
    private TextView nowTitle, nowArtist, factView;
    private boolean playing, musicHidden, debugToggled;
    private TextView debugView;
    private final Runnable debugTick = new Runnable() {
        @Override
        public void run() {
            if (debugView == null) return;
            String s = glow == null ? "no glow engine" : glow.debugLine();
            debugView.setText("DEBUG  " + s);
            ui.postDelayed(this, 500);
        }
    };

    /** Hold OK for 3 s: shows the current family/preset, beat confidence and fps; hold again to hide. */
    private void toggleDebug() {
        if (debugView != null) {
            ui.removeCallbacks(debugTick);
            root.removeView(debugView);
            debugView = null;
            return;
        }
        debugView = new TextView(this);
        debugView.setTextSize(16);
        debugView.setTextColor(0xFFE8E8E8);
        debugView.setBackgroundColor(0x99000000);
        debugView.setPadding(24, 16, 24, 16);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        lp.setMargins(0, 0, 48, 48);
        root.addView(debugView, lp);
        ui.post(debugTick);
    }
    private String nowKey = "";
    private String originSong = "";
    private static final long REVEAL_MS = 10000;

    private final Runnable rehide = new Runnable() {
        @Override
        public void run() {
            applyMusicMode();
        }
    };

    private final Runnable nowTick = new Runnable() {
        @Override
        public void run() {
            if (!playing) return;
            refreshNowPlaying();
            ui.postDelayed(this, 3000);
        }
    };

    /** Called when the visualizer hears music start or stop. */
    private void onMusic(boolean active) {
        playing = active;
        Lights.get(this).setPlaying(active);
        if (active) {
            // the screen stays on, and the screensaver stays away, for as long as music plays
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            ui.removeCallbacks(nowTick);
            ui.post(nowTick);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            ui.removeCallbacks(nowTick);
        }
        applyMusicMode();
        syncMilk();
        syncAurora();
    }

    private void applyMusicMode() {
        if (page == null) return;
        boolean hide = playing && "on".equals(cfg.get(Config.MUSIC_MODE));
        musicHidden = hide;
        page.animate().alpha(hide ? 0f : 1f).setDuration(350).start();
        if (hide) nowView.setVisibility(View.VISIBLE);
        nowView.animate().alpha(hide ? 1f : 0f).setDuration(350).start();
    }

    private long okDownAt;
    private boolean okFired;

    private void revealApps() {
        ui.removeCallbacks(rehide);
        ui.postDelayed(rehide, REVEAL_MS);
        if (!musicHidden) return;
        musicHidden = false;
        page.animate().alpha(1f).setDuration(250).start();
        nowView.animate().alpha(0f).setDuration(250).start();
    }

    /**
     * Any key while music mode hides the apps shows them for a while, except while only the visuals
     * show: left/right = previous/next preset or scene, up = like, down = ban, long-press OK = dim, off.
     */
    private boolean musicKey(KeyEvent e) {
        if (!playing || !"on".equals(cfg.get(Config.MUSIC_MODE))) return false;
        int code = e.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (musicHidden && ok) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    okDownAt = android.os.SystemClock.uptimeMillis();
                    okFired = false;
                } else if (!okFired && android.os.SystemClock.uptimeMillis() - okDownAt >= 700) {
                    okFired = true;
                    Toast.makeText(this, stage.cycleDim(), Toast.LENGTH_SHORT).show();
                }
            } else if (!okFired) {
                revealApps();
            }
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return musicHidden;
        if (musicHidden) {
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                if (glow != null && glow.calibrating()) { // calibration: left/right nudge the visual delay, 10 ms a press
                    int d = Math.max(-300, Math.min(300, glow.delay() + dir * 10));
                    glow.setDelay(d);
                    cfg.set("react_delay_ms", String.valueOf(d));
                    Toast.makeText(this, "Visual delay " + d + " ms", Toast.LENGTH_SHORT).show();
                    android.util.Log.i("dumbsync", "calibrate delay=" + d + "ms");
                    return true;
                }
                if (milk != null) milk.skip(dir);
                else if (glow != null) glow.skip(dir);
                else stage.skip(dir);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                String liked = milk != null ? milk.likeCurrent() : null;
                String scene = stage.like();
                if (liked != null || scene != null) {
                    Toast.makeText(this, "Liked: " + (liked != null ? liked : scene), Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                String banned = milk != null ? milk.banCurrent() : stage.ban();
                if (banned != null) Toast.makeText(this, "Banned: " + banned, Toast.LENGTH_SHORT).show();
                return true;
            }
        }
        boolean wasHidden = musicHidden;
        revealApps();
        return wasHidden;
    }

    /** Reads song, artist and cover from the active media session; needs notification access. */
    private void refreshNowPlaying() {
        String title = "Music playing", artist = "", autoText = "", autoPkg = "";
        android.graphics.Bitmap art = null;
        try {
            android.media.session.MediaSessionManager m = (android.media.session.MediaSessionManager)
                    getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<android.media.session.MediaController> list = m.getActiveSessions(
                    new android.content.ComponentName(this, Now.class));
            android.media.session.MediaController pick = null;
            for (android.media.session.MediaController c : list) {
                android.media.session.PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING) {
                    pick = c;
                    break;
                }
            }
            if (pick == null && !list.isEmpty()) pick = list.get(0);
            if (pick != null && pulse != null) {
                android.media.session.PlaybackState ps = pick.getPlaybackState();
                if (ps != null) {
                    long pos = ps.getPosition();
                    if (ps.getState() == android.media.session.PlaybackState.STATE_PLAYING && ps.getLastPositionUpdateTime() > 0) {
                        pos += (long) ((android.os.SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
                    }
                    pulse.singerSync(pos, ps.getState() == android.media.session.PlaybackState.STATE_PLAYING);
                }
            }
            android.media.MediaMetadata md = pick == null ? null : pick.getMetadata();
            if (md != null) {
                CharSequence t = md.getText(android.media.MediaMetadata.METADATA_KEY_TITLE);
                CharSequence a = md.getText(android.media.MediaMetadata.METADATA_KEY_ARTIST);
                if (t != null && t.length() > 0) title = t.toString();
                if (a != null) artist = a.toString();
                art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
                autoText = title + " " + artist + " " + md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)
                        + " " + md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                        + " " + md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
                        + " " + pick.getQueueTitle();
                autoPkg = pick.getPackageName();
            }
        } catch (SecurityException e) {
            artist = "Song titles need notification access: run tune-firetv.sh react";
        } catch (RuntimeException ignored) {
            // no session service: keep the plain text
        }
        String key = title + "|" + artist + "|" + (art != null);
        if (key.equals(nowKey)) return;
        nowKey = key;
        if (resumed && autoPkg.length() > 0) Meditation.auto(this, cfg, autoText, autoPkg);
        if (resumed && autoPkg.length() > 0 && "calm_gallery".equals(cfg.get(Config.REACT_STYLE))) Meditation.galleryOnce(this);
        if (pulse != null) {
            String song = title + "|" + artist;
            pulse.setSong(song);
            curArtist = artist;
            curTitle = title;
            if (milk != null) milk.setSong(artist, title);
            if (glow != null) glow.setSong(artist, title);
            stage.setSong(artist, title);
            final String forArtist = artist;
            if (!"on".equals(cfg.get(Config.REACT_ORIGIN))) stage.setOrigin(null, "");
            final String forLyrics = song;
            Journey.synced(this, artist, title, new Journey.TextCallback() {
                @Override
                public void got(String text) {
                    if (forLyrics.equals(originSong) && pulse != null) pulse.singerLrc(text);
                }
            });
            Journey.lyrics(this, artist, title, new Journey.TextCallback() {
                @Override
                public void got(String text) {
                    if (forLyrics.equals(originSong)) {
                        stage.setLyrics(text);
                        if (milk != null) milk.setLyrics(text);
                    }
                }
            });
            if ("on".equals(cfg.get(Config.REACT_FACTS)) && factView != null) {
                final String forFact = song;
                Facts.fetch(this, artist, title, new Facts.Callback() {
                    @Override
                    public void got(final String line) {
                        // a moment after the song starts, and only if it is still the one playing
                        factView.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (forFact.equals(originSong) && resumed && playing) showFact(line);
                            }
                        }, 2500);
                    }
                });
            }
            if (!song.equals(originSong)) {
                originSong = song;
                curInfo = null;
                if ("on".equals(cfg.get(Config.REACT_ORIGIN))) {
                    // what is known without network now; MusicBrainz is asked in the background
                    Origin.Info known = Origin.cached(this, artist, title);
                    pulse.setOrigin(known);
                    // a theme is applied only once it has resolved; until then the picture stays neutral
                    curInfo = known.resolved ? known : null;
                    if (milk != null) milk.setIdentity(curInfo);
                    if (known.resolved) stage.setOrigin(known, Origin.place(this, forArtist));
                    if (!known.resolved) {
                        final String forSong = song;
                        Origin.fetch(this, artist, title, new Origin.Callback() {
                            @Override
                            public void done(Origin.Info info) {
                                if (pulse != null && forSong.equals(originSong)) {
                                    pulse.setOrigin(info);
                                    curInfo = info;
                                    if (milk != null) milk.setIdentity(info);
                                    stage.setOrigin(info, Origin.place(Home.this, forArtist));
                                }
                            }
                        });
                    }
                } else {
                    pulse.setOrigin(null);
                    if (milk != null) milk.setIdentity(null);
                }
            }
        }
        nowTitle.setText(title);
        nowArtist.setText(artist);
        nowArt.setImageBitmap(art);
        nowArt.setVisibility(art == null ? View.GONE : View.VISIBLE);
        // cover colors, worked out off the UI thread, tint the grade
        curPalette = null;
        if (milk != null) milk.setPalette(null);
        if (art != null) {
            final android.graphics.Bitmap cover = art;
            final String forKey = key;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final int[] pal = ArtColors.extract(cover);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!forKey.equals(nowKey)) return;
                            curPalette = pal;
                            if (milk != null) milk.setPalette(pal);
                            stage.setCover(pal);
                            if (glow != null) glow.setCover(pal);
                        }
                    });
                }
            }, "dumbtv-art").start();
        }
    }

    private LinearLayout quick;
    private final List<TextView> quickViews = new ArrayList<>();
    private TextView vpnChip;

    private Aurora aurora;
    private Blips blips;
    private ViewGroup lastBlipRow;

    /** The nebula wallpaper shows while nothing plays; music fades it out under the visuals. */
    private void syncAurora() {
        if (aurora == null) return;
        int theme = Aurora.themeOf(cfg.get(Config.BACKGROUND));
        boolean show = !playing && theme >= 0;
        if (theme >= 0) aurora.setTheme(theme);
        aurora.animate().cancel();
        if (show) {
            aurora.setVisibility(View.VISIBLE);
            aurora.animate().alpha(1f).setDuration(600).start();
        } else {
            aurora.animate().alpha(0f).setDuration(600).withEndAction(new Runnable() { @Override public void run() {
                if (playing || Aurora.themeOf(cfg.get(Config.BACKGROUND)) < 0) aurora.setVisibility(View.GONE);
            } }).start();
        }
    }

    private View build() {
        if (blips == null) blips = new Blips(this, cfg);
        if (warm == null) warm = new SpotifyWarm(this, ui);
        root = new FrameLayout(this);
        wall = new ImageView(this);
        VizLab.register(this, root);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wall, new FrameLayout.LayoutParams(MATCH, MATCH));
        aurora = new Aurora(this);
        root.addView(aurora, new FrameLayout.LayoutParams(MATCH, MATCH));
        shade = new View(this);
        root.addView(shade, new FrameLayout.LayoutParams(MATCH, MATCH));
        tint = new View(this);
        root.addView(tint, new FrameLayout.LayoutParams(MATCH, MATCH));
        pulse = new Pulse(this);
        root.addView(pulse, new FrameLayout.LayoutParams(MATCH, MATCH));
        stage = new Stage(this, pulse);
        root.addView(stage, new FrameLayout.LayoutParams(MATCH, MATCH));

        ScrollView scroll = new ScrollView(this) {
            /** Scrolls in whole rows: the focused row shows complete, and a row starts at the top edge. */
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                return rowTarget(this, r) - getScrollY();
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                fitBottom(this);
            }

            /**
             * Up and down only move the focus; scrolling follows it. Left to ScrollView, a press
             * with nothing focusable that way scrolls half a screen and leaves rows cut.
             */
            @Override
            public boolean executeKeyEvent(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_PAGE_UP:
                    case KeyEvent.KEYCODE_PAGE_DOWN:
                    case KeyEvent.KEYCODE_SPACE:
                        return false;
                    default:
                        return super.executeKeyEvent(e);
                }
            }
        };
        scroll.setFocusable(false);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));
        page = scroll;

        nowView = new LinearLayout(this);
        nowView.setOrientation(LinearLayout.HORIZONTAL);
        nowView.setGravity(Gravity.CENTER_VERTICAL);
        nowView.setVisibility(View.GONE);
        nowView.setAlpha(0f);
        nowArt = new ImageView(this);
        nowArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        nowArt.setVisibility(View.GONE);
        nowArt.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(8));
            }
        });
        nowArt.setClipToOutline(true);
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        artLp.rightMargin = dp(14);
        nowView.addView(nowArt, artLp);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        nowTitle = Ui.text(this, 20, Ui.TEXT, Ui.MEDIUM);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowArtist = Ui.text(this, 14, Ui.MUTED, Ui.MEDIUM);
        nowArtist.setSingleLine(true);
        nowArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(nowTitle);
        words.addView(nowArtist);
        nowView.addView(words, new LinearLayout.LayoutParams(dp(360), WRAP));
        nowView.setPadding(dp(12), dp(10), dp(18), dp(10));
        nowView.setBackground(Ui.round(0x99000000, dp(14)));
        FrameLayout.LayoutParams nowLp = new FrameLayout.LayoutParams(WRAP, WRAP,
                Gravity.TOP | Gravity.START);
        nowLp.leftMargin = dp(48);
        nowLp.topMargin = dp(36);
        root.addView(nowView, nowLp);
        // react_facts: one soft line along the bottom, away from the now-playing card
        factView = Ui.text(this, 14, Ui.MUTED, Ui.MEDIUM);
        factView.setAlpha(0f);
        factView.setSingleLine(true);
        factView.setGravity(Gravity.CENTER);
        factView.setShadowLayer(dp(4), 0, 0, 0xCC000000);
        FrameLayout.LayoutParams factLp = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM);
        factLp.bottomMargin = dp(40);
        root.addView(factView, factLp);
        pulse.setListener(new Pulse.Listener() {
            @Override
            public void onActive(boolean active) {
                onMusic(active);
            }
        });

        LinearLayout column = new LinearLayout(this) {
            @Override
            public View focusSearch(View focused, int direction) {
                // The Quick launch pills stop at both ends instead of jumping to the header chips.
                int pillAt = quickViews.indexOf(focused);
                if (pillAt >= 0) {
                    if (direction == View.FOCUS_LEFT && pillAt == 0) return focused;
                    if (direction == View.FOCUS_RIGHT && pillAt == quickViews.size() - 1) return focused;
                }
                if (direction == View.FOCUS_DOWN && chips.contains(focused)
                        && !quickViews.contains(focused) && quick != null
                        && quick.getVisibility() == View.VISIBLE) {
                    View pill = nearest(quick, focused);
                    if (pill != null) return pill;
                }
                if (direction == View.FOCUS_DOWN && chips.contains(focused)) {
                    View remembered = rememberedTile(lastFocusedKind);
                    if (remembered != null) return remembered;
                    View below = nearest(apps.tiles(), focused);
                    if (below != null) return below;
                }
                if (focused instanceof Tile
                        && focused.getParent() instanceof ViewGroup) {
                    Tile tile = (Tile) focused;
                    ViewGroup parent = (ViewGroup) tile.getParent();
                    int idx = parent.indexOfChild(tile);
                    int count = parent.getChildCount();
                    boolean grid = parent instanceof Grid;
                    int cols = grid ? ((Grid) parent).columns() : 1;

                    // Left and right stop at the edge of a row instead of jumping away
                    // from it: no tile of another row takes the focus.
                    if (direction == View.FOCUS_LEFT) {
                        boolean edge = grid ? idx % cols == 0 : idx == 0;
                        if (edge) return focused;
                    }
                    if (direction == View.FOCUS_RIGHT) {
                        boolean edge = grid
                                ? (idx + 1) % cols == 0 || idx == count - 1
                                : idx == count - 1;
                        if (edge) return focused;
                    }

                    // Down from the last line of the apps row lands on the input that was
                    // focused last, not on whichever one happens to be closest.
                    if (direction == View.FOCUS_DOWN
                            && Catalog.APPS.equals(tile.kind)) {
                        boolean hasBelow = grid && belowInGrid(parent, tile);
                        if (!hasBelow) {
                            View remembered = rememberedTile(Catalog.INPUTS);
                            if (remembered != null) return remembered;
                            View below = nearest(inputs.tiles(), focused);
                            if (below != null) return below;
                        }
                    }
                    // Up from the first line of the inputs row goes back to the app that was
                    // focused last.
                    if (direction == View.FOCUS_UP
                            && Catalog.INPUTS.equals(tile.kind)) {
                        boolean hasAbove = grid && aboveInGrid(parent, tile);
                        if (!hasAbove) {
                            View remembered = rememberedTile(Catalog.APPS);
                            if (remembered != null) return remembered;
                            View above = nearest(apps.tiles(), focused);
                            if (above != null) return above;
                        }
                    }
                }
                return super.focusSearch(focused, direction);
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(0, dp(40), 0, dp(32));
        scroll.addView(column, new FrameLayout.LayoutParams(MATCH, WRAP));

        LinearLayout head = new LinearLayout(this);
        head.setClipChildren(false);
        head.setClipToPadding(false);
        head.setGravity(Gravity.TOP);
        head.setPadding(dp(56), 0, dp(56), dp(12));
        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        clock = Ui.text(this, 52, Ui.TEXT, Ui.LIGHT);
        date = Ui.text(this, 15, Ui.MUTED, Ui.MEDIUM);
        date.setPadding(dp(2), dp(8), 0, 0);
        when.addView(clock);
        when.addView(date);
        meter = new Meter(this);
        meter.setVisibility(View.GONE);
        LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(WRAP, WRAP);
        under.topMargin = dp(12);
        under.leftMargin = dp(2);
        when.addView(meter, under);
        head.addView(when, new LinearLayout.LayoutParams(0, WRAP, 1f));
        vpnChip = chip("VPN", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Quick.run(Home.this, cfg, "vpn");
            }
        });
        head.addView(vpnChip);
        head.addView(chip("Options", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Home.this, Options.class));
            }
        }));
        head.addView(chip("Settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings();
            }
        }));
        column.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        quick = new LinearLayout(this);
        quick.setClipChildren(false);
        quick.setClipToPadding(false);
        quick.setPadding(dp(46), 0, dp(56), dp(2));
        quick.setVisibility(View.GONE);
        column.addView(quick, new LinearLayout.LayoutParams(MATCH, WRAP));

        apps = new Row(Catalog.APPS, "Apps", column);
        inputs = new Row(Catalog.INPUTS, "Inputs", column);
        stage.setCard(nowView);
        return root;
    }

    /** True when the grid has another line below {@code tile}. */
    private static boolean belowInGrid(ViewGroup parent, Tile tile) {
        int cols = ((Grid) parent).columns();
        return parent.indexOfChild(tile) + cols < parent.getChildCount();
    }

    /** True when the grid has another line above {@code tile}. */
    private static boolean aboveInGrid(ViewGroup parent, Tile tile) {
        int cols = ((Grid) parent).columns();
        return parent.indexOfChild(tile) - cols >= 0;
    }

    /**
     * The child of {@code strip} horizontally closest to {@code from}, restricted to its top
     * visual row so a wrapped grid does not match a tile several rows further down.
     */
    private static View nearest(ViewGroup strip, View from) {
        int n = strip.getChildCount();
        if (n == 0) return null;
        int[] at = new int[2];
        from.getLocationOnScreen(at);
        int x = at[0] + from.getWidth() / 2;
        int[] ys = new int[n];
        int topY = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            strip.getChildAt(i).getLocationOnScreen(at);
            ys[i] = at[1];
            topY = Math.min(topY, at[1]);
        }
        View best = null;
        int bestGap = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (ys[i] != topY) continue;
            View c = strip.getChildAt(i);
            c.getLocationOnScreen(at);
            int gap = Math.abs(at[0] + c.getWidth() / 2 - x);
            if (gap < bestGap) {
                bestGap = gap;
                best = c;
            }
        }
        return best;
    }

    private boolean locked() {
        return "on".equals(cfg.get(Config.LOCK));
    }

    private TextView chip(String label, View.OnClickListener click) {
        TextView v = Ui.text(this, 14, Ui.TEXT, Ui.MEDIUM);
        v.setText(label);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(18), dp(9), dp(18), dp(9));
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean has) {
                view.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(110).start();
                if (has) {
                    focusBackground(null);
                    ViewGroup row = (ViewGroup) view.getParent();
                    if (blips != null) blips.playPill(row == null ? 0 : row.indexOfChild(view));
                    lastBlipRow = null;
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(10);
        lp.topMargin = dp(6);
        v.setLayoutParams(lp);
        chips.add(v);
        return v;
    }

    /** Rebuilds the Quick launch pills from home.conf. */
    private void renderQuick() {
        for (TextView v : quickViews) chips.remove(v);
        quickViews.clear();
        quick.removeAllViews();
        boolean show = "on".equals(cfg.get(Config.QUICK)) && !locked();
        if (show) {
            for (final Quick.Entry e : Quick.entries(this, cfg)) {
                TextView pill = chip(e.label, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Quick.run(Home.this, cfg, e.id);
                    }
                });
                quick.addView(pill);
                quickViews.add(pill);
            }
        }
        quick.setVisibility(quickViews.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Sets the VPN chip: a green dot and "VPN on" while a VPN network is up. */
    private void updateVpn() {
        boolean on = Quick.vpnOn(this);
        SpannableString s = new SpannableString("\u25CF  VPN " + (on ? "on" : "off"));
        s.setSpan(new android.text.style.ForegroundColorSpan(on ? 0xFF34C759 : 0xFF8A8A90), 0, 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        vpnChip.setText(s);
        vpnChip.setVisibility(locked() || !"on".equals(cfg.get(Config.VPN_CHIP)) ? View.GONE : View.VISIBLE);
    }

    private void apply() {
        applied = cfg.version();
        int accent = cfg.accent();
        renderQuick();
        updateVpn();
        updatePulse();
        for (TextView c : chips) {
            Ui.focusStyle(c, accent, 0x22A5B4FF, dp(18));
            c.setVisibility(locked() ? View.GONE : View.VISIBLE);
        }
        updateVpn();
        meter.setVisibility("on".equals(cfg.get(Config.MEMORY)) ? View.VISIBLE : View.GONE);
        meter.setAccent(accent);
        render(apps);
        render(inputs);
        background();
        tick();
    }

    /** Rebuilds the app list, keeping loaded art for every package except {@code changed}. */
    private void loadApps(String changed) {
        int generation = ++appsGeneration;
        io.execute(new Runnable() { @Override public void run() {
            final List<Item> list = Catalog.apps(Home.this);
            ui.post(new Runnable() { @Override public void run() {
                if (generation != appsGeneration || isDestroyed()) return;
                for (Item fresh : list) {
                    for (Item old : apps.all) {
                        if (old.id.equals(fresh.id) && !old.id.equals(changed) && old.artRequested) {
                            fresh.banner = old.banner;
                            fresh.icon = old.icon;
                            fresh.artRequested = true;
                            fresh.backgroundColor = old.backgroundColor;
                        }
                    }
                }
                apps.all = list;
                render(apps);
            } });
        } });
    }

    private void loadInputs() {
        inputs.all = Catalog.inputs(this, tv);
        render(inputs);
    }

    /** The last-used times to sort {@code kind} by, or null when it keeps the user's order. */
    private Map<String, Long> recentFor(String kind) {
        return Catalog.APPS.equals(kind) && "recent".equals(cfg.get(Config.ORDER)) ? recent : null;
    }

    /** With App order › Recently used, re-sorts the apps row on the way back home. */
    private void refreshRecent() {
        if (recentFor(Catalog.APPS) == null) return;
        io.execute(new Runnable() { @Override public void run() {
            final Map<String, Long> times = Recent.times(Home.this);
            ui.post(new Runnable() { @Override public void run() {
                if (isDestroyed() || recentFor(Catalog.APPS) == null) return;
                List<Item> before = Catalog.visible(cfg, Catalog.APPS, apps.all, recent);
                recent = times;
                if (!before.equals(Catalog.visible(cfg, Catalog.APPS, apps.all, recent))) render(apps);
            } });
        } });
    }

    private void render(Row row) {
        if (moving != null && moving.kind.equals(row.kind)) stopMoving();
        View focused = getCurrentFocus();
        String keep = focused instanceof Tile && ((Tile) focused).kind.equals(row.kind)
                ? ((Tile) focused).item.id : null;
        boolean gridMode = "grid".equals(cfg.get(Config.LAYOUT));
        ViewGroup target = gridMode ? row.grid : row.strip;
        row.strip.removeAllViews();
        row.grid.removeAllViews();

        boolean enabled = !Catalog.INPUTS.equals(row.kind) || "on".equals(cfg.get(Config.INPUTS));
        List<Item> shown = enabled ? Catalog.visible(cfg, row.kind, row.all, recentFor(row.kind))
                : new ArrayList<>();
        int[] size = cardSize();
        int w = size[0], h = Catalog.APPS.equals(row.kind) ? size[1] : Math.round(size[1] * 0.56f);
        String labels = cfg.get(Config.LABELS);
        int accent = cfg.accent();
        int index = 0, restore = -1;
        for (Item it : shown) {
            Tile t = new Tile(this, it, row.kind, Catalog.label(cfg, it), w, h, labels, accent);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open((Tile) v);
                }
            });
            t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean has) {
                    if (has) {
                        Tile tile = (Tile) v;
                        lastFocusedKind = tile.kind;
                        if (Catalog.APPS.equals(tile.kind)) {
                            lastAppId = tile.item.id;
                        } else {
                            lastInputId = tile.item.id;
                        }
                        focusBackground(tile.item);
                        ViewGroup row = (ViewGroup) tile.getParent();
                        blips.play(row == lastBlipRow ? row.indexOfChild(tile) : -1);
                        lastBlipRow = row;
                    }
                }
            });
            t.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (!locked()) menu((Tile) v);
                    return true;
                }
            });
            if (gridMode) {
                target.addView(t, new ViewGroup.LayoutParams(WRAP, WRAP));
            } else {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
                lp.rightMargin = dp(18);
                target.addView(t, lp);
            }
            if (it.id.equals(keep)) restore = index;
            index++;
            if (Catalog.APPS.equals(row.kind) && (!it.artRequested
                    || "afterglow".equals(cfg.get(Config.TILE_STYLE)) && it.icon == null)) loadArt(it);
        }
        row.setVisible(!shown.isEmpty());
        if (shown.isEmpty()) {
            // Nothing is left to come back to: forget the tile that used to be here.
            if (Catalog.APPS.equals(row.kind)) lastAppId = null;
            else lastInputId = null;
        }
        if (restore >= 0) {
            target.getChildAt(restore).requestFocus();
        } else if (keep != null && target.getChildCount() > 0) {
            target.getChildAt(0).requestFocus();
        } else if (getCurrentFocus() == null || !landed) {
            focusFirst();
        }
    }

    private void loadArt(Item it) {
        it.artRequested = true;
        boolean cornerIcon = "afterglow".equals(cfg.get(Config.TILE_STYLE));
        io.execute(new Runnable() { @Override public void run() {
            Catalog.art(getPackageManager(), it, cornerIcon);
            ui.post(new Runnable() { @Override public void run() {
                ViewGroup tiles = apps.tiles();
                for (int i = 0; i < tiles.getChildCount(); i++) tiles.getChildAt(i).invalidate();
                if (focusedItem() == it) focusBackground(it);
            } });
        } });
    }

    private Item focusedItem() {
        View focused = getCurrentFocus();
        return focused instanceof Tile ? ((Tile) focused).item : null;
    }

    private void focusBackground(Item it) {
        String mode = cfg.get(Config.BACKGROUND);
        if (!resumed || !"adaptive".equals(mode) && !"adaptive-tint".equals(mode)) return;
        if ("adaptive-tint".equals(mode) && !tintWallpaper) return;
        if (it != null && it.info != null && it.backgroundColor == 0 && !it.colorRequested
                && (it.banner != null || it.icon != null)) {
            it.colorRequested = true;
            colorIo.execute(new Runnable() { @Override public void run() {
                final int color = FocusColor.from(it.banner != null ? it.banner : it.icon);
                ui.post(new Runnable() { @Override public void run() {
                    it.backgroundColor = color;
                    if (focusedItem() == it) focusBackground(it);
                } });
            } });
        }
        int color = it == null || it.backgroundColor == 0 ? FocusColor.FALLBACK : it.backgroundColor;
        if ("adaptive-tint".equals(mode)) color = FocusColor.tint(color);
        if (color == targetColor) return;
        targetColor = color;
        stopColorAnimation();
        if (Settings.Global.getFloat(getContentResolver(), "animator_duration_scale", 1f) == 0f) {
            setAdaptiveColor(color);
            return;
        }
        colorAnimation = ValueAnimator.ofArgb(displayedColor, color);
        colorAnimation.setDuration(250);
        colorAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                setAdaptiveColor((Integer) a.getAnimatedValue());
            }
        });
        colorAnimation.start();
    }

    private void setAdaptiveColor(int color) {
        displayedColor = color;
        if ("adaptive-tint".equals(backgroundMode)) tint.setBackgroundColor(color);
        else root.setBackgroundColor(color);
    }

    private void stopColorAnimation() {
        if (colorAnimation != null) {
            colorAnimation.cancel();
            colorAnimation = null;
        }
    }

    private int[] cardSize() {
        switch (cfg.get(Config.TILES)) {
            case "small":
                return new int[] {dp(144), dp(81)};
            case "large":
                return new int[] {dp(224), dp(126)};
            default:
                return new int[] {dp(180), dp(101)};
        }
    }

    /**
     * The tile of {@code kind} that was focused last, or null when there is none left.
     */
    private View rememberedTile(String kind) {
        if (kind == null) return null;
        String id = Catalog.APPS.equals(kind) ? lastAppId : lastInputId;
        if (id == null) return null;
        View v = findTile(kind, id);
        if (v == null) {
            // The tile is gone: the row was rebuilt without it.
            if (Catalog.APPS.equals(kind)) lastAppId = null;
            else lastInputId = null;
        }
        return v;
    }

    /** The tile with this id in the row of {@code kind}, or null when there is none. */
    private View findTile(String kind, String id) {
        if (kind == null || id == null) return null;
        Row row = Catalog.APPS.equals(kind) ? apps : inputs;
        ViewGroup tiles = row.tiles();
        for (int i = 0; i < tiles.getChildCount(); i++) {
            View v = tiles.getChildAt(i);
            if (v instanceof Tile
                    && ((Tile) v).kind.equals(kind)
                    && ((Tile) v).item.id.equals(id)) {
                return v;
            }
        }
        return null;
    }

    private void focusFirst() {
        apps.scroll.scrollTo(0, 0);
        inputs.scroll.scrollTo(0, 0);
        View first = apps.tiles().getChildAt(0);
        if (first == null) first = inputs.tiles().getChildAt(0);
        landed = apps.tiles().getChildCount() > 0;
        if (first == null) first = chips.get(0);
        first.requestFocus();
    }

    private void tick() {
        String mode = cfg.get(Config.CLOCK);
        boolean h24 = "24h".equals(mode) || "auto".equals(mode) && DateFormat.is24HourFormat(this);
        Date now = new Date();
        if (h24) {
            clock.setText(DateFormat.format("HH:mm", now));
        } else {
            SpannableString half = new SpannableString(" " + DateFormat.format("a", now));
            half.setSpan(new RelativeSizeSpan(0.36f), 0, half.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            clock.setText(DateFormat.format("h:mm", now));
            clock.append(half);
        }
        Locale locale = Locale.getDefault();
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), now));
        if (meter.getVisibility() == View.VISIBLE) {
            meter.update((android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE));
        }
        if (vpnChip != null) updateVpn();
        // hourly and time-of-day rotations change while the screen stays open; a no-op otherwise
        background();
    }

    /** A new scroll sound was picked: rebuild the notes and play a short preview of the new voice. */
    void applySound() {
        if (blips != null) blips.release();
        blips = new Blips(this, cfg);
        ui.postDelayed(new Runnable() { @Override public void run() { if (blips != null) blips.playPill(0); } }, 700);
        ui.postDelayed(new Runnable() { @Override public void run() { if (blips != null) blips.playPill(2); } }, 1000);
        ui.postDelayed(new Runnable() { @Override public void run() { if (blips != null) blips.playPill(4); } }, 1300);
    }

    /** A dialog list moved to item pos: play its note. */
    void tick(int pos) {
        if (blips != null) blips.playPill(pos);
    }

    private SpotifyWarm warm;

    void applyWallpaper() {
        background();
    }

    private void background() {
        String mode = cfg.get(Config.BACKGROUND);
        boolean image = "image".equals(mode) || "adaptive-tint".equals(mode);
        File file = image ? cfg.wallpaper() : null;
        tintWallpaper = "adaptive-tint".equals(mode) && file != null;
        if ("adaptive".equals(mode)) {
            if (!mode.equals(backgroundMode)) {
                stopColorAnimation();
                targetColor = 0;
                root.setBackgroundColor(FocusColor.FALLBACK);
                displayedColor = FocusColor.FALLBACK;
                tint.setBackgroundColor(Color.TRANSPARENT);
            }
            backgroundMode = mode;
            focusBackground(focusedItem());
        } else if ("adaptive-tint".equals(mode)) {
            root.setBackgroundColor(file == null ? cfg.background() : Color.BLACK);
            if (!mode.equals(backgroundMode) || file == null) {
                stopColorAnimation();
                targetColor = 0;
                tint.setBackgroundColor(Color.TRANSPARENT);
                displayedColor = Color.TRANSPARENT;
            }
            backgroundMode = mode;
            if (tintWallpaper) focusBackground(focusedItem());
        } else {
            stopColorAnimation();
            targetColor = 0;
            backgroundMode = mode;
            tint.setBackgroundColor(Color.TRANSPARENT);
            root.setBackgroundColor(cfg.background());
        }
        syncAurora();
        int dim = Integer.parseInt(cfg.get(Config.DIM));
        shade.setBackgroundColor(file == null ? Color.TRANSPARENT : Color.argb(dim * 255 / 100, 0, 0, 0));
        String key = file == null ? null : file.getPath() + ":" + file.lastModified();
        if (key == null ? wallKey == null : key.equals(wallKey)) return;
        wallKey = key;
        if (file == null) {
            wall.setImageDrawable(null);
            return;
        }
        io.execute(new Runnable() { @Override public void run() {
            final Bitmap b = decode(file, getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            ui.post(new Runnable() { @Override public void run() {
                if (key.equals(wallKey)) wall.setImageBitmap(b);
            } });
        } });
    }

    private static Bitmap decode(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) {
            o.inSampleSize *= 2;
        }
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /**
     * Fire OS answers the generic settings action with a stub that reopens Amazon's home, which
     * Guard then replaces with ours. The TV settings entry point opens the real settings screen.
     */
    private void openSettings() {
        Intent tvSettings = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.LEANBACK_SETTINGS");
        boolean found = tvSettings.resolveActivity(getPackageManager()) != null;
        launch(found ? tvSettings : new Intent(Settings.ACTION_SETTINGS), null);
    }

    private void open(Tile t) {
        if (Catalog.APPS.equals(t.kind)) {
            launch(new Intent(t.item.intent), t.item.id);
            return;
        }
        Intent own = Input.intent(this, cfg, t.item, Catalog.label(cfg, t.item));
        // The built-in screen goes on top of this one in the same task, so BACK returns here and
        // the HOME takeover's singleTask relaunch clears it. NEW_TASK | RESET_TASK_IF_NEEDED on an
        // activity of the task that is already in front only brings that task forward and starts
        // nothing (ActivityStarter.complyActivityFlags), so it is started plainly.
        if (own != null) startActivity(own);
        else launch(new Intent(t.item.intent), null);
    }

    private void launch(Intent i, String pkg) {
        if (Guard.AMAZON.equals(pkg)) Guard.pause();
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            if (pkg != null) Recent.opened(this, pkg);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Could not open that", Toast.LENGTH_SHORT).show();
        }
    }

    private void menu(Tile t) {
        boolean app = Catalog.APPS.equals(t.kind);
        String[] actions = app
                ? new String[] {"Move", "Rename", "Hide", "App info"}
                : new String[] {"Move", "Rename", "Hide"};
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Catalog.label(cfg, t.item))
                .setItems(actions, new android.content.DialogInterface.OnClickListener() {
                  @Override
                  public void onClick(android.content.DialogInterface d, int which) {
                    switch (which) {
                        case 0:
                            if (t.getParent() == null) break;
                            if (app && recentFor(t.kind) != null) {
                                Toast.makeText(Home.this, "App order is Recently used. Pick Your order "
                                        + "in Options to move tiles.", Toast.LENGTH_LONG).show();
                                break;
                            }
                            moving = t;
                            t.setMoving(true);
                            break;
                        case 1:
                            rename(t);
                            break;
                        case 2:
                            Catalog.setShown(cfg, t.kind, t.item, false);
                            render(app ? apps : inputs);
                            Toast.makeText(Home.this, "Hidden. Bring it back from Options.", Toast.LENGTH_SHORT).show();
                            break;
                        default:
                            launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", t.item.id, null)), null);
                    }
                  }
                })
                .show();
    }

    private void rename(Tile t) {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setText(Catalog.label(cfg, t.item));
        field.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field);
        String key = "name." + t.item.id;
        AlertDialog d = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Rename")
                .setView(box)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int w) {
                        String name = field.getText().toString().trim();
                        cfg.set(key, name.equals(t.item.label.toString()) ? null : name);
                        apply();
                    }
                })
                .setNeutralButton("Original", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int w) {
                        cfg.set(key, null);
                        apply();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void shift(int by) {
        ViewGroup strip = (ViewGroup) moving.getParent();
        int from = strip.indexOfChild(moving), to = from + by;
        if (to < 0 || to >= strip.getChildCount()) return;
        ViewGroup.LayoutParams lp = moving.getLayoutParams();
        strip.removeViewAt(from);
        strip.addView(moving, to, lp);
        moving.requestFocus();
    }

    private void stopMoving() {
        Tile t = moving;
        moving = null;
        t.setMoving(false);
        ViewGroup strip = (ViewGroup) t.getParent();
        if (strip == null) return;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < strip.getChildCount(); i++) ids.add(((Tile) strip.getChildAt(i)).item.id);
        Catalog.saveOrder(cfg, t.kind, ids);
        applied = cfg.version();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
