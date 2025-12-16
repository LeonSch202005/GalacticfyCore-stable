package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.service.EconomyService;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.stream.Collectors;

public class EcoCommand implements SimpleCommand {

    private static final String PERM_ADMIN = "galacticfy.eco.admin";
    private static final Logger LOGGER = LoggerFactory.getLogger(EcoCommand.class);

    private final ProxyServer proxy;
    private final EconomyService economy;
    private final GalacticfyPermissionService perms;

    public EcoCommand(ProxyServer proxy, EconomyService economy, GalacticfyPermissionService perms) {
        this.proxy = proxy;
        this.economy = economy;
        this.perms = perms;
    }

    private Component prefix() {
        return Component.text("§8[§6Money§8] §r");
    }

    private boolean isAdmin(CommandSource src) {
        if (!(src instanceof Player p)) {
            // Konsole darf immer
            return true;
        }
        return perms.hasPluginPermission(p, PERM_ADMIN);
    }

    // ------------------------------------------------------------
    // SQL-Safety Helper
    // ------------------------------------------------------------

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    @FunctionalInterface
    private interface SqlLongSupplier {
        long getAsLong() throws SQLException;
    }

    private boolean runSql(CommandSource src, String context, SqlAction action) {
        try {
            action.run();
            return true;
        } catch (SQLException e) {
            LOGGER.error("EcoCommand SQL error ({}): {}", context, e.getMessage(), e);
            src.sendMessage(prefix().append(Component.text("§cDatenbankfehler. Bitte später erneut versuchen.")));
            return false;
        }
    }

    private OptionalLong getSqlLong(CommandSource src, String context, SqlLongSupplier supplier) {
        try {
            return OptionalLong.of(supplier.getAsLong());
        } catch (SQLException e) {
            LOGGER.error("EcoCommand SQL error ({}): {}", context, e.getMessage(), e);
            src.sendMessage(prefix().append(Component.text("§cDatenbankfehler. Bitte später erneut versuchen.")));
            return OptionalLong.empty();
        }
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!isAdmin(src)) {
            src.sendMessage(prefix().append(Component.text("§cKeine Rechte.")));
            return;
        }

        if (args.length < 3) {
            sendUsage(src);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String targetName = args[1];
        long amount;

        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            src.sendMessage(prefix().append(Component.text("§cUngültiger Betrag.")));
            return;
        }

        if (amount < 0) {
            src.sendMessage(prefix().append(Component.text("§cBetrag darf nicht negativ sein.")));
            return;
        }

        Player target = proxy.getPlayer(targetName).orElse(null);
        if (target == null) {
            src.sendMessage(prefix().append(Component.text("§cSpieler ist nicht online.")));
            return;
        }

        // Account sicherstellen (Name updaten) – SQL-safe
        boolean ensured = runSql(src, "ensureAccount(" + target.getUsername() + ")", () ->
                economy.ensureAccount(target.getUniqueId(), target.getUsername())
        );
        if (!ensured) return;

        switch (sub) {
            case "set" -> handleSet(src, target, amount);
            case "add" -> handleAdd(src, target, amount);
            case "remove" -> handleRemove(src, target, amount);
            default -> src.sendMessage(prefix().append(Component.text("§cUnbekannter Subcommand.")));
        }
    }

    private void handleSet(CommandSource src, Player target, long amount) {
        // Falls setBalance SQLException wirft: ebenfalls SQL-safe ausführen
        boolean ok = runSql(src, "setBalance(" + target.getUsername() + ")", () -> {
            boolean res = economy.setBalance(target.getUniqueId(), amount);
            if (!res) throw new SQLException("setBalance returned false");
        });
        if (!ok) return;

        src.sendMessage(prefix().append(Component.text(
                "§aBalance von §e" + target.getUsername() +
                        " §awurde auf §e" + amount + "⛃ §agesetzt."
        )));
        target.sendMessage(prefix().append(Component.text(
                "§7Dein Guthaben wurde auf §e" + amount + "⛃ §7gesetzt."
        )));
    }

    private void handleAdd(CommandSource src, Player target, long amount) {
        if (amount == 0) {
            src.sendMessage(prefix().append(Component.text("§cBetrag muss größer als 0 sein.")));
            return;
        }

        boolean ok = runSql(src, "deposit(" + target.getUsername() + ")", () -> {
            boolean res = economy.deposit(target.getUniqueId(), amount);
            if (!res) throw new SQLException("deposit returned false");
        });
        if (!ok) return;

        OptionalLong newBalOpt = getSqlLong(src, "getBalance(after deposit, " + target.getUsername() + ")", () ->
                economy.getBalance(target.getUniqueId())
        );
        if (newBalOpt.isEmpty()) return;

        long newBal = newBalOpt.getAsLong();

        src.sendMessage(prefix().append(Component.text(
                "§aBalance von §e" + target.getUsername() +
                        " §awurde um §e" + amount + "⛃ §aerhöht (neu: §e" + newBal + "⛃§a)."
        )));
        target.sendMessage(prefix().append(Component.text(
                "§aDu hast §e+" + amount + "⛃ §aerhalten. Neues Guthaben: §e" + newBal + "⛃§a."
        )));
    }

    private void handleRemove(CommandSource src, Player target, long amount) {
        if (amount == 0) {
            src.sendMessage(prefix().append(Component.text("§cBetrag muss größer als 0 sein.")));
            return;
        }

        OptionalLong currentOpt = getSqlLong(src, "getBalance(before withdraw, " + target.getUsername() + ")", () ->
                economy.getBalance(target.getUniqueId())
        );
        if (currentOpt.isEmpty()) return;

        long current = currentOpt.getAsLong();
        if (current < amount) {
            src.sendMessage(prefix().append(Component.text(
                    "§cSpieler hat nicht genug Guthaben (§e" + current + "⛃§c)."
            )));
            return;
        }

        boolean ok = runSql(src, "withdraw(" + target.getUsername() + ")", () -> {
            boolean res = economy.withdraw(target.getUniqueId(), amount);
            if (!res) throw new SQLException("withdraw returned false");
        });
        if (!ok) return;

        OptionalLong newBalOpt = getSqlLong(src, "getBalance(after withdraw, " + target.getUsername() + ")", () ->
                economy.getBalance(target.getUniqueId())
        );
        if (newBalOpt.isEmpty()) return;

        long newBal = newBalOpt.getAsLong();

        src.sendMessage(prefix().append(Component.text(
                "§aBalance von §e" + target.getUsername() +
                        " §awurde um §e" + amount + "⛃ §averringert (neu: §e" + newBal + "⛃§a)."
        )));
        target.sendMessage(prefix().append(Component.text(
                "§cDir wurden §e" + amount + "⛃ §cabgezogen. Neues Guthaben: §e" + newBal + "⛃§c."
        )));
    }

    private void sendUsage(CommandSource src) {
        src.sendMessage(Component.text(" "));
        src.sendMessage(prefix().append(Component.text("§6Eco §7| §6Admin-Befehle")));
        src.sendMessage(Component.text(" "));
        src.sendMessage(Component.text("§8» §e/eco set <Spieler> <Betrag>"));
        src.sendMessage(Component.text("§7    Setzt die Balance eines Spielers."));
        src.sendMessage(Component.text("§8» §e/eco add <Spieler> <Betrag>"));
        src.sendMessage(Component.text("§7    Fügt dem Konto Guthaben hinzu."));
        src.sendMessage(Component.text("§8» §e/eco remove <Spieler> <Betrag>"));
        src.sendMessage(Component.text("§7    Entfernt Guthaben vom Konto."));
        src.sendMessage(Component.text(" "));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        CommandSource src = invocation.source();

        boolean admin = isAdmin(src);

        if (!admin) return List.of();

        if (args.length == 0) {
            return List.of("set", "add", "remove");
        }

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("set", "add", "remove").stream()
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
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
        // Sichtbar für alle, echte Rechte prüfen wir via isAdmin(...)
        return true;
    }
}
