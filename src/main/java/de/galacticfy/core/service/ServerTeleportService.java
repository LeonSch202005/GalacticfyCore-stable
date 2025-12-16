package de.galacticfy.core.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ServerTeleportService {

    private final ProxyServer proxy;
    private final Logger logger;

    // Cooldown in Millisekunden (z.B. 3 Sekunden)
    private final long teleportCooldownMs = 3000L;

    // Thread-sicher, weil von mehreren Threads genutzt werden kann
    private final Map<UUID, Long> lastTeleport = new ConcurrentHashMap<>();

    public ServerTeleportService(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    /**
     * Teleportiert einen Spieler auf einen Backend-Server.
     *
     * @param player         Spieler
     * @param backendName    exakter Name des Servers in Velocity (z.B. "Lobby-1")
     * @param displayName    hübscher Anzeigename ("der Lobby", "Citybuild")
     * @param ignoreCooldown true, wenn kein Cooldown gelten soll (z.B. bei /send)
     */
    public void sendToServer(Player player, String backendName, String displayName, boolean ignoreCooldown) {
        // Cooldown prüfen
        if (!ignoreCooldown && !checkCooldown(player)) {
            return;
        }

        Optional<RegisteredServer> optional = proxy.getServer(backendName);

        if (optional.isEmpty()) {
            player.sendMessage(Component.text("§cServer \"" + displayName + "\" ist momentan nicht verfügbar."));
            logger.warn("Spieler {} wollte zu \"{}\", aber der Server ist nicht registriert (Backend-Name: {}).",
                    player.getUsername(), displayName, backendName);
            return;
        }

        RegisteredServer target = optional.get();

        // Ist der Spieler schon dort?
        if (player.getCurrentServer()
                .map(conn -> conn.getServer().equals(target))
                .orElse(false)) {
            player.sendMessage(Component.text("§eDu bist bereits auf " + displayName + "."));
            return;
        }

        // ActionBar beim Start
        player.sendActionBar(Component.text("§7Verbinde mit §b" + displayName + "§7..."));

        player.createConnectionRequest(target).connect().thenAccept(result -> {
            switch (result.getStatus()) {
                case SUCCESS -> {
                    logger.info("Spieler {} wurde zu {} gesendet.", player.getUsername(), displayName);
                    player.sendMessage(Component.text("§aDu wurdest zu " + displayName + " gesendet."));

                    // Titel beim Ankommen
                    player.showTitle(
                            Title.title(
                                    Component.text("§a" + displayName),
                                    Component.text("§7Viel Spaß!"),
                                    Title.Times.times(
                                            Duration.ofMillis(250),
                                            Duration.ofSeconds(2),
                                            Duration.ofMillis(500)
                                    )
                            )
                    );
                }
                case ALREADY_CONNECTED ->
                        player.sendMessage(Component.text("§eDu bist bereits auf " + displayName + "."));
                case CONNECTION_IN_PROGRESS ->
                        player.sendMessage(Component.text("§eVerbindung zu " + displayName + " läuft bereits..."));
                default ->
                        player.sendMessage(Component.text("§cKonnte dich nicht mit " + displayName + " verbinden."));
            }
        });
    }

    private boolean checkCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastTeleport.get(player.getUniqueId());

        if (last != null) {
            long diff = now - last;
            if (diff < teleportCooldownMs) {
                long remaining = (teleportCooldownMs - diff) / 1000;
                if (remaining <= 0) remaining = 1;
                player.sendMessage(Component.text("§cBitte warte noch §e" + remaining + "§c Sekunde(n), bevor du den Befehl erneut nutzt."));
                return false;
            }
        }

        lastTeleport.put(player.getUniqueId(), now);
        return true;
    }
}
