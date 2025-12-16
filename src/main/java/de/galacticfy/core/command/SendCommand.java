package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.service.ServerTeleportService;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class SendCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final ServerTeleportService teleportService;
    private final GalacticfyPermissionService perms;

    public SendCommand(ProxyServer proxy,
                       ServerTeleportService teleportService,
                       GalacticfyPermissionService perms) {
        this.proxy = proxy;
        this.teleportService = teleportService;
        this.perms = perms;
    }

    private Component prefix() {
        return Component.text("§8[§bGalacticfy§8] §r");
    }

    private boolean hasSendPermission(CommandSource source) {
        // Konsole darf immer
        if (!(source instanceof Player player)) {
            return true;
        }

        String node = "galacticfy.command.send";

        if (perms != null) {
            // Zentrale Plugin-Permission (inkl. "*", Wildcards, LP-Fallback)
            return perms.hasPluginPermission(source, node);
        }

        return player.hasPermission(node);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();

        if (!hasSendPermission(src)) {
            src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length < 2) {
            src.sendMessage(prefix().append(Component.text("§eBenutzung: §b/send <Spieler> <Server>")));
            return;
        }

        String targetName = args[0];
        String backendName = args[1];

        Optional<Player> optional = proxy.getPlayer(targetName);
        if (optional.isEmpty()) {
            src.sendMessage(prefix().append(Component.text("§cSpieler \"" + targetName + "\" wurde nicht gefunden.")));
            return;
        }

        Player target = optional.get();
        teleportService.sendToServer(target, backendName, backendName, true);
        src.sendMessage(prefix().append(Component.text("§aSende §e" + target.getUsername() + " §aan Server §e" + backendName + "§a.")));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return hasSendPermission(invocation.source());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        CommandSource src = invocation.source();
        if (!hasSendPermission(src)) {
            return List.of();
        }

        String[] args = invocation.arguments();

        // Noch keine Argumente getippt: alle Spieler vorschlagen
        if (args.length == 0) {
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .toList();
        }

        // 1. Argument: Spieler
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }

        // 2. Argument: Server
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            if (prefix.isEmpty()) {
                return proxy.getAllServers().stream()
                        .map(s -> s.getServerInfo().getName())
                        .toList();
            }

            return proxy.getAllServers().stream()
                    .map(s -> s.getServerInfo().getName())
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }

        return List.of();
    }
}
