package tv.dumbtv.home;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * Haptic ticks for moving along a row: each tile has its own pitch on a pentatonic scale, so
 * sliding right climbs and sliding left falls, and a row change is a deeper thump. The notes
 * are synthesized once at start-up (no asset files) and replayed from memory.
 */
final class Blips {
    private static final int RATE = 22050;
    private static final int NOTES = 15;
    /** Semitones of a major pentatonic scale over three octaves, from C4. */
    private static final int[] SCALE = {0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24, 26, 28, 31, 33};

    /** Fifteen tiles play the first phrase of Ode to Joy (public domain), so a slide along a row is a tune. */
    private static final int[] TUNE = {4, 4, 5, 7, 7, 5, 4, 2, 0, 0, 2, 4, 4, 2, 2};
    /** The pills answer a fifth above, a rising line that resolves. */
    private static final int[] ANSWER = {7, 9, 11, 12, 11, 9, 7, 4};
    private static final int PILLS = 8;
    private final AudioTrack[] tracks = new AudioTrack[NOTES + 1 + PILLS];
    private final AudioManager audio;
    private volatile boolean ready;

    private final Config cfg;
    private final boolean buzz;
    private final String mode;

    Blips(android.content.Context c, Config cfg) {
        this.cfg = cfg;
        mode = cfg.get(Config.SCROLL_SOUND);
        buzz = "buzz".equals(mode);
        audio = (AudioManager) c.getSystemService(android.content.Context.AUDIO_SERVICE);
        Thread t = new Thread(new Runnable() { @Override public void run() { build(); } }, "dumbtv-blips");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void build() {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            AudioFormat fmt = new AudioFormat.Builder().setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
            for (int i = 0; i < tracks.length; i++) {
                short[] pcm;
                if (buzz) pcm = note(i == NOTES ? 60.0 : 80.0 + SCALE[i] * 2.0, i == NOTES ? 0.16 : 0.11);
                else if (i == NOTES) pcm = mallet(98.0, 0.12);
                else if (i > NOTES) {
                    int j = i - NOTES - 1;
                    pcm = "tune".equals(mode)
                            ? voice(mode, Math.pow(2, ANSWER[j] / 12.0), Math.pow(2, ANSWER[(j + 1) % PILLS] / 12.0))
                            : voice(mode, 1.4983 * Math.pow(2, SCALE[j] / 12.0), 1.0); // pills: the answering line
                } else {
                    pcm = "tune".equals(mode)
                            ? voice(mode, Math.pow(2, TUNE[i] / 12.0), Math.pow(2, TUNE[(i + 1) % NOTES] / 12.0))
                            : voice(mode, Math.pow(2, SCALE[i] / 12.0), 1.0);
                }
                AudioTrack tr = new AudioTrack(attrs, fmt, pcm.length * 2, AudioTrack.MODE_STATIC,
                        AudioManager.AUDIO_SESSION_ID_GENERATE);
                tr.write(pcm, 0, pcm.length);
                tr.setVolume(buzz ? (i == NOTES ? 0.8f : 0.6f) : (i == NOTES ? 0.55f : 0.34f));
                tracks[i] = tr;
            }
            ready = true;
        } catch (RuntimeException e) {
            ready = false;
        }
    }

    /**
     * A soft glass pluck: a clean tone that glides up a hair on the attack, with two inharmonic
     * bell partials that die quickly and a one-millisecond tick on the front. Row changes
     * (below 150 Hz) are a hollow wood knock instead.
     */
    private static short[] mallet(double hz, double seconds) {
        int n = (int) (RATE * seconds);
        short[] out = new short[n];
        boolean knock = hz < 150.0;
        double f0 = knock ? 190.0 : hz;
        int seed = 777;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double glide = 1.0 + 0.025 * Math.exp(-t * 90.0);
            double ph = 2 * Math.PI * f0 * glide * t;
            double v;
            if (knock) {
                v = Math.sin(ph) * Math.exp(-t * 38.0) + 0.5 * Math.sin(ph * 2.1) * Math.exp(-t * 70.0);
            } else {
                v = Math.sin(ph) * Math.exp(-t * 24.0);
                if (f0 * 2.76 < 10000) v += 0.32 * Math.sin(ph * 2.76) * Math.exp(-t * 60.0);
                if (f0 * 5.4 < 10000) v += 0.12 * Math.sin(ph * 5.4) * Math.exp(-t * 100.0);
            }
            if (t < 0.001) {
                seed = seed * 1103515245 + 12345;
                v += 0.25 * (((seed >> 16) & 0x7FFF) / 16384.0 - 1.0);
            }
            double env = Math.min(1.0, t / 0.0015) * Math.min(1.0, (seconds - t) / 0.03);
            out[i] = (short) (Math.max(-1.0, Math.min(1.0, v * env * 0.8)) * 12000);
        }
        return out;
    }

    /**
     * One step as a little chord: the note, a perfect fifth above it a beat later, and a soft room
     * tail of three echoes. Every note is from the D major pentatonic, so any run of steps, left
     * and right, stays in key and the tails of neighbouring tiles blend into harmony.
     */
    private static short[] bloom(double hz) {
        short[] a = mallet(hz, 0.18), b = mallet(hz * 1.5, 0.18);
        int n = (int) (RATE * 0.5);
        int[] at = {0, (int) (RATE * 0.04), (int) (RATE * 0.09), (int) (RATE * 0.18), (int) (RATE * 0.29)};
        double[] g = {1.0, 0.42, 0.3, 0.16, 0.08};
        short[][] src = {a, b, a, a, a};
        float[] mix = new float[n];
        for (int k = 0; k < at.length; k++) {
            for (int i = 0; i < src[k].length && at[k] + i < n; i++) mix[at[k] + i] += (float) (src[k][i] * g[k]);
        }
        short[] out = new short[n];
        for (int i = 0; i < n; i++) out[i] = (short) Math.max(-30000f, Math.min(30000f, mix[i] * 0.7f));
        return out;
    }

    /** One tile's sound in the chosen voice; k is the pitch ratio above the voice's base note. */
    private static short[] voice(String mode, double k, double kn) {
        switch (mode) {
            case "tune":
                return tune(261.63 * k, 261.63 * kn);
            case "pop":
                return pop(330.0 * k);
            case "boing":
                return boing(246.94 * k);
            case "bloop":
                return bloop(392.0 * k);
            case "arcade":
                return arcade(293.66 * k);
            case "bongo":
                return bongo(196.0 * k);
            case "glass":
                return mallet(392.0 * k, 0.2);
            case "marimba":
                return marimba(261.63 * k);
            case "kalimba":
                return kalimba(523.25 * k);
            case "bubble":
                return bubble(330.0 * k);
            case "synth":
                return synth(293.66 * k);
            default:
                return bloom(293.66 * k);
        }
    }

    /** A bright, bouncy pluck with a quick upward scoop and a small echo: easy to hear, and it carries the tune. */
    private static short[] tune(double hz, double next) {
        int n = (int) (RATE * 0.42);
        short[] out = new short[n];
        // a long-short figure: the tile's own note, then a softer lead-in to the next note of the tune
        addTone(out, 0, hz, 1.0);
        addTone(out, (int) (RATE * 0.14), next, 0.38);
        return out;
    }

    /** A smooth, rounded tone: soft attack, gentle upper partials and an easy release. */
    private static void addTone(short[] out, int from, double hz, double amp) {
        int len = (int) (RATE * 0.26);
        for (int i = 0; i < len && from + i < out.length; i++) {
            double t = i / (double) RATE;
            double ph = 2 * Math.PI * hz * t;
            double v = Math.sin(ph) + 0.28 * Math.sin(ph * 2.0) * Math.exp(-t * 14.0) + 0.08 * Math.sin(ph * 3.0) * Math.exp(-t * 22.0);
            double env = Math.min(1.0, t / 0.009) * Math.exp(-t * 9.0) * Math.min(1.0, (0.26 - t) / 0.06);
            int j = from + i;
            out[j] = (short) Math.max(-30000, Math.min(30000, out[j] + v * env * amp * 9500));
        }
    }

    /** The previous bouncy version, kept for reference. */
    private static short[] tuneBouncy(double hz) {
        int n = (int) (RATE * 0.3);
        short[] out = new short[n];
        int echo = (int) (RATE * 0.09);
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            ph += 2 * Math.PI * hz * (1.0 + 0.06 * Math.exp(-t * 120.0) - 0.06) / RATE + 2 * Math.PI * hz * 0.06 / RATE;
            double v = Math.sin(ph) + 0.45 * Math.sin(ph * 2.0) * Math.exp(-t * 18.0) + 0.18 * Math.sin(ph * 3.0) * Math.exp(-t * 30.0);
            double env = Math.exp(-t * 11.0) * Math.min(1.0, t / 0.002) * Math.min(1.0, (0.3 - t) / 0.04);
            out[i] = (short) Math.max(-30000, Math.min(30000, out[i] + v * env * 10500));
            if (i + echo < n) out[i + echo] = (short) (out[i + echo] + v * env * 2600);
        }
        return out;
    }

    /** A cork pop: starts low and snaps up, with a tiny click. Short and rubbery. */
    private static short[] pop(double hz) {
        int n = (int) (RATE * 0.085);
        short[] out = new short[n];
        double ph = 0;
        int seed = 4242;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            ph += 2 * Math.PI * hz * (0.62 + 0.55 * (1.0 - Math.exp(-t * 90.0))) / RATE;
            double v = Math.sin(ph) + 0.3 * Math.sin(ph * 2.0);
            if (t < 0.0015) {
                seed = seed * 1103515245 + 12345;
                v += 0.5 * (((seed >> 16) & 0x7FFF) / 16384.0 - 1.0);
            }
            out[i] = (short) (v * Math.exp(-t * 42.0) * Math.min(1.0, t / 0.001) * 11000);
        }
        return out;
    }

    /** A spring: the pitch wobbles like a plucked ruler and settles. */
    private static short[] boing(double hz) {
        int n = (int) (RATE * 0.26);
        short[] out = new short[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            ph += 2 * Math.PI * hz * (1.0 + 0.22 * Math.sin(2 * Math.PI * 24.0 * t) * Math.exp(-t * 11.0) + 0.5 * Math.exp(-t * 40.0)) / RATE;
            double v = Math.sin(ph) + 0.35 * Math.sin(ph * 2.0) * Math.exp(-t * 20.0);
            out[i] = (short) (v * Math.exp(-t * 12.0) * Math.min(1.0, t / 0.002) * Math.min(1.0, (0.26 - t) / 0.03) * 9500);
        }
        return out;
    }

    /** A soft water bloop: the pitch falls as it opens, with a round body. */
    private static short[] bloop(double hz) {
        int n = (int) (RATE * 0.15);
        short[] out = new short[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            ph += 2 * Math.PI * hz * (1.35 - 0.55 * (1.0 - Math.exp(-t * 26.0))) / RATE;
            out[i] = (short) (Math.sin(ph) * Math.exp(-t * 17.0) * Math.min(1.0, t / 0.004) * Math.min(1.0, (0.15 - t) / 0.02) * 11000);
        }
        return out;
    }

    /** A chiptune blip: two quick square-wave steps, the second a fifth up. */
    private static short[] arcade(double hz) {
        int n = (int) (RATE * 0.12);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double f = t < 0.045 ? hz : hz * 1.5;
            double sq = Math.sin(2 * Math.PI * f * t) >= 0 ? 1.0 : -1.0;
            double env = Math.exp(-(t < 0.045 ? t : t - 0.045) * 30.0) * Math.min(1.0, (0.12 - t) / 0.01);
            out[i] = (short) (sq * env * 4200);
        }
        return out;
    }

    /** A small hand drum: a pitch that drops fast, a body thump and a skin tick. */
    private static short[] bongo(double hz) {
        int n = (int) (RATE * 0.13);
        short[] out = new short[n];
        double ph = 0;
        int seed = 99;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            ph += 2 * Math.PI * hz * (1.0 + 0.9 * Math.exp(-t * 55.0)) / RATE;
            double v = Math.sin(ph) * Math.exp(-t * 32.0);
            if (t < 0.006) {
                seed = seed * 1103515245 + 12345;
                v += 0.45 * (((seed >> 16) & 0x7FFF) / 16384.0 - 1.0) * Math.exp(-t * 500.0);
            }
            out[i] = (short) (v * 13000);
        }
        return out;
    }

    private static short[] marimba(double hz) {
        int n = (int) (RATE * 0.22);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double v = Math.sin(2 * Math.PI * hz * t) + 0.28 * Math.sin(4 * Math.PI * hz * t) * Math.exp(-t * 40.0);
            out[i] = (short) (v * Math.exp(-t * 22.0) * Math.min(1.0, i / 90.0) * 9000);
        }
        return out;
    }

    /** A thumb piano: a plucked tine with a metallic overtone and a long, gentle ring. */
    private static short[] kalimba(double hz) {
        int n = (int) (RATE * 0.4);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double v = Math.sin(2 * Math.PI * hz * t) * Math.exp(-t * 9.0) + (hz * 5.4 < 10000 ? 0.22 * Math.sin(2 * Math.PI * hz * 5.4 * t) * Math.exp(-t * 45.0) : 0.0);
            out[i] = (short) (v * Math.min(1.0, t / 0.002) * Math.min(1.0, (0.4 - t) / 0.05) * 9500);
        }
        return out;
    }

    /** A water drop: a short sine that swoops upward. */
    private static short[] bubble(double hz) {
        int n = (int) (RATE * 0.14);
        short[] out = new short[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            ph += 2 * Math.PI * hz * (1.0 + 0.9 * (1.0 - Math.exp(-t * 28.0))) / RATE;
            out[i] = (short) (Math.sin(ph) * Math.exp(-t * 20.0) * Math.min(1.0, t / 0.003) * 11000);
        }
        return out;
    }

    /** A soft synth arpeggio: the note and its major third, a filtered saw each, 45 ms apart. */
    private static short[] synth(double hz) {
        int n = (int) (RATE * 0.3);
        short[] out = new short[n];
        double[] f = {hz, hz * 1.25};
        int gap = (int) (RATE * 0.045);
        for (int k = 0; k < 2; k++) {
            for (int i = 0; i + k * gap < n; i++) {
                double t = i / (double) RATE, v = 0;
                for (int h = 1; h <= 5; h++) v += Math.sin(2 * Math.PI * f[k] * h * t) / (h * h);
                int j = i + k * gap;
                out[j] = (short) Math.max(-30000, Math.min(30000, out[j] + v * Math.exp(-t * 14.0) * Math.min(1.0, t / 0.004) * 6500));
            }
        }
        return out;
    }

    /** A vibration-motor buzz: a short steady low tone, shaken at about 45 Hz, with a quick attack and release. */
    private static short[] note(double hz, double seconds) {
        int n = (int) (RATE * seconds);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double env = Math.min(1.0, t / 0.004) * Math.min(1.0, (seconds - t) / 0.03);
            double shake = 0.62 + 0.38 * Math.sin(2 * Math.PI * 45.0 * t);
            double ph = 2 * Math.PI * hz * t;
            double v = (Math.sin(ph) + 0.5 * Math.sin(2 * ph) + 0.25 * Math.sin(3 * ph)) * shake;
            out[i] = (short) (Math.max(-1.0, Math.min(1.0, v * env * 0.6)) * 15000);
        }
        return out;
    }

    /** Tile {@code index} of a row; index below zero is a row change. */
    /** A Quick launch pill or header chip: its own set of notes, a fifth above the tiles. */
    void playPill(int index) {
        playTrack(NOTES + 1 + Math.max(0, Math.min(PILLS - 1, index)));
    }

    void play(int index) {
        playTrack(index < 0 ? NOTES : Math.min(index, NOTES - 1));
    }

    private void playTrack(int i) {
        if (!ready || audio == null || "off".equals(cfg.get(Config.SCROLL_SOUND))) return;
        AudioTrack t = tracks[i];
        if (t == null) return;
        try {
            float base = buzz ? (i == NOTES ? 0.8f : 0.6f) : (i == NOTES ? 0.55f : 0.34f);
            t.setVolume(audio.isMusicActive() ? base * 0.5f : base); // quieter over music, never silent
            t.stop();
            t.setPlaybackHeadPosition(0);
            t.play();
        } catch (RuntimeException ignored) {
            // the track is busy or was released: skip this note
        }
    }

    void release() {
        ready = false;
        for (int i = 0; i < tracks.length; i++) {
            if (tracks[i] != null) {
                try { tracks[i].release(); } catch (RuntimeException ignored) { }
                tracks[i] = null;
            }
        }
    }
}
