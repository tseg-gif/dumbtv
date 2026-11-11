package tv.dumbtv.home;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;

/** Settings for the two Bluetooth light bars: scan, assign Left and Right, pick the protocol, test. */
public final class LightsActivity extends Activity {
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String[] NAME_HINTS = {"ELK-BLEDOM", "ELK-BLE", "LEDBLE", "BLEDOM", "TRIONES", "MELK", "LOTUS"};

    private Config cfg;
    private Lights lights;
    private LinearLayout list, found;
    private TextView leftStatus, rightStatus, scanInfo;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, ScanResult> results = new LinkedHashMap<String, ScanResult>();
    private boolean scanning;
    private ScanCallback scanCb;
    private long[] taps = new long[0];
    private boolean tapMode;
    private long tapStart;
    private final ArrayList<Long> tapOffsets = new ArrayList<Long>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.get(this);
        lights = Lights.get(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0C0C0E);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(56), dp(40), dp(56), dp(40));
        scroll.addView(list);
        setContentView(scroll);

        TextView t = Ui.text(this, 30, Ui.TEXT, Ui.LIGHT);
        t.setText("Lights (Bluetooth bars)");
        t.setPadding(dp(16), 0, 0, dp(8));
        list.addView(t);
        TextView note = Ui.text(this, 14, Ui.MUTED, Ui.MEDIUM);
        note.setText("Close the Lotus Lantern phone app first: the lamp accepts only one connection.\n"
            + "If scanning finds nothing: adb shell pm grant tv.dumbtv.home android.permission.ACCESS_COARSE_LOCATION");
        note.setPadding(dp(16), 0, 0, dp(16));
        list.addView(note);

        leftStatus = Ui.text(this, 14, Ui.MUTED, Ui.MEDIUM);
        rightStatus = Ui.text(this, 14, Ui.MUTED, Ui.MEDIUM);
        leftStatus.setPadding(dp(16), 0, 0, 0);
        rightStatus.setPadding(dp(16), 0, 0, dp(12));
        list.addView(leftStatus);
        list.addView(rightStatus);

        list.addView(cycle("Mode", Lights.K_MODE, new String[] {"0", "1", "2"}, new String[] {"Follow", "Calm", "Off"}, "0"));
        list.addView(cycle("Protocol", Lights.K_PROFILE, new String[] {"0", "1", "2"},
            new String[] {"Auto", "A: ELK-BLEDOM (FFF0/FFF3)", "B: Triones (FFE5/FFE9)"}, "0"));
        list.addView(cycle("When a video app or sports is in front", Lights.K_IDLE, new String[] {"warm", "off"},
            new String[] {"Dim warm colour", "Off"}, "warm"));
        String[] dv = new String[21], dl = new String[21];
        for (int i = 0; i < 21; i++) {
            int ms = -100 + i * 20;
            dv[i] = String.valueOf(ms);
            dl[i] = ms + " ms";
        }
        list.addView(cycle("Light delay (flashes earlier by this much)", Lights.K_DELAY, dv, dl, "80"));
        list.addView(action("Test colours (red, green, blue, white)", new Runnable() {
            @Override
            public void run() {
                lights.start(null);
                lights.test();
            }
        }));
        list.addView(action("Tap test: press OK on every blink (info only)", new Runnable() {
            @Override
            public void run() {
                startTapTest();
            }
        }));
        list.addView(action("Scan for bars", new Runnable() {
            @Override
            public void run() {
                scan();
            }
        }));
        list.addView(action("Forget both bars", new Runnable() {
            @Override
            public void run() {
                cfg.set(Lights.K_LEFT, "");
                cfg.set(Lights.K_RIGHT, "");
                refreshStatus();
            }
        }));
        scanInfo = Ui.text(this, 14, Ui.MUTED, Ui.MEDIUM);
        scanInfo.setPadding(dp(16), dp(12), 0, dp(4));
        list.addView(scanInfo);
        found = new LinearLayout(this);
        found.setOrientation(LinearLayout.VERTICAL);
        list.addView(found);
        lights.start(null);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(statusTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScan();
        ui.removeCallbacks(statusTick);
    }

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            ui.postDelayed(this, 1000);
        }
    };

    private void refreshStatus() {
        leftStatus.setText("Left:  " + lights.status(0));
        rightStatus.setText("Right: " + lights.status(1) + "     writes/s: " + lights.writeRate());
    }

    // ---- rows

    private TextView label(String s) {
        TextView t = Ui.text(this, 18, Ui.TEXT, Ui.MEDIUM);
        t.setText(s);
        return t;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(760), WRAP);
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);
        row.setBackgroundColor(Color.TRANSPARENT);
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                v.setBackgroundColor(has ? 0x33FFFFFF : Color.TRANSPARENT);
            }
        });
        return row;
    }

    private View action(String text, final Runnable run) {
        LinearLayout r = row();
        r.addView(label(text), new LinearLayout.LayoutParams(0, WRAP, 1f));
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run.run();
            }
        });
        return r;
    }

    private View cycle(final String title, final String key, final String[] values, final String[] labels, final String def) {
        final LinearLayout r = row();
        final TextView name = label(title);
        final TextView val = Ui.text(this, 18, Ui.MUTED, Ui.MEDIUM);
        r.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1f));
        r.addView(val);
        final Runnable show = new Runnable() {
            @Override
            public void run() {
                String cur = cfg.get(key, def);
                for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) val.setText(labels[i]);
            }
        };
        show.run();
        final int[] dir = {1};
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step(key, values, def, dir[0]);
                show.run();
            }
        });
        r.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    step(key, values, def, -1);
                    show.run();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    step(key, values, def, 1);
                    show.run();
                    return true;
                }
                return false;
            }
        });
        return r;
    }

    private void step(String key, String[] values, String def, int d) {
        String cur = cfg.get(key, def);
        int i = 0;
        for (int k = 0; k < values.length; k++) if (values[k].equals(cur)) i = k;
        i = (i + d + values.length) % values.length;
        cfg.set(key, values[i]);
    }

    // ---- scanning

    @SuppressLint("MissingPermission")
    private void scan() {
        if (scanning) {
            stopScan();
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 7);
            scanInfo.setText("Location permission is needed to scan. Allow it, or run the adb command above, then scan again.");
            return;
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) {
            scanInfo.setText("Bluetooth is off or missing.");
            return;
        }
        final BluetoothLeScanner sc = ad.getBluetoothLeScanner();
        if (sc == null) {
            scanInfo.setText("No LE scanner available.");
            return;
        }
        results.clear();
        found.removeAllViews();
        scanning = true;
        scanInfo.setText("Scanning for 12 s ...");
        scanCb = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult r) {
                results.put(r.getDevice().getAddress(), r);
                redrawFound();
            }

            @Override
            public void onScanFailed(int code) {
                scanInfo.setText("Scan failed (" + code + ").");
                scanning = false;
            }
        };
        try {
            sc.startScan(scanCb);
        } catch (RuntimeException e) {
            scanInfo.setText("Scan failed: " + e.getMessage());
            scanning = false;
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (scanning) stopScan();
            }
        }, 12000);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            BluetoothLeScanner sc = bm.getAdapter().getBluetoothLeScanner();
            if (sc != null && scanCb != null) sc.stopScan(scanCb);
        } catch (RuntimeException ignored) {
            // the scan already ended
        }
        scanInfo.setText(results.size() + " devices found. Pick a bar, then Left or Right.");
    }

    private static boolean looksLikeBar(String n) {
        if (n == null) return false;
        String u = n.toUpperCase(Locale.US);
        for (String h : NAME_HINTS) if (u.startsWith(h) || (h.equals("LOTUS") && u.contains(h))) return true;
        return false;
    }

    @SuppressLint("MissingPermission")
    private void redrawFound() {
        ArrayList<ScanResult> rs = new ArrayList<ScanResult>(results.values());
        Collections.sort(rs, new Comparator<ScanResult>() {
            @Override
            public int compare(ScanResult a, ScanResult b) {
                boolean la = looksLikeBar(a.getDevice().getName()), lb = looksLikeBar(b.getDevice().getName());
                if (la != lb) return la ? -1 : 1;
                return b.getRssi() - a.getRssi();
            }
        });
        found.removeAllViews();
        for (ScanResult r : rs) {
            final BluetoothDevice d = r.getDevice();
            String n = d.getName();
            boolean bar = looksLikeBar(n);
            LinearLayout rw = row();
            TextView tv = Ui.text(this, 16, bar ? 0xFF7CFF8A : Ui.TEXT, Ui.MEDIUM);
            tv.setText((bar ? "★ " : "") + (n == null ? "(no name)" : n) + "   " + d.getAddress() + "   " + r.getRssi() + " dBm");
            rw.addView(tv);
            rw.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    assign(d.getAddress());
                }
            });
            found.addView(rw);
        }
    }

    private void assign(final String mac) {
        new AlertDialog.Builder(this).setTitle(mac)
            .setItems(new String[] {"Use as Left", "Use as Right", "Cancel"}, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    if (which == 0) cfg.set(Lights.K_LEFT, mac);
                    if (which == 1) cfg.set(Lights.K_RIGHT, mac);
                    refreshStatus();
                }
            }).show();
    }

    // ---- tap test: the bars blink at 2 Hz; the average lag of your OK presses is shown (it includes your own reaction time)

    private void startTapTest() {
        tapMode = true;
        tapOffsets.clear();
        tapStart = SystemClock.uptimeMillis();
        lights.start(null);
        lights.blink(tapStart);
        scanInfo.setText("Tap test: press OK on every blink for 10 s ...");
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (!tapMode) return;
                long now = SystemClock.uptimeMillis();
                if (now - tapStart > 10000) {
                    tapMode = false;
                    if (tapOffsets.isEmpty()) {
                        scanInfo.setText("No taps seen.");
                        return;
                    }
                    Collections.sort(tapOffsets);
                    long med = tapOffsets.get(tapOffsets.size() / 2);
                    scanInfo.setText("Your taps trail the blinks by about " + med + " ms (includes your reaction time of roughly 150-250 ms).");
                    return;
                }
                ui.postDelayed(this, 50);
            }
        });
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (tapMode && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && e.getRepeatCount() == 0) {
            long rel = (SystemClock.uptimeMillis() - tapStart) % 500;
            tapOffsets.add(rel < 250 ? rel : rel - 500);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
