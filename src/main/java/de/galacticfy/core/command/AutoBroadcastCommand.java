package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import de.galacticfy.core.service.AutoBroadcastService;
import net.kyori.adventure.text.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AutoBroadcastCommand implements SimpleCommand {

    private static final String PERM_AUTOBCAST = "galacticfy.autobc";

    private final AutoBroadcastService autoBroadcast;

    public AutoBroadcastCommand(AutoBroadcastService autoBroadcast) {
        this.autoBroadcast = autoBroadcast;
    }

    private Component prefix() {
        return Component.text("§8[§bAutoBC§8] §r");
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!hasPermission(invocation)) {
            src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
            return;
        }

        if (args.length == 0) {
            sendUsage(src);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "status" -> handleStatus(src);
            case "toggle" -> handleToggle(src);
            case "minplayers" -> handleMinPlayers(src, args);
            case "interval" -> handleInterval(src, args);
            case "list" -> handleList(src);
            case "add" -> handleAdd(src, args);
            case "remove" -> handleRemove(src, args);
            default -> sendUsage(src);
        }
    }

    private void sendUsage(CommandSource src) {
        src.sendMessage(Component.text(" "));
        src.sendMessage(prefix().append(Component.text("§bAutoBroadcast Verwaltung")));
        src.sendMessage(Component.text(" "));
        src.sendMessage(Component.text("§8» §b/autobc status"));
        src.sendMessage(Component.text("   §7Zeigt aktuellen Status."));
        src.sendMessage(Component.text("§8» §b/autobc toggle"));
        src.sendMessage(Component.text("   §7Startet oder stoppt den AutoBroadcast."));
        src.sendMessage(Component.text("§8» §b/autobc minplayers <Zahl>"));
        src.sendMessage(Component.text("   §7Setzt die Mindestanzahl an Spielern."));
        src.sendMessage(Component.text("§8» §b/autobc interval <Minuten>"));
        src.sendMessage(Component.text("   §7Setzt das Intervall zwischen Nachrichten."));
        src.sendMessage(Component.text("§8» §b/autobc list"));
        src.sendMessage(Component.text("   §7Listet alle AutoBroadcast-Nachrichten."));
        src.sendMessage(Component.text("§8» §b/autobc add <Nachricht...>"));
        src.sendMessage(Component.text("   §7Fügt eine neue Nachricht hinzu."));
        src.sendMessage(Component.text("§8» §b/autobc remove <ID>"));
        src.sendMessage(Component.text("   §7Löscht eine Nachricht anhand der ID (aus /autobc list)."));
        src.sendMessage(Component.text(" "));
    }

    private void handleStatus(CommandSource src) {
        boolean running = autoBroadcast.isRunning();
        int minPlayers = autoBroadcast.getMinPlayersForBroadcast();
        long intervalMin = autoBroadcast.getInterval().toMinutes();

        src.sendMessage(prefix().append(Component.text(
                "§7Status: " + (running ? "§aAktiv" : "§cInaktiv")
        )));
        src.sendMessage(Component.text("§7Mindestspieler: §b" + minPlayers));
        src.sendMessage(Component.text("§7Intervall: §b" + intervalMin + " §7Minuten"));
    }

    private void handleToggle(CommandSource src) {
        if (autoBroadcast.isRunning()) {
            autoBroadcast.shutdown();
            src.sendMessage(prefix().append(Component.text("§cAutoBroadcast wurde gestoppt.")));
        } else {
            autoBroadcast.start();
            src.sendMessage(prefix().append(Component.text("§aAutoBroadcast wurde gestartet.")));
        }
    }

    private void handleMinPlayers(CommandSource src, String[] args) {
        if (args.length < 2) {
            src.sendMessage(prefix().append(Component.text(
                    "§eBenutzung: §b/autobc minplayers <Zahl>"
            )));
            return;
        }

        try {
            int value = Integer.parseInt(args[1]);
            autoBroadcast.setMinPlayersForBroadcast(value);
            src.sendMessage(prefix().append(Component.text(
                    "§aMindestspieler wurde auf §e" + autoBroadcast.getMinPlayersForBroadcast() + " §agesetzt."
            )));
        } catch (NumberFormatException e) {
            src.sendMessage(prefix().append(Component.text("§cBitte gib eine gültige Zahl an.")));
        }
    }

    private void handleInterval(CommandSource src, String[] args) {
        if (args.length < 2) {
            src.sendMessage(prefix().append(Component.text(
                    "§eBenutzung: §b/autobc interval <Minuten>"
            )));
            return;
        }

        try {
            int minutes = Integer.parseInt(args[1]);
            if (minutes < 1) minutes = 1;

            autoBroadcast.setInterval(Duration.ofMinutes(minutes));
            src.sendMessage(prefix().append(Component.text(
                    "§aIntervall wurde auf §e" + minutes + " §aMinuten gesetzt."
            )));
        } catch (NumberFormatException e) {
            src.sendMessage(prefix().append(Component.text("§cBitte gib eine gültige Zahl an.")));
        }
    }

    private void handleList(CommandSource src) {
        List<String> msgs = autoBroadcast.getMessages();

        if (msgs.isEmpty()) {
            src.sendMessage(prefix().append(Component.text("§7Es sind keine AutoBroadcast-Nachrichten definiert.")));
            return;
        }

        src.sendMessage(prefix().append(Component.text("§bAktuelle AutoBroadcast-Nachrichten:")));
        for (int i = 0; i < msgs.size(); i++) {
            String line = "§8[§e" + i + "§8] §7" + msgs.get(i);
            src.sendMessage(Component.text(line));
        }
    }

    private void handleAdd(CommandSource src, String[] args) {
        if (args.length < 2) {
            src.sendMessage(prefix().append(Component.text(
                    "§eBenutzung: §b/autobc add <Nachricht...>"
            )));
            return;
        }

        String msg = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        autoBroadcast.addMessage(msg);

        src.sendMessage(prefix().append(Component.text(
                "§aNachricht hinzugefügt: §7" + msg
        )));
    }

    private void handleRemove(CommandSource src, String[] args) {
        if (args.length < 2) {
            src.sendMessage(prefix().append(Component.text(
                    "§eBenutzung: §b/autobc remove <ID>"
            )));
            return;
        }

        try {
            int id = Integer.parseInt(args[1]);
            boolean ok = autoBroadcast.removeMessage(id);
            if (ok) {
                src.sendMessage(prefix().append(Component.text(
                        "§aNachricht mit ID §e" + id + " §awurde entfernt."
                )));
            } else {
                src.sendMessage(prefix().append(Component.text(
                        "§cKeine Nachricht mit ID §e" + id + " §cgefunden."
                )));
            }
        } catch (NumberFormatException e) {
            src.sendMessage(prefix().append(Component.text("§cBitte gib eine gültige ID (Zahl) an.")));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERM_AUTOBCAST);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length == 0) {
            return List.of("status", "toggle", "minplayers", "interval", "list", "add", "remove");
        }

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> base = List.of("status", "toggle", "minplayers", "interval", "list", "add", "remove");
            List<String> out = new ArrayList<>();
            for (String s : base) {
                if (s.startsWith(prefix)) out.add(s);
            }
            return out;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            // IDs von 0..n-1 vorschlagen
            List<String> out = new ArrayList<>();
            for (int i = 0; i < autoBroadcast.getMessages().size(); i++) {
                out.add(String.valueOf(i));
            }
            return out;
        }

        return List.of();
    }
}
