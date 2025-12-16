package de.galacticfy.core.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import de.galacticfy.core.service.MaintenanceService;
import de.galacticfy.core.service.ServerTeleportService;
import net.kyori.adventure.text.Component;

public class HubCommand implements SimpleCommand {

    private final ServerTeleportService teleportService;
    private final MaintenanceService maintenanceService;

    private static final String BACKEND_NAME = "Lobby-1";

    public HubCommand(ServerTeleportService teleportService,
                      MaintenanceService maintenanceService) {
        this.teleportService = teleportService;
        this.maintenanceService = maintenanceService;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("Dieser Befehl ist nur für Spieler."));
            return;
        }

        if (maintenanceService.isServerInMaintenance(BACKEND_NAME)) {
            player.sendMessage(Component.text(
                    "§cDie Lobby befindet sich derzeit im Wartungsmodus.§7 Bitte versuche es später erneut."
            ));
            return;
        }

        teleportService.sendToServer(player, BACKEND_NAME, "der Lobby", false);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
