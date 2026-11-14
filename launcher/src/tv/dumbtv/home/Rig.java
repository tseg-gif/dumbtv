package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.util.ArrayList;

/**
 * The concert rig over the GL backdrop: beams, foreground silhouettes and accents drawn from a {@link Look}, chosen by
 * the {@link Director}. The backdrop is one of Glow's scenes (pinned while the look lasts); this view draws the other
 * four layers on top and applies the camera to both. A failure here must never take the visualizer down, so every
 * frame is guarded and the rig switches itself off after repeated errors.
 */
final class Rig extends View {
    private static final String TAG = "dumbrig";
    private static final long FRAME_MS = 33, MIN_SCENE_MS = 25000;
    private final java.util.Random varRnd = new java.util.Random();
    private boolean pendingPin;
    private long lastPinMs;

    private final Pulse pulse;
    private final Glow glow;
    private final Director director;
    private final RigGeom geom = new RigGeom();
    private final RigGeom.In in = new RigGeom.In();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] cam = new float[4];
    private final float[] hsv = new float[3];
    private final long epoch = SystemClock.uptimeMillis();

    private boolean cameraOn = true, running = true;
    private Look shownLook, pinnedLook;
    private long lastDirectorMs, lastBars;
    private int lastSnares = -1, errors;
    private float lastSnareT = -99f, prevKick = -99f, lastKickT = -99f, kickEnv, bassS, midS, highS, enerS;
    private long lastBeatIdx = -1;
    private String songKey = "";
    private boolean pinOn;
    private float camPrevScale = 1f, camPrevTx, camPrevTy, camPrevRot;

    // the song's mood-table entry (null = live fallback), and the live features the fallback derives one from
    private SongLooks.Entry entry;
    private String tags = "";
    private float altNow = 260f;
    private long songAtMs, snareBase;
    private float featN, featEner, featBright, featTonal;
    private boolean derived;
    private static final long DERIVE_AFTER_MS = 25000;
    private Look beamLook;                 // the look whose beam episode is being timed
    private float beamStartT = -1f;
    private boolean beamSpent;

    Rig(Context c, Pulse p, Glow g) {
        super(c);
        pulse = p;
        glow = g;
        director = new Director(SystemClock.uptimeMillis());
        final Context ctxF = c;
        director.setMemory(new Director.Memory() { // the persistent scene history Glow keeps: rules across songs and restarts
            @Override
            public boolean blocked(String scene, long now) {
                int id = Glow.idOf(scene);
                return id >= 0 && Glow.memory(ctxF).blocked(id, Glow.variantsOf(id), System.currentTimeMillis(), false);
            }

            @Override
            public int family(String scene) {
                return Glow.familyOf(scene);
            }
        });
        setWillNotDraw(false);
        setFocusable(false);
        setClickable(false);
    }

    void setCameraEnabled(boolean on) {
        cameraOn = on;
        if (!on) resetCamera();
    }

    void newSong(String artist, String title, String genre) {
        String key = (artist == null ? "" : artist) + "|" + (title == null ? "" : title);
        if (key.equals(songKey)) return;
        songKey = key;
        long now = SystemClock.uptimeMillis();
        songAtMs = now;
        snareBase = pulse.snares();
        featN = featEner = featBright = featTonal = 0f;
        derived = false;
        tags = "";
        entry = null;
        try {
            entry = SongLooks.lookup(getContext(), artist, title);
        } catch (RuntimeException e) {
            Log.w(TAG, "song look lookup failed", e);
        }
        useEntry(entry);
        TrackLog.started(getContext(), artist, title, entry == null ? "fallback" : "entry:" + entry.source);
        director.newSong(key, genre, now, barsAt(glow.syncedNowMs()), entry);
        Log.i(TAG, "song " + key + " genre=" + director.genre() + " entry=" + (entry == null ? "none" : entry.key + "/" + entry.source));
    }

    /** palette and motion into Glow, kick style into the beat envelope and camera */
    private void useEntry(SongLooks.Entry e) {
        glow.setLook(e == null ? null : e.hues, e == null ? 0.58f : e.satCap, e != null && e.dark, e == null ? 1f : e.speed());
        in.punch = e == null ? 0.04f : e.punch();
        in.ringKick = e != null && "ring".equals(e.kick);
        if (e == null) pulse.tempo().setHalfTime(false);
    }

    /** MusicBrainz tags for the playing song; they sharpen a derived entry */
    void setTags(String t) {
        tags = t == null ? "" : t;
        if (derived && entry != null && "derived".equals(entry.source) && !tags.isEmpty()) derive(true);
    }

    /** No table entry: after 25 s of live features (and tags if they arrived) build one from the genre / mood guide, cache it, and use it. */
    private void derive(boolean again) {
        if (featN < 20f) return;
        long age = Math.max(1000, SystemClock.uptimeMillis() - songAtMs);
        float onsets = (pulse.snares() - snareBase) * 1000f / age;
        Tempo tp = pulse.tempo();
        String nk = SongLooks.normalize(songKey.contains("|") ? songKey.substring(0, songKey.indexOf('|')) : songKey,
            songKey.contains("|") ? songKey.substring(songKey.indexOf('|') + 1) : "");
        SongLooks.Entry e = SongLooks.derive(nk, director.genre(), tags, featEner / featN, tp.rawBpm(), onsets, featBright / featN, featTonal / featN);
        derived = true;
        entry = e;
        SongLooks.cache(getContext(), e);
        int bar = songKey.indexOf('|');
        TrackLog.matched(getContext(), bar < 0 ? songKey : songKey.substring(0, bar), bar < 0 ? "" : songKey.substring(bar + 1), "entry:derived");
        useEntry(e);
        director.setEntry(e, SystemClock.uptimeMillis(), barsAt(glow.syncedNowMs()));
        Log.i(TAG, "derived " + nk + " energy=" + e.energy + " valence=" + e.valence + " families=" + java.util.Arrays.toString(e.families) + (again ? " (tags)" : ""));
    }

    void setGenre(String genre) {
        if (genre == null || "unknown".equals(genre)) return;
        director.setGenre(genre, SystemClock.uptimeMillis(), barsAt(glow.syncedNowMs()));
    }

    void stop() {
        running = false;
        resetCamera();
        if (pinOn) {
            glow.pin(null, 0, 0);
            pinOn = false;
        }
    }

    private void resetCamera() {
        View wall = glow.view();
        wall.setScaleX(1f);
        wall.setScaleY(1f);
        wall.setTranslationX(0f);
        wall.setTranslationY(0f);
        wall.setRotation(0f);
    }

    /** bar number from the beat clock when it is locked, else from an assumed 110 BPM so the director still has a pulse */
    private long barsAt(long at) {
        Tempo tp = pulse.tempo();
        if (tp.conf() >= 0.5f && tp.anchor() != 0) return tp.bars(at);
        return at / (4 * beatMs());
    }

    /** the beat length assumed while the clock is not locked: the entry's felt tempo, else 110 BPM */
    private long beatMs() {
        return entry != null && entry.tempoHint > 0 ? Math.round(60000.0 / entry.tempoHint) : 545L;
    }

    String debugText() {
        long now = SystemClock.uptimeMillis();
        Tempo tp = pulse.tempo();
        String dir = director.debug(now);
        return dir + "\nbpm=" + Math.round(tp.bpm()) + " conf=" + Math.round(tp.conf() * 100) + "% " + glow.rigStats();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!running) return;
        if (glow.view().getVisibility() != View.VISIBLE) {
            postInvalidateDelayed(500);
            return;
        }
        try {
            drawFrame(canvas);
            errors = 0;
        } catch (RuntimeException e) {
            Log.w(TAG, "rig frame failed", e);
            if (++errors > 5) {
                Log.w(TAG, "rig switched off after repeated errors");
                stop();
                return;
            }
        }
        postInvalidateDelayed(FRAME_MS);
    }

    private void drawFrame(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        long now = SystemClock.uptimeMillis();
        long at = glow.syncedNowMs();
        Tempo tp = pulse.tempo();
        boolean locked = tp.conf() >= 0.5f && tp.anchor() != 0;
        float t = (at - epoch) / 1000f;

        // the beat clock, or an assumed 110 BPM when it is not locked
        long beatIdx;
        float phase;
        int bib;
        long bars;
        float beatSec;
        if (locked) {
            beatIdx = tp.beats(at);
            phase = tp.phase(at);
            bib = tp.beatInBar(at);
            bars = tp.bars(at);
            beatSec = (float) (tp.period() / 1000.0);
        } else {
            long bm = beatMs();
            beatSec = bm / 1000f;
            beatIdx = at / bm;
            phase = (at % bm) / (float) bm;
            bib = (int) (beatIdx & 3);
            bars = beatIdx >> 2;
        }
        in.t = t;
        in.mt = t * (entry == null ? 1f : entry.speed());
        in.beatIdx = beatIdx;
        in.beatPhase = phase;
        in.barPos = bars + (bib + phase) / 4.0;
        // a smooth beat envelope: about 16 ms attack, then a decay: the +-25% swell never steps
        float decay = entry == null ? 4.5f : entry.decay();
        float attack = entry != null && !"bloom".equals(entry.kick) ? 60f : entry != null ? 25f : 60f; // bloom swells in a little slower
        float env = (float) (Math.exp(-phase * decay) * (1.0 - Math.exp(-phase * attack)));
        kickEnv += (env - kickEnv) * 0.6f;
        in.kickEnv = kickEnv;
        in.snareEnv = (bib == 1 || bib == 3) ? kickEnv : 0f;
        if (beatIdx != lastBeatIdx) {
            lastBeatIdx = beatIdx;
            prevKick = lastKickT;
            lastKickT = t - phase * beatSec;
        }
        in.lastKickT = lastKickT;
        in.prevKickT = prevKick;
        int sc = pulse.snares();
        if (lastSnares < 0) lastSnares = sc;
        if (sc != lastSnares) {
            lastSnares = sc;
            lastSnareT = t;
        }
        in.lastSnareT = lastSnareT;
        bassS += (pulse.bass() - bassS) * 0.3f;
        midS += (pulse.mid() - midS) * 0.3f;
        highS += (pulse.high() - highS) * 0.3f;
        enerS += (pulse.ener() - enerS) * 0.2f;
        in.bass = bassS;
        in.mid = midS;
        in.high = highS;
        in.ener = enerS;

        featN++;
        featEner += pulse.ener();
        featBright += highS / (bassS + midS + highS + 0.02f);
        featTonal += pulse.notes.tonalNow();
        if (now - lastDirectorMs >= 250) {
            lastDirectorMs = now;
            if (entry != null && entry.tempoHint > 0 && tp.conf() >= 0.4f) { // the felt tempo: count half-time when that lands nearer the hint
                float raw = tp.rawBpm();
                tp.setHalfTime(raw >= 110f && Math.abs(raw / 2f - entry.tempoHint) < Math.abs(raw - entry.tempoHint));
            }
            if (entry == null && !derived && now - songAtMs >= DERIVE_AFTER_MS) derive(false);
            director.update(now, bars, bib, enerS, glow.sectionNow());
        }
        Look look = director.look();
        if (!director.active() || look == null) {
            if (pinOn) {
                glow.pin(null, 0, 0); // a sleep or rain playlist: back to the normal rooms, no rig
                pinOn = false;
                shownLook = pinnedLook = null;
            }
            resetCamera();
            return;
        }
        if (look != pinnedLook) { // a new look: its backdrop is a pinned Glow scene
            pinnedLook = look;
            pendingPin = true;
            Log.i(TAG, "look " + look.name() + " [" + Director.KIND[look.kind] + "] bar=" + bars);
        }
        // a scene lasts at least 25 s: a drop that changes the look soon after the last backdrop change keeps the
        // backdrop and gets the new one as soon as the 25 s are up
        if (pendingPin && (lastPinMs == 0 || now - lastPinMs >= MIN_SCENE_MS)) {
            pendingPin = false;
            lastPinMs = now;
            int chosen = Director.safeBackdrop(look.backdrop, director.memory(), System.currentTimeMillis(),
                Glow.memory(getContext()).lastFamily(), varRnd, director.bannedBackdrops());
            String name = Look.BACKDROPS[chosen];
            int sid = Glow.idOf(name);
            int var = sid < 0 ? look.variant : Glow.memory(getContext()).pickVariant(sid, Glow.variantsOf(sid), varRnd, System.currentTimeMillis());
            if (glow.pin(name, var, 3600) != null) pinOn = true;
        }
        in.kind = director.kind();
        // a beam episode: starts when this look has beams and the section is a drop or chorus; capped at 12 s per look (not for entries that prefer beams)
        if (look != beamLook) {
            beamLook = look;
            beamStartT = -1f;
            beamSpent = false;
        }
        in.beamsFree = director.beamsFree();
        if (look.beam != 0 && !beamSpent && (in.kind == Director.DROP || in.kind == Director.CHORUS)) {
            if (beamStartT < 0f) beamStartT = t;
            in.beamAge = t - beamStartT;
            if (!in.beamsFree && in.beamAge > RigGeom.BEAM_MAX_S + 1f) beamSpent = true;
        } else {
            in.beamAge = 0f;
            beamStartT = -1f;
        }
        in.kindProgress = director.kindProgress(now);
        in.lookAge = director.lookAgeMs(now) / 1000f;
        in.dropT = director.dropAt() < 0 ? -99f : (director.dropAt() - epoch) / 1000f;

        // hue from the cover art via Glow's palette; the alt colour sits 60 degrees away
        float hue = glow.hueNow();
        altNow = glow.altHue();
        float fade = director.fade(now, (long) (beatSec * 4000));
        float density = RigGeom.densityFor(in.kind, in.kindProgress);

        applyCamera(look, w, h);
        canvas.save();
        if (cameraOn) {
            canvas.translate(w / 2f + cam[1], h / 2f + cam[2]);
            canvas.rotate(cam[3]);
            canvas.scale(cam[0], cam[0]);
            canvas.translate(-w / 2f, -h / 2f);
        }
        Look prev = director.previous();
        if (prev != null && fade < 1f) draw(canvas, prev, hue, 1f - fade, density, w, h);
        draw(canvas, look, hue, prev != null ? fade : 1f, density, w, h);
        canvas.restore();
    }

    private void applyCamera(Look look, int w, int h) {
        if (!cameraOn) return;
        RigGeom.camera(look, in, w, h, cam);
        View wall = glow.view();
        if (Math.abs(cam[0] - camPrevScale) > 0.0005f) {
            wall.setScaleX(cam[0]);
            wall.setScaleY(cam[0]);
            camPrevScale = cam[0];
        }
        if (Math.abs(cam[1] - camPrevTx) > 0.3f) {
            wall.setTranslationX(cam[1]);
            camPrevTx = cam[1];
        }
        if (Math.abs(cam[2] - camPrevTy) > 0.3f) {
            wall.setTranslationY(cam[2]);
            camPrevTy = cam[2];
        }
        if (Math.abs(cam[3] - camPrevRot) > 0.01f) {
            wall.setRotation(cam[3]);
            camPrevRot = cam[3];
        }
    }

    private int color(int col, float alpha, float hue) {
        int a = Math.round(255f * Math.min(1f, Math.max(0f, alpha)));
        switch (col) {
            case RigGeom.COL_WHITE: return Color.argb(a, 235, 240, 255);
            case RigGeom.COL_SHADE: return Color.argb(a, 3, 3, 6);
            case RigGeom.COL_ALT: hsv[0] = altNow % 360f; break;
            default: hsv[0] = hue % 360f;
        }
        hsv[1] = entry == null ? 0.55f : Math.min(0.55f, entry.satCap); // comfort: saturation capped at 0.55, value 0.9 (the alpha cap keeps peak luminance under 0.8)
        hsv[2] = 0.9f;
        return Color.HSVToColor(a, hsv);
    }

    private void draw(Canvas c, Look look, float hue, float alpha, float density, int w, int h) {
        ArrayList<RigGeom.Prim> prims = geom.frame(look, in, w, h, alpha, density);
        for (int i = 0; i < prims.size(); i++) {
            RigGeom.Prim p = prims.get(i);
            paint.setColor(color(p.col, p.a, hue));
            switch (p.kind) {
                case RigGeom.LINE:
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Math.max(1f, p.w));
                    c.drawLine(p.x1, p.y1, p.x2, p.y2, paint);
                    break;
                case RigGeom.POLY:
                    paint.setStyle(Paint.Style.FILL);
                    path.rewind();
                    path.moveTo(p.poly[0], p.poly[1]);
                    for (int k = 1; k < p.n; k++) path.lineTo(p.poly[2 * k], p.poly[2 * k + 1]);
                    path.close();
                    c.drawPath(path, paint);
                    break;
                case RigGeom.ELLIPSE:
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Math.max(1f, p.w));
                    c.drawOval(p.x1 - p.x2, p.y1 - p.y2, p.x1 + p.x2, p.y1 + p.y2, paint);
                    break;
                default:
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(p.x1, p.y1, p.x2, paint);
            }
        }
    }
}
