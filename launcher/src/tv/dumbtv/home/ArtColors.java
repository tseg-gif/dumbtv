package tv.dumbtv.home;

import android.graphics.Bitmap;
import android.graphics.Color;

/** Dominant colors of a cover: 16x16 downsample, a few rounds of k-means, softened for the grade. */
final class ArtColors {
    private ArtColors() {
    }

    /** Up to 4 RGB colors, biggest cluster first, saturation <= 0.55 and value in 0.3..0.85. */
    static int[] extract(Bitmap src) {
        Bitmap s = Bitmap.createScaledBitmap(src, 16, 16, true);
        int[] px = new int[256];
        s.getPixels(px, 0, 16, 0, 0, 16, 16);
        int k = 4;
        float[][] c = new float[k][3];
        for (int i = 0; i < k; i++) c[i] = rgb(px[i * 64 + 37]);
        int[] owner = new int[256], size = new int[k];
        for (int round = 0; round < 6; round++) {
            float[][] sum = new float[k][3];
            size = new int[k];
            for (int i = 0; i < 256; i++) {
                float[] p = rgb(px[i]);
                int best = 0;
                float bd = Float.MAX_VALUE;
                for (int j = 0; j < k; j++) {
                    float d = sq(p[0] - c[j][0]) + sq(p[1] - c[j][1]) + sq(p[2] - c[j][2]);
                    if (d < bd) {
                        bd = d;
                        best = j;
                    }
                }
                owner[i] = best;
                size[best]++;
                for (int a = 0; a < 3; a++) sum[best][a] += p[a];
            }
            for (int j = 0; j < k; j++) {
                if (size[j] > 0) for (int a = 0; a < 3; a++) c[j][a] = sum[j][a] / size[j];
            }
        }
        int n = 0;
        int[] out = new int[k];
        boolean[] used = new boolean[k];
        for (int r = 0; r < k; r++) {
            int top = -1;
            for (int j = 0; j < k; j++) if (!used[j] && size[j] > 0 && (top < 0 || size[j] > size[top])) top = j;
            if (top < 0) break;
            used[top] = true;
            float[] hsv = new float[3];
            Color.RGBToHSV((int) c[top][0], (int) c[top][1], (int) c[top][2], hsv);
            hsv[1] = Math.min(hsv[1], 0.55f);
            hsv[2] = Math.max(0.3f, Math.min(hsv[2], 0.85f));
            out[n++] = Color.HSVToColor(hsv) & 0xFFFFFF;
        }
        int[] res = new int[n];
        System.arraycopy(out, 0, res, 0, n);
        return res;
    }

    private static float[] rgb(int p) {
        return new float[] {Color.red(p), Color.green(p), Color.blue(p)};
    }

    private static float sq(float v) {
        return v * v;
    }
}
