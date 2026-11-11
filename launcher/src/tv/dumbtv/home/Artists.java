package tv.dumbtv.home;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Offline artist -> place table: city, two colors, silhouette layers, weather and a mood nudge.
 * Only places, landmarks, colors and weather. Layers use the page's names: sun, palms, dunes, hills,
 * acacia, grid, trails, ridges:N, skyline:KIND (la, empire, cn, eiffel, bigben, tv, tokyo, domes, none).
 * Row: name|city|layers|weather (snow, rain or -)|sky color|ground color|mood bias.
 */
final class Artists {
    static final class Entry {
        final String id, city, layers, weather;
        final int c1, c2;
        final float bias;

        Entry(String key, String city, String layers, String weather, int c1, int c2, float bias) {
            this.id = "_c" + key;
            this.city = city;
            this.layers = layers;
            this.weather = weather;
            this.c1 = c1;
            this.c2 = c2;
            this.bias = bias;
        }

        /** The theme as the page wants it. */
        String spec() {
            StringBuilder l = new StringBuilder();
            for (String s : layers.split(";")) l.append(l.length() == 0 ? "" : ",").append('"').append(s).append('"');
            return "{\"c1\":" + rgb(c1) + ",\"c2\":" + rgb(c2) + ",\"a\":0.3,\"l\":[" + l + "],\"wx\":\""
                    + (weather.equals("-") ? "" : weather) + "\"}";
        }

        private static String rgb(int c) {
            return "[" + (c >> 16 & 255) + "," + (c >> 8 & 255) + "," + (c & 255) + "]";
        }
    }

    private static final String TABLE =
        "Drake|Toronto|skyline:cn|snow|1b3a8a|d9a520|-0.1\n"
        + "The Weeknd|Toronto|skyline:cn;trails|rain|5a1a7a|c0392b|0\n"
        + "Kendrick Lamar|Compton|sun;skyline:la;palms;trails|-|ff7a3d|a03aa0|0\n"
        + "Dr. Dre|Compton|sun;skyline:la;palms;trails|-|ff7a3d|8a2a90|0.05\n"
        + "Snoop Dogg|Long Beach|sun;palms;skyline:la|-|ffa040|2a7a6a|0\n"
        + "Ice Cube|Los Angeles|sun;skyline:la;trails|-|ff8a30|7a2a60|0.05\n"
        + "Tyler, the Creator|Los Angeles|sun;palms;skyline:la|-|ff9aa0|60b0c0|0.05\n"
        + "Billie Eilish|Los Angeles|skyline:la;hills|-|3a5a4a|101820|-0.2\n"
        + "SZA|St. Louis|skyline:empire|-|e08a50|3a2a5a|-0.1\n"
        + "Jay-Z|New York|skyline:empire|-|ffc060|20407a|0.05\n"
        + "Nas|New York|skyline:empire|rain|8aa0b8|202a40|-0.05\n"
        + "The Notorious B.I.G.|New York|skyline:empire;trails|-|e0a040|2a2a50|0\n"
        + "Wu-Tang Clan|New York|skyline:empire|rain|909aa8|1a2030|0.05\n"
        + "Alicia Keys|New York|skyline:empire|-|ffb050|3a3a6a|-0.05\n"
        + "Cardi B|New York|skyline:empire;trails|-|ff5aa0|3a2a70|0.1\n"
        + "Nicki Minaj|New York|skyline:empire|-|ff70c0|4a2a80|0.1\n"
        + "A$AP Rocky|New York|skyline:empire;trails|-|c0a0e0|201830|0\n"
        + "Miles Davis|New York|skyline:empire|-|3a5aa0|10182c|-0.25\n"
        + "John Coltrane|Philadelphia|skyline:empire|-|2a4a8a|0c1428|-0.25\n"
        + "Nina Simone|North Carolina|hills|-|c08a50|2a1a30|-0.25\n"
        + "Taylor Swift|Nashville|sun;hills|-|f0a8b8|7a5a90|0\n"
        + "Johnny Cash|Arkansas|hills;sun|-|c08a50|2a1e1a|-0.1\n"
        + "Dolly Parton|Tennessee|sun;hills|-|f0b070|7a5a50|-0.05\n"
        + "Willie Nelson|Texas|sun;hills|-|e0a050|6a4a30|-0.1\n"
        + "Morgan Wallen|Nashville|sun;hills|-|e09050|4a3a40|0.05\n"
        + "Luke Combs|Nashville|sun;hills|-|e0a860|4a4040|0.05\n"
        + "Beyonce|Houston|sun;skyline:none|-|ffc050|8a2a70|0.1\n"
        + "Travis Scott|Houston|skyline:none;trails|-|d05a20|201018|0.1\n"
        + "Post Malone|Dallas|sun;skyline:none|-|e08a40|40306a|0\n"
        + "Kanye West|Chicago|skyline:empire|-|8aa0c0|1c2438|0\n"
        + "Chance the Rapper|Chicago|skyline:empire;sun|-|ffb840|2a4a8a|0.05\n"
        + "Eminem|Detroit|skyline:empire|rain|7a8a98|181c24|0.1\n"
        + "Lil Wayne|New Orleans|skyline:none;trails|-|a050c0|205a40|0.05\n"
        + "OutKast|Atlanta|skyline:none;trails|-|ff9050|2a5a4a|0.05\n"
        + "Future|Atlanta|skyline:none;trails|-|8050d0|10101c|0.1\n"
        + "Migos|Atlanta|skyline:none;trails|-|f0b030|1a1a2c|0.1\n"
        + "Lil Baby|Atlanta|skyline:none;trails|-|e0a040|181828|0.1\n"
        + "Lil Nas X|Atlanta|sun;skyline:none|-|ff70c0|7a40d0|0.1\n"
        + "Prince|Minneapolis|skyline:none|snow|7a2aa8|180c2a|0.05\n"
        + "Bon Iver|Wisconsin|hills|snow|a0b0c0|283038|-0.3\n"
        + "Nirvana|Seattle|hills;skyline:none|rain|6a7a7a|1a2022|0.1\n"
        + "Pearl Jam|Seattle|hills;skyline:none|rain|6a7a82|1a2226|0.05\n"
        + "Metallica|San Francisco|skyline:none|-|8a8a90|141418|0.2\n"
        + "Bruce Springsteen|New Jersey|skyline:empire;trails|-|e0a060|2a3050|0\n"
        + "Dua Lipa|London|skyline:bigben|-|ff7ab0|3a2a8a|0.15\n"
        + "Adele|London|skyline:bigben|rain|8a98b0|28304a|-0.25\n"
        + "Coldplay|London|skyline:bigben;sun|-|ffb060|4a50a0|0\n"
        + "Amy Winehouse|London|skyline:bigben|rain|a08a90|241c28|-0.15\n"
        + "Stormzy|London|skyline:bigben;trails|rain|7a8aa8|141828|0.1\n"
        + "Pink Floyd|London|skyline:bigben|-|8a60c0|0c0c1c|-0.1\n"
        + "Led Zeppelin|London|hills;sun|-|d08a40|2a1a1a|0.1\n"
        + "The Beatles|Liverpool|skyline:none|-|a0a8b0|3a4050|0\n"
        + "Oasis|Manchester|skyline:none|rain|8a909a|1c2028|0.05\n"
        + "Radiohead|Oxford|hills|rain|6a7a88|10161c|-0.2\n"
        + "Ed Sheeran|Suffolk|hills;sun|-|f0a878|5a7a60|-0.05\n"
        + "Burna Boy|Port Harcourt|sun;skyline:none;trails|-|ffb030|1a7a4a|0.1\n"
        + "Wizkid|Lagos|sun;skyline:none;trails|-|ffb830|1c8050|0.05\n"
        + "Davido|Lagos|sun;skyline:none;trails|-|ffc040|1a7a50|0.1\n"
        + "Tems|Lagos|sun;skyline:none|-|f0a050|2a6a5a|-0.1\n"
        + "Asake|Lagos|sun;skyline:none;trails|-|ffb040|20805a|0.15\n"
        + "Fela Kuti|Lagos|sun;skyline:none|-|f0a030|1a6a40|0.05\n"
        + "Bob Marley|Kingston|sun;palms|-|ffc820|188a3c|-0.05\n"
        + "Sean Paul|Kingston|sun;palms|-|ffc830|188a40|0.15\n"
        + "Shaggy|Kingston|sun;palms|-|ffc028|1c8a40|0.1\n"
        + "Damian Marley|Kingston|sun;palms|-|ffb820|1a7a3a|0\n"
        + "Chronixx|Kingston|sun;palms|-|ffc030|1a8040|-0.05\n"
        + "Teddy Afro|Addis Ababa|sun;ridges:3|-|f0aa3c|7a3c1e|0\n"
        + "Aster Aweke|Addis Ababa|sun;ridges:3|-|f0a840|843e20|-0.05\n"
        + "Mulatu Astatke|Addis Ababa|sun;ridges:3|-|e8a038|6e3418|-0.2\n"
        + "Mahmoud Ahmed|Addis Ababa|sun;ridges:3|-|eaa43c|74381c|-0.1\n"
        + "Tilahun Gessesse|Addis Ababa|sun;ridges:3|-|e8a23a|70361a|-0.15\n"
        + "Rophnan|Addis Ababa|sun;ridges:3;trails|-|f0a83c|7a3820|0.15\n"
        + "Hachalu Hundessa|Ambo|sun;ridges:3|-|eca63c|76361a|0\n"
        + "Gigi|Addis Ababa|sun;ridges:3|-|eea83e|78381c|-0.1\n"
        + "Betty G|Addis Ababa|sun;ridges:3|-|f0aa40|7c3a1e|0\n"
        + "Daft Punk|Paris|skyline:eiffel|-|e060c0|202060|0.15\n"
        + "David Guetta|Paris|skyline:eiffel|-|ff70d0|2a2a80|0.2\n"
        + "Edith Piaf|Paris|skyline:eiffel|-|f0c8a0|5a4a78|-0.3\n"
        + "Rammstein|Berlin|skyline:tv|-|a05040|141418|0.25\n"
        + "Kraftwerk|Dusseldorf|grid|-|a060e0|20c8d8|0\n"
        + "Paul Kalkbrenner|Berlin|skyline:tv|-|70b0c0|182430|0.1\n"
        + "BTS|Seoul|skyline:tokyo|-|a090f0|f070b0|0.1\n"
        + "BLACKPINK|Seoul|skyline:tokyo;trails|-|ff70b0|202030|0.15\n"
        + "PSY|Seoul|skyline:tokyo;trails|-|ff80a0|3a40b0|0.2\n"
        + "Utada Hikaru|Tokyo|skyline:tokyo|-|ff90b0|3a3a90|-0.1\n"
        + "YOASOBI|Tokyo|skyline:tokyo;trails|-|ff80c0|2a40a0|0.1\n"
        + "Kenshi Yonezu|Tokyo|skyline:tokyo|-|90b0e0|2a3070|0\n"
        + "Arijit Singh|Mumbai|skyline:domes|-|ffa040|a0305a|-0.1\n"
        + "A. R. Rahman|Chennai|skyline:domes;sun|-|ffa840|a8365a|0\n"
        + "Shakira|Barranquilla|sun;palms|-|ffa070|20a0a0|0.1\n"
        + "Bad Bunny|San Juan|sun;palms|-|ff7ab0|20b0b0|0.1\n"
        + "Daddy Yankee|San Juan|sun;palms;trails|-|ff8050|20a8a8|0.15\n"
        + "J Balvin|Medellin|ridges:2;sun|-|ff6aa0|30b0a0|0.15\n"
        + "Anitta|Rio de Janeiro|sun;ridges:2;palms|-|ff7090|20b0a8|0.15\n"
        + "Gilberto Gil|Salvador|sun;palms|-|ffb060|20a0a0|-0.05\n"
        + "Umm Kulthum|Cairo|sun;dunes;skyline:domes|-|f0cc80|9a5a30|-0.3\n"
        + "Fairuz|Beirut|sun;skyline:domes|-|f0c890|7a5a40|-0.25\n"
        + "Amr Diab|Cairo|sun;dunes;skyline:domes|-|f0c078|9a5a2e|0.1\n";

    private static Map<String, Entry> map;

    private Artists() {
    }

    /** Lower case, accents and punctuation removed: "Beyoncé" and "beyonce" are one key. */
    static String key(String name) {
        String s = Normalizer.normalize(name, Normalizer.Form.NFD).toLowerCase(Locale.US);
        return s.replaceAll("[^a-z0-9]", "");
    }

    static synchronized Entry find(String primaryArtist) {
        if (map == null) {
            map = new HashMap<>();
            for (String row : TABLE.split("\n")) {
                String[] p = row.split("\\|");
                if (p.length < 7) continue;
                String k = key(p[0]);
                map.put(k, new Entry(k, p[1], p[2], p[3], Integer.parseInt(p[4], 16), Integer.parseInt(p[5], 16),
                        Float.parseFloat(p[6])));
            }
        }
        return map.get(key(primaryArtist));
    }
}
