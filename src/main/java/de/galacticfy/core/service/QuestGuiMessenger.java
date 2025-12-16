package de.galacticfy.core.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import de.galacticfy.core.service.QuestService.PlayerQuestView;
import de.galacticfy.core.service.QuestService.QuestDefinition;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class QuestGuiMessenger {

    private final ProxyServer proxy;
    private final ChannelIdentifier channel;
    private final Logger logger;
    private final QuestService questService;

    public QuestGuiMessenger(ProxyServer proxy,
                             ChannelIdentifier channel,
                             Logger logger,
                             QuestService questService) {
        this.proxy = proxy;
        this.channel = channel;
        this.logger = logger;
        this.questService = questService;
    }

    /**
     * /quests → GUI öffnen
     */
    public void openGui(Player player) {
        if (player == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        List<PlayerQuestView> questsForPlayer = questService.getQuestsFor(uuid);

        byte[] payload = serialize(questsForPlayer, "OPEN");

        Optional<ServerConnection> optConn = player.getCurrentServer();
        if (optConn.isEmpty()) {
            logger.warn("[QuestGuiMessenger] openGui: Spieler {} ist auf KEINEM Server.", player.getUsername());
            return;
        }

        ServerConnection conn = optConn.get();
        String serverName = conn.getServerInfo().getName();
        logger.info("[QuestGuiMessenger] Sende OPEN-Payload ({} Bytes) für Spieler {} an Server {} über Channel {}",
                payload.length, player.getUsername(), serverName, channel.getId());

        conn.sendPluginMessage(channel, payload);
    }

    /**
     * Wird vom QuestService beim Fortschritt/Abschluss aufgerufen
     * (über updateHook).
     */
    public void pushUpdate(UUID uuid) {
        if (uuid == null) return;

        Player player = proxy.getPlayer(uuid).orElse(null);
        if (player == null) {
            logger.debug("[QuestGuiMessenger] pushUpdate: Spieler {} ist nicht online.", uuid);
            return;
        }

        Optional<ServerConnection> optConn = player.getCurrentServer();
        if (optConn.isEmpty()) {
            logger.debug("[QuestGuiMessenger] pushUpdate: Spieler {} ist auf keinem Server.", player.getUsername());
            return;
        }

        List<PlayerQuestView> list = questService.getQuestsFor(uuid);
        byte[] payload = serialize(list, "UPDATE");

        ServerConnection conn = optConn.get();
        String serverName = conn.getServerInfo().getName();
        logger.debug("[QuestGuiMessenger] Sende UPDATE-Payload ({} Bytes) für Spieler {} an Server {}",
                payload.length, player.getUsername(), serverName);

        conn.sendPluginMessage(channel, payload);
    }

    /**
     * Payload-Format (UTF-8):
     *
     *   Zeile 0:
     *     MODE
     *       MODE = "OPEN" oder "UPDATE"
     *
     *   ab Zeile 1:
     *     key|title|desc|type|goal|progress|galas|stardust|completed
     *
     *  - key       → quest_key (z.B. "daily_break_stone_120_daily")
     *  - title     → Anzeigename
     *  - desc      → Beschreibung (eine Zeile, \n entfernt)
     *  - type      → DAILY / WEEKLY / MONTHLY / LIFETIME / EVENT
     *  - goal      → Ziel (z.B. 120)
     *  - progress  → aktueller Fortschritt
     *  - galas     → reward_galas
     *  - stardust  → reward_stardust
     *  - completed → 1 = fertig, 0 = nicht fertig
     */
    private byte[] serialize(List<PlayerQuestView> quests, String mode) {
        StringBuilder sb = new StringBuilder(quests.size() * 128);

        // Erste Zeile: MODE
        sb.append(mode).append('\n');

        for (PlayerQuestView view : quests) {
            QuestDefinition def = view.definition();
            long progress   = view.progress();
            boolean completed = view.completed();

            sb.append(def.key()).append('|')
                    .append(def.title()).append('|')
                    .append(sanitize(def.description())).append('|')
                    .append(def.type().name()).append('|')
                    .append(def.goal()).append('|')
                    .append(progress).append('|')
                    .append(def.rewardGalas()).append('|')
                    .append(def.rewardStardust()).append('|')
                    .append(completed ? '1' : '0')
                    .append('\n');
        }

        String s = sb.toString();
        logger.debug("[QuestGuiMessenger] serialize(mode={}, quests={}) → {} Zeichen",
                mode, quests.size(), s.length());
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String sanitize(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ')
                .replace('\r', ' ');
    }
}
