package de.galacticfy.core.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import de.galacticfy.core.service.LobbyGuiMessenger;
import net.kyori.adventure.text.Component;

public class ServerCommand implements SimpleCommand {

    private final LobbyGuiMessenger lobbyGui;

    public ServerCommand(LobbyGuiMessenger lobbyGui) {
        this.lobbyGui = lobbyGui;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("§cNur ingame."));
            return;
        }
        lobbyGui.openServerGui(player);
    }
}
