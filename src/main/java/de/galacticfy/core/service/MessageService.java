package de.galacticfy.core.service;

import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

public class MessageService {

    private final ProxyServer proxy;
    private final Logger logger;

    public MessageService(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    // Gemeinsame Linie
    private static final Component LINE =
            Component.text("§8§m────────────────────────────────");

    // ============================================================
    // BROADCAST (normal)
    // ============================================================

    public void broadcast(String message) {
        logger.info("[Broadcast] {}", message);

        Component header = Component.text("§8§m──────§r §e§lBroadcast §7| §eNetzwerk §8§m──────");
        Component body   = Component.text("§7" + message);

        proxy.sendMessage(Component.text(" "));
        proxy.sendMessage(header);
        proxy.sendMessage(Component.text(" "));
        proxy.sendMessage(body);
        proxy.sendMessage(Component.text(" "));
        proxy.sendMessage(LINE);
        proxy.sendMessage(Component.text(" "));
    }

    // ============================================================
    // ALERT (wichtig / rot)
    // ============================================================

    public void alert(String message) {
        logger.warn("[ALERT] {}", message);

        Component header = Component.text("§8§m──────§r §4§lALERT §7| §cNetzwerk §8§m──────");
        Component body   = Component.text("§7" + message);

        proxy.sendMessage(Component.text(" "));
        proxy.sendMessage(header);
        proxy.sendMessage(Component.text(" "));
        proxy.sendMessage(body);
        proxy.sendMessage(Component.text(" "));
        proxy.sendMessage(LINE);
        proxy.sendMessage(Component.text(" "));
    }

    // ============================================================
    // ANNOUNCEMENT (schöne Ankündigung)
    // ============================================================

    public void announce(String message) {
        logger.info("[Announcement] {}", message);

        Component header = Component.text("§8§m──────§r §b§lAnkündigung §7| §bGalacticfy §8§m──────");
        Component body   = Component.text("§7" + message);

        proxy.sendMessage(Component.text(" "));
        proxy.sendMessage(header);
        proxy.sendMessage(Component.text(" "));
        proxy.sendMessage(body);
        proxy.sendMessage(Component.text(" "));
        proxy.sendMessage(LINE);
        proxy.sendMessage(Component.text(" "));
    }
}
