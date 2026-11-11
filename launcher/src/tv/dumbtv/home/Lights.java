package tv.dumbtv.home;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.util.Calendar;
import java.util.List;
import java.util.UUID;

/**
 * Two Bluetooth LE light bars (Lotus Lantern / ELK-BLEDOM / Triones family) that follow the music. Everything runs
 * on its own thread and fails silently: the visuals never wait for, or depend on, the lamps. At most 12 writes a
 * second per bar, only the latest state is sent (no queue), and changes under about 4% are skipped.
 */
final class Lights {
    private static final String TAG = "dumblights";
    static final String K_LEFT = "light_left", K_RIGHT = "light_right", K_PROFILE = "light_profile", K_MODE = "light_mode",
            K_DELAY = "light_delay_ms", K_IDLE = "light_idle";
    static final int P_AUTO = 0, P_A = 1, P_B = 2;
    private static final UUID SVC_A = uuid16(0xFFF0), CH_A = uuid16(0xFFF3), SVC_B = uuid16(0xFFE5), CH_B = uuid16(0xFFE9);
    private static final long TICK_MS = 60, MIN_WRITE_GAP_MS = 85;

    private static Lights inst;

    static synchronized Lights get(Context c) {
        if (inst == null) inst = new Lights(c.getApplicationContext());
        return inst;
    }

    private static UUID uuid16(int v) {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v));
    }

    private final Context app;
    private final Handler h;
    private final LightCore core = new LightCore();
    private final Link left = new Link("L"), right = new Link("R");
    private volatile Pulse pulse;
    private volatile float accHue = 200f, accSat = 0.5f, master = 1f;
    private volatile int ctx = LightCore.CTX_MUSIC;
    private volatile boolean playing, running;
    private volatile int testStep = -1;
    private volatile long testAt, blinkStart;
    private long writesWindowAt, writesInWindow;
    private volatile int writesPerSec;

    private Lights(Context c) {
        app = c;
        HandlerThread ht = new HandlerThread("lights", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        ht.start();
        h = new Handler(ht.getLooper());
    }

    // ---- inputs from the rest of the app (all cheap, all safe to call from any thread)

    static void setAccent(float hue, float sat) {
        Lights l = inst;
        if (l != null) {
            l.accHue = hue;
            l.accSat = sat;
        }
    }

    void setPlaying(boolean p) {
        playing = p;
    }

    /** Which situation the lamps are in; Meditation forces calm warm light, Away is a sports app or the launcher in the background. */
    void setContext(int c) {
        ctx = c;
    }

    /** 0..1 multiplier, e.g. the meditation sleep timer's fade. */
    void setMaster(float m) {
        master = Math.max(0f, Math.min(1f, m));
    }

    /** Starts (or keeps) the engine; call from Home when it comes to the front. */
    void start(Pulse p) {
        if (p != null) pulse = p;
        if (running) return;
        running = true;
        h.post(tick);
    }

    /** Lights off and disconnected; call when the launcher goes away for good. */
    void stop() {
        running = false;
        h.post(new Runnable() {
            @Override
            public void run() {
                left.shutdown();
                right.shutdown();
            }
        });
    }

    String status(int side) {
        return (side == 0 ? left : right).describe();
    }

    int writeRate() {
        return writesPerSec;
    }

    /** Blinks white at 2 Hz for 10 s from the given uptime, for the tap test. */
    void blink(long startMs) {
        blinkStart = startMs;
    }

    /** Cycles red, green, blue, white for one second each on both bars so the working profile can be judged by eye. */
    void test() {
        testStep = 0;
        testAt = SystemClock.uptimeMillis();
    }

    // ---- the loop

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                step();
            } catch (RuntimeException e) {
                Log.w(TAG, "tick: " + e);
            }
            h.postDelayed(this, TICK_MS);
        }
    };

    private void step() {
        Config cfg = Config.get(app);
        left.setMac(cfg.get(K_LEFT, ""));
        right.setMac(cfg.get(K_RIGHT, ""));
        if (!left.has() && !right.has()) return;
        int profile = parseInt(cfg.get(K_PROFILE, "0"), 0);
        left.profile = profile;
        right.profile = profile;
        long now = SystemClock.uptimeMillis();
        int[] lc, rc;
        boolean on = true;
        if (testStep >= 0) {
            if (now - testAt > 1000) {
                testAt = now;
                testStep++;
            }
            if (testStep > 3) {
                testStep = -1;
            }
        }
        if (blinkStart > 0 && now - blinkStart < 10500) {
            long ph = (now - blinkStart) % 500;
            lc = ph < 120 ? new int[] {255, 255, 255} : new int[] {12, 12, 12};
            rc = lc;
        } else if (testStep >= 0) {
            int[][] cols = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 255}};
            lc = cols[testStep];
            rc = cols[testStep];
        } else {
            Pulse p = pulse;
            long beats = 0;
            int bib = 0;
            float conf = 0f;
            if (p != null) {
                Tempo t = p.tempo();
                long at = now + parseInt(cfg.get(K_DELAY, "80"), 80); // triggers fire earlier by the delay
                beats = t.beats(at);
                bib = t.beatInBar(at);
                conf = t.conf();
            }
            int mode = parseInt(cfg.get(K_MODE, "0"), 0);
            core.tick(now, beats, bib, conf, accHue, accSat, mode, ctx, Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                master, playing, !"off".equals(cfg.get(K_IDLE, "warm")), p == null ? 0f : p.kickEnv());
            lc = core.left;
            rc = core.right;
            on = core.on;
        }
        left.drive(lc, on, now);
        right.drive(rc, on, now);
        if (now - writesWindowAt >= 1000) {
            writesPerSec = (int) writesInWindow;
            writesInWindow = 0;
            writesWindowAt = now;
        }
    }

    private static int parseInt(String s, int d) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return d;
        }
    }

    /** One bar. */
    private final class Link extends BluetoothGattCallback {
        final String name;
        String mac = "";
        int profile;
        BluetoothGatt gatt;
        BluetoothGattCharacteristic ch;
        int used = -1; // 0 = ELK-BLEDOM, 1 = Triones
        boolean ready, connecting, wasOn;
        volatile boolean inFlight;
        long inFlightAt, lastWrite, nextTry;
        int fails;
        final int[] sent = {-100, -100, -100};

        Link(String n) {
            name = n;
        }

        void setMac(String m) {
            m = m == null ? "" : m.trim().toUpperCase();
            if (!m.equals(mac)) {
                shutdown();
                mac = m;
                fails = 0;
                nextTry = 0;
            }
        }

        boolean has() {
            return mac.length() == 17;
        }

        String describe() {
            if (!has()) return "not assigned";
            return mac + (ready ? " connected, profile " + (used == 0 ? "A ELK-BLEDOM" : used == 1 ? "B Triones" : "?")
                : connecting ? " connecting" : " waiting (retry " + fails + ")");
        }

        @SuppressLint("MissingPermission")
        void drive(int[] c, boolean on, long now) {
            if (!has()) return;
            if (!ready) {
                if (!connecting && now >= nextTry) connect(now);
                return;
            }
            if (inFlight && now - inFlightAt > 250) inFlight = false;
            if (inFlight || now - lastWrite < MIN_WRITE_GAP_MS) return;
            if (!on) {
                if (wasOn) {
                    if (write(used == 0 ? OFF_A : OFF_B)) {
                        wasOn = false;
                        stamp(now);
                    }
                }
                return;
            }
            if (!wasOn) {
                if (write(used == 0 ? ON_A : ON_B)) {
                    wasOn = true;
                    sent[0] = -100;
                    stamp(now);
                }
                return;
            }
            if (!LightCore.changed(c, sent)) return;
            if (write(color(c))) {
                sent[0] = c[0];
                sent[1] = c[1];
                sent[2] = c[2];
                stamp(now);
            }
        }

        private void stamp(long now) {
            lastWrite = now;
            writesInWindow++;
        }

        private byte[] color(int[] c) {
            byte r = (byte) c[0], g = (byte) c[1], b = (byte) c[2];
            if (used == 0) return new byte[] {0x7E, 0x00, 0x05, 0x03, r, g, b, 0x00, (byte) 0xEF};
            return new byte[] {0x56, r, g, b, 0x00, (byte) 0xF0, (byte) 0xAA};
        }

        @SuppressLint("MissingPermission")
        private boolean write(byte[] v) {
            try {
                BluetoothGatt g = gatt;
                BluetoothGattCharacteristic c = ch;
                if (g == null || c == null) return false;
                c.setValue(v);
                boolean ok = g.writeCharacteristic(c);
                if (ok) {
                    inFlight = true;
                    inFlightAt = SystemClock.uptimeMillis();
                }
                return ok;
            } catch (RuntimeException e) {
                return false;
            }
        }

        @SuppressLint("MissingPermission")
        private void connect(long now) {
            try {
                BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
                if (ad == null || !ad.isEnabled()) throw new IllegalStateException("bluetooth off");
                BluetoothDevice d = ad.getRemoteDevice(mac);
                connecting = true;
                gatt = d.connectGatt(app, false, this, BluetoothDevice.TRANSPORT_LE);
                if (gatt == null) throw new IllegalStateException("connectGatt null");
                // a connection that never completes is dropped and retried
                final BluetoothGatt mine = gatt;
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!ready && gatt == mine && connecting) fail("connect timeout");
                    }
                }, 15000);
            } catch (RuntimeException e) {
                fail("connect: " + e);
            }
        }

        @SuppressLint("MissingPermission")
        private void fail(String why) {
            Log.w(TAG, name + " " + mac + ": " + why);
            cleanup();
            fails++;
            nextTry = SystemClock.uptimeMillis() + Math.min(30000L, 2000L << Math.min(fails, 4));
        }

        @SuppressLint("MissingPermission")
        private void cleanup() {
            ready = false;
            connecting = false;
            wasOn = false;
            inFlight = false;
            ch = null;
            BluetoothGatt g = gatt;
            gatt = null;
            if (g != null) {
                try {
                    g.close();
                } catch (RuntimeException ignored) {
                    // nothing to close
                }
            }
        }

        /** Lights off, then disconnect. */
        @SuppressLint("MissingPermission")
        void shutdown() {
            try {
                if (ready && ch != null && gatt != null) {
                    ch.setValue(used == 0 ? OFF_A : OFF_B);
                    gatt.writeCharacteristic(ch);
                    final BluetoothGatt g = gatt;
                    // give the write a moment, then close
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                g.close();
                            } catch (RuntimeException ignored) {
                                // already closed
                            }
                        }
                    }, 300);
                    ready = false;
                    connecting = false;
                    ch = null;
                    gatt = null;
                    return;
                }
            } catch (RuntimeException ignored) {
                // fall through to a plain cleanup
            }
            cleanup();
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(final BluetoothGatt g, final int status, final int newState) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (g != gatt) return;
                    if (newState == 2 && status == 0) { // STATE_CONNECTED
                        try {
                            g.discoverServices();
                        } catch (RuntimeException e) {
                            fail("discover: " + e);
                        }
                    } else if (newState == 0 || status != 0) {
                        fail("disconnected status=" + status);
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt g, int status) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (g != gatt) return;
                    pick(g);
                }
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            inFlight = false;
        }

        private void pick(BluetoothGatt g) {
            BluetoothGattCharacteristic a = null, b = null, any = null;
            List<BluetoothGattService> svcs = g.getServices();
            for (BluetoothGattService s : svcs) {
                if (SVC_A.equals(s.getUuid())) a = s.getCharacteristic(CH_A);
                if (SVC_B.equals(s.getUuid())) b = s.getCharacteristic(CH_B);
                if (any == null) {
                    for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        int pr = c.getProperties();
                        boolean generic = s.getUuid().toString().startsWith("00001800") || s.getUuid().toString().startsWith("00001801");
                        if (!generic && (pr & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                            any = c;
                            break;
                        }
                    }
                }
            }
            if (profile == P_B && b != null) {
                ch = b;
                used = 1;
            } else if (profile == P_A && a != null) {
                ch = a;
                used = 0;
            } else if (a != null) { // Auto: ELK-BLEDOM first, then Triones
                ch = a;
                used = 0;
            } else if (b != null) {
                ch = b;
                used = 1;
            } else if (any != null) {
                ch = any;
                used = 0;
            }
            if (ch == null) {
                fail("no writable characteristic");
                return;
            }
            if ((ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            }
            ready = true;
            connecting = false;
            fails = 0;
            wasOn = false;
            Log.i(TAG, name + " " + mac + " ready: profile " + (used == 0 ? "A" : "B") + " char " + ch.getUuid());
        }
    }

    private static final byte[] ON_A = {0x7E, 0x00, 0x04, (byte) 0xF0, 0x00, 0x01, (byte) 0xFF, 0x00, (byte) 0xEF};
    private static final byte[] OFF_A = {0x7E, 0x00, 0x04, 0x00, 0x00, 0x00, (byte) 0xFF, 0x00, (byte) 0xEF};
    private static final byte[] ON_B = {(byte) 0xCC, 0x23, 0x33};
    private static final byte[] OFF_B = {(byte) 0xCC, 0x24, 0x33};
}
