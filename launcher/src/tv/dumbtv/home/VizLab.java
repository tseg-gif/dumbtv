package tv.dumbtv.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import java.util.HashMap;

/**
 * Viz Lab: a closed loop for looking at the live visuals from a laptop.
 *  - logcat tag VIZLAB: every detected onset and every predicted beat, as one JSON line, on the uptime clock
 *  - a corner overlay: a 24-bit uptime timecode as black/white cells plus a marker cell that lights on every beat,
 *    so a screen recording says exactly when each pulse became visible
 *  - a bundled 90 s test track (assets/viztest.ogg) played through the normal capture path
 *  - broadcast tv.dumbtv.VIZLAB:  --es scene <name|id> --es preset <0-3> --ei seconds 20   pins a scene
 *                                 --es cmd start|stop|live|off|list
 */
final class VizLab {
    static final String ACTION = "tv.dumbtv.VIZLAB";
    static final String TAG = "VIZLAB";
    static volatile boolean logging, overlayOn;
    static volatile Glow glow;

    private static MediaPlayer player;
    private static AudioManager am;
    private static Overlay overlay;
    private static final AudioManager.OnAudioFocusChangeListener NOFOCUS = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int change) {
        }
    };

    private VizLab() {
    }

    /** One detected or predicted event (any thread). */
    static void ev(long tMs, String band, float strength, String kind, String scene, int preset) {
        if (!logging) return;
        Log.i(TAG, "{\"t_ms\":" + tMs + ",\"band\":\"" + band + "\",\"strength\":" + Math.round(strength * 100) / 100f
            + ",\"kind\":\"" + kind + "\",\"scene\":\"" + scene + "\",\"preset\":" + preset + "}");
    }

    static void register(final Context c, FrameLayout root) {
        overlay = new Overlay(c);
        overlay.setVisibility(View.GONE);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.setElevation(40f);
        c.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent it) {
                handle(ctx, it);
            }
        }, new IntentFilter(ACTION));
    }

    private static void handle(Context c, Intent it) {
        String cmd = it.getStringExtra("cmd");
        String scene = it.getStringExtra("scene");
        Glow g = glow;
        if ("start".equals(cmd)) {
            setOn(true);
            startTrack(c);
        } else if ("stop".equals(cmd)) {
            stopTrack();
            setOn(false);
            if (g != null) g.pin(null, 0, 0);
        } else if ("live".equals(cmd)) {
            setOn(true);
        } else if ("off".equals(cmd)) {
            setOn(false);
        } else if ("list".equals(cmd)) {
            for (String line : listing()) Log.i(TAG, "intent " + line);
        }
        if (scene != null) {
            int secs = it.getIntExtra("seconds", 20);
            int preset = 0;
            try {
                preset = Integer.parseInt(String.valueOf(it.getStringExtra("preset") != null ? it.getStringExtra("preset") : it.getIntExtra("preset", 0)));
            } catch (NumberFormatException e) {
                preset = 0;
            }
            if (!overlayOn) setOn(true);
            String name = g == null ? null : g.pin(scene, preset, secs);
            Log.i(TAG, "{\"ev\":\"pin\",\"t_ms\":" + SystemClock.uptimeMillis() + ",\"scene\":\"" + name + "\",\"preset\":" + preset + ",\"seconds\":" + secs + "}");
            if (name != null) Log.i(TAG, "intent " + name + ": " + intent(name));
        }
    }

    private static void setOn(boolean on) {
        logging = on;
        overlayOn = on;
        if (overlay != null) overlay.setVisibility(on ? View.VISIBLE : View.GONE);
        if (overlay != null && on) overlay.invalidate();
        Log.i(TAG, "{\"ev\":\"lab\",\"on\":" + on + ",\"t_ms\":" + SystemClock.uptimeMillis() + "}");
    }

    private static void startTrack(Context c) {
        stopTrack();
        try {
            am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            am.requestAudioFocus(NOFOCUS, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            AssetFileDescriptor fd = c.getAssets().openFd("viztest.ogg");
            player = new MediaPlayer();
            player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setVolume(0.8f, 0.8f);
            player.prepare();
            long t0 = SystemClock.uptimeMillis();
            player.start();
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    stopTrack();
                }
            });
            Log.i(TAG, "{\"ev\":\"track\",\"start_ms\":" + t0 + ",\"bpm\":100}");
        } catch (java.io.IOException | RuntimeException e) {
            Log.w(TAG, "test track failed: " + e);
        }
    }

    private static void stopTrack() {
        if (player != null) {
            try {
                player.stop();
            } catch (RuntimeException ignored) {
                // not started
            }
            player.release();
            player = null;
            Log.i(TAG, "{\"ev\":\"track\",\"end_ms\":" + SystemClock.uptimeMillis() + "}");
        }
        if (am != null) am.abandonAudioFocus(NOFOCUS);
    }

    /** Hold OK a second time (with the debug line showing): the test track starts or stops. */
    static void toggleTrack(Context c) {
        if (player != null) {
            stopTrack();
            setOn(false);
        } else {
            setOn(true);
            startTrack(c);
        }
    }

    static boolean trackPlaying() {
        return player != null;
    }

    /** Timecode and beat marker in the bottom-left corner. Cells are H/45 px: 24 px on a 1080p screen. */
    private static final class Overlay extends View {
        private final Paint p = new Paint();

        Overlay(Context c) {
            super(c);
            setWillNotDraw(false);
            setFocusable(false);
            setClickable(false);
        }

        @Override
        protected void onDraw(Canvas cv) {
            long t = SystemClock.uptimeMillis();
            int w = getWidth(), h = getHeight();
            float cell = h / 45f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF000000);
            cv.drawRect(0, h - cell * 2.2f, cell * 29f, h, p);
            Glow g = glow;
            boolean lit = g != null && g.markerLevel() > 0.5f;
            p.setColor(lit ? 0xFFFFFFFF : 0xFF202020);
            cv.drawRect(0, h - cell * 2f, cell * 2f, h, p);
            // sync cells: white, black, then 24 bits (most significant first)
            for (int i = 0; i < 26; i++) {
                boolean on = i == 0 || (i >= 2 && ((t >> (25 - i)) & 1L) != 0);
                p.setColor(on ? 0xFFFFFFFF : 0xFF000000);
                float x = cell * (2.5f + i);
                cv.drawRect(x, h - cell, x + cell, h, p);
            }
            if (overlayOn) postInvalidateOnAnimation();
        }
    }

    // ---- intents: what each scene should do, in one line (read against what the capture shows) ----
    private static final HashMap<String, String> INTENT = new HashMap<String, String>();

    private static void in(String n, String s) {
        INTENT.put(n, s);
    }

    static String intent(String name) {
        String s = INTENT.get(name);
        return s == null ? "no intent written" : s;
    }

    static String[] listing() {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        for (java.util.Map.Entry<String, String> e : new java.util.TreeMap<String, String>(INTENT).entrySet()) out.add(e.getKey() + ": " + e.getValue());
        return out.toArray(new String[0]);
    }

    static {
        in("dunes", "kick: the whole dune field lifts and ripples; snare: sand crest flicks; hat: fine sparkle; melody: hue drifts; see: rolling glowing dune rows to the horizon");
        in("tunnel", "kick: rings surge toward the viewer; snare: ring sideways flick; hat: dots sparkle; melody: tunnel tint; see: a lit square tunnel of dots receding to a bright centre");
        in("planet", "kick: planet glow swells; snare: orbiting dots flick; hat: starfield twinkle; melody: ring tilt; see: a lit planet with a ring and stars");
        in("rain", "kick: drops burst brighter; snare: gust sideways; hat: sparkle on drops; melody: colour; see: streaks of glowing rain falling toward the viewer");
        in("galaxy", "kick: arms swell; snare: rotation nudge; hat: star twinkle; melody: core hue; see: a spiral galaxy turning slowly");
        in("beam", "kick: beams flare; snare: beams sweep; hat: dust sparkle; melody: colour shift; see: crossing light beams through haze");
        in("firefly_meadow", "kick: fireflies flare together; snare: swarm drifts; hat: tiny blinks; melody: grass tint; see: glowing fireflies over a meadow silhouette");
        in("aurora_ridge", "kick: curtains brighten; snare: curtain sweep; hat: star glints; melody: curtain colour; see: aurora curtains above a mountain ridge");
        in("night_ocean", "kick: moon path glitters harder; snare: wave crest; hat: spray sparkle; melody: sky tint; see: moonlit sea with glitter road");
        in("sunset_rays", "kick: rays pulse; snare: ray sweep; hat: dust glints; melody: sky colour; see: sun on the horizon with long rays");
        in("synth_road", "kick: road lines surge; snare: grid flash lines; hat: star twinkle; melody: sun colour; see: a neon grid road to a low sun");
        in("hyperspace", "kick: stars stretch; snare: streak flick; hat: sparkle; melody: tint; see: star streaks rushing past the viewer");
        in("helix", "kick: strands expand; snare: twist nudge; hat: node sparkle; melody: colour; see: a double helix of dots turning");
        in("mandala_bloom", "kick: petals open; snare: ring spin flip; hat: tip sparkle; melody: hue; see: a layered flower mandala blooming");
        in("lanterns", "kick: lanterns brighten; snare: sway; hat: sparks; melody: warm tint; see: lanterns rising into a night sky");
        in("embers", "kick: embers burst upward; snare: swirl; hat: crackle sparks; melody: heat colour; see: glowing embers drifting up");
        in("plexus", "kick: links flare; snare: network shifts; hat: node blinks; melody: colour; see: a connected network of glowing nodes");
        in("city_drive", "kick: street lights pulse; snare: lane flick; hat: window twinkle; melody: sky tint; see: a night drive down a city street of lights");
        in("vortex", "kick: spiral tightens; snare: spin reverse; hat: dot glints; melody: colour; see: a swirling vortex of dots");
        in("eclipse", "kick: corona flares; snare: ray flick; hat: star twinkle; melody: corona colour; see: a dark disc with a bright corona");
        in("crystal_tunnel", "kick: facets flash; snare: tunnel twist; hat: sparkle; melody: tint; see: a tunnel of crystal shards");
        in("pond_ripples", "kick: big ring spreads; snare: second ring; hat: droplet sparkle; melody: water tint; see: expanding rings on dark water");
        in("lissajous_ribbons", "kick: ribbons swell; snare: phase jump; hat: bead sparkle; melody: colour; see: looping light ribbons");
        in("snowfall", "kick: flakes brighten; snare: gust; hat: glitter; melody: tint; see: soft snow falling through coloured light");
        in("bubbles", "kick: bubbles swell; snare: pop ring; hat: glints; melody: tint; see: crisp rising bubbles with rims");
        in("pendulum_wave", "kick: row of pendulums lights; snare: phase shift; hat: tip sparkle; melody: colour; see: a row of swinging dots forming waves");
        in("torus_knot", "kick: knot swells; snare: spin flip; hat: bead glints; melody: hue; see: a rotating knot of glowing beads");
        in("supernova", "kick: shell bursts outward; snare: second shell; hat: sparks; melody: colour; see: expanding glowing shells and sparks");
        in("sound_lines", "kick: lines rise with the bass; snare: lines flick; hat: tips sparkle; melody: colour; see: stacked wave lines");
        in("atom_orbits", "kick: nucleus pulses; snare: orbit flip; hat: electron glints; melody: colour; see: electrons orbiting a bright nucleus");
        in("jellyfish", "kick: domes pulse and swim up; snare: tentacle flick; hat: speck glints; melody: colour; see: lit jellyfish drifting in dark water");
        in("mandala_rings", "kick: rings expand; snare: ring spin; hat: dot sparkle; melody: hue; see: concentric rotating rings of dots");
        in("dot_sheet", "kick: sheet heaves; snare: ripple across; hat: dot glints; melody: colour; see: a grid of dots forming waves");
        in("portal_door", "kick: door glow surges; snare: light spill; hat: sparks; melody: colour; see: a glowing doorway into another light");
        in("planet_horizon", "kick: atmosphere glow swells; snare: ring flick; hat: star twinkle; melody: tint; see: a planet horizon with a glowing limb");
        in("underwater_night", "kick: light shafts pulse; snare: current sweep; hat: plankton glints; melody: tint; see: dark water with light shafts and drifting specks");
        in("c_bars", "kick: bass bars jump; snare: mid bars flick; hat: high bars twitch; melody: colour; see: a spectrum of bars");
        in("c_wave", "kick: line amplitude jumps; snare: line kink; hat: fine ripple; melody: colour; see: a glowing waveform line");
        in("c_mountains", "kick: peaks lift; snare: ridge flash; hat: star twinkle; melody: sky; see: layered mountain ridges");
        in("c_ring", "kick: ring swells; snare: ring flick; hat: spark; melody: colour; see: a pulsing ring");
        in("c_orbit", "kick: orbits expand; snare: swap direction; hat: spark; melody: colour; see: dots orbiting a centre");
        in("c_fountain", "kick: jet bursts; snare: side jet; hat: droplets; melody: colour; see: a fountain of light drops");
        in("c_ripples", "kick: big ripple; snare: small ripple; hat: dot; melody: colour; see: rings spreading across the screen");
        in("c_aurora", "kick: curtains brighten; snare: sweep; hat: glints; melody: colour; see: aurora curtains");
        in("c_fireworks", "kick: a shell launches and bursts; snare: second shell; hat: sparks; melody: colour; see: fireworks over a dark sky");
        in("c_helix", "kick: strands expand; snare: twist; hat: nodes; melody: colour; see: a helix of dots");
        in("c_blob", "kick: core swells; snare: side bulge; hat: speckle; melody: colour; see: a living blob");
        in("c_note_marble", "kick: a marble drops; snare: marble bounces; hat: tick; melody: each note lands a marble on its pitch lane; see: marbles falling onto notes");
        in("c_note_keys", "kick: bass keys press; snare: key flash; hat: sparkle; melody: the played key lights and its bar grows; see: a keyboard with growing bars");
        in("c_note_bottles", "kick: low bottle rings; snare: tap; hat: glint; melody: a bottle per note lights; see: a row of glowing bottles");
        in("c_note_harp", "kick: low strings pluck; snare: strum; hat: glint; melody: the string of each note vibrates; see: harp strings plucked by the melody");
        in("c_note_musicbox", "kick: drum turns; snare: comb tick; hat: glint; melody: pins lift notes; see: a music box cylinder");
        in("c_note_stars", "kick: constellation flares; snare: star flick; hat: twinkle; melody: a star per note; see: stars appearing for each note");
        in("c_note_ribbon", "kick: ribbon swells; snare: kink; hat: bead; melody: the ribbon height follows the pitch; see: a ribbon following the tune");
        in("c_note_parade", "kick: lead figure steps; snare: figure hop; hat: tick; melody: figures per note; see: a parade of notes");
        in("c_note_water", "kick: big drop; snare: small drop; hat: speck; melody: a ripple per note at its pitch; see: ripples on water per note");
        in("cinema", "kick: zoom punch; snare: drift; hat: sparkles; melody: none; see: a full-screen AI clip with the beat layer");
    }
}
