package de.galacticfy.core.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.service.PunishmentService;
import de.galacticfy.core.service.PunishmentService.Punishment;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

public class PunishmentLoginListener {

    // Wer die Alerts sehen darf
    private static final String PERM_PUNISH_ALERTS = "galacticfy.punish.alerts";

    private final PunishmentService punishmentService;
    private final Logger logger;
    private final ProxyServer proxy;
    private final GalacticfyPermissionService perms;

    public PunishmentLoginListener(PunishmentService punishmentService,
                                   Logger logger,
                                   ProxyServer proxy,
                                   GalacticfyPermissionService perms) {
        this.punishmentService = punishmentService;
        this.logger = logger;
        this.proxy = proxy;
        this.perms = perms;
    }

    private Component prefix() {
        return Component.text("§8[§bGalacticfy§8] §r");
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        // Player-Daten
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getUsername();

// IP korrekt aus Velocity ziehen
        InetSocketAddress addr = player.getRemoteAddress();
        String ip = addr.getAddress().getHostAddress();


        // ================================
        // 1) Aktiver Ban -> Join blocken
        // ================================
        Punishment ban = punishmentService.getActiveBan(uuid, ip);
        if (ban != null) {
            String remaining = punishmentService.formatRemaining(ban);
            Component kick = Component.text(
                    "§cDu bist vom Netzwerk gebannt.\n" +
                            "§7Grund: §e" + ban.reason + "\n" +
                            "§7Dauer: §e" + remaining + "\n" +
                            "§7Von: §b" + ban.staff
            );
            event.setResult(ResultedEvent.ComponentResult.denied(kick));
            logger.info("Blockiere Login von {} ({}) wegen aktivem Ban.", name, uuid);
            return;
        }

        // ===========================================
        // 2) Kein aktiver Ban -> Alt-Check per IP
        // ===========================================
        if (ip != null && !ip.isBlank()) {
            // aktive Bans auf derselben IP, aber NICHT dieser Spieler
            List<Punishment> altBans = punishmentService.findActiveBansByIp(ip, uuid, 5);

            if (!altBans.isEmpty()) {
                String altNames = altBans.stream()
                        .map(p -> p.name)
                        .filter(n -> n != null && !n.isBlank())
                        .map(n -> n) // optional: .map(n -> "§c" + n + "§7")
                        .distinct()
                        .collect(Collectors.joining("§7, §c"));

                Component alert = prefix().append(Component.text(
                        "§eAlt-Check: §f" + name + " §7teilt die IP mit gebannten Accounts: §c" + altNames
                ));

                // an alle mit galacticfy.punish.alerts schicken
                proxy.getAllPlayers().forEach(p -> {
                    boolean canSee;
                    if (perms != null) {
                        canSee = perms.hasPluginPermission(p, PERM_PUNISH_ALERTS);
                    } else {
                        canSee = p.hasPermission(PERM_PUNISH_ALERTS);
                    }
                    if (canSee) {
                        p.sendMessage(alert);
                    }
                });

                proxy.getConsoleCommandSource().sendMessage(alert);

                logger.info("[Alt-Check] {} ({}) teilt IP {} mit gebannten Accounts: {}",
                        name, uuid, ip, altNames.replace("§", ""));
            }
        }
    }
}
