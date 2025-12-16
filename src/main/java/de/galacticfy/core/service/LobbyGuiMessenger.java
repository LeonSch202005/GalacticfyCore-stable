package de.galacticfy.core.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class LobbyGuiMessenger {

    private final ProxyServer proxy;
    private final ChannelIdentifier channel;
    private final Logger logger;

    public LobbyGuiMessenger(ProxyServer proxy, ChannelIdentifier channel, Logger logger) {
        this.proxy = proxy;
        this.channel = channel;
        this.logger = logger;
    }

    public void openServerGui(Player player) {
        send(player, "OPEN|SERVER");
    }

    private void send(Player player, String payload) {
        if (player == null) return;

        Optional<ServerConnection> optConn = player.getCurrentServer();
        if (optConn.isEmpty()) {
            logger.warn("[LobbyGuiMessenger] Spieler {} ist auf keinem Server.", player.getUsername());
            return;
        }
        optConn.get().sendPluginMessage(channel, payload.getBytes(StandardCharsets.UTF_8));
    }
}
