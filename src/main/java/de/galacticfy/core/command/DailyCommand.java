package de.galacticfy.core.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.service.DailyRewardService;
import de.galacticfy.core.service.DailyRewardService.ClaimResult;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class DailyCommand implements SimpleCommand {

    private static final String PERM_ADMIN = "galacticfy.daily.admin";

    private final DailyRewardService dailyService;
    private final GalacticfyPermissionService perms;
    private final ProxyServer proxy;

    public DailyCommand(DailyRewardService dailyService,
                        GalacticfyPermissionService perms,
                        ProxyServer proxy) {
        this.dailyService = dailyService;
        this.perms = perms;
        this.proxy = proxy;
    }

    private Component prefix() {
        return Component.text("§8[§aDaily§8] §r");
    }

    @Override
    public void execute(Invocation invocation) {
        var src = invocation.source();
        var args = invocation.arguments();

        // /daily
        if (args.length == 0) {
            if (!(src instanceof Player p)) {
                src.sendMessage(prefix().append(Component.text(
                        "§cNur Spieler können tägliche Belohnungen abholen."
                )));
                return;
            }

            ClaimResult result = dailyService.claimDaily(p.getUniqueId(), p.getUsername());

            if (result.alreadyClaimed()) {
                src.sendMessage(prefix().append(Component.text(
                        "§cDu hast deine tägliche Belohnung heute bereits abgeholt!"
                )));
                src.sendMessage(Component.text(
                        "§7Dein aktueller §aStreak§7: §a" + result.streak() + " §7Tag(e)."
                ));
                return;
            }

            if (!result.success()) {
                src.sendMessage(prefix().append(Component.text(
                        "§cEs ist ein Fehler beim Abholen deiner Belohnung aufgetreten."
                )));
                return;
            }

            long galas = result.galasReward();
            long stardust = result.stardustReward();

            src.sendMessage(Component.text(" "));
            src.sendMessage(prefix().append(Component.text(
                    "§aDu hast deine tägliche Belohnung abgeholt!"
            )));
            src.sendMessage(Component.text(
                    "§7Streak: §a" + result.streak() + " §7Tag(e) hintereinander."
            ));
            src.sendMessage(Component.text(
                    "§8» §6+ " + galas + " §eGalas"
            ));
            if (stardust > 0) {
                src.sendMessage(Component.text(
                        "§8» §d+ " + stardust + "✧ §dStardust §7(Bonus!)"
                ));
            }
            src.sendMessage(Component.text(" "));
            return;
        }

        // Admin-Subcommands
        if (args.length >= 1 && args[0].equalsIgnoreCase("reset")) {
            if (!isAdmin(src)) {
                src.sendMessage(prefix().append(Component.text(
                        "§cDazu hast du keine Berechtigung."
                )));
                return;
            }

            if (args.length < 2) {
                src.sendMessage(prefix().append(Component.text(
                        "§eBenutzung: §a/daily reset <Spieler>"
                )));
                return;
            }

            String targetName = args[1];
            Player target = proxy.getPlayer(targetName).orElse(null);

            if (target == null) {
                src.sendMessage(prefix().append(Component.text("§cSpieler muss online sein.")));
                return;
            }

            boolean ok = dailyService.resetDaily(target.getUniqueId());
            if (!ok) {
                src.sendMessage(prefix().append(Component.text(
                        "§cFehler beim Zurücksetzen der Daily-Daten."
                )));
                return;
            }

            src.sendMessage(prefix().append(Component.text(
                    "§aDaily-Streak und Claim für §f" + target.getUsername() + " §awurden zurückgesetzt."
            )));
            target.sendMessage(prefix().append(Component.text(
                    "§eDeine Daily-Daten wurden von einem Teammitglied zurückgesetzt."
            )));
            return;
        }

        // Falscher Subcommand
        src.sendMessage(prefix().append(Component.text(
                "§eBenutzung: §a/daily §7oder §a/daily reset <Spieler>"
        )));
    }

    private boolean isAdmin(com.velocitypowered.api.command.CommandSource src) {
        if (!(src instanceof Player p)) {
            return true; // Konsole darf immer
        }
        return perms.hasPluginPermission(p, PERM_ADMIN);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        var src = invocation.source();
        var args = invocation.arguments();

        boolean admin = isAdmin(src);

        // /daily <...>
        if (args.length == 1 && admin) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("reset").stream()
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }

        // /daily reset <Spieler>
        if (args.length == 2 && admin && args[0].equalsIgnoreCase("reset")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
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
        // /daily selber darf jeder ausführen
        return true;
    }
}
