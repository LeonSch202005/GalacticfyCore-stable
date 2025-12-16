package de.galacticfy.core.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.galacticfy.core.service.MaintenanceService;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public class ConnectionProtectionListener {

    private final Logger logger;
    private final ProxyServer proxy;
    private final MaintenanceService maintenanceService;

    private final Set<String> restrictedServers = Set.of(
            "dev-1",
            "event-1"
    );

    public ConnectionProtectionListener(Logger logger, ProxyServer proxy, MaintenanceService maintenanceService) {
        this.logger = logger;
        this.proxy = proxy;
        this.maintenanceService = maintenanceService;
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();

        if (event.getOriginalServer() == null) {
            return;
        }

        String target = event.getOriginalServer().getServerInfo().getName();
        String lower = target.toLowerCase(Locale.ROOT);

        // ============================================================
        // PRO-SERVER-MAINTENANCE + FALLBACK-LOBBY
        // ============================================================
        if (maintenanceService.isServerInMaintenance(lower)) {

            if (player.hasPermission("galacticfy.maintenance.bypass")
                    || maintenanceService.isPlayerWhitelisted(player.getUsername())) {

                player.sendMessage(Component.text(
                        "§eHinweis: §cDieser Server befindet sich im Wartungsmodus."
                ));
                return;
            }

            var fallback = findFallbackLobby();

            fallback.ifPresentOrElse(lobby -> {
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(lobby));
                player.sendMessage(Component.text(
                        "§cDieser Server befindet sich im Wartungsmodus.§7 Du wirst in die Lobby geschickt."
                ));
            }, () -> {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                player.sendMessage(Component.text(
                        "§cDieser Server befindet sich im Wartungsmodus.\n" +
                                "§7Es ist derzeit keine Lobby als Fallback verfügbar."
                ));
            });

            logger.info("Spieler {} wurde daran gehindert, {} (Server-Maintenance) zu betreten.",
                    player.getUsername(), target);
            return;
        }

        // ============================================================
        // EVENT-SERVER NUR MIT PERMISSION
        // ============================================================
        if (target.equalsIgnoreCase("event-1") && !player.hasPermission("galacticfy.event.join")) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.sendMessage(Component.text("§cDu darfst diesem Event nicht beitreten."));
            logger.info("Spieler {} wurde daran gehindert, {} ohne Permission zu joinen.", player.getUsername(), target);
            return;
        }

        // ============================================================
        // GENERELLE RESTRICTED-SERVER-BLACKLIST
        // ============================================================
        if (restrictedServers.contains(lower)
                && !player.hasPermission("galacticfy.bypass.serverblacklist")) {

            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.sendMessage(Component.text("§cDu kannst diesen Server nicht direkt betreten."));
            logger.info("Spieler {} wurde daran gehindert, restricted Server {} zu joinen.", player.getUsername(), target);
        }
    }

    private Optional<RegisteredServer> findFallbackLobby() {
        var fallbackOrder = java.util.List.of(
                "Lobby-1",
                "Lobby-2",
                "Lobby-Backup",
                "Hub-1"
        );

        for (String name : fallbackOrder) {
            Optional<RegisteredServer> srv = proxy.getServer(name);
            if (srv.isPresent()) {
                return srv;
            }
        }
        return Optional.empty();
    }
}
