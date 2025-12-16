package de.galacticfy.core.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Schickes Tablist-Design:
 *  - Header: Galacticfy-Logo + aktueller Server
 *  - Footer: Online-Count + Website + Discord
 *
 * Läuft komplett auf dem Proxy.
 */
public class TablistDesignListener {

    private final ProxyServer proxy;
    private final Logger logger;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public TablistDesignListener(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        applyTabDesign(player);
        logger.info("[Tablist] Header/Footer für {} gesetzt (PostLogin).", player.getUsername());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        // Spieleranzahl ändert sich → bei allen neu setzen, damit der Online-Count stimmt
        for (Player online : proxy.getAllPlayers()) {
            applyTabDesign(online);
        }
    }

    /**
     * Setzt das Tablist-Design für einen Spieler.
     */
    private void applyTabDesign(Player player) {
        int online = proxy.getAllPlayers().size();
        int maxSlots = 500;

        String serverName = player.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName())
                .orElse("Verbinde...");

        String prettyServer = formatServerName(serverName);

        Component header = mm.deserialize(
                "<gradient:#00E5FF:#C800FF><bold>✦ Galacticfy Netzwerk ✦</bold></gradient>\n" +
                        "<gray>Zwischen den Sternen beginnt dein Abenteuer.</gray>\n" +
                        "<dark_gray>➥ <white>" + prettyServer + "</white>"
        );

        Component footer = mm.deserialize(
                "<gray>Online:</gray> <aqua>" + online + "</aqua><gray>/</gray><aqua>" + maxSlots + "</aqua>\n" +
                        "<yellow>Website:</yellow> <aqua>galacticfy.de</aqua>\n" +
                        "<yellow>Discord:</yellow> <aqua>discord.gg/galacticfy</aqua>"
        );

        // NEUE Velocity API
        player.getTabList().setHeaderAndFooter(header, footer);
    }

    /**
     * Macht aus „lobby-1“ → „Lobby-1“, „skyblock-core-1“ → „Skyblock Core 1“ usw.
     */
    private String formatServerName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Unbekannter Server";
        }

        String name = raw.replace('_', ' ');
        String[] parts = name.split(" ");
        StringBuilder out = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            if (i > 0) out.append(" ");
            out.append(p.substring(0, 1).toUpperCase(Locale.ROOT));
            if (p.length() > 1) {
                out.append(p.substring(1).toLowerCase(Locale.ROOT));
            }
        }

        return out.toString();
    }
}
