package tv.dumbtv.home;

import android.app.AlertDialog;
import android.content.DialogInterface;

/** The home page's Wallpapers pill: pick a built-in wallpaper or the sound the home row makes while you scroll. */
final class Wallpapers {
    private Wallpapers() {}

    static void show(final Home home, final Config cfg) {
        final AlertDialog menu = new AlertDialog.Builder(home, Ui.DIALOG)
                .setTitle("Wallpapers & sounds")
                .setItems(new String[] {"Wallpapers", "Scroll sounds"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) wallpapers(home, cfg);
                        else sounds(home, cfg);
                    }
                })
                .setNegativeButton("Close", null)
                .create();
        hook(home, menu);
        menu.show();
    }

    private static void wallpapers(final Home home, final Config cfg) {
        final String[] names = new String[Aurora.NAMES.length + 1];
        System.arraycopy(Aurora.NAMES, 0, names, 0, Aurora.NAMES.length);
        names[names.length - 1] = "Plain black";
        int current = Aurora.themeOf(cfg.get(Config.BACKGROUND));
        final AlertDialog pick = new AlertDialog.Builder(home, Ui.DIALOG)
                .setTitle("Wallpapers")
                .setSingleChoiceItems(names, current < 0 ? names.length - 1 : current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        cfg.set(Config.BACKGROUND.key, which < Aurora.IDS.length ? Aurora.IDS[which] : "black");
                        home.applyWallpaper();
                    }
                })
                .setPositiveButton("Done", null)
                .create();
        hook(home, pick);
        pick.show();
    }

    private static void sounds(final Home home, final Config cfg) {
        final Config.Choice c = Config.SCROLL_SOUND;
        int current = 0;
        for (int i = 0; i < c.values.length; i++) if (c.values[i].equals(cfg.get(c))) current = i;
        final AlertDialog snd = new AlertDialog.Builder(home, Ui.DIALOG)
                .setTitle("Scroll sound")
                .setSingleChoiceItems(c.names, current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        cfg.set(c.key, c.values[which]);
                        home.applySound();
                    }
                })
                .setPositiveButton("Done", null)
                .create();
        hook(home, snd);
        snd.show();
    }

    /** Scrolling a list in these dialogs plays the same notes as the Quick row. */
    private static void hook(final Home home, final AlertDialog d) {
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface x) {
                d.getListView().setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                        home.tick(pos);
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> p) { }
                });
            }
        });
    }
}
