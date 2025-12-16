package de.galacticfy.core.punish;

import java.util.*;
import java.util.stream.Collectors;

public final class ReasonPresets {

    public record Preset(String key, String display, Long defaultDurationMs) {}

    private static final List<Preset> PRESETS = List.of(
            // Chat / Verhalten
            new Preset("spam",              "Chat-Spam",                         30L * 60_000L),
            new Preset("caps",              "Übermäßige Großschreibung (Caps)",  15L * 60_000L),
            new Preset("beleidigung",       "Beleidigung",                       2L  * 60L * 60_000L),
            new Preset("schwerebeleidigung","Schwere Beleidigung",               24L * 60L * 60_000L),
            new Preset("rassismus",         "Rassismus / Diskriminierung",       7L  * 24L * 60L * 60_000L),
            new Preset("drohung",           "Bedrohung",                         3L  * 24L * 60L * 60_000L),
            new Preset("teambeleidigung",   "Beleidigung gegenüber Teammitgliedern", 3L * 24L * 60L * 60_000L),
            new Preset("provokation",       "Ständige Provokation",              60L * 60_000L),

            // Werbung
            new Preset("werbung",           "Unerlaubte Werbung",                24L * 60L * 60_000L),
            new Preset("fremdwerbung",      "Werbung für fremde Server",         3L  * 24L * 60L * 60_000L),

            // Gameplay
            new Preset("bugusing",          "Bugusing / Ausnutzen von Fehlern",  7L  * 24L * 60L * 60_000L),
            new Preset("griefing",          "Griefing / mutwillige Zerstörung",  24L * 60L * 60_000L),
            new Preset("teamtrolling",      "Trolling von Mitspielern/Team",     12L * 60L * 60_000L),

            // Cheats
            new Preset("hackclient",        "Hacking / verbotene Mods",          null),
            new Preset("killaura",          "Hacking (Killaura/Combat)",         null),
            new Preset("autoclicker",       "Autoklicker / Makro",               7L * 24L * 60L * 60_000L),
            new Preset("xray",              "X-Ray / Ressourcen-Hacks",          7L * 24L * 60L * 60_000L),

            // Account / Sicherheit
            new Preset("accountsharing",    "Account-Sharing",                   7L * 24L * 60L * 60_000L),
            new Preset("banumgehung",       "Umgehung eines Bans",               null),
            new Preset("scamming",          "Scamming / Betrug",                 7L * 24L * 60L * 60_000L),

            // Namen / Skins
            new Preset("name",              "Unangemessener Name",               24L * 60L * 60_000L),
            new Preset("skin",              "Unangemessener Skin",               24L * 60L * 60_000L),

            // RL / Bedrohungen
            new Preset("ddos",              "Drohung mit DDoS / Hacks",          null),
            new Preset("rechteextrem",      "Rechtsextreme Inhalte / Symbole",   null),

            // Voice / sonstiges
            new Preset("voicebeleidigung",  "Beleidigung im Voice",              2L * 60L * 60_000L),

            // allgemein
            new Preset("regelverstoß",      "Allgemeiner Regelverstoß",          60L * 60_000L)
    );

    private static final Map<String, Preset> BY_KEY = PRESETS.stream()
            .collect(Collectors.toMap(
                    p -> p.key().toLowerCase(Locale.ROOT),
                    p -> p,
                    (a, b) -> a,
                    LinkedHashMap::new
            ));

    private ReasonPresets() {}

    public static Preset find(String key) {
        if (key == null) return null;
        return BY_KEY.get(key.toLowerCase(Locale.ROOT));
    }

    public static List<String> allKeys() {
        return new ArrayList<>(BY_KEY.keySet());
    }

    public static List<String> tabComplete(String prefix) {
        if (prefix == null) prefix = "";
        String p = prefix.toLowerCase(Locale.ROOT);
        return BY_KEY.keySet().stream()
                .filter(k -> p.isEmpty() || k.startsWith(p))
                .sorted()
                .collect(Collectors.toList());
    }
}
