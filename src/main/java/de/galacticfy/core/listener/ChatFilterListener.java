package de.galacticfy.core.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import de.galacticfy.core.service.ChatFilterService;
import net.kyori.adventure.text.Component;

public class ChatFilterListener {

    private final ChatFilterService filter;

    public ChatFilterListener(ChatFilterService filter) {
        this.filter = filter;
    }

    private Component prefix() {
        return Component.text("§8[§bGalacticfy§8] §r");
    }

    @Subscribe
    public void onChat(PlayerChatEvent event) {
        Player p = event.getPlayer();
        String msg = event.getMessage();

        if (filter.isBlocked(msg)) {
            event.setResult(PlayerChatEvent.ChatResult.denied());
            p.sendMessage(prefix().append(Component.text(
                    "§cDeine Nachricht wurde vom Chatfilter blockiert."
            )));
        }
    }
}
