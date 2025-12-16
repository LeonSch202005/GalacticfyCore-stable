package de.galacticfy.core.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.service.MaintenanceService;
import de.galacticfy.core.service.ServerTeleportService;
import net.kyori.adventure.text.Component;

public class EventCommand implements SimpleCommand {

    private final ServerTeleportService teleportService;
    private final MaintenanceService maintenanceService;
    private final GalacticfyPermissionService perms;

    private static final String BACKEND_NAME = "event-1";

    public EventCommand(ServerTeleportService teleportService,
                        MaintenanceService maintenanceService,
                        GalacticfyPermissionService perms) {
        this.teleportService = teleportService;
        this.maintenanceService = maintenanceService;
        this.perms = perms;
    }

    private Component prefix() {
        return Component.text("§8[§bGalacticfy§8] §r");
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(prefix().append(
                    Component.text("§cDieser Befehl ist nur für Spieler.")
            ));
            return;
        }

        // Zentrale Plugin-Permission (inkl. Rank-System + "*")
        boolean allowed = (perms != null)
                ? perms.hasPluginPermission(player, "galacticfy.event.join")
                : player.hasPermission("galacticfy.event.join");

        if (!allowed) {
            player.sendMessage(prefix().append(
                    Component.text("§cDu hast keine Berechtigung, dem Event beizutreten.")
            ));
            return;
        }

        if (maintenanceService.isServerInMaintenance(BACKEND_NAME)) {
            player.sendMessage(prefix().append(Component.text(
                    "§cDas Event befindet sich derzeit im Wartungsmodus.§7 Du kannst es momentan nicht betreten."
            )));
            return;
        }

        teleportService.sendToServer(player, BACKEND_NAME, "dem Event", false);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            return true; // Konsole darf immer
        }

        return (perms != null)
                ? perms.hasPluginPermission(player, "galacticfy.event.join")
                : player.hasPermission("galacticfy.event.join");
    }
}
