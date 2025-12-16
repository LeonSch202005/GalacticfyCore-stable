package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.service.MessageService;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Locale;

public class AnnounceCommand implements SimpleCommand {

    private static final String PERM_ANNOUNCE = "galacticfy.announce";

    private final MessageService messageService;
    private final GalacticfyPermissionService perms;

    public AnnounceCommand(MessageService messageService,
                           GalacticfyPermissionService perms) {
        this.messageService = messageService;
        this.perms = perms;
    }

    private boolean hasAnnouncePermission(CommandSource src) {
        if (src instanceof Player player) {
            if (perms != null) {
                return perms.hasPluginPermission(player, PERM_ANNOUNCE);
            }
            return player.hasPermission(PERM_ANNOUNCE);
        }
        return true; // Konsole darf
    }

    private void noPerm(CommandSource src) {
        src.sendMessage(Component.text("§8[§bGalacticfy§8] §cDazu hast du keine Berechtigung."));
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!hasAnnouncePermission(src)) {
            noPerm(src);
            return;
        }

        if (args.length == 0) {
            src.sendMessage(Component.text("§8[§bGalacticfy§8] §eBenutzung: §b/announce <Nachricht...>"));
            return;
        }

        String msg = String.join(" ", args);

        // 🔥 passt zum MessageService: announce(String)
        messageService.announce(msg);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return hasAnnouncePermission(invocation.source());
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
