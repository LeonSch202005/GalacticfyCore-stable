package de.galacticfy.core.command;

import com.velocitypowered.api.command.SimpleCommand;
import de.galacticfy.core.service.EconomyService;
import de.galacticfy.core.service.EconomyService.Account;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class BaltopCommand implements SimpleCommand {

    private final EconomyService economy;

    public BaltopCommand(EconomyService economy) {
        this.economy = economy;
    }

    private Component prefix() {
        return Component.text("§8[§6Baltop§8] §r");
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        var src = invocation.source();

        boolean stardustMode = false;
        int page = 1;

        // ========================
        // Argument Parsing
        // ========================
        if (args.length >= 1) {
            if (args[0].equalsIgnoreCase("stardust") || args[0].equalsIgnoreCase("dust")) {
                stardustMode = true;

                if (args.length >= 2) {
                    try {
                        page = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ignored) {}
                }
            } else {
                try {
                    page = Integer.parseInt(args[0]);
                } catch (NumberFormatException ignored) {}
            }
        }

        if (page < 1) page = 1;

        // ========================
        // Pagination
        // ========================
        int perPage = 10;
        int offset = (page - 1) * perPage;

        List<Account> list = stardustMode
                ? economy.getTopAccountsStardust(perPage, offset)
                : economy.getTopAccounts(perPage, offset);

        // ========================
        // Header
        // ========================
        src.sendMessage(Component.text(" "));
        src.sendMessage(prefix().append(Component.text(
                stardustMode
                        ? "§dTop Stardust ✧ §7(Seite §b" + page + "§7)"
                        : "§eTop Galas ⛃ §7(Seite §b" + page + "§7)"
        )));
        src.sendMessage(Component.text(" "));

        // ========================
        // Empty?
        // ========================
        if (list.isEmpty()) {
            src.sendMessage(Component.text("§7Es sind noch keine Kontodaten vorhanden."));
            src.sendMessage(Component.text(" "));
            return;
        }

        // ========================
        // List Output
        // ========================
        int position = offset + 1;
        for (Account acc : list) {
            String name = acc.name() != null ? acc.name() : "Unbekannt";

            String line;
            if (stardustMode) {
                line = "§8#§d" + position + " §7" + name + " §8» §d" + acc.stardust() + "✧";
            } else {
                line = "§8#§e" + position + " §7" + name + " §8» §e" + acc.balance() + "⛃";
            }

            src.sendMessage(Component.text(line));
            position++;
        }

        src.sendMessage(Component.text(" "));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        // /baltop <TAB>
        if (args.length == 0) {
            return List.of("1", "stardust");
        }

        // /baltop <arg1>
        if (args.length == 1) {
            String s = args[0].toLowerCase(Locale.ROOT);
            return Stream.of("1", "2", "3", "stardust")
                    .filter(x -> x.toLowerCase(Locale.ROOT).startsWith(s))
                    .toList();
        }

        // /baltop stardust <TAB>
        if (args.length == 2 && args[0].equalsIgnoreCase("stardust")) {
            return List.of("1", "2", "3");
        }

        return List.of();
    }
}
