package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import net.kyori.adventure.text.Component;

import java.util.List;

public class StaffChatCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final GalacticfyPermissionService perms;

    private static final String PERM_STAFFCHAT = "galacticfy.staffchat";

    public StaffChatCommand(ProxyServer proxy,
                            GalacticfyPermissionService perms) {
        this.proxy = proxy;
        this.perms = perms;
    }

    private Component prefix() {
        return Component.text("§8[§cStaffChat§8] §r");
    }

    private boolean hasStaffChat(CommandSource src) {
        if (src instanceof Player p) {
            if (perms != null) {
                return perms.hasPluginPermission(p, PERM_STAFFCHAT);
            }
            return p.hasPermission(PERM_STAFFCHAT);
        }
        return true;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!hasStaffChat(src)) {
            if (src instanceof Player) {
                src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
            }
            return;
        }

        if (args.length == 0) {
            src.sendMessage(prefix().append(Component.text("§eBenutzung: §b/staffchat <Nachricht>")));
            return;
        }

        String senderName = (src instanceof Player p) ? p.getUsername() : "Konsole";
        String msg = String.join(" ", args);

        Component out = Component.text("§8[§cSC§8] §c" + senderName + " §8» §f" + msg);

        proxy.getAllPlayers().forEach(player -> {
            if (hasStaffChat(player)) {
                player.sendMessage(out);
            }
        });

        if (!(src instanceof Player)) {
            // Konsole auch anzeigen
            src.sendMessage(out);
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return hasStaffChat(invocation.source());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!hasStaffChat(invocation.source())) {
            return List.of();
        }
        // Keine speziellen Tab-Vorschläge nötig
        return List.of();
    }
}
