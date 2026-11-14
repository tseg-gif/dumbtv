package tv.dumbtv.home;

/**
 * One concert "look": a stack of five independently chosen layers. The signature is the five layer ids;
 * the beam pattern and the backdrop preset add variety but do not count as a different look, and neither
 * does the palette (it comes from the cover art and is always the last variation axis).
 */
final class Look {
    /** Glow scene names (GL particle rooms) used as the LED-wall backdrop. */
    static final String[] BACKDROPS = {"tunnel", "galaxy", "dunes", "rain", "aurora_ridge", "mandala_bloom", "plexus",
        "synth_road", "hyperspace", "night_ocean", "city_drive", "crystal_tunnel", "dot_sheet", "lanterns",
        "firefly_meadow", "sunset_rays", "vortex", "underwater_night", "torus_knot"};
    static final String[] BEAMS = {"none", "fan", "heads", "pillars", "lattice", "rings", "wipers", "cross", "curtain"};
    static final String[] FGS = {"edge", "truss", "haze", "floor", "rise", "motes", "rail"}; // all non-human
    static final String[] ACCENTS = {"fountain", "confetti", "sparks", "risers", "shock", "dust"};
    static final String[] CAMS = {"push", "drift", "tilt", "wide"};
    static final int LAYERS = 5;

    final int backdrop, beam, fg, accent, cam;
    final int pattern, variant; // beam pattern 0..2, backdrop preset 0..3: not part of the signature
    final int kind;             // the section kind this look was planned for (Director.INTRO ...)

    Look(int backdrop, int beam, int fg, int accent, int cam, int pattern, int variant, int kind) {
        this.backdrop = backdrop;
        this.beam = beam;
        this.fg = fg;
        this.accent = accent;
        this.cam = cam;
        this.pattern = pattern;
        this.variant = variant;
        this.kind = kind;
    }

    int layer(int i) {
        switch (i) {
            case 0: return backdrop;
            case 1: return beam;
            case 2: return fg;
            case 3: return accent;
            default: return cam;
        }
    }

    /** How many of the five layers differ. */
    int differ(Look o) {
        int n = 0;
        for (int i = 0; i < LAYERS; i++) if (layer(i) != o.layer(i)) n++;
        return n;
    }

    String signature() {
        return backdrop + "/" + beam + "/" + fg + "/" + accent + "/" + cam;
    }

    String name() {
        return BACKDROPS[backdrop] + " + " + BEAMS[beam] + (beam == 0 ? "" : "." + pattern) + " + " + FGS[fg] + " + "
            + ACCENTS[accent] + " + " + CAMS[cam];
    }

    static int idx(String[] vocab, String s) {
        for (int i = 0; i < vocab.length; i++) if (vocab[i].equals(s)) return i;
        throw new IllegalArgumentException(s);
    }
}
