package de.galacticfy.core.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.service.ReportService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ReportJoinNotifyListener {

    private static final String PERM_REPORT_VIEW = "galacticfy.report.staff";

    private final ReportService reportService;
    private final GalacticfyPermissionService perms;

    // Anti-Spam: pro Session einmal
    private final Set<UUID> notified = ConcurrentHashMap.newKeySet();

    public ReportJoinNotifyListener(ReportService reportService,
                                    GalacticfyPermissionService perms) {
        this.reportService = reportService;
        this.perms = perms;
    }

    private Component prefix() {
        return Component.text("§8[§dReports§8] §r");
    }

    private boolean canView(Player player) {
        if (perms != null) return perms.hasPluginPermission(player, PERM_REPORT_VIEW);
        return player.hasPermission(PERM_REPORT_VIEW);
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();

        // Nur Staff
        if (!canView(player)) return;

        // Anti-Spam
        if (!notified.add(player.getUniqueId())) return;

        int open = reportService.countOpenReports();
        if (open <= 0) return;

        Component msg = prefix().append(Component.text(
                "§7Es gibt aktuell §e" + open + " §7offene Reports. "
        ));

        Component button = Component.text("§8[§dÖffnen§8]")
                .clickEvent(ClickEvent.runCommand("/reports openall"))
                .hoverEvent(HoverEvent.showText(Component.text("§7Klicke um alle offenen Reports zu sehen")));

        player.sendMessage(msg.append(button));
    }
}
