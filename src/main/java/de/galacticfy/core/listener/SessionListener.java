package de.galacticfy.core.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import de.galacticfy.core.service.PlayerIdentityCacheService;
import de.galacticfy.core.service.QuestService;
import de.galacticfy.core.service.SessionService;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.UUID;

public class SessionListener {

    private final SessionService sessions;
    private final QuestService quests;
    private final PlayerIdentityCacheService identityCache;
    private final Logger logger;

    public SessionListener(SessionService sessions,
                           QuestService quests,
                           PlayerIdentityCacheService identityCache,
                           Logger logger) {
        this.sessions = sessions;
        this.quests = quests;
        this.identityCache = identityCache;
        this.logger = logger;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player p = event.getPlayer();
        String serverName = p.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName())
                .orElse("Unbekannt");

        // DB Session updaten
        sessions.onLogin(p.getUniqueId(), p.getUsername(), serverName);

        // Cache updaten (Name<->UUID)
        if (identityCache != null) {
            identityCache.update(p.getUniqueId(), p.getUsername());
        }

        quests.handleLogin(p.getUniqueId(), p.getUsername(), (uuid, msg) -> sendToPlayer(uuid, msg, p));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player p = event.getPlayer();
        UUID uuid = p.getUniqueId();

        // SessionService gibt z.B. gespielte Minuten für diese Session zurück
        long minutes = sessions.onLogout(uuid);
        if (minutes > 0) {
            quests.handlePlaytime(uuid, p.getUsername(), minutes, (u, msg) -> sendToPlayer(u, msg, p));
        }
    }

    private void sendToPlayer(UUID uuid, Component msg, Player player) {
        if (player.getUniqueId().equals(uuid)) {
            player.sendMessage(msg);
        } else {
            logger.debug("SessionListener: UUID mismatch beim Senden einer Quest-Nachricht.");
        }
    }
}
