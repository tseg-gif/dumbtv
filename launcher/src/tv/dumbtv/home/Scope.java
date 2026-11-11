package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

/** Sits above the Milkdrop WebView and lets Pulse draw its faint spectrum ring there. */
final class Scope extends View {
    private final Pulse pulse;

    Scope(Context c, Pulse p) {
        super(c);
        pulse = p;
        setWillNotDraw(false);
        setFocusable(false);
    }

    @Override
    protected void onDraw(Canvas c) {
        pulse.drawScope(c, getWidth(), getHeight());
    }
}
