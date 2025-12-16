package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.service.MessageService;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Locale;

public class BroadcastCommand implements SimpleCommand {

    private static final String PERM_BROADCAST = "galacticfy.broadcast";

    private final MessageService messageService;
    private final GalacticfyPermissionService perms;

    public BroadcastCommand(MessageService messageService,
                            GalacticfyPermissionService perms) {
        this.messageService = messageService;
        this.perms = perms;
    }

    private boolean hasBroadcastPermission(CommandSource src) {
        if (src instanceof Player player) {
            if (perms != null) {
                return perms.hasPluginPermission(player, PERM_BROADCAST);
            }
            return player.hasPermission(PERM_BROADCAST);
        }
        return true; // Konsole darf immer
    }

    private void noPerm(CommandSource src) {
        src.sendMessage(Component.text("§8[§bGalacticfy§8] §cDazu hast du keine Berechtigung."));
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!hasBroadcastPermission(src)) {
            noPerm(src);
            return;
        }

        if (args.length == 0) {
            src.sendMessage(Component.text("§8[§bGalacticfy§8] §eBenutzung: §b/broadcast <Nachricht...>"));
            return;
        }

        String msg = String.join(" ", args);

        // passt zur MessageService#broadcast(String)
        messageService.broadcast(msg);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return hasBroadcastPermission(invocation.source());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) {
            return List.of("Server befindet sich aktuell in Wartungen.");
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            String example = "Server befindet sich aktuell in Wartungen.";
            return example.toLowerCase(Locale.ROOT).startsWith(prefix)
                    ? List.of(example)
                    : List.of();
        }
        return List.of();
    }
}
