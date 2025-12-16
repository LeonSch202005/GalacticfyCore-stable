package de.galacticfy.core.listener;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.service.MaintenanceService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

public class MaintenanceListener {

    private final MaintenanceService maintenanceService;
    private final Logger logger;
    private final GalacticfyPermissionService permissionService;

    private final MiniMessage mm = MiniMessage.miniMessage();

    public MaintenanceListener(MaintenanceService maintenanceService,
                               Logger logger,
                               GalacticfyPermissionService permissionService) {
        this.maintenanceService = maintenanceService;
        this.logger = logger;
        this.permissionService = permissionService;
    }

    @Subscribe
    public void onLogin(LoginEvent event) {

        if (!maintenanceService.isMaintenanceEnabled()) {
            return;
        }

        Player player = event.getPlayer();

        if (permissionService != null && permissionService.hasRankPermission(player, "galacticfy.maintenance.bypass")) {
            return;
        }

        if (permissionService != null && permissionService.hasMaintenanceBypass(player)) {
            return;
        }

        if (maintenanceService.isPlayerWhitelisted(player.getUsername())) {
            return;
        }

        // Gruppen-Whitelist: Rolle aus gf_roles.name gegen Maintenance-Whitelist-Gruppen prüfen
        if (permissionService != null) {
            var role = permissionService.getRoleFor(player.getUniqueId());
            if (role != null && maintenanceService.isGroupWhitelisted(role.name)) {
                return;
            }
        }

        Component kickMessage = mm.deserialize(
                "\n" +
                        "<gradient:#00E5FF:#7A00FF><bold>Galacticfy</bold></gradient> <gray>|</gray> <red><bold>Wartungsmodus aktiv</bold></red>\n" +
                        "<gray>Das Netzwerk befindet sich derzeit in Wartungsarbeiten.</gray>\n" +
                        "<gray>Bitte versuche es später erneut.</gray>\n" +
                        "\n" +
                        "<gold><bold>Weitere Infos:</bold></gold>\n" +
                        "<yellow>• Website:</yellow> <aqua>https://galacticfy.de</aqua>\n" +
                        "<yellow>• Discord:</yellow> <aqua>discord.gg/galacticfy</aqua>"
        );

        event.setResult(ResultedEvent.ComponentResult.denied(kickMessage));
        logger.info("Spieler {} wurde wegen Maintenance geblockt.", player.getUsername());
    }
}
