package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.service.PlayerIdentityCacheService;
import de.galacticfy.core.service.PunishmentService;
import de.galacticfy.core.service.PunishmentService.Punishment;
import net.kyori.adventure.text.Component;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class CheckCommand implements SimpleCommand {

    private static final String PERM_CHECK = "galacticfy.punish.check";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final ProxyServer proxy;
    private final GalacticfyPermissionService perms;
    private final PunishmentService punishmentService;
    private final PlayerIdentityCacheService identityCache;

    public CheckCommand(ProxyServer proxy,
                        GalacticfyPermissionService perms,
                        PunishmentService punishmentService,
                        PlayerIdentityCacheService identityCache) {
        this.proxy = proxy;
        this.perms = perms;
        this.punishmentService = punishmentService;
        this.identityCache = identityCache;
    }

    private Component prefix() {
        return Component.text("§8[§bGalacticfy§8] §r");
    }

    private boolean hasCheckPermission(CommandSource src) {
        if (src instanceof Player p) {
            if (perms != null) {
                return perms.hasPluginPermission(p, PERM_CHECK);
            }
            return p.hasPermission(PERM_CHECK);
        }
        return true;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!hasCheckPermission(src)) {
            src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
            return;
        }

        if (args.length < 1) {
            src.sendMessage(prefix().append(Component.text("§eBenutzung: §b/check <spieler>")));
            return;
        }

        String targetName = args[0];

        Player online = proxy.getPlayer(targetName).orElse(null);

        UUID uuid = null;
        String storedName = targetName;
        String serverName = "Offline";

        if (online != null) {
            uuid = online.getUniqueId();
            storedName = online.getUsername();
            serverName = online.getCurrentServer()
                    .map(conn -> conn.getServerInfo().getName())
                    .orElse("Unbekannt");
        } else {
            // Offline -> UUID via Cache/DB (wenn vorhanden)
            if (identityCache != null) {
                uuid = identityCache.findUuidByName(targetName).orElse(null);
                if (uuid != null) {
                    storedName = identityCache.findNameByUuid(uuid).orElse(storedName);
                }
            }
        }

        String ip = null;
        if (online != null) {
            Object remote = online.getRemoteAddress();
            if (remote instanceof InetSocketAddress isa && isa.getAddress() != null) {
                ip = isa.getAddress().getHostAddress();
            }
        } else {
            // offline: wenn uuid da ist -> genauer
            ip = punishmentService.getLastKnownIp(uuid, storedName);
        }

        String ipText = (ip != null) ? ip : "Unbekannt";

        Punishment activeBan = punishmentService.getActiveBanForCheck(uuid, storedName, ip);
        Punishment activeMute = punishmentService.getActiveMuteForCheck(uuid, storedName, ip);
        int warnCount = punishmentService.countWarns(uuid, storedName);
        List history = punishmentService.getHistory(uuid, storedName, 3);

        src.sendMessage(Component.text(" "));
        src.sendMessage(prefix().append(Component.text("§bCheck für §f" + storedName)));
        src.sendMessage(Component.text("§8§m────────────────────────────────"));
        src.sendMessage(Component.text(
                "§7Status: " + (online != null ? "§aOnline" : "§cOffline") +
                        " §8| §7Server: §f" + serverName
        ));
        src.sendMessage(Component.text("§7Letzte IP: §f" + ipText));
        src.sendMessage(Component.text("§7Aktive Warns: §e" + warnCount));

        // ===== FIX: Variablen für Lambdas final machen =====
        final UUID finalUuid = uuid;

        if (ip != null) {
            final String finalIp = ip;

            List<String> altsOnline = proxy.getAllPlayers().stream()
                    // FIX: alle außer "target" (wenn uuid bekannt)
                    .filter(p -> finalUuid == null || !p.getUniqueId().equals(finalUuid))
                    .filter(p -> {
                        Object r = p.getRemoteAddress();
                        if (r instanceof InetSocketAddress other && other.getAddress() != null) {
                            return finalIp.equals(other.getAddress().getHostAddress());
                        }
                        return false;
                    })
                    .map(Player::getUsername)
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());

            if (altsOnline.isEmpty()) {
                src.sendMessage(Component.text("§7Online-ALTs: §aKeine gefunden"));
            } else {
                String joined = String.join("§7, §c", altsOnline);
                src.sendMessage(Component.text("§7Online-ALTs: §c" + joined));
            }
        } else {
            src.sendMessage(Component.text("§7Online-ALTs: §8Keine IP bekannt"));
        }

        if (ip != null) {
            List<String> altsHistory = punishmentService.findAltsByIp(ip, uuid, 25);
            if (altsHistory.isEmpty()) {
                src.sendMessage(Component.text("§7IP-ALTs (History): §aKeine gefunden"));
            } else {
                String joined = String.join("§7, §c", altsHistory);
                src.sendMessage(Component.text("§7IP-ALTs (History): §c" + joined));
            }
        } else {
            src.sendMessage(Component.text("§7IP-ALTs (History): §8Keine IP bekannt"));
        }

        if (activeBan != null) {
            String date = activeBan.createdAt != null
                    ? DATE_FORMAT.format(LocalDateTime.ofInstant(activeBan.createdAt, ZoneId.systemDefault()))
                    : "-";
            src.sendMessage(Component.text(
                    "§c⛔ Aktiver Ban: §7" + activeBan.reason +
                            " §8| §7seit §f" + date +
                            " §8| §7Dauer: §f" + punishmentService.formatRemaining(activeBan) +
                            " §8| §7Von: §f" + activeBan.staff
            ));
        } else {
            src.sendMessage(Component.text("§7Aktiver Ban: §aKeiner"));
        }

        if (activeMute != null) {
            String date = activeMute.createdAt != null
                    ? DATE_FORMAT.format(LocalDateTime.ofInstant(activeMute.createdAt, ZoneId.systemDefault()))
                    : "-";
            src.sendMessage(Component.text(
                    "§6🔇 Aktiver Mute: §7" + activeMute.reason +
                            " §8| §7seit §f" + date +
                            " §8| §7Dauer: §f" + punishmentService.formatRemaining(activeMute) +
                            " §8| §7Von: §f" + activeMute.staff
            ));
        } else {
            src.sendMessage(Component.text("§7Aktiver Mute: §aKeiner"));
        }

        src.sendMessage(Component.text("§8§m────────────────────────────────"));
        src.sendMessage(Component.text("§7Letzte Einträge:"));

        if (history.isEmpty()) {
            src.sendMessage(Component.text("§8• §7Keine History."));
        } else {
            for (Punishment p : (List<Punishment>) history) {
                String icon;
                String color;

                switch (p.type) {
                    case BAN -> { icon = "⛔"; color = "§c"; }
                    case IP_BAN -> { icon = "⛔"; color = "§4"; }
                    case MUTE -> { icon = "🔇"; color = "§6"; }
                    case KICK -> { icon = "👢"; color = "§e"; }
                    case WARN -> { icon = "⚠"; color = "§e"; }
                    default -> { icon = "❔"; color = "§7"; }
                }

                String date = p.createdAt != null
                        ? DATE_FORMAT.format(LocalDateTime.ofInstant(p.createdAt, ZoneId.systemDefault()))
                        : "-";

                String duration = punishmentService.formatRemaining(p);

                src.sendMessage(Component.text(
                        "§8• " + color + icon + " §7" + p.type.name() +
                                " §8| §7am §f" + date +
                                " §8| §7Dauer: §f" + duration +
                                " §8| §7Von: §f" + p.staff +
                                " §8| §7Grund: §f" + p.reason
                ));
            }
        }

        src.sendMessage(Component.text("§8§m────────────────────────────────"));
        src.sendMessage(Component.text(" "));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return hasCheckPermission(invocation.source());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!hasCheckPermission(invocation.source())) return List.of();

        String[] args = invocation.arguments();
        if (args.length == 0 || args.length == 1) {
            String prefix = (args.length == 0 ? "" : args[0]).toLowerCase(Locale.ROOT);

            Set<String> result = new LinkedHashSet<>();
            result.addAll(punishmentService.findKnownNames(prefix, 30));

            proxy.getAllPlayers().forEach(p -> {
                String n = p.getUsername();
                if (n.toLowerCase(Locale.ROOT).startsWith(prefix)) result.add(n);
            });

            return new ArrayList<>(result);
        }
        return List.of();
    }
}
