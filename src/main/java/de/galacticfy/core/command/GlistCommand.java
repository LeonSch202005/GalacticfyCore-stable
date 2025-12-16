package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class GlistCommand implements SimpleCommand {

    private static final String PERM_GLIST = "galacticfy.core.glist";

    private final ProxyServer proxy;

    public GlistCommand(ProxyServer proxy) {
        this.proxy = proxy;
    }

    private Component prefix() {
        return Component.text("§8[§bGalacticfy§8] §r");
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();

        if (!hasPermission(invocation)) {
            src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
            return;
        }

        Collection<RegisteredServer> servers = proxy.getAllServers();

        src.sendMessage(Component.text(" "));
        src.sendMessage(prefix().append(Component.text("§bGlobale Spielerliste")));

        int total = 0;
        for (RegisteredServer server : servers) {
            Collection<Player> players = server.getPlayersConnected();
            total += players.size();

            String names = players.stream()
                    .map(Player::getUsername)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .reduce((a, b) -> a + "§7, §f" + b)
                    .orElse("§7(keine Spieler)");

            src.sendMessage(Component.text(
                    "§8» §b" + server.getServerInfo().getName() +
                            " §8(§f" + players.size() + "§8): §f" + names
            ));
        }

        src.sendMessage(Component.text("§8» §7Gesamt: §b" + total + " §7Spieler"));
        src.sendMessage(Component.text(" "));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERM_GLIST);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        // /glist hat keine Argumente, einfach leer
        return List.of();
    }

}
