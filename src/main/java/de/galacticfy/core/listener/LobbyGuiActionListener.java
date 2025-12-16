package de.galacticfy.core.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import de.galacticfy.core.service.ServerTeleportService;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;

public class LobbyGuiActionListener {

    private final ProxyServer proxy;
    private final ChannelIdentifier channel;
    private final Logger logger;
    private final ServerTeleportService teleportService;

    public LobbyGuiActionListener(ProxyServer proxy,
                                  ChannelIdentifier channel,
                                  Logger logger,
                                  ServerTeleportService teleportService) {
        this.proxy = proxy;
        this.channel = channel;
        this.logger = logger;
        this.teleportService = teleportService;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(channel)) return;
        if (!(event.getSource() instanceof ServerConnection serverConn)) return;

        event.setResult(PluginMessageEvent.ForwardResult.handled());

        var player = serverConn.getPlayer();
        String msg = new String(event.getData(), StandardCharsets.UTF_8);

        // ACTION|SERVER|CONNECT|Lobby-1|der Lobby
        String[] p = msg.split("\\|", -1);
        if (p.length < 4) return;
        if (!"ACTION".equalsIgnoreCase(p[0])) return;

        String module = p[1];
        String action = p[2];

        if ("SERVER".equalsIgnoreCase(module) && "CONNECT".equalsIgnoreCase(action)) {
            String backendName = p[3];
            String displayName = (p.length >= 5 && !p[4].isBlank()) ? p[4] : backendName;

            teleportService.sendToServer(player, backendName, displayName, false);
        }
    }
}
