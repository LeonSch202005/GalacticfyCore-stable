package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class WhereisCommand implements SimpleCommand {

    private static final String PERM_WHEREIS = "galacticfy.core.whereis";

    private final ProxyServer proxy;

    public WhereisCommand(ProxyServer proxy) {
        this.proxy = proxy;
    }

    private Component prefix() {
        return Component.text("§8[§bGalacticfy§8] §r");
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!hasPermission(invocation)) {
            src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
            return;
        }

        if (args.length < 1) {
            src.sendMessage(prefix().append(Component.text("§eBenutzung: §b/whereis <Spieler>")));
            return;
        }

        String name = args[0];
        Optional<Player> opt = proxy.getPlayer(name);

        if (opt.isEmpty()) {
            src.sendMessage(prefix().append(Component.text(
                    "§7Spieler §e" + name + " §7ist nicht online."
            )));
            return;
        }

        Player p = opt.get();
        String serverName = p.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName())
                .orElse("Unbekannt");

        src.sendMessage(prefix().append(Component.text(
                "§7Spieler §f" + p.getUsername() + " §7ist auf §b" + serverName + "§7."
        )));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERM_WHEREIS);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length == 0) {
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }

        if (args.length == 1) {
            String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }

        return List.of();
    }

}
