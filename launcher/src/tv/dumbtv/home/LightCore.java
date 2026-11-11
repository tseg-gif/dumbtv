package tv.dumbtv.home;

/**
 * The light bars' colour logic, with no Android classes so it can be tested on a desktop. Feed it the predicted beat
 * grid and the song accent every tick; it gives the Left and Right colours. Comfort limits are built in: saturation
 * at most 0.7, base brightness at most 60% (30% from 9 pm to 6 am), at most 3 pulses a second.
 */
final class LightCore {
    static final int MODE_FOLLOW = 0, MODE_CALM = 1, MODE_OFF = 2;
    static final int CTX_MUSIC = 0, CTX_MEDITATION = 1, CTX_AWAY = 2;
    private static final float WARM_HUE = 28f;

    final int[] left = new int[3], right = new int[3];
    /** False when the lamps should be switched off. */
    boolean on = true;

    private float lastKickEnv;
    private long lastBeats = -1, lastPulseAt, lastTick;
    private float env, nudgeL, nudgeR, hue = 200f, sat = 0.5f;
    private boolean snareSide;
    private boolean haveHue;

    /**
     * @param beats      predicted beats since the clock started, read at (now + the light delay)
     * @param beatInBar  0..3, 0 is the downbeat
     * @param conf       beat clock confidence 0..1
     * @param idleWarm   when away: true = static dim warm colour, false = off
     */
    void tick(long nowMs, long beats, int beatInBar, float conf, float accHue, float accSat, int mode, int ctx, int hour,
            float master, boolean playing, boolean idleWarm, float kickEnv) {
        float dt = lastTick == 0 ? 0.06f : Math.min(0.5f, (nowMs - lastTick) / 1000f);
        lastTick = nowMs;
        boolean night = hour >= 21 || hour < 6;
        float cap = night ? 0.30f : 0.60f;
        float targetHue = accHue, targetSat = Math.min(0.7f, accSat);
        float v;
        boolean calm = mode == MODE_CALM || ctx == CTX_MEDITATION || !playing;
        on = true;
        if (mode == MODE_OFF) on = false;
        if (ctx == CTX_AWAY) {
            if (!idleWarm) on = false;
            targetHue = WARM_HUE;
            targetSat = 0.55f;
            calm = true;
        }
        if (ctx == CTX_MEDITATION) {
            targetHue = WARM_HUE;
            targetSat = 0.55f;
        }
        if (!haveHue) {
            hue = targetHue;
            sat = targetSat;
            haveHue = true;
        }
        // crossfade of at least 1.5 s: a first-order follower with a 0.6 s time constant settles in about that long
        float k = 1f - (float) Math.exp(-dt / 0.6f);
        float dh = ((targetHue - hue + 540f) % 360f) - 180f;
        hue = (hue + dh * k + 360f) % 360f;
        sat += (targetSat - sat) * k;

        if (ctx == CTX_AWAY) {
            v = 0.12f;
            env = 0f;
        } else if (calm) {
            v = cap * 0.35f * (0.75f + 0.25f * (float) Math.sin(nowMs / 1000.0 * 2.0 * Math.PI / 9.0));
            if (ctx == CTX_MEDITATION) v = Math.min(v, 0.22f);
            env = 0f;
        } else {
            boolean kickEdge = kickEnv >= 0.95f && lastKickEnv < 0.95f;
            lastKickEnv = kickEnv;
            boolean beatNew = conf >= 0.5f ? beats != lastBeats && lastBeats >= 0 : kickEdge;
            if (beatNew && nowMs - lastPulseAt >= 334) {
                lastPulseAt = nowMs;
                env = beatInBar == 0 ? 0.35f : 0.28f; // +28% on a beat, a little more on the downbeat
                if (beatInBar == 1 || beatInBar == 3) {
                    snareSide = !snareSide;
                    if (snareSide) nudgeL = 18f;
                    else nudgeR = -18f;
                }
            }
            env *= (float) Math.exp(-dt / 0.10f); // about 300 ms to nothing
            nudgeL *= (float) Math.exp(-dt / 0.45f);
            nudgeR *= (float) Math.exp(-dt / 0.45f);
            v = Math.min(cap, cap * 0.72f * (1f + env));
        }
        lastBeats = beats;
        v *= master;
        fill(left, hue + (calm ? 0f : nudgeL), sat, v);
        fill(right, hue + (calm ? 0f : nudgeR), sat, v);
    }

    private static void fill(int[] o, float h, float s, float v) {
        h = ((h % 360f) + 360f) % 360f;
        float c = v * s, x = c * (1f - Math.abs((h / 60f) % 2f - 1f)), m = v - c;
        float r, g, b;
        int sec = (int) (h / 60f);
        switch (sec) {
            case 0: r = c; g = x; b = 0; break;
            case 1: r = x; g = c; b = 0; break;
            case 2: r = 0; g = c; b = x; break;
            case 3: r = 0; g = x; b = c; break;
            case 4: r = x; g = 0; b = c; break;
            default: r = c; g = 0; b = x; break;
        }
        o[0] = Math.round((r + m) * 255f);
        o[1] = Math.round((g + m) * 255f);
        o[2] = Math.round((b + m) * 255f);
    }

    /** True when a colour differs from the last sent one by about 4% or more. */
    static boolean changed(int[] a, int[] last) {
        for (int i = 0; i < 3; i++) if (Math.abs(a[i] - last[i]) >= 10) return true;
        return false;
    }
}
