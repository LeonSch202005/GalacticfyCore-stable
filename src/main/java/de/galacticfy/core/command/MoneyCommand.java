package de.galacticfy.core.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.service.EconomyService;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class MoneyCommand implements SimpleCommand {

    private static final String PERM_ADMIN = "galacticfy.eco.admin";

    private final ProxyServer proxy;
    private final EconomyService economy;
    private final GalacticfyPermissionService perms;

    public MoneyCommand(ProxyServer proxy,
                        EconomyService economy,
                        GalacticfyPermissionService perms) {
        this.proxy = proxy;
        this.economy = economy;
        this.perms = perms;
    }

    private Component prefix() {
        return Component.text("§8[§6Economy§8] §r");
    }

    @Override
    public void execute(Invocation invocation) {
        var src  = invocation.source();
        var args = invocation.arguments();

        // /money → eigenes Guthaben
        if (args.length == 0) {
            if (!(src instanceof Player p)) {
                src.sendMessage(prefix().append(Component.text(
                        "§eBenutzung: §b/money <Spieler>"
                )));
                return;
            }

            long galas = economy.getBalance(p.getUniqueId());
            long dust  = economy.getStardust(p.getUniqueId());

            src.sendMessage(Component.text(" "));
            src.sendMessage(prefix().append(Component.text("§7Dein Kontostand:")));
            src.sendMessage(Component.text("§8» §6Galas: §e" + galas));
            src.sendMessage(Component.text("§8» §dStardust: §d" + dust + "✧"));
            src.sendMessage(Component.text(" "));
            return;
        }

        // /money <Spieler> → Admin (oder Konsole)
        if (!(src instanceof Player p) || perms.hasPluginPermission(p, PERM_ADMIN)) {
            String targetName = args[0];
            Player target = proxy.getPlayer(targetName).orElse(null);

            if (target == null) {
                src.sendMessage(prefix().append(Component.text("§cSpieler ist nicht online.")));
                return;
            }

            long galas = economy.getBalance(target.getUniqueId());
            long dust  = economy.getStardust(target.getUniqueId());

            src.sendMessage(Component.text(" "));
            src.sendMessage(prefix().append(Component.text(
                    "§7Kontostand von §f" + target.getUsername() + "§7:"
            )));
            src.sendMessage(Component.text("§8» §6Galas: §e" + galas));
            src.sendMessage(Component.text("§8» §dStardust: §d" + dust + "✧"));
            src.sendMessage(Component.text(" "));
            return;
        }

        src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        var src  = invocation.source();
        var args = invocation.arguments();

        if (!(src instanceof Player p)) {
            return List.of();
        }

        boolean isAdmin = perms.hasPluginPermission(p, PERM_ADMIN);
        if (!isAdmin) {
            // normale Spieler haben nur /money ohne Argument
            return List.of();
        }

        // direkt nach "/money " → alle Spieler
        if (args.length == 0) {
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .sorted(String::compareToIgnoreCase)
                    .collect(Collectors.toList());
        }

        // /money <Spieler>
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String::compareToIgnoreCase)
                    .collect(Collectors.toList());
        }

        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // /money ist für alle nutzbar, Admin-Check nur bei /money <Spieler>
        return true;
    }
}
