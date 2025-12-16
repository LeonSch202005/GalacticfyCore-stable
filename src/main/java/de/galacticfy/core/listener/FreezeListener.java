package de.galacticfy.core.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import de.galacticfy.core.service.FreezeService;
import net.kyori.adventure.text.Component;

public class FreezeListener {

    private final FreezeService freezeService;

    public FreezeListener(FreezeService freezeService) {
        this.freezeService = freezeService;
    }

    private Component prefix() {
        return Component.text("§8[§bGalacticfy§8] §r");
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player p = event.getPlayer();
        if (freezeService.isFrozen(p.getUniqueId())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            p.sendMessage(prefix().append(Component.text(
                    "§cDu bist eingefroren und kannst den Server nicht wechseln."
            )));
        }
    }

    @Subscribe
    public void onChat(PlayerChatEvent event) {
        Player p = event.getPlayer();
        if (freezeService.isFrozen(p.getUniqueId())) {
            event.setResult(PlayerChatEvent.ChatResult.denied());
            p.sendMessage(prefix().append(Component.text(
                    "§cDu bist eingefroren und kannst nicht schreiben."
            )));
        }
    }
}
