package de.galacticfy.core.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FreezeService {

    // Plugin-Message-Channel: muss mit Spigot-Seite übereinstimmen ("galacticfy:freeze")
    public static final MinecraftChannelIdentifier FREEZE_CHANNEL =
            MinecraftChannelIdentifier.create("galacticfy", "freeze");

    private final ProxyServer proxy;
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();

    public FreezeService(ProxyServer proxy) {
        this.proxy = proxy;
    }

    public void freeze(UUID uuid) {
        frozen.add(uuid);
        sendFreezeUpdate(uuid, true);
    }

    public void unfreeze(UUID uuid) {
        frozen.remove(uuid);
        sendFreezeUpdate(uuid, false);
    }

    public boolean isFrozen(UUID uuid) {
        return frozen.contains(uuid);
    }

    /**
     * Schickt eine Plugin-Message an den Spigot-Server, damit dort Bewegung/Chat geblockt wird.
     * Format: "<uuid>:freeze" oder "<uuid>:unfreeze"
     */
    private void sendFreezeUpdate(UUID uuid, boolean nowFrozen) {
        String msg = uuid.toString() + ":" + (nowFrozen ? "freeze" : "unfreeze");
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);

        proxy.getPlayer(uuid).ifPresent(player ->
                player.getCurrentServer().ifPresent(conn ->
                        conn.sendPluginMessage(FREEZE_CHANNEL, data)
                )
        );
    }
}
