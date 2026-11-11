package tv.dumbtv.home;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.Random;

/**
 * The Piano family. A: a piano roll over a night city, with a glowing keyboard bar; notes rise from
 * the keys (first play) or fall onto them with a 1.5 s lookahead (replays, from stored events). B: a
 * perspective sheet-music flyover with a procedural engraved score; every estimated note lights up
 * on its staff position. Variants are seeded per song: moon / sun / ocean / stars, four palettes,
 * rising or falling. One variant never stays longer than 16 s.
 */
final class PianoScene {
    private static final int[][] PAL = {
        {0xFFA06BFF, 0xFF5EEAA0}, {0xFFFFB347, 0xFF35D6FF}, {0xFFFF7AA8, 0xFF2BC4E8}, {0xFFFF9A5C, 0xFF6B9BFF}};
    private static final int NP = 72;
    // Colors follow the register, not the note name: low notes deep, high notes bright, a chord is a few neighbours on the
    // wheel, and the whole palette drifts with the song (faster when the playing is busy), so it never repeats and never jumps.
    private final int[] PCOL = new int[128];
    private final float[] hsv = {0f, 0.72f, 0.9f};
    private float baseHue, hueSpan = 110f;
    private int auroraB;
    private int lastMidi = 60;
    private int scheme, accA, accB;
    boolean warm; // piano_palette=warm: rose, red, orange and amber only, no blue, cyan, green or violet

    private void recolor() {
        for (int m = 36; m <= 108; m++) {
            float t = (m - 36) / 72f, hue, sat = 0.62f - 0.12f * t;
            if (warm) { // a slow triangle wave over rose..amber, so the register still reads as a gradient
                float x = t + 0.5f * (float) Math.sin(baseHue * 0.01745f);
                x -= (float) Math.floor(x);
                hsv[0] = (345f + 70f * (x < 0.5f ? x * 2f : 2f - 2f * x)) % 360f;
                hsv[1] = 0.5f + 0.1f * t;
                hsv[2] = 0.88f;
                PCOL[m] = android.graphics.Color.HSVToColor(hsv) & 0xFFFFFF;
                continue;
            }
            hsv[2] = 0.9f;
            switch (scheme) {
                case 1: // split: lows one hue, highs its opposite
                    hue = baseHue + (t < 0.5f ? t * 70f : 180f + (t - 0.5f) * 70f);
                    break;
                case 2: // banded: each octave a step along the wheel
                    hue = baseHue + (float) Math.floor(t * 6f) * 55f + (t * 6f % 1f) * 18f;
                    break;
                case 3: // neon: narrow and fully saturated
                    hue = baseHue + t * hueSpan * 0.5f;
                    sat = 0.74f;
                    break;
                default: // a smooth sweep across the register
                    hue = baseHue + t * hueSpan;
            }
            hsv[0] = (hue + 720f) % 360f;
            hsv[1] = sat;
            PCOL[m] = android.graphics.Color.HSVToColor(hsv) & 0xFFFFFF;
        }
        hsv[1] = 0.58f;
        hsv[2] = 0.9f;
        hsv[0] = warm ? 30f : (baseHue + 150f) % 360f; // accents: the opposite side of the wheel, and a triad step
        accA = android.graphics.Color.HSVToColor(hsv) & 0xFFFFFF;
        hsv[0] = warm ? 350f : (baseHue + 240f) % 360f;
        accB = android.graphics.Color.HSVToColor(hsv) & 0xFFFFFF;
        auroraB = accA;
    }
    boolean on;
    float alpha;
    int view; // 0 piano roll, 1 sheet music
    int backdrop, pal;
    int look; // 0 city roll, 1 curtains, 2 mandala, 3 fountain, 4 constellation: chosen per song
    float calm = 0.5f; // 0..1, set by the stage before begin()
    boolean falling;
    boolean pianoRollOnly; // piano-tagged songs: always the glowing piano roll, never the sheet-music page

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG); // additive: overlapping light brightens

    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Matrix mat = new Matrix();
    private final float[] src = new float[8], dst = new float[8];
    private final Random rng = new Random(5);
    private final float[] keyLit = new float[128], keyTgt = new float[128]; // shown level eases toward the target
    private final float[] rx = new float[NP], ry = new float[NP], rl = new float[NP], rh = new float[NP], rw = new float[NP];
    private final int[] rc = new int[NP];
    private int rNext, seen;
    private float time, dtLast;
    private long songStart;
    private final float[] skyH = new float[24], skyW = new float[24];
    // effects: sparks at landings, chord shockwaves, shooting stars, a wave of lit windows, a beat of the moon
    private static final int NS = 360, NR = 6, NWIN = 48;
    private final float[] spX = new float[NS], spY = new float[NS], spVX = new float[NS], spVY = new float[NS], spL = new float[NS];
    private final int[] spC = new int[NS];
    private int spNext;
    private final float[] rgX = new float[NR], rgT = new float[NR];
    private final int[] rgC = new int[NR];
    private int rgNext;
    private final float[] winLit = new float[NWIN];
    private float starT = -1f, starY, moonPulse, activity, lastOnset;
    private int chordCount;
    // stored events of an earlier play: midi and time in tenths of a second
    private int[] stM = new int[0], stT = new int[0];
    private int stN;

    PianoScene() {
        glow.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD));
        glow.setStrokeCap(Paint.Cap.ROUND);
        Random r = new Random(11);
        for (int i = 0; i < 24; i++) {
            skyH[i] = 0.05f + 0.16f * r.nextFloat() * r.nextFloat();
            skyW[i] = 0.02f + 0.035f * r.nextFloat();
        }
    }

    void setStored(String packed) {
        stN = 0;
        if (packed == null || packed.length() < 8) return;
        String[] p = packed.split(",");
        if (stM.length < p.length) {
            stM = new int[p.length];
            stT = new int[p.length];
        }
        for (String s : p) {
            int d = s.indexOf('.');
            if (d <= 0) continue;
            try {
                stM[stN] = Integer.parseInt(s.substring(0, d));
                stT[stN++] = Integer.parseInt(s.substring(d + 1));
            } catch (NumberFormatException ignored) {
                // skip a damaged entry
            }
        }
    }

    boolean hasStored() {
        return stN >= 40;
    }

    /** A new variant: seeded so the same song and the same step look the same. */
    void begin(int seed, int step, Notes notes, long songStartMs) {
        Random r = new Random(seed * 31L + step * 977L);
        view = pianoRollOnly ? 0 : (step & 1) == 0 ? r.nextInt(2) : 1 - view;
        backdrop = r.nextInt(4);
        int[] order = {0, 1, 2, 3, 4};
        Random sr = new Random(seed * 7919L + 13L); // the song picks the order of its looks; the step walks along it
        for (int i = order.length - 1; i > 0; i--) {
            int j = sr.nextInt(i + 1), t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        look = order[(step / 2) % order.length];
        if (look == 3 && calm > 0.6f) look = 1; // a calm song gets no fountain
        else if (look == 4 && calm < 0.3f) look = 2; // a hard one gets no slow constellation
        pal = r.nextInt(PAL.length);
        baseHue = r.nextFloat() * 360f;
        scheme = r.nextInt(4);
        hueSpan = 70f + 70f * r.nextFloat();
        recolor();
        falling = r.nextBoolean();
        on = true;
        songStart = songStartMs;
        seen = notes.evCount;
        for (int i = 0; i < NP; i++) rl[i] = 0f;
    }

    void update(float dt, long now, Notes notes, long posMs, boolean fastEnough) {
        time += dt;
        baseHue = (baseHue + dt * (2f + 3f * activity)) % 360f;
        recolor();
        for (int i = 0; i < 128; i++) {
            if (keyTgt[i] > 0f) keyTgt[i] = Math.max(0f, keyTgt[i] - dt * 0.6f);
            float d = keyTgt[i] - keyLit[i];
            if (d != 0f) keyLit[i] += d * Math.min(1f, dt * (d > 0f ? 5f : 1.6f));
            if (keyLit[i] < 0.004f && keyTgt[i] == 0f) keyLit[i] = 0f;
        }
        for (int i = 0; i < NP; i++) if (rl[i] > 0f) rl[i] -= dt * (view == 0 ? (look == 4 ? 0.22f : 0.6f) : 0.5f);
        for (int i = 0; i < NS; i++) {
            if (spL[i] > 0f) {
                spL[i] -= dt * 1.3f;
                spX[i] += spVX[i] * dt;
                spY[i] += spVY[i] * dt;
                spVY[i] += 260f * dt; // sparks arc and fall
            }
        }
        for (int i = 0; i < NR; i++) if (rgT[i] >= 0f) rgT[i] = rgT[i] + dt > 1f ? -1f : rgT[i] + dt;
        for (int i = 0; i < NWIN; i++) if (winLit[i] > 0f) winLit[i] = Math.max(0f, winLit[i] - dt * 0.9f);
        if (starT >= 0f) starT = starT + dt > 0.9f ? -1f : starT + dt;
        moonPulse = Math.max(0f, moonPulse - dt * 1.6f);
        activity += (Math.min(1f, notes.activeN / 4f) - activity) * Math.min(1f, dt * 0.6f);
        lastOnset += dt;
        float top = 1f;
        for (int m = 36; m <= 108; m++) if (notes.on[m]) top = Math.max(top, notes.level[m]);
        for (int m = 36; m <= 108; m++) { // a held note keeps its key lit, breathing with its own loudness
            if (!notes.on[m]) continue;
            float breath = 0.65f + 0.35f * Math.min(1f, notes.level[m] / top) * (0.9f + 0.1f * (float) Math.sin(time * 2.2f + m * 0.5f));
            if (keyTgt[m] < breath) keyTgt[m] = breath;
        }
        // new live events become notes, with sparks, window waves and chord shockwaves
        int from = Math.max(seen, notes.evCount - Notes.CAP);
        int fresh = 0;
        for (int i = from; i < notes.evCount; i++) {
            int e = i % Notes.CAP;
            int q = rNext++ % NP;
            int midi = notes.evMidi[e];
            rx[q] = midi;
            ry[q] = look == 4 ? 0.14f + 0.5f * (midi - 36) / 72f + 0.1f * rng.nextFloat() : 0f; // a star's height follows its pitch
            rl[q] = 1f;
            rh[q] = 0.35f + 0.65f * notes.evVel[e];
            rw[q] = 0.3f + 0.7f * notes.evConf[e];
            rc[q] = 2 + midi;
            lastMidi = midi;
            keyTgt[midi] = Math.max(keyTgt[midi], 0.45f + 0.45f * notes.evVel[e]);
            moonPulse = Math.min(1f, moonPulse + 0.25f * notes.evVel[e]);
            winLit[Math.max(0, Math.min(NWIN - 1, (int) ((midi - 36) / 72f * NWIN)))] = 1f;
            if (midi > 90 && starT < 0f) {
                starT = 0f;
                starY = 0.08f + 0.2f * ((midi * 37) % 10) / 10f;
            }
            fresh++;
            if (lastW > 0f) spark(keyXpx(midi), lastKy, 2 + midi, (look == 3 ? 2 : 1) * (3 + (int) (4 * notes.evVel[e])));
        }
        if (look == 3 && lastW > 0f) { // fountain: a sounding key keeps throwing sparks
            for (int m = 36; m <= 108; m++) {
                if (notes.on[m] && rng.nextFloat() < dt * 14f) spark(keyXpx(m), lastKy, 2 + m, 2 + (int) (3 * keyLit[m]));
            }
        }
        if (fresh > 0) {
            chordCount = lastOnset < 0.12f ? chordCount + fresh : fresh;
            lastOnset = 0f;
            if (chordCount >= 3) { // a chord: one big ring from the keyboard
                int r = rgNext++ % NR;
                rgX[r] = notes.evCount > 0 ? keyXpx(notes.evMidi[(notes.evCount - 1) % Notes.CAP]) : lastW * 0.5f;
                rgT[r] = 0f;
                rgC[r] = 200 + (rgNext & 1); // chord rings in the accent colors
                chordCount = 0;
            }
        }
        seen = notes.evCount;
    }

    private float lastW, lastKy;

    private float keyXpx(float midi) {
        return keyX(midi, lastW);
    }

    private void spark(float x, float y, int pal, int n) {
        for (int k = 0; k < n; k++) {
            int q = spNext++ % NS;
            boolean jet = look == 3;
            float a = -3.1416f * (jet ? 0.32f + 0.36f * rng.nextFloat() : 0.15f + 0.7f * rng.nextFloat()), sp = lastW * (jet ? 0.12f + 0.2f * rng.nextFloat() : 0.05f + 0.14f * rng.nextFloat());
            spX[q] = x;
            spY[q] = y;
            spVX[q] = (float) Math.cos(a) * sp;
            spVY[q] = (float) Math.sin(a) * sp;
            spL[q] = 0.6f + 0.5f * rng.nextFloat();
            int pc = pal;
            if (jet && pal >= 2 && pal < 200) pc = 2 + Math.max(36, Math.min(108, pal - 2 + (int) ((rng.nextFloat() - 0.5f) * 34f))); // each spark a shade apart
            spC[q] = rng.nextFloat() < (jet ? 0.5f : 0.4f) ? 200 + (k & 1) : pc; // some sparks in the accent colors
        }
    }

    private int col(int idx, float a) {
        int c = idx >= 200 ? (idx == 200 ? accA : accB) : idx >= 2 ? PCOL[Math.max(36, Math.min(108, idx - 2))] : Variety.cap(PAL[pal][idx]);
        return (Math.max(0, Math.min(255, (int) (a * 255f))) << 24) | (c & 0xFFFFFF);
    }

    void draw(Canvas c, float w, float h, long posMs, boolean fine) {
        if (alpha <= 0.01f) return;
        sky(c, w, h);
        if (view == 0) roll(c, w, h, posMs, fine);
        else sheet(c, w, h, fine);
    }

    private void sky(Canvas c, float w, float h) {
        float a = alpha;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 6; i++) { // night gradient, darker at the top
            int g = 14 + i * 5;
            paint.setColor(((int) (0.96f * a * 255) << 24) | (warm ? ((g + 8) << 16) | (g << 8) | Math.max(0, g - 6) : (g << 16) | (g << 8) | (g + 14)));
            c.drawRect(0, h * i / 6f, w, h * (i + 1) / 6f + 1f, paint);
        }
        for (int k = 0; k < 8; k++) { // the room takes the colour of the last note, deepest at the keys
            paint.setColor(((int) ((0.025f + 0.045f * activity) * a * 255) << 24) | PCOL[lastMidi]);
            c.drawRect(0, h * (0.25f + 0.07f * k), w, h, paint);
        }
        // two ribbons of aurora that sway with the music
        for (int k = 0; k < 2; k++) {
            path.reset();
            float y0 = h * (0.18f + 0.12f * k), amp = h * (0.03f + 0.04f * activity);
            path.moveTo(0, y0 + h * 0.1f);
            for (int i = 0; i <= 16; i++) path.lineTo(w * i / 16f, y0 + amp * (float) Math.sin(i * 0.6f + time * (0.3f + 0.1f * k) + k * 2f));
            path.lineTo(w, y0 + h * 0.1f + amp);
            path.close();
            paint.setColor(k == 0 ? col(2 + lastMidi, (0.05f + 0.09f * activity) * a) : (((int) ((0.05f + 0.08f * activity) * a * 255)) << 24) | auroraB); // second ribbon opposite on the wheel
            c.drawPath(path, paint);
        }
        float mx = keyX(lastMidi, w); // the ocean reflects the last note, no moon or sun
        for (int i = 0; i < 48; i++) { // stars that twinkle harder with the music
            float sx = ((i * 7919) % 1000) / 1000f * w, sy = ((i * 104729) % 700) / 1000f * h;
            float tw = 0.45f + 0.55f * (float) Math.sin(time * 1.1f + i);
            paint.setColor(((int) (0.55f * tw * a * 255) << 24) | (warm ? 0xFFEBD2 : 0xE8F0FF));
            c.drawCircle(sx, sy, 1.5f + activity, paint);
        }
        if (starT >= 0f) { // a shooting star on a high note
            float f = starT / 0.9f, x = w * (0.9f - 0.7f * f), y = h * (starY + 0.25f * f);
            paint.setColor(((int) ((1f - f) * 0.8f * a * 255) << 24) | 0xF4F8FF);
            c.drawCircle(x, y, 3f, paint);
            for (int k = 1; k <= 6; k++) {
                paint.setColor(((int) ((1f - f) * 0.5f / k * a * 255) << 24) | 0xF4F8FF);
                c.drawCircle(x + k * w * 0.012f, y - k * h * 0.008f, 2.4f - k * 0.25f, paint);
            }
        }
        float hy = h * 0.8f;
        for (int k = 0; k < 6; k++) { // a soft horizon glow in the colour of the last note
            paint.setColor(((int) ((0.015f + 0.03f * activity) * (6 - k) * a * 255) << 24) | PCOL[lastMidi]);
            c.drawRect(0, hy - k * h * 0.035f, w, hy, paint);
        }
        paint.setColor(((int) (0.9f * a * 255) << 24) | 0x080A14);
        c.drawRect(0, hy, w, h, paint);
    }

    private float keyX(float midi, float w) {
        return w * (0.03f + 0.94f * Math.max(0f, Math.min(1f, (midi - 36f) / 72f)));
    }

    private void roll(Canvas c, float w, float h, long posMs, boolean fine) {
        float a = alpha, ky = h * 0.81f, kh = h * 0.16f, kw = w * 0.94f / 72f;
        lastW = w;
        lastKy = ky;
        paint.setStyle(Paint.Style.FILL);
        switch (look) {
            case 1: curtains(c, w, h, ky, kw, a); break;
            case 2: bloom(c, w, h, ky, kw, a, fine); break;
            case 3: fountainBase(c, w, h, ky, kw, a); break;
            case 4: constellation(c, w, h, ky, kw, a); break;
            default:
                beams(c, w, h, ky, kw, a);
                classicNotes(c, w, h, ky, kw, a, posMs);
        }
        ringsAndSparks(c, w, h, ky, a);
        keys(c, w, h, ky, kh, kw, a, fine);
    }

    /** Light beams standing up from the lit keys. */
    private void beams(Canvas c, float w, float h, float ky, float kw, float a) {
        for (int m = 36; m <= 108; m++) {
            float lit = keyLit[m];
            if (lit < 0.05f) continue;
            float x = keyX(m, w);
            int cc = PCOL[m];
            for (int k = 0; k < 3; k++) {
                paint.setColor(((int) (0.17f * lit * a * 255) << 24) | cc);
                path.reset();
                path.moveTo(x - kw * (0.5f + k * 0.3f), ky);
                path.lineTo(x - kw * (0.2f + k * 0.5f), ky - h * (0.28f + 0.2f * lit));
                path.lineTo(x + kw * (0.2f + k * 0.5f), ky - h * (0.28f + 0.2f * lit));
                path.lineTo(x + kw * (0.5f + k * 0.3f), ky);
                path.close();
                c.drawPath(path, paint);
            }
        }
    }

    private void classicNotes(Canvas c, float w, float h, float ky, float kw, float a, long posMs) {
        float lane = h * 0.62f;
        boolean replay = hasStored() && posMs > 0;
        if (replay) { // an earlier play: notes fall onto the keys with a 1.5 s lookahead
            for (int i = 0; i < stN; i++) {
                float dtMs = stT[i] * 100f - posMs;
                if (dtMs < -200f || dtMs > 1500f) continue;
                float f = dtMs / 1500f, y = ky - Math.max(0f, f) * lane, x = keyX(stM[i], w);
                note(c, x, y, kw, h, 2 + stM[i], 0.9f * a * (1f - 0.4f * Math.max(0f, f)), 0.7f, true);
                if (dtMs <= 0f && keyTgt[stM[i]] < 0.5f) {
                    keyTgt[stM[i]] = 0.85f;
                    spark(x, ky, 2 + stM[i], 3);
                }
            }
        } else { // first play: notes rise from the keys with a trail
            for (int i = 0; i < NP; i++) {
                if (rl[i] <= 0f) continue;
                float age = 1f - rl[i], y = ky - age * lane, x = keyX(rx[i], w);
                note(c, x, y, kw, h, rc[i], 0.7f * rl[i] * a * rw[i], rh[i], false);
            }
        }
    }

    /** Shock rings from chords, then sparks. */
    private void ringsAndSparks(Canvas c, float w, float h, float ky, float a) {
        // shock rings from chords, then sparks
        for (int r = 0; r < NR; r++) {
            if (rgT[r] < 0f) continue;
            float f = rgT[r];
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, h * 0.006f * (1f - f)));
            paint.setColor(col(rgC[r], 0.22f * (1f - f) * a));
            c.drawCircle(rgX[r], ky, w * (0.04f + 0.3f * f), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < NS; i++) {
            if (spL[i] <= 0f) continue;
            if (look == 3) {
                float l = Math.min(1f, spL[i]), r = h * (0.004f + 0.006f * l);
                glow.setStyle(Paint.Style.STROKE);
                glow.setStrokeWidth(r * 1.6f);
                glow.setColor(col(spC[i], 0.55f * l * a));
                c.drawLine(spX[i], spY[i], spX[i] - spVX[i] * 0.05f, spY[i] - spVY[i] * 0.05f, glow);
                glow.setStyle(Paint.Style.FILL);
                glow.setColor(col(spC[i], 0.3f * l * a));
                c.drawCircle(spX[i], spY[i], r * 3.2f, glow);
                paint.setColor(((int) (0.5f * l * a * 255) << 24) | 0xFFE8D0);
                c.drawCircle(spX[i], spY[i], r, paint);
            } else {
                paint.setColor(col(spC[i], Math.min(1f, spL[i]) * 0.8f * a));
                c.drawCircle(spX[i], spY[i], 2.4f + 2f * spL[i], paint);
            }
        }
    }

    private static int ac(int rgb, float al) {
        return (Math.max(0, Math.min(255, (int) (al * 255f))) << 24) | (rgb & 0xFFFFFF);
    }

    private static boolean black(int m) {
        int pc = m % 12;
        return pc == 1 || pc == 3 || pc == 6 || pc == 8 || pc == 10;
    }

    /**
     * The keyboard: a glass case with a rainbow underglow, an LED bead above every key, keys that spring down in the
     * note's colour with a white-hot top, and a little LED meter that climbs above each held key.
     */
    private void keys(Canvas c, float w, float h, float ky, float kh, float kw, float a, boolean fine) {
        paint.setStyle(Paint.Style.FILL);
        glow.setStyle(Paint.Style.FILL);
        for (int m = 36; m <= 108; m++) { // halo and beam above a sounding key, behind the case
            float lit = keyLit[m];
            if (lit < 0.03f) continue;
            float x = keyX(m, w);
            glow.setColor(col(2 + m, 0.14f * lit * a));
            c.drawCircle(x, ky - h * 0.02f, kw * (2.5f + 7f * (1f - lit)), glow);
            glow.setColor(col(2 + m, 0.09f * lit * a));
            c.drawRect(x - kw * 0.5f, ky - kh * 3f * lit, x + kw * 0.5f, ky, glow);
        }
        // underglow: the register's colours along the bottom edge, brighter under lit keys
        for (int m = 36; m <= 108; m++) {
            float x = keyX(m, w), g = 0.12f + 0.25f * activity + 0.7f * keyLit[m];
            glow.setColor(ac(PCOL[m], g * a));
            rect.set(x - kw * 0.6f, ky + kh, x + kw * 0.6f, ky + kh + h * (0.012f + 0.025f * keyLit[m]));
            c.drawRect(rect, glow);
        }
        // the case
        paint.setColor(ac(warm ? 0x140C0A : 0x0A0C18, 0.95f * a));
        rect.set(w * 0.012f, ky - h * 0.03f, w * 0.988f, ky + kh + h * 0.012f);
        c.drawRoundRect(rect, h * 0.02f, h * 0.02f, paint);
        for (int m = 36; m <= 108; m++) { // LED beads along the rim
            float x = keyX(m, w), lit = keyLit[m];
            glow.setColor(ac(PCOL[m], (0.28f + 0.72f * lit) * a));
            c.drawCircle(x, ky - h * 0.016f, kw * (0.22f + 0.3f * lit), glow);
        }
        // white keys: bright face, darker lip, spring down and fill with the note's colour when lit
        for (int m = 36; m <= 108; m++) {
            if (black(m)) continue;
            float x = keyX(m, w), lit = keyLit[m], press = lit * kh * 0.12f, bottom = ky + kh - h * 0.004f;
            paint.setColor(ac(warm ? 0xF8EFE4 : 0xF2F4FB, 0.96f * a));
            rect.set(x - kw * 0.47f, ky + press, x + kw * 0.47f, bottom);
            c.drawRoundRect(rect, kw * 0.22f, kw * 0.22f, paint);
            paint.setColor(ac(warm ? 0xCDB9A6 : 0xB9C0D8, 0.9f * a));
            rect.set(x - kw * 0.47f, ky + kh * 0.84f + press * 0.4f, x + kw * 0.47f, bottom);
            c.drawRoundRect(rect, kw * 0.22f, kw * 0.22f, paint);
            if (lit > 0.02f) {
                paint.setColor(col(2 + m, Math.min(1f, 1.3f * lit) * a));
                rect.set(x - kw * 0.45f, ky + press, x + kw * 0.45f, bottom);
                c.drawRoundRect(rect, kw * 0.22f, kw * 0.22f, paint);
                paint.setColor(ac(0xFFFFFF, 0.2f * lit * a)); // soft bright top
                rect.set(x - kw * 0.4f, ky + press, x + kw * 0.4f, ky + press + kh * 0.3f);
                c.drawRoundRect(rect, kw * 0.2f, kw * 0.2f, paint);
            }
        }
        // black keys: glossy, shorter, a thin highlight, lit in colour
        for (int m = 36; m <= 108; m++) {
            if (!black(m)) continue;
            float x = keyX(m, w), lit = keyLit[m], press = lit * kh * 0.06f;
            paint.setColor(ac(lit > 0.1f ? 0x1B2140 : 0x05060C, 0.97f * a));
            rect.set(x - kw * 0.32f, ky + press, x + kw * 0.32f, ky + kh * 0.64f + press);
            c.drawRoundRect(rect, kw * 0.2f, kw * 0.2f, paint);
            if (lit > 0.05f) {
                paint.setColor(col(2 + m, 0.9f * lit * a));
                rect.set(x - kw * 0.27f, ky + press + kh * 0.04f, x + kw * 0.27f, ky + kh * 0.6f + press);
                c.drawRoundRect(rect, kw * 0.16f, kw * 0.16f, paint);
            } else {
                paint.setColor(ac(0xFFFFFF, 0.16f * a));
                rect.set(x - kw * 0.2f, ky + kh * 0.04f, x - kw * 0.1f, ky + kh * 0.5f);
                c.drawRect(rect, paint);
            }
        }
        // halo, beam and LED meter above sounding keys
        for (int m = 36; m <= 108; m++) {
            float lit = keyLit[m];
            if (lit < 0.03f) continue;
            float x = keyX(m, w);
            if (!fine) continue;
            int segs = 1 + (int) (lit * 7f);
            for (int sgm = 0; sgm < segs; sgm++) {
                float y = ky - h * 0.04f - sgm * h * 0.024f, f = 1f - sgm / 8f;
                glow.setColor(sgm == segs - 1 ? ac(0xFFFFFF, 0.3f * lit * a) : col(2 + m, 0.65f * f * lit * a));
                rect.set(x - kw * 0.34f, y - h * 0.016f, x + kw * 0.34f, y);
                c.drawRoundRect(rect, kw * 0.1f, kw * 0.1f, glow);
            }
        }
    }

    /** Look 1: curtains of light hang from every sounding key and sway; fresh notes send up wide ribbons. */
    private void curtains(Canvas c, float w, float h, float ky, float kw, float a) {
        for (int m = 36; m <= 108; m++) {
            float lit = keyLit[m];
            if (lit < 0.05f) continue;
            float x = keyX(m, w) + (float) Math.sin(time * 1.3f + m * 0.7f) * kw * 1.5f * lit, top = ky - h * (0.3f + 0.45f * lit);
            for (int k = 0; k < 5; k++) {
                float half = kw * (0.6f + k * 0.9f);
                paint.setColor(((int) ((0.22f - k * 0.035f) * lit * a * 255) << 24) | PCOL[m]);
                rect.set(x - half, top + k * h * 0.035f, x + half, ky);
                c.drawRoundRect(rect, half, half, paint);
            }
        }
        for (int i = 0; i < NP; i++) {
            if (rl[i] <= 0f) continue;
            float age = 1f - rl[i], y = ky - age * h * 0.72f, x = keyX(rx[i], w) + (float) Math.sin(age * 7f + i) * kw * 2.5f, len = h * (0.08f + 0.1f * rh[i]);
            paint.setColor(col(rc[i], 0.55f * rl[i] * rw[i] * a));
            rect.set(x - kw * 0.4f, y - len, x + kw * 0.4f, y);
            c.drawRoundRect(rect, kw * 0.4f, kw * 0.4f, paint);
        }
    }

    /** Look 3: every sounding key is a nozzle: a hot column of light, a pool of glow at its foot, jets above. */
    private void fountainBase(Canvas c, float w, float h, float ky, float kw, float a) {
        for (int m = 36; m <= 108; m++) {
            float lit = keyLit[m];
            if (lit < 0.05f) continue;
            float x = keyX(m, w), top = ky - h * (0.12f + 0.4f * lit);
            glow.setStyle(Paint.Style.FILL);
            for (int k = 0; k < 3; k++) {
                float half = kw * (0.25f + k * 0.45f);
                glow.setColor(((int) ((0.3f - k * 0.08f) * lit * a * 255) << 24) | PCOL[m]);
                rect.set(x - half, top + k * h * 0.04f, x + half, ky);
                c.drawRoundRect(rect, half, half, glow);
            }
            glow.setColor(((int) (0.18f * lit * a * 255) << 24) | PCOL[m]);
            c.drawCircle(x, ky, kw * (2f + 4f * lit), glow);
        }
    }

    /** Look 2: a mandala. Six-fold, mirrored, with turning rings of beads; held notes are spokes, fresh notes bloom with trails. */
    private void bloom(Canvas c, float w, float h, float ky, float kw, float a, boolean fine) {
        final int n = 6;
        final float step = 6.2832f / n;
        float cx = w * 0.5f, cy = h * 0.4f, rad = h * 0.42f, spin = time * 0.14f;
        glow.setStyle(Paint.Style.STROKE);
        for (int k = 0; k < 3; k++) { // slow outer rings
            glow.setStrokeWidth(Math.max(2f, h * 0.004f));
            glow.setColor(((int) ((0.25f + 0.25f * activity) * a * 255) << 24) | PCOL[36 + k * 30]);
            c.drawCircle(cx, cy, rad * (0.55f + 0.22f * k) * (1f + 0.05f * activity * (float) Math.sin(time * 2f + k)), glow);
        }
        glow.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 24; i++) { // a ring of beads, each in its own colour, turning against the spokes
            float an = i * 0.2618f - spin * 2f, r = rad * 0.64f * (1f + 0.03f * (float) Math.sin(time * 3f + i));
            glow.setColor(((int) ((0.28f + 0.25f * activity) * a * 255) << 24) | PCOL[36 + i * 3]);
            c.drawCircle(cx + (float) Math.cos(an) * r, cy + (float) Math.sin(an) * r, kw * (0.8f + 2.4f * activity), glow);
        }
        for (int m = 36; m <= 108; m++) {
            float lit = keyLit[m];
            if (lit < 0.05f) continue;
            float ang = (m - 36) / 72f * 6.2832f + spin, len = rad * (0.3f + 0.68f * lit);
            for (int q = 0; q < n; q++) {
                float an = ang + q * step, ex = cx + (float) Math.cos(an) * len, ey = cy + (float) Math.sin(an) * len;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2f + h * 0.004f * lit);
                paint.setColor(col(2 + m, 0.38f * lit * a));
                c.drawLine(cx + (ex - cx) * 0.22f, cy + (ey - cy) * 0.22f, ex, ey, paint); // starts off-centre so the middle stays clear
                paint.setStyle(Paint.Style.FILL);
                glow.setStyle(Paint.Style.FILL);
                glow.setColor(col(2 + m, 0.2f * lit * a));
                c.drawCircle(ex, ey, kw * (1.2f + 2.2f * lit), glow);
                paint.setColor(((int) (0.35f * lit * a * 255) << 24) | 0xFFFFFF);
                c.drawCircle(ex, ey, kw * (0.6f + 1.2f * lit), paint);
            }
        }
        glow.setStyle(Paint.Style.FILL);
        for (int i = 0; i < NP; i++) {
            if (rl[i] <= 0f) continue;
            float age = 1f - rl[i], ang0 = (rx[i] - 36) / 72f * 6.2832f + spin;
            int trail = fine ? 3 : 0;
            for (int t = 0; t <= trail; t++) {
                float ag = Math.max(0f, age - t * 0.045f), r = ag * rad * 1.1f, ang = ang0 + ag * 0.8f, sz = (4f + 12f * rh[i]) * (0.4f + 0.6f * rl[i]) * (1f - 0.22f * t);
                glow.setColor(col(rc[i], (0.45f - 0.12f * t) * rl[i] * rw[i] * a));
                for (int q = 0; q < n; q++) {
                    float an = ang + q * step;
                    c.drawCircle(cx + (float) Math.cos(an) * r, cy + (float) Math.sin(an) * r, sz, glow);
                    if (t == 0) { // the mirror image, so it reads as a kaleidoscope
                        float am = -an; // reflected across the horizontal axis
                        c.drawCircle(cx + (float) Math.cos(am) * r, cy + (float) Math.sin(am) * r, sz * 0.8f, glow);
                    }
                }
            }
        }
        float core = rad * 0.12f * (1f + 0.7f * activity);
        glow.setColor(col(2 + lastMidi, 0.16f * a));
        c.drawCircle(cx, cy, core * 1.4f, glow);
        glow.setColor(col(2 + lastMidi, 0.25f * a));
        c.drawCircle(cx, cy, core * 0.8f, glow);
        paint.setColor(((int) (0.45f * a * 255) << 24) | 0xFFFFFF);
        c.drawCircle(cx, cy, core * 0.4f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Look 4: every note is a star at its pitch; the melody is joined up into a constellation that fades slowly. */
    private void constellation(Canvas c, float w, float h, float ky, float kw, float a) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, h * 0.003f));
        for (int i = 0; i < NP; i++) {
            int j = (i + NP - 1) % NP;
            if (rl[i] <= 0f || rl[j] <= 0f) continue;
            paint.setColor(col(rc[i], 0.6f * Math.min(rl[i], rl[j]) * a));
            c.drawLine(keyX(rx[j], w), ky - ry[j] * h, keyX(rx[i], w), ky - ry[i] * h, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < NP; i++) {
            if (rl[i] <= 0f) continue;
            float x = keyX(rx[i], w), y = ky - ry[i] * h, tw = 0.75f + 0.25f * (float) Math.sin(time * 5f + i);
            paint.setColor(col(rc[i], 0.18f * rl[i] * a));
            c.drawCircle(x, y, kw * (2f + 5f * rh[i]) * (0.5f + 0.5f * rl[i]), paint);
            paint.setColor(col(rc[i], 0.95f * rl[i] * tw * a));
            c.drawCircle(x, y, kw * (0.8f + 1.5f * rh[i]), paint);
            paint.setColor(((int) (0.9f * rl[i] * a * 255) << 24) | 0xFFFFFF);
            c.drawCircle(x, y, kw * 0.35f, paint);
        }
    }

    /** One falling or rising note: a glow, a long soft trail, then the bright rounded body. */
    private void note(Canvas c, float x, float y, float kw, float h, int pal, float a, float vel, boolean falling) {
        float len = h * (0.07f + 0.09f * vel);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(col(pal, 0.1f * a));
        c.drawCircle(x, y - len * 0.5f, kw * 1.7f, paint);
        for (int k = 3; k >= 1; k--) { // trail
            paint.setColor(col(pal, 0.18f * a / k));
            float ty = falling ? y - len - k * len * 0.9f : y + k * len * 0.7f;
            rect.set(x - kw * 0.28f, ty - len * 0.8f, x + kw * 0.28f, ty);
            c.drawRoundRect(rect, kw * 0.2f, kw * 0.2f, paint);
        }
        paint.setColor(col(pal, a));
        rect.set(x - kw * 0.45f, y - len, x + kw * 0.45f, y);
        c.drawRoundRect(rect, kw * 0.3f, kw * 0.3f, paint);
        paint.setColor(((int) (0.3f * a * 255) << 24) | 0xFFFFFF);
        rect.set(x - kw * 0.2f, y - len + 2f, x + kw * 0.2f, y - len * 0.5f);
        c.drawRoundRect(rect, kw * 0.2f, kw * 0.2f, paint);
    }

    private float staffStep(int midi) {
        int[] st = {0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6};
        return (midi / 12) * 7 + st[midi % 12];
    }

    // The score is flat in "plane" pixels; these project a point onto the screen by hand. A canvas
    // matrix with perspective made the GPU renderer balloon to over 500 MB and the TV killed the app.
    private float qw, qh, qpw, qph, qsway;

    private float zf(float y) {
        return 1f + (1f - y / qph) * 1.1f;
    }

    private float pxX(float u, float y) {
        return qw * 0.5f + (u - qpw * 0.5f) * (qw * 1.15f / qpw) / zf(y) + qsway;
    }

    private float pxY(float y) {
        float z = zf(y), t = (1f / z - 1f / 2.1f) / (1f - 1f / 2.1f);
        return qh * 0.32f + qh * 0.6f * t;
    }

    private void sheet(Canvas c, float w, float h, boolean fine) {
        float a = alpha;
        qw = w;
        qh = h;
        qpw = w * 1.5f;
        qph = h * 0.5f;
        qsway = (float) Math.sin(time * 0.15f) * w * 0.03f;
        float pw = qpw, ph = qph;
        float sp = ph * 0.075f, t0 = ph * 0.17f, b0 = t0 + 6f * sp; // treble bottom line, bass top line
        float speed = pw * 0.12f, playX = pw * 0.62f, scroll = (time * speed) % (sp * 8f);
        float top = t0 - 4f * sp, bot = b0 + 4f * sp;
        // the page, as a quadrilateral
        paint.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(pxX(0, 0), pxY(0));
        path.lineTo(pxX(pw, 0), pxY(0));
        path.lineTo(pxX(pw, ph), pxY(ph));
        path.lineTo(pxX(0, ph), pxY(ph));
        path.close();
        paint.setColor(((int) (0.1f * a * 255) << 24) | 0xE8ECF8);
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        for (int s = 0; s < 2; s++) { // staff lines
            float y0 = s == 0 ? top : b0;
            for (int l = 0; l < 5; l++) {
                float y = y0 + l * sp, z = zf(y);
                paint.setStrokeWidth(Math.max(1.2f, h * 0.0035f / z));
                paint.setColor(((int) (0.55f * a * 255) << 24) | 0xD0D6E8);
                c.drawLine(pxX(0, y), pxY(y), pxX(pw, y), pxY(y), paint);
            }
        }
        paint.setStrokeWidth(Math.max(1.2f, h * 0.002f));
        paint.setColor(((int) (0.4f * a * 255) << 24) | 0xD0D6E8);
        for (float x = -scroll; x < pw; x += sp * 8f) { // bar lines
            c.drawLine(pxX(x, top), pxY(top), pxX(x, bot), pxY(bot), paint);
        }
        // clefs, as projected curves
        float cx = sp * 3f;
        paint.setStrokeWidth(Math.max(2f, h * 0.006f));
        paint.setColor(((int) (0.75f * a * 255) << 24) | 0xE0E4F4);
        path.reset();
        path.moveTo(pxX(cx - sp * 0.4f, t0 + sp * 0.6f), pxY(t0 + sp * 0.6f));
        path.cubicTo(pxX(cx + sp * 1.6f, t0 - sp * 3.8f), pxY(t0 - sp * 3.8f), pxX(cx - sp * 1.6f, t0 - sp * 3.8f), pxY(t0 - sp * 3.8f),
                pxX(cx + sp * 0.2f, t0 - sp * 0.8f), pxY(t0 - sp * 0.8f));
        path.cubicTo(pxX(cx + sp * 1.4f, t0 + sp * 1.4f), pxY(t0 + sp * 1.4f), pxX(cx - sp * 1.4f, t0 + sp * 1.4f), pxY(t0 + sp * 1.4f),
                pxX(cx + sp * 0.1f, t0 - sp * 1.2f), pxY(t0 - sp * 1.2f));
        path.lineTo(pxX(cx + sp * 0.1f, t0 + sp * 1.6f), pxY(t0 + sp * 1.6f));
        c.drawPath(path, paint);
        path.reset();
        path.moveTo(pxX(cx - sp, b0 + sp * 0.4f), pxY(b0 + sp * 0.4f));
        path.cubicTo(pxX(cx + sp * 1.4f, b0 + sp * 0.1f), pxY(b0 + sp * 0.1f), pxX(cx + sp * 1.4f, b0 + sp * 2.8f), pxY(b0 + sp * 2.8f),
                pxX(cx - sp * 0.8f, b0 + sp * 3.6f), pxY(b0 + sp * 3.6f));
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(pxX(cx + sp * 1.7f, b0 + sp * 0.5f), pxY(b0 + sp * 0.5f), h * 0.004f, paint);
        c.drawCircle(pxX(cx + sp * 1.7f, b0 + sp * 1.5f), pxY(b0 + sp * 1.5f), h * 0.004f, paint);
        // notes at their staff positions, drifting away from the playhead
        for (int i = 0; i < NP; i++) {
            if (rl[i] <= 0f) continue;
            int midi = (int) rx[i];
            float d = staffStep(midi), y;
            if (midi >= 60) y = t0 - (d - 37f) * sp * 0.5f;
            else y = b0 + (33f - d) * sp * 0.5f;
            float age = 1f - rl[i], x = playX - age * speed * 1.7f;
            int cc = Variety.cap(midi < 60 ? 0xFFFFB347 : 0xFF5BD0FF) & 0xFFFFFF;
            float al = rl[i] * a, z = zf(y), sx = pxX(x, y), sy = pxY(y), k = h * 0.04f / z;
            if (fine) {
                paint.setColor(((int) (0.16f * al * 255) << 24) | cc);
                c.drawCircle(sx, sy, k * (1.4f + 1.6f * (1f - rl[i])), paint);
                for (int q = 1; q <= 3; q++) { // trailing sparkles
                    paint.setColor(((int) (0.4f * al / q * 255) << 24) | cc);
                    c.drawCircle(sx + q * k * 0.9f, sy + (q % 2 == 0 ? -1 : 1) * k * 0.25f * q, k * 0.12f, paint);
                }
            }
            paint.setColor(((int) (0.95f * al * 255) << 24) | cc);
            rect.set(sx - k * 0.62f, sy - k * 0.42f, sx + k * 0.62f, sy + k * 0.42f);
            c.drawOval(rect, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, k * 0.1f));
            boolean up = y > (midi >= 60 ? t0 - 2f * sp : b0 + 2f * sp);
            float stx = up ? sx + k * 0.6f : sx - k * 0.6f;
            c.drawLine(stx, sy, stx, up ? sy - k * 3f : sy + k * 3f, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        // the playhead
        paint.setColor(((int) (0.12f * a * 255) << 24) | 0xFFFFFF);
        rect.set(pxX(playX, top) - 3f, pxY(top), pxX(playX, bot) + 3f, pxY(bot));
        c.drawRect(rect, paint);
    }
}
