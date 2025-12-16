package de.galacticfy.core.broadcast;

public final class MessageDesign {

    private MessageDesign() {}

    // Linien
    private static final String TOP    = "§8╔════════════════════════════════════╗";
    private static final String MID    = "§8╟────────────────────────────────────╢";
    private static final String BOTTOM = "§8╚════════════════════════════════════╝";

    // =============================
    // 🔥 ALERT
    // =============================
    public static String alert(String msg) {
        return String.join("\n",
                TOP,
                "§8║ §c§l⚠ ALERT §8| §7Netzwerk",
                MID,
                "§8║ §7" + msg,
                BOTTOM
        );
    }

    // =============================
    // ✨ ANKÜNDIGUNG
    // =============================
    public static String announce(String msg) {
        return String.join("\n",
                TOP,
                "§8║ §b§l✦ Ankündigung §8| §7Galacticfy",
                MID,
                "§8║ §7" + msg,
                BOTTOM
        );
    }

    // =============================
    // 📢 BROADCAST
    // =============================
    public static String broadcast(String msg) {
        return String.join("\n",
                TOP,
                "§8║ §e§l📢 Broadcast §8| §7Netzwerk",
                MID,
                "§8║ §7" + msg,
                BOTTOM
        );
    }
}
