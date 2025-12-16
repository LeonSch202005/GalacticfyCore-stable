package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class StaffCommand implements SimpleCommand {

    private static final String PERM_STAFF_LIST = "galacticfy.staff.list";
    private static final String PERM_STAFF_FLAG = "galacticfy.report.view";

    private final ProxyServer proxy;
    private final GalacticfyPermissionService perms;

    public StaffCommand(ProxyServer proxy, GalacticfyPermissionService perms) {
        this.proxy = proxy;
        this.perms = perms;
    }

    private Component prefix() {
        return Component.text("§8[§cStaff§8] §r");
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();

        if (!hasPermission(invocation)) {
            src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
            return;
        }

        List<Player> staffOnline = new ArrayList<>();

        for (Player p : proxy.getAllPlayers()) {
            if (isStaff(p)) {
                staffOnline.add(p);
            }
        }

        staffOnline.sort(Comparator.comparing(p -> p.getUsername().toLowerCase(Locale.ROOT)));

        src.sendMessage(Component.text(" "));
        src.sendMessage(prefix().append(Component.text("§bOnline Teammitglieder")));

        if (staffOnline.isEmpty()) {
            src.sendMessage(Component.text("§7Aktuell ist §ckein §7Teammitglied online."));
            src.sendMessage(Component.text(" "));
            return;
        }

        src.sendMessage(Component.text("§7Anzahl: §e" + staffOnline.size()));

        for (Player p : staffOnline) {
            String serverName = p.getCurrentServer()
                    .map(conn -> conn.getServerInfo().getName())
                    .orElse("Unbekannt");

            src.sendMessage(Component.text(
                    "§8» §c" + p.getUsername() + " §8- §7Server: §b" + serverName
            ));
        }

        src.sendMessage(Component.text(" "));
    }

    private boolean isStaff(Player p) {
        if (perms != null) {
            return perms.hasPluginPermission(p, PERM_STAFF_FLAG)
                    || perms.hasPluginPermission(p, PERM_STAFF_LIST);
        }
        return p.hasPermission(PERM_STAFF_FLAG) || p.hasPermission(PERM_STAFF_LIST);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        CommandSource src = invocation.source();
        if (src instanceof Player p) {
            if (perms != null) {
                return perms.hasPluginPermission(p, PERM_STAFF_LIST);
            }
            return p.hasPermission(PERM_STAFF_LIST);
        }
        return true;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        // /staff hat keine Argumente
        return List.of();
    }

}
