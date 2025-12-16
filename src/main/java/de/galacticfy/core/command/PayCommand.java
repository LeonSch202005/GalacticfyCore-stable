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

public class PayCommand implements SimpleCommand {

    // Normales /pay darf jeder nutzen
    // /pay * nur mit diesem Recht:
    private static final String PERM_PAY_ALL = "galacticfy.eco.payall";

    private final ProxyServer proxy;
    private final EconomyService economy;
    private final GalacticfyPermissionService perms;

    public PayCommand(ProxyServer proxy,
                      EconomyService economy,
                      GalacticfyPermissionService perms) {
        this.proxy = proxy;
        this.economy = economy;
        this.perms = perms;
    }

    private Component prefix() {
        return Component.text("§8[§6Money§8] §r");
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player sender)) {
            invocation.source().sendMessage(Component.text("§cNur Spieler können /pay benutzen."));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length != 2) {
            sendUsage(sender);
            return;
        }

        String targetArg = args[0];
        long amount;

        try {
            amount = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(prefix().append(Component.text("§cBitte gib einen gültigen Betrag an.")));
            return;
        }

        if (amount <= 0) {
            sender.sendMessage(prefix().append(Component.text("§cBetrag muss größer als 0 sein.")));
            return;
        }

        // /pay * <amount> → an alle anderen Spieler, nur mit Permission
        if (targetArg.equalsIgnoreCase("*")) {
            if (!perms.hasPluginPermission(sender, PERM_PAY_ALL)) {
                sender.sendMessage(prefix().append(Component.text(
                        "§cDazu hast du keine Berechtigung."
                )));
                return;
            }
            handlePayAll(sender, amount);
            return;
        }

        // /pay <Spieler> <amount> → für alle erlaubt
        Player target = proxy.getPlayer(targetArg).orElse(null);
        if (target == null) {
            sender.sendMessage(prefix().append(Component.text("§cDieser Spieler ist nicht online.")));
            return;
        }

        if (target.getUniqueId().equals(sender.getUniqueId())) {
            sender.sendMessage(prefix().append(Component.text("§cDu kannst dir selbst kein Geld senden.")));
            return;
        }

        long senderBal = economy.getBalance(sender.getUniqueId());
        if (senderBal < amount) {
            sender.sendMessage(prefix().append(Component.text("§cDu hast nicht genug Guthaben.")));
            return;
        }

        boolean okWithdraw = economy.withdraw(sender.getUniqueId(), amount);
        boolean okDeposit = economy.deposit(target.getUniqueId(), amount);

        if (!okWithdraw || !okDeposit) {
            sender.sendMessage(prefix().append(Component.text("§cEs ist ein Fehler beim Transfer aufgetreten.")));
            return;
        }

        sender.sendMessage(prefix().append(Component.text(
                "§7Du hast §e" + amount + "⛃ §7an §e" + target.getUsername() + " §7gesendet."
        )));
        target.sendMessage(prefix().append(Component.text(
                "§7Du hast §e" + amount + "⛃ §7von §e" + sender.getUsername() + " §7erhalten."
        )));
    }

    private void handlePayAll(Player sender, long amount) {
        List<Player> targets = proxy.getAllPlayers().stream()
                .filter(p -> !p.getUniqueId().equals(sender.getUniqueId()))
                .collect(Collectors.toList());

        if (targets.isEmpty()) {
            sender.sendMessage(prefix().append(Component.text(
                    "§7Es sind keine anderen Spieler online."
            )));
            return;
        }

        long needed = amount * targets.size();
        long senderBal = economy.getBalance(sender.getUniqueId());

        if (senderBal < needed) {
            sender.sendMessage(prefix().append(Component.text(
                    "§cDu brauchst §e" + needed + "⛃§c, hast aber nur §e" + senderBal + "⛃§c."
            )));
            return;
        }

        boolean okWithdraw = economy.withdraw(sender.getUniqueId(), needed);
        if (!okWithdraw) {
            sender.sendMessage(prefix().append(Component.text(
                    "§cBeim Abbuchen deines Guthabens ist ein Fehler aufgetreten."
            )));
            return;
        }

        for (Player target : targets) {
            economy.deposit(target.getUniqueId(), amount);
            target.sendMessage(prefix().append(Component.text(
                    "§7Du hast §e" + amount + "⛃ §7von §e" + sender.getUsername() + " §7erhalten."
            )));
        }

        sender.sendMessage(prefix().append(Component.text(
                "§7Du hast §e" + amount + "⛃ §7an §e" + targets.size() +
                        " §7Spieler gesendet (§einsgesamt §e" + needed + "⛃§7)."
        )));
    }

    private void sendUsage(Player sender) {
        sender.sendMessage(prefix().append(Component.text("§eVerwendung:")));
        sender.sendMessage(Component.text("§8» §b/pay <Spieler> <Betrag>"));
        sender.sendMessage(Component.text("§8» §b/pay * <Betrag> §7- an alle online Spieler §8(§dnur mit Permission§8)"));
    }

    // ============================
    // TAB-COMPLETE
    // ============================

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        var src = invocation.source();

        if (!(src instanceof Player p)) {
            return List.of();
        }

        // direkt nach "/pay " → alle Spieler (und ggf. "*") anzeigen
        if (args.length == 0) {
            return buildTargetSuggestions(p, "");
        }

        // /pay <Ziel>
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return buildTargetSuggestions(p, prefix);
        }

        // /pay <Ziel> <Betrag>
        if (args.length == 2) {
            String prefix = args[1];
            return List.of("10", "50", "100", "250", "1000").stream()
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }

        return List.of();
    }

    private List<String> buildTargetSuggestions(Player requester, String prefix) {
        // alle Spielernamen sammeln
        List<String> names = proxy.getAllPlayers().stream()
                .map(Player::getUsername)
                .collect(Collectors.toList());

        // "*" nur vorschlagen, wenn der Spieler die Pay-All-Permission hat
        if (perms.hasPluginPermission(requester, PERM_PAY_ALL)) {
            names.add("*");
        }

        String lower = prefix.toLowerCase(Locale.ROOT);

        return names.stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // /pay soll jeder benutzen können
        return true;
    }
}
