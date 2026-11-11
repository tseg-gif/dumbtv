package tv.dumbtv.home;

/**
 * The singing silhouette as plain bezier geometry (no Android classes, so it can be previewed on a desktop).
 * Units: the head is about 1.1 tall, origin at the middle of the skull, the face looks right, y grows down.
 * The jaw is its own shape hinged at the ear; it rotates open. Nothing here infers who the singer is.
 */
final class SingerGeom {
    private SingerGeom() {
    }

    /** What the geometry draws onto (an AWT or Canvas adapter). */
    interface Gfx {
        void save();

        void restore();

        void translate(float x, float y);

        void rotate(float deg);

        void moveTo(float x, float y);

        void lineTo(float x, float y);

        void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3);

        /** Closes the current path, fills it and starts a new one. */
        void fill(int argb);

        void circle(float cx, float cy, float r, int argb);

        void line(float x0, float y0, float x1, float y1, float width, int argb);
    }

    static final int CAP = 0, AFRO = 1, LONG = 2, SHORT = 3, PHONES = 4, BARE = 5;
    static final int HAIRS = 6;
    /** Where the head turns on the neck. */
    static final float NX = -0.10f, NY = 0.45f;
    /** The jaw hinge, just below and behind the ear. */
    static final float HX = -0.02f, HY = 0.13f;

    /** Back layer: long hair behind, neck and shoulders. */
    static void back(Gfx g, int hair) {
        if (hair == LONG) {
            g.moveTo(-0.30f, -0.40f);
            g.cubicTo(-0.66f, -0.30f, -0.72f, 0.50f, -0.80f, 1.10f);
            g.cubicTo(-0.60f, 1.24f, -0.30f, 1.20f, -0.12f, 1.10f);
            g.cubicTo(-0.22f, 0.70f, -0.18f, 0.20f, -0.05f, 0.05f);
            g.fill(0);
        }
        // neck and shoulders
        g.moveTo(-0.34f, 0.20f);
        g.cubicTo(-0.34f, 0.46f, -0.38f, 0.62f, -0.44f, 0.72f);
        g.cubicTo(-0.68f, 0.78f, -0.96f, 0.90f, -1.02f, 1.20f);
        g.lineTo(-1.02f, 1.50f);
        g.lineTo(1.02f, 1.50f);
        g.cubicTo(1.00f, 1.00f, 0.80f, 0.84f, 0.46f, 0.74f);
        g.cubicTo(0.26f, 0.68f, 0.20f, 0.60f, 0.18f, 0.48f);
        g.cubicTo(0.16f, 0.36f, 0.08f, 0.30f, -0.04f, 0.26f);
        g.fill(0);
    }

    /** Head layer: skull, jaw (open by jawDeg), hair or hat, drawn with the head turned headDeg about the neck. */
    static void head(Gfx g, int hair, float jawDeg, float headDeg) {
        g.save();
        g.translate(NX, NY);
        g.rotate(headDeg);
        g.translate(-NX, -NY);
        if (hair == AFRO) {
            float[][] c = {{-0.14f, -0.30f, 0.52f}, {-0.50f, -0.12f, 0.30f}, {0.10f, -0.62f, 0.34f}, {-0.36f, -0.56f, 0.34f},
                {0.30f, -0.44f, 0.24f}, {-0.58f, -0.34f, 0.26f}, {-0.06f, -0.74f, 0.28f}, {-0.52f, 0.10f, 0.20f}};
            for (float[] k : c) g.circle(k[0], k[1], k[2], 0);
        }
        // skull and upper face down to the mouth line
        g.moveTo(HX, 0.19f);
        g.cubicTo(-0.42f, 0.14f, -0.54f, -0.18f, -0.40f, -0.40f);
        g.cubicTo(-0.28f, -0.62f, 0.18f, -0.68f, 0.37f, -0.41f);
        g.cubicTo(0.45f, -0.30f, 0.46f, -0.20f, 0.45f, -0.11f);
        g.cubicTo(0.48f, -0.05f, 0.52f, 0.00f, 0.51f, 0.05f);
        g.cubicTo(0.50f, 0.08f, 0.46f, 0.09f, 0.44f, 0.11f);
        g.cubicTo(0.455f, 0.15f, 0.46f, 0.18f, 0.43f, 0.215f);
        g.lineTo(0.30f, 0.215f);
        g.cubicTo(0.20f, 0.26f, 0.06f, 0.28f, HX, 0.19f);
        g.fill(0);
        // jaw, hinged at the ear
        g.save();
        g.translate(HX, HY);
        g.rotate(jawDeg);
        g.translate(-HX, -HY);
        g.moveTo(0.43f, 0.20f);
        g.cubicTo(0.465f, 0.23f, 0.46f, 0.27f, 0.42f, 0.29f);
        g.cubicTo(0.39f, 0.31f, 0.395f, 0.34f, 0.41f, 0.37f);
        g.cubicTo(0.43f, 0.43f, 0.37f, 0.52f, 0.27f, 0.53f);
        g.cubicTo(0.14f, 0.52f, 0.04f, 0.46f, -0.06f, 0.35f);
        g.cubicTo(-0.13f, 0.28f, -0.15f, 0.15f, -0.08f, 0.04f);
        g.lineTo(0.20f, 0.04f);
        g.fill(0);
        g.restore();
        switch (hair) {
            case CAP:
                g.moveTo(-0.46f, -0.10f);
                g.cubicTo(-0.56f, -0.58f, -0.10f, -0.80f, 0.26f, -0.64f);
                g.cubicTo(0.40f, -0.58f, 0.46f, -0.44f, 0.47f, -0.34f);
                g.lineTo(-0.10f, -0.30f);
                g.fill(0);
                g.moveTo(0.20f, -0.40f);
                g.cubicTo(0.46f, -0.44f, 0.78f, -0.40f, 0.90f, -0.30f);
                g.cubicTo(0.92f, -0.26f, 0.88f, -0.24f, 0.82f, -0.25f);
                g.cubicTo(0.60f, -0.30f, 0.40f, -0.30f, 0.18f, -0.28f);
                g.fill(0);
                break;
            case SHORT:
                g.moveTo(-0.44f, 0.02f);
                g.cubicTo(-0.60f, -0.24f, -0.52f, -0.58f, -0.20f, -0.68f);
                g.cubicTo(0.12f, -0.78f, 0.42f, -0.62f, 0.46f, -0.32f);
                g.cubicTo(0.32f, -0.44f, 0.08f, -0.46f, -0.14f, -0.30f);
                g.cubicTo(-0.26f, -0.20f, -0.30f, -0.04f, -0.34f, 0.08f);
                g.fill(0);
                break;
            case LONG:
                g.moveTo(-0.44f, 0.10f);
                g.cubicTo(-0.62f, -0.24f, -0.50f, -0.62f, -0.12f, -0.70f);
                g.cubicTo(0.22f, -0.76f, 0.44f, -0.58f, 0.46f, -0.30f);
                g.cubicTo(0.28f, -0.46f, 0.00f, -0.44f, -0.16f, -0.26f);
                g.cubicTo(-0.28f, -0.10f, -0.30f, 0.10f, -0.34f, 0.30f);
                g.fill(0);
                break;
            case PHONES:
                for (int i = 0; i < 12; i++) {
                    float a0 = (float) Math.PI * (0.02f + 0.96f * i / 12f), a1 = (float) Math.PI * (0.02f + 0.96f * (i + 1) / 12f);
                    g.line(-0.02f - 0.50f * (float) Math.cos(a0), 0.04f - 0.66f * (float) Math.sin(a0),
                        -0.02f - 0.50f * (float) Math.cos(a1), 0.04f - 0.66f * (float) Math.sin(a1), 0.07f, 0);
                }
                g.circle(-0.02f, 0.04f, 0.15f, 0);
                break;
            default:
                break;
        }
        g.restore();
    }

    /** A hand-held microphone in front of the mouth. */
    static void mic(Gfx g) {
        g.line(0.80f, 0.34f, 0.98f, 1.50f, 0.07f, 0);
        g.circle(0.74f, 0.27f, 0.095f, 0);
    }

    /**
     * Everything for one frame. The rim is the same shape pushed up and right, drawn first, so a thin light edge
     * shows on the lit side; the body goes on top. rimArgb is white in the atlas and tinted when drawn.
     */
    static void render(Gfx g, int hair, boolean mic, float jawDeg, float headDeg, int rimArgb, int bodyArgb, float rim, boolean backToo, boolean headToo) {
        for (int pass = 0; pass < 3; pass++) {
            g.save();
            int col = bodyArgb;
            if (pass == 0) {
                g.translate(rim * 1.9f, -rim * 0.9f);
                col = (rimArgb & 0x00FFFFFF) | 0x50000000;
            } else if (pass == 1) {
                g.translate(rim, -rim * 0.45f);
                col = rimArgb;
            }
            Recolor r = new Recolor(g, col);
            if (backToo) back(r, hair);
            if (headToo) head(r, hair, jawDeg, headDeg);
            if (mic && backToo) mic(r);
            g.restore();
        }
    }

    /** Forces one colour on every fill. */
    private static final class Recolor implements Gfx {
        private final Gfx g;
        private final int col;

        Recolor(Gfx g, int col) {
            this.g = g;
            this.col = col;
        }

        public void save() {
            g.save();
        }

        public void restore() {
            g.restore();
        }

        public void translate(float x, float y) {
            g.translate(x, y);
        }

        public void rotate(float deg) {
            g.rotate(deg);
        }

        public void moveTo(float x, float y) {
            g.moveTo(x, y);
        }

        public void lineTo(float x, float y) {
            g.lineTo(x, y);
        }

        public void cubicTo(float a, float b, float c, float d, float e, float f) {
            g.cubicTo(a, b, c, d, e, f);
        }

        public void fill(int argb) {
            g.fill(col);
        }

        public void circle(float cx, float cy, float r, int argb) {
            g.circle(cx, cy, r, col);
        }

        public void line(float x0, float y0, float x1, float y1, float w, int argb) {
            g.line(x0, y0, x1, y1, w, col);
        }
    }
}
