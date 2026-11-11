package tv.dumbtv.home;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * Empties the app's GPU resource cache. The Canvas engine draws paths whose shape changes every
 * frame, and each one is rasterized into a cached mask texture; the cache is allowed to grow to
 * ~375 MB before it evicts anything, which is more than this TV can spare. Trimming it now and then
 * keeps the app under ~250 MB. It is a hidden framework call, so every failure is swallowed and the
 * app simply carries on without trimming.
 */
final class Gpu {
    /** ComponentCallbacks2.TRIM_MEMORY_COMPLETE: free every GPU resource that is not in use. */
    private static final int COMPLETE = 80;
    private static Method trim;
    private static boolean tried, failed;

    private Gpu() {
    }

    static void trim() {
        if (failed) return;
        try {
            if (!tried) {
                tried = true;
                for (String cls : new String[] {"android.graphics.HardwareRenderer", "android.view.ThreadedRenderer"}) {
                    try {
                        Method m = Class.forName(cls).getDeclaredMethod("trimMemory", int.class);
                        m.setAccessible(true);
                        trim = m;
                        Log.i("dumbtv", "gpu cache trim via " + cls);
                        break;
                    } catch (Throwable ignored) {
                        // try the next class
                    }
                }
            }
            if (trim == null) {
                failed = true;
                Log.w("dumbtv", "gpu cache trim unavailable");
                return;
            }
            trim.invoke(null, COMPLETE);
        } catch (Throwable t) {
            failed = true;
            Log.w("dumbtv", "gpu cache trim failed: " + t);
        }
    }
}
