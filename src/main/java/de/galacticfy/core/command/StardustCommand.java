package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.service.EconomyService;
import de.galacticfy.core.service.EconomyService.Account;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class StardustCommand implements SimpleCommand {

    private static final String PERM_ADMIN = "galacticfy.stardust.admin";

    private final ProxyServer proxy;
    private final EconomyService economy;
    private final GalacticfyPermissionService perms;

    public StardustCommand(ProxyServer proxy,
                           EconomyService economy,
                           GalacticfyPermissionService perms) {
        this.proxy = proxy;
        this.economy = economy;
        this.perms = perms;
    }

    private Component prefix() {
        return Component.text("§8[§d✧Stardust§8] §r");
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        // /stardust → eigenes Guthaben
        if (args.length == 0) {
            if (!(src instanceof Player p)) {
                src.sendMessage(prefix().append(Component.text(
                        "§cNur Spieler können ihr Stardust-Guthaben abrufen."
                )));
                return;
            }

            long dust = economy.getStardust(p.getUniqueId());
            p.sendMessage(prefix().append(Component.text(
                    "§7Du besitzt aktuell §d" + dust + "✧ §7Stardust."
            )));
            return;
        }

        // Ab hier: Admin-Subcommands
        if (!isAdmin(src)) {
            src.sendMessage(prefix().append(Component.text(
                    "§cDazu hast du keine Berechtigung."
            )));
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "give", "add" -> handleGive(src, args);
            case "remove", "take" -> handleRemove(src, args);
            case "set" -> handleSet(src, args);
            case "top" -> handleTop(src);
            default -> sendUsage(src, true);
        }
    }

    private boolean isAdmin(CommandSource src) {
        if (!(src instanceof Player p)) {
            // Konsole darf immer
            return true;
        }
        return perms.hasPluginPermission(p, PERM_ADMIN);
    }

    private void handleGive(CommandSource src, String[] args) {
        if (args.length < 3) {
            src.sendMessage(prefix().append(Component.text(
                    "§eBenutzung: §d/stardust give <Spieler> <Menge>"
            )));
            return;
        }

        String targetName = args[1];
        long amount = parseAmount(args[2]);
        if (amount <= 0) {
            src.sendMessage(prefix().append(Component.text("§cUngültige Menge.")));
            return;
        }

        Player target = proxy.getPlayer(targetName).orElse(null);
        if (target == null) {
            src.sendMessage(prefix().append(Component.text("§cSpieler muss online sein.")));
            return;
        }

        boolean ok = economy.addStardust(target.getUniqueId(), amount);
        if (!ok) {
            src.sendMessage(prefix().append(Component.text("§cFehler beim Hinzufügen von Stardust.")));
            return;
        }

        long newAmount = economy.getStardust(target.getUniqueId());

        src.sendMessage(prefix().append(Component.text(
                "§aDu hast §d" + amount + "✧ §aan §f" + target.getUsername() +
                        " §avergeben (neu: §d" + newAmount + "✧§a)."
        )));

        target.sendMessage(prefix().append(Component.text(
                "§aDu hast §d+" + amount + "✧ §aStardust erhalten!"
        )));
    }

    private void handleRemove(CommandSource src, String[] args) {
        if (args.length < 3) {
            src.sendMessage(prefix().append(Component.text(
                    "§eBenutzung: §d/stardust remove <Spieler> <Menge>"
            )));
            return;
        }

        String targetName = args[1];
        long amount = parseAmount(args[2]);
        if (amount <= 0) {
            src.sendMessage(prefix().append(Component.text("§cUngültige Menge.")));
            return;
        }

        Player target = proxy.getPlayer(targetName).orElse(null);
        if (target == null) {
            src.sendMessage(prefix().append(Component.text("§cSpieler muss online sein.")));
            return;
        }

        boolean ok = economy.removeStardust(target.getUniqueId(), amount);
        if (!ok) {
            src.sendMessage(prefix().append(Component.text(
                    "§cSpieler hat nicht genug Stardust oder Fehler aufgetreten."
            )));
            return;
        }

        long newAmount = economy.getStardust(target.getUniqueId());

        src.sendMessage(prefix().append(Component.text(
                "§aDu hast §d" + amount + "✧ §avor §f" + target.getUsername() +
                        " §aentfernt (neu: §d" + newAmount + "✧§a)."
        )));

        target.sendMessage(prefix().append(Component.text(
                "§cDir wurden §d" + amount + "✧ §cStardust abgezogen."
        )));
    }

    private void handleSet(CommandSource src, String[] args) {
        if (args.length < 3) {
            src.sendMessage(prefix().append(Component.text(
                    "§eBenutzung: §d/stardust set <Spieler> <Menge>"
            )));
            return;
        }

        String targetName = args[1];
        long amount = parseAmount(args[2]);
        if (amount < 0) {
            src.sendMessage(prefix().append(Component.text("§cUngültige Menge.")));
            return;
        }

        Player target = proxy.getPlayer(targetName).orElse(null);
        if (target == null) {
            src.sendMessage(prefix().append(Component.text("§cSpieler muss online sein.")));
            return;
        }

        boolean ok = economy.setStardust(target.getUniqueId(), amount);
        if (!ok) {
            src.sendMessage(prefix().append(Component.text(
                    "§cFehler beim Setzen von Stardust."
            )));
            return;
        }

        src.sendMessage(prefix().append(Component.text(
                "§aDu hast das Stardust-Guthaben von §f" + target.getUsername() +
                        " §aauf §d" + amount + "✧ §agesetzt."
        )));

        target.sendMessage(prefix().append(Component.text(
                "§7Dein Stardust-Guthaben wurde auf §d" + amount + "✧ §7gesetzt."
        )));
    }

    private void handleTop(CommandSource src) {
        List<Account> top = economy.getTopStardust(10);

        src.sendMessage(Component.text(" "));
        src.sendMessage(Component.text("§8§m──────────────§r §d✧ Stardust Top 10 ✧§8 §m──────────────"));
        src.sendMessage(Component.text(" "));

        if (top.isEmpty()) {
            src.sendMessage(Component.text("§7Es gibt noch keine Einträge."));
        } else {
            int i = 1;
            for (Account entry : top) {
                String name = entry.name() != null ? entry.name() : "Unbekannt";
                String line = "§d" + i + ". §f" + name +
                        " §8- §d" + entry.stardust() + "✧";
                src.sendMessage(Component.text(line));
                i++;
            }
        }

        src.sendMessage(Component.text(" "));
    }

    private long parseAmount(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void sendUsage(CommandSource src, boolean admin) {
        src.sendMessage(Component.text(" "));
        src.sendMessage(prefix().append(Component.text("§dStardust §7| §dSystem")));
        src.sendMessage(Component.text(" "));

        src.sendMessage(Component.text("§8» §d/stardust §7- Zeigt dein Stardust-Guthaben."));

        if (admin) {
            src.sendMessage(Component.text(" "));
            src.sendMessage(Component.text("§dAdmin-Befehle:"));
            src.sendMessage(Component.text("§8» §d/stardust give <Spieler> <Menge>"));
            src.sendMessage(Component.text("§8» §d/stardust remove <Spieler> <Menge>"));
            src.sendMessage(Component.text("§8» §d/stardust set <Spieler> <Menge>"));
            src.sendMessage(Component.text("§8» §d/stardust top"));
        }

        src.sendMessage(Component.text(" "));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        var src = invocation.source();
        var args = invocation.arguments();

        boolean isAdmin = isAdmin(src);

        // /stardust
        if (args.length == 0) return List.of();

        // /stardust <...>
        if (args.length == 1) {
            if (!isAdmin) return List.of();

            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("give", "remove", "set", "top").stream()
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }

        // /stardust <sub> <Spieler>
        if (args.length == 2 && isAdmin) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("give") || sub.equals("remove") || sub.equals("set")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return proxy.getAllPlayers().stream()
                        .map(Player::getUsername)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .sorted(String::compareToIgnoreCase)
                        .collect(Collectors.toList());
            }
        }

        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // /stardust ist für alle sichtbar, Admin-Sachen werden intern geprüft
        return true;
    }
}
