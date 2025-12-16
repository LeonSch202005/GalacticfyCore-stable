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

public class WarningsCommand implements SimpleCommand {

    private static final String PERM_WARNINGS = "galacticfy.punish.warnings";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final ProxyServer proxy;
    private final PunishmentService punishmentService;
    private final GalacticfyPermissionService perms;
    private final PlayerIdentityCacheService identityCache;

    public WarningsCommand(ProxyServer proxy,
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

    private boolean hasWarningsPermission(CommandSource src) {
        if (src instanceof Player p) {
            if (perms != null) return perms.hasPluginPermission(p, PERM_WARNINGS);
            return p.hasPermission(PERM_WARNINGS);
        }
        return true;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!hasWarningsPermission(src)) {
            src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
            return;
        }

        if (args.length < 1) {
            src.sendMessage(prefix().append(Component.text("§eBenutzung: §b/warnings <spieler> [Seite]")));
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
            if (identityCache != null) {
                uuid = identityCache.findUuidByName(targetName).orElse(null);
                if (uuid != null) storedName = identityCache.findNameByUuid(uuid).orElse(storedName);
            }
        }

        // Wir holen History und filtern WARN
        List<Punishment> history = (List<Punishment>) punishmentService.getHistory(uuid, storedName, 500);
        List<Punishment> warns = history.stream()
                .filter(p -> p.type == PunishmentType.WARN)
                .sorted(Comparator.comparing((Punishment p) -> p.createdAt).reversed())
                .collect(Collectors.toList());

        int perPage = 8;
        int totalPages = Math.max(1, (int) Math.ceil(warns.size() / (double) perPage));
        if (page > totalPages) page = totalPages;

        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, warns.size());

        List<Punishment> pageList = warns.subList(start, end);

        src.sendMessage(Component.text(" "));
        src.sendMessage(prefix().append(Component.text(
                "§eWarnings für §f" + storedName + " §7(Seite " + page + "§7/§3" + totalPages + "§7)"
        )));
        src.sendMessage(Component.text("§8§m────────────────────────────────"));

        if (pageList.isEmpty()) {
            src.sendMessage(Component.text("§7Keine Verwarnungen gefunden."));
        } else {
            for (Punishment p : pageList) {
                String date = "-";
                if (p.createdAt != null) {
                    LocalDateTime ldt = LocalDateTime.ofInstant(p.createdAt, ZoneId.systemDefault());
                    date = DATE_FORMAT.format(ldt);
                }

                String idText = (p.id > 0 ? String.valueOf(p.id) : "-");


                src.sendMessage(Component.text(
                        "§e⚠ §7ID: §f" + idText +
                                " §8| §7am §f" + date +
                                " §8| §7von §f" + p.staff + "\n" +
                                " §7Grund: §f" + p.reason
                ));
            }
        }

        src.sendMessage(Component.text("§8§m────────────────────────────────"));
        src.sendMessage(Component.text("§7Seiten: §f/warnings " + storedName + " <1-" + totalPages + ">"));
        src.sendMessage(Component.text(" "));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return hasWarningsPermission(invocation.source());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!hasWarningsPermission(invocation.source())) return List.of();

        String[] args = invocation.arguments();
        if (args.length == 0) return suggestNames("");
        if (args.length == 1) return suggestNames(args[0]);
        if (args.length == 2) return List.of("1", "2", "3", "4", "5");
        return List.of();
    }

    private List<String> suggestNames(String prefixRaw) {
        String prefix = (prefixRaw == null ? "" : prefixRaw).toLowerCase(Locale.ROOT);

        LinkedHashSet<String> out = new LinkedHashSet<>();
        proxy.getAllPlayers().forEach(p -> {
            String n = p.getUsername();
            if (n != null && n.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(n);
        });

        try {
            out.addAll(punishmentService.findKnownNames(prefix, 25));
        } catch (Throwable ignored) {}

        return out.stream().sorted(String.CASE_INSENSITIVE_ORDER).limit(40).collect(Collectors.toList());
    }
}
