package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class SendAllCommand implements SimpleCommand {

    private static final String PERM_SENDALL = "galacticfy.core.sendall";

    private final ProxyServer proxy;

    public SendAllCommand(ProxyServer proxy) {
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
            src.sendMessage(prefix().append(Component.text(
                    "§eBenutzung: §b/sendall <ZielServer>"
            )));
            return;
        }

        String targetName = args[0];
        Optional<RegisteredServer> optServer = proxy.getServer(targetName);

        if (optServer.isEmpty()) {
            src.sendMessage(prefix().append(Component.text(
                    "§cServer §e" + targetName + " §cwurde nicht gefunden."
            )));
            return;
        }

        RegisteredServer target = optServer.get();

        int moved = 0;
        for (Player p : proxy.getAllPlayers()) {
            p.createConnectionRequest(target).connect();
            moved++;
        }

        src.sendMessage(prefix().append(Component.text(
                "§7Es wurden §e" + moved + " §7Spieler nach §b" + target.getServerInfo().getName() + " §7gesendet."
        )));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERM_SENDALL);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        // /sendall <Server>
        if (args.length == 0) {
            return proxy.getAllServers().stream()
                    .map(s -> s.getServerInfo().getName())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }

        if (args.length == 1) {
            String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
            return proxy.getAllServers().stream()
                    .map(s -> s.getServerInfo().getName())
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }

        return List.of();
    }

}
