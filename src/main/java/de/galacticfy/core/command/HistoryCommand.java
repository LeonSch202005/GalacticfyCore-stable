package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.service.PlayerIdentityCacheService;
import de.galacticfy.core.service.PunishmentService;
import de.galacticfy.core.service.PunishmentService.Punishment;
import de.galacticfy.core.service.PunishmentService.PunishmentType;
import net.kyori.adventure.text.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class HistoryCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final PunishmentService punishmentService;
    private final GalacticfyPermissionService perms;
    private final PlayerIdentityCacheService identityCache;

    private static final String PERM_HISTORY = "galacticfy.punish.history";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public HistoryCommand(ProxyServer proxy,
                          PunishmentService punishmentService,
                          GalacticfyPermissionService perms,
                          PlayerIdentityCacheService identityCache) {
        this.proxy = proxy;
        this.punishmentService = punishmentService;
        this.perms = perms;
        this.identityCache = identityCache;
    }

    private Component prefix() {
        return Component.text("§8[§bGalacticfy§8] §r");
    }

    private boolean hasHistoryPermission(CommandSource src) {
        if (src instanceof Player player) {
            if (perms != null) {
                return perms.hasPluginPermission(player, PERM_HISTORY);
            }
            return player.hasPermission(PERM_HISTORY);
        }
        return true;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!hasHistoryPermission(src)) {
            src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
            return;
        }

        if (args.length < 1) {
            src.sendMessage(prefix().append(Component.text("§eBenutzung: §b/history <spieler> [Seite]")));
            return;
        }

        String targetName = args[0];

        int page = 1;
        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
                if (page <= 0) page = 1;
            } catch (NumberFormatException ignored) {
                page = 1;
            }
        }

        Player online = proxy.getPlayer(targetName).orElse(null);

        UUID uuid = null;
        String storedName = targetName;

        if (online != null) {
            uuid = online.getUniqueId();
            storedName = online.getUsername();
        } else {
            // NEU: Offline -> UUID via Cache/DB
            if (identityCache != null) {
                uuid = identityCache.findUuidByName(targetName).orElse(null);
                if (uuid != null) {
                    storedName = identityCache.findNameByUuid(uuid).orElse(storedName);
                }
            }
        }

        List list = punishmentService.getHistory(uuid, storedName, 200);
        list = (List<Punishment>) list.stream()
                .sorted(Comparator.comparing((Punishment p) -> p.createdAt).reversed())
                .collect(Collectors.toList());

        int perPage = 8;
        int totalPages = Math.max(1, (int) Math.ceil(list.size() / (double) perPage));
        if (page > totalPages) page = totalPages;

        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, list.size());

        List pageList = list.subList(start, end);

        src.sendMessage(Component.text(" "));
        src.sendMessage(prefix().append(Component.text(
                "§bHistory für §f" + storedName + " §7(Seite " + page + "§7/§3" + totalPages + "§7)"
        )));
        src.sendMessage(Component.text("§8§m────────────────────────────────"));

        if (pageList.isEmpty()) {
            src.sendMessage(Component.text("§7Keine Einträge gefunden."));
        } else {
            for (Punishment p : (List<Punishment>) pageList) {
                String icon;
                String color;

                if (p.type == PunishmentType.BAN || p.type == PunishmentType.IP_BAN) {
                    icon = "⛔";
                    color = (p.type == PunishmentType.IP_BAN ? "§4" : "§c");
                } else if (p.type == PunishmentType.MUTE) {
                    icon = "🔇";
                    color = "§6";
                } else if (p.type == PunishmentType.KICK) {
                    icon = "👢";
                    color = "§e";
                } else if (p.type == PunishmentType.WARN) {
                    icon = "⚠";
                    color = "§e";
                } else {
                    icon = "❔";
                    color = "§7";
                }

                String date = "-";
                if (p.createdAt != null) {
                    LocalDateTime ldt = LocalDateTime.ofInstant(p.createdAt, ZoneId.systemDefault());
                    date = DATE_FORMAT.format(ldt);
                }

                String duration = punishmentService.formatRemaining(p);
                String active = p.active ? "§aAKTIV" : "§7inaktiv";

                src.sendMessage(Component.text(
                        color + icon + " §7" + p.type.name() +
                                " §8| §7von §f" + p.staff +
                                " §8| §7am §f" + date +
                                " §8| §7Dauer: §f" + duration +
                                " §8| " + active + "\n" +
                                " §7Grund: §f" + p.reason
                ));
            }
        }

        src.sendMessage(Component.text("§8§m────────────────────────────────"));
        src.sendMessage(Component.text("§7Seiten: §f/history " + storedName + " <1-" + totalPages + ">"));
        src.sendMessage(Component.text(" "));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return hasHistoryPermission(invocation.source());
    }

    @Override
    public List suggest(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!hasHistoryPermission(src)) return List.of();

        if (args.length == 0) {
            return punishmentService.getAllPunishedNames().stream()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return punishmentService.getAllPunishedNames().stream()
                    .filter(n -> prefix.isEmpty() || n.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            return List.of("1", "2", "3", "4", "5");
        }

        return List.of();
    }
}
