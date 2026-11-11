package tv.dumbtv.home;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Quick launch pills under the clock, the VPN indicator, and the shortcuts Options offers.
 * A pill is either a special id (appstore, amazon, wifi, display, vpn) or an app's package name.
 * The list lives in home.conf as quick=id,id,...; "-" means none.
 */
final class Quick {
    static final String KEY = "quick";
    static final String DEFAULT = "appstore,wallpapers,meditation,com.spotify.tv.android,com.esaba.downloader,ch.protonvpn.android";
    static final String VPN_APP_KEY = "vpn.app";
    static final String VPN_APP = "ch.protonvpn.android";
    private static final String APPSTORE_URI_KEY = "appstore.uri";
    private static final String APPSTORE_URI = "amzn://apps/android?s=";

    /** What a pill shows and does. */
    static final class Entry {
        final String id;
        final String label;

        Entry(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private Quick() {}

    /** The ids to show, in order: home.conf's quick= or the default list. */
    static List<String> ids(Config cfg) {
        String raw = cfg.get(KEY, DEFAULT);
        List<String> out = new ArrayList<>();
        if ("-".equals(raw.trim())) return out;
        for (String s : raw.split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    static void saveIds(Config cfg, List<String> ids) {
        cfg.set(KEY, ids.isEmpty() ? "-" : android.text.TextUtils.join(",", ids));
    }

    /** Pills to draw now: specials always, apps only when installed. */
    static List<Entry> entries(Context c, Config cfg) {
        List<Entry> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (String id : ids(cfg)) {
            String special = specialLabel(id);
            if (special != null) {
                out.add(new Entry(id, special));
                continue;
            }
            if (launchIntent(pm, id) == null) continue;
            out.add(new Entry(id, appLabel(pm, id)));
        }
        return out;
    }

    static String specialLabel(String id) {
        switch (id) {
            case "appstore":
                return "Appstore";
            case "amazon":
                return "Amazon home";
            case "wifi":
                return "Wi-Fi";
            case "display":
                return "Display";
            case "vpn":
                return "VPN";
            case "wallpapers":
                return "Wallpapers";
            case "meditation":
                return "Meditation";
            default:
                return null;
        }
    }

    /** Every id Options can offer: the specials, then each installed app. */
    static List<Entry> candidates(Context c) {
        List<Entry> out = new ArrayList<>();
        for (String s : Arrays.asList("appstore", "amazon", "wifi", "display", "vpn", "wallpapers", "meditation")) {
            out.add(new Entry(s, specialLabel(s)));
        }
        for (Item it : Catalog.apps(c)) {
            if (!c.getPackageName().equals(it.id)) out.add(new Entry(it.id, it.label.toString()));
        }
        return out;
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    private static String appLabel(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static void run(Context c, Config cfg, String id) {
        switch (id) {
            case "appstore":
                appstore(c, cfg, "");
                return;
            case "amazon":
                Guard.amazon(c);
                return;
            case "wifi":
                start(c, new Intent(Settings.ACTION_WIFI_SETTINGS));
                return;
            case "display":
                start(c, new Intent(Settings.ACTION_DISPLAY_SETTINGS));
                return;
            case "vpn":
                vpn(c, cfg);
                return;
            case "wallpapers":
                if (c instanceof Home) Wallpapers.show((Home) c, cfg);
                return;
            case "meditation":
                Meditation.open(c);
                return;
            default:
                Intent i = launchIntent(c.getPackageManager(), id);
                if (i == null) {
                    Toast.makeText(c, "Not installed", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (Guard.AMAZON.equals(id)) Guard.pause();
                start(c, i);
                Recent.opened(c, id);
        }
    }

    /**
     * Fire OS has no Leanback entry for the Appstore, and its launcher activity bounces to
     * Amazon's home. The store's own amzn:// link opens its search instead; if nothing answers
     * it, Amazon's home screen (where the Apps tab lives) is the fallback.
     */
    static void appstore(Context c, Config cfg, String query) {
        String base = cfg.get(APPSTORE_URI_KEY, APPSTORE_URI);
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(base + Uri.encode(query)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(c, "Appstore link not found, opening Amazon's home", Toast.LENGTH_SHORT).show();
            Guard.amazon(c);
        }
    }

    /** A text box for the store's search, since the store itself cannot be browsed from here. */
    static void appstoreSearch(Context c, Config cfg) {
        EditText box = new EditText(c);
        box.setSingleLine(true);
        box.setInputType(InputType.TYPE_CLASS_TEXT);
        box.setTextColor(Ui.TEXT);
        box.setHint("App name");
        new AlertDialog.Builder(c, Ui.DIALOG)
                .setTitle("Search the Appstore")
                .setView(box)
                .setPositiveButton("Search", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        appstore(c, cfg, box.getText().toString().trim());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void vpn(Context c, Config cfg) {
        String pkg = cfg.get(VPN_APP_KEY, VPN_APP);
        Intent i = launchIntent(c.getPackageManager(), pkg);
        start(c, i != null ? i : new Intent("android.settings.VPN_SETTINGS"));
    }

    private static void start(Context c, Intent i) {
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(c, "Could not open that", Toast.LENGTH_SHORT).show();
        }
    }

    /** True while any network on the TV is a VPN. */
    @SuppressWarnings("deprecation")
    static boolean vpnOn(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (RuntimeException ignored) {
            // no permission or no service: show the VPN as off
        }
        return false;
    }
}
