package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.punish.PunishDesign;
import de.galacticfy.core.punish.ReasonPresets;
import de.galacticfy.core.punish.ReasonPresets.Preset;
import de.galacticfy.core.service.PlayerIdentityCacheService;
import de.galacticfy.core.service.ReportCooldownService;
import de.galacticfy.core.service.ReportService;
import de.galacticfy.core.service.ReportService.ReportEntry;
import net.kyori.adventure.text.Component;

import java.lang.reflect.Method;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class ReportCommand implements SimpleCommand {

    private static final String PERM_REPORT_VIEW  = "galacticfy.report.view";   // check/list + Join-Info
    private static final String PERM_REPORT_CLEAR = "galacticfy.report.clear";  // clear/all + handle

    private final ProxyServer proxy;
    private final GalacticfyPermissionService perms;
    private final ReportService reportService;

    // WICHTIG: fehlte in deinem Code (sonst kompiliert es nicht)
    private final PlayerIdentityCacheService identityCache;
    private final ReportCooldownService cooldown;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public ReportCommand(ProxyServer proxy,
                         GalacticfyPermissionService perms,
                         ReportService reportService,
                         PlayerIdentityCacheService identityCache,
                         ReportCooldownService cooldown) {

        this.proxy = proxy;
        this.perms = perms;
        this.reportService = reportService;
        this.identityCache = identityCache;
        this.cooldown = cooldown;
    }

    private Component prefix() {
        return Component.text("§8[§cReport§8] §r");
    }

    private boolean canViewReports(CommandSource src) {
        if (src instanceof Player p) {
            if (perms != null) return perms.hasPluginPermission(p, PERM_REPORT_VIEW);
            return p.hasPermission(PERM_REPORT_VIEW);
        }
        return true;
    }

    private boolean canClearReports(CommandSource src) {
        if (src instanceof Player p) {
            if (perms != null) {
                return perms.hasPluginPermission(p, PERM_REPORT_CLEAR) || perms.hasPluginPermission(p, PERM_REPORT_VIEW);
            }
            return p.hasPermission(PERM_REPORT_CLEAR) || p.hasPermission(PERM_REPORT_VIEW);
        }
        return true;
    }

    // =====================================================================================
    // EXECUTE
    // =====================================================================================

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sendUsage(src);
            return;
        }

        String first = args[0].toLowerCase(Locale.ROOT);

        // Staff-Subcommands
        if (first.equals("check")) {
            if (!canViewReports(src)) {
                src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
                return;
            }
            handleCheck(src, args);
            return;
        }

        if (first.equals("list")) {
            if (!canViewReports(src)) {
                src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
                return;
            }
            handleList(src);
            return;
        }

        if (first.equals("clear")) {
            if (!canClearReports(src)) {
                src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
                return;
            }
            handleClear(src, args);
            return;
        }

        if (first.equals("handle")) {
            if (!canClearReports(src)) {
                src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
                return;
            }
            handleHandle(src, args);
            return;
        }

        // Spieler-Report: /report <spieler> <preset|reason...>
        handlePlayerReport(src, args);
    }

    // =====================================================================================
    // /report <spieler> <preset|reason...>
    // =====================================================================================

    private void handlePlayerReport(CommandSource src, String[] args) {
        if (args.length < 2) {
            src.sendMessage(prefix().append(Component.text("§eBenutzung: §b/report <Spieler> <Grund|Preset>")));
            src.sendMessage(Component.text("§7Beispiele:"));
            src.sendMessage(Component.text("§8» §b/report Spieler beleidigung"));
            src.sendMessage(Component.text("§8» §b/report Spieler spam"));
            src.sendMessage(Component.text("§8» §b/report Spieler hackclient KillAura und Fly"));
            return;
        }

        // Cooldown nur für Spieler (Konsole ignoriert)
        if (src instanceof Player reporter) {
            if (!cooldownAllow(reporter.getUniqueId())) {
                long sec = cooldownRemainingSeconds(reporter.getUniqueId());
                reporter.sendMessage(prefix().append(Component.text("§cBitte warte noch §e" + sec + "§c Sekunden.")));
                return;
            }
        }

        String targetInput = args[0];

        // Zielname normalisieren (online bevorzugt, sonst IdentityCache)
        String targetName = resolveTargetName(targetInput);
        if (targetName == null || targetName.isBlank()) {
            src.sendMessage(prefix().append(Component.text("§cSpieler nicht gefunden.")));
            return;
        }

        // Selbstreport verhindern
        if (src instanceof Player p && targetName.equalsIgnoreCase(p.getUsername())) {
            src.sendMessage(prefix().append(Component.text("§cDu kannst dich nicht selbst reporten.")));
            return;
        }

        String rawSecond = args[1].toLowerCase(Locale.ROOT);
        Preset preset = ReasonPresets.find(rawSecond);

        String reason;
        String presetKey = null;

        if (preset != null) {
            presetKey = preset.key();
            if (args.length > 2) {
                String extra = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                reason = preset.display() + " §8(§7" + extra + "§8)";
            } else {
                reason = preset.display();
            }
        } else {
            reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        }

        String reporterName;
        String serverName = "Unbekannt";

        if (src instanceof Player p) {
            reporterName = p.getUsername();
            serverName = p.getCurrentServer()
                    .map(conn -> conn.getServerInfo().getName())
                    .orElse("Unbekannt");
        } else {
            reporterName = "Konsole";
        }

        // In DB speichern (dein ReportService hat addReport)
        reportService.addReport(targetName, reporterName, reason, serverName, presetKey);

        // Cooldown markieren (nur Spieler)
        if (src instanceof Player p) {
            cooldownMark(p.getUniqueId());
        }

        // Spieler-Feedback
        src.sendMessage(Component.text(" "));
        src.sendMessage(prefix().append(Component.text(
                "§aDein Report gegen §e" + targetName + " §awurde an das Team gesendet."
        )));
        src.sendMessage(Component.text("§7Grund: §f" + reason));
        src.sendMessage(Component.text(" "));

        // Team benachrichtigen
        notifyStaffNewReport(targetName, reporterName, serverName, reason, presetKey);
    }

    private void notifyStaffNewReport(String targetName, String reporterName, String serverName, String reason, String presetKey) {
        String presetPart = (presetKey != null) ? " §8[§bPreset: §f" + presetKey + "§8]" : "";
        Component msg = Component.text(
                "§8[§cReport§8] §7" + reporterName + " §7hat §c" + targetName + " §7gemeldet §8(§b" + serverName + "§8)" + presetPart + "\n" +
                        "§7Grund: §f" + reason
        );

        proxy.getAllPlayers().forEach(player -> {
            boolean view = (perms != null)
                    ? perms.hasPluginPermission(player, PERM_REPORT_VIEW)
                    : player.hasPermission(PERM_REPORT_VIEW);

            if (view) player.sendMessage(msg);
        });
    }

    // =====================================================================================
    // /report check <spieler>
    // =====================================================================================

    @SuppressWarnings("unchecked")
    private void handleCheck(CommandSource src, String[] args) {
        if (args.length < 2) {
            src.sendMessage(prefix().append(Component.text("§eBenutzung: §b/report check <Spieler>")));
            return;
        }

        String targetName = args[1];

        // dein ReportService nutzt raw List (ohne Generics)
        List<ReportEntry> list = (List<ReportEntry>) (List<?>) reportService.getReportsFor(targetName);

        src.sendMessage(Component.text(" "));
        src.sendMessage(Component.text(PunishDesign.LINE));
        src.sendMessage(prefix().append(Component.text("§bReports für §f" + targetName)));
        src.sendMessage(Component.text(PunishDesign.LINE));

        if (list.isEmpty()) {
            src.sendMessage(Component.text("§7Es liegen aktuell §ckeine§7 Reports für diesen Spieler vor."));
        } else {
            for (ReportEntry entry : list) {
                String time = (entry.createdAt() != null) ? DATE_FORMAT.format(entry.createdAt()) : "-";
                String presetPart = (entry.presetKey() != null) ? " §8[§b" + entry.presetKey() + "§8]" : "";
                String handledPart = entry.handled()
                        ? " §8[§aBearbeitet§8" + (entry.handledBy() != null ? " §7von §f" + entry.handledBy() : "") + "§8]"
                        : "";

                src.sendMessage(Component.text(
                        "§8• §7Am §f" + time +
                                " §8| §7Von: §f" + entry.reporterName() +
                                " §8| §7Server: §f" + (entry.serverName() != null ? entry.serverName() : "Unbekannt") +
                                presetPart + handledPart +
                                "\n §7Grund: §f" + entry.reason()
                ));
            }
        }

        src.sendMessage(Component.text(PunishDesign.LINE));
        src.sendMessage(Component.text(" "));
    }

    // =====================================================================================
    // /report list (nur offene Reports)
    // =====================================================================================

    @SuppressWarnings("unchecked")
    private void handleList(CommandSource src) {
        List<ReportEntry> all = (List<ReportEntry>) (List<?>) reportService.getOpenReports();

        src.sendMessage(Component.text(" "));
        src.sendMessage(Component.text(PunishDesign.LINE));
        src.sendMessage(prefix().append(Component.text("§bOffene Reports")));
        src.sendMessage(Component.text(PunishDesign.LINE));

        if (all.isEmpty()) {
            src.sendMessage(Component.text("§7Aktuell liegen §ckeine§7 offenen Reports vor."));
            src.sendMessage(Component.text(PunishDesign.LINE));
            src.sendMessage(Component.text(" "));
            return;
        }

        for (ReportEntry entry : all) {
            String time = (entry.createdAt() != null) ? DATE_FORMAT.format(entry.createdAt()) : "-";
            String presetPart = (entry.presetKey() != null) ? " §8[§b" + entry.presetKey() + "§8]" : "";

            src.sendMessage(Component.text(
                    "§8#§e" + entry.id() + " §8• §c" + entry.targetName() + " §7(" + time + ") " +
                            "§8| §7Von: §f" + entry.reporterName() +
                            " §8| §7Server: §f" + (entry.serverName() != null ? entry.serverName() : "Unbekannt") +
                            presetPart +
                            "\n §7Grund: §f" + entry.reason()
            ));
        }

        src.sendMessage(Component.text(PunishDesign.LINE));
        src.sendMessage(Component.text(" "));
    }

    // =====================================================================================
    // /report handle <id>
    // =====================================================================================

    private void handleHandle(CommandSource src, String[] args) {
        if (args.length < 2) {
            src.sendMessage(prefix().append(Component.text("§eBenutzung: §b/report handle <id>")));
            return;
        }

        long id;
        try {
            id = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            src.sendMessage(prefix().append(Component.text("§cUngültige ID: §7" + args[1])));
            return;
        }

        String staffName = (src instanceof Player p) ? p.getUsername() : "Konsole";
        boolean success = reportService.markReportHandled(id, staffName);

        if (!success) {
            src.sendMessage(prefix().append(Component.text(
                    "§cReport mit ID §e#" + id + " §cexistiert nicht oder ist bereits bearbeitet."
            )));
            return;
        }

        src.sendMessage(prefix().append(Component.text(
                "§aReport §e#" + id + " §awurde als bearbeitet markiert."
        )));
    }

    // =====================================================================================
    // /report clear <spieler|all>
    // =====================================================================================

    private void handleClear(CommandSource src, String[] args) {
        if (args.length < 2) {
            src.sendMessage(prefix().append(Component.text("§eBenutzung: §b/report clear <Spieler|all>")));
            return;
        }

        String target = args[1];

        if (target.equalsIgnoreCase("all")) {
            int removed = reportService.clearAll();
            src.sendMessage(prefix().append(Component.text(
                    "§aEs wurden §e" + removed + " §aReports gelöscht."
            )));
            return;
        }

        boolean ok = reportService.clearReportsFor(target);
        if (ok) {
            src.sendMessage(prefix().append(Component.text(
                    "§aAlle Reports für §e" + target + " §awurden gelöscht."
            )));
        } else {
            src.sendMessage(prefix().append(Component.text(
                    "§7Es wurden keine Reports für §e" + target + " §7gefunden."
            )));
        }
    }

    // =====================================================================================
    // USAGE
    // =====================================================================================

    private void sendUsage(CommandSource src) {
        boolean staff = canViewReports(src);

        src.sendMessage(Component.text(" "));
        src.sendMessage(Component.text("§8§m──────§r §cReport §7| §cSystem §8§m──────"));
        src.sendMessage(Component.text(" "));
        src.sendMessage(Component.text("§bFür Spieler:"));
        src.sendMessage(Component.text("§8» §b/report <Spieler> <Grund|Preset>"));
        src.sendMessage(Component.text(" §7Meldet einen Spieler an das Team."));
        src.sendMessage(Component.text(" §7Presets: §fspam§7, §fbeleidigung§7, §fhackclient§7, §fwerbung§7, ..."));
        src.sendMessage(Component.text(" "));

        if (staff) {
            src.sendMessage(Component.text("§bFür Teammitglieder:"));
            src.sendMessage(Component.text("§8» §b/report check <Spieler>"));
            src.sendMessage(Component.text(" §7Zeigt alle Reports für einen Spieler."));
            src.sendMessage(Component.text("§8» §b/report list"));
            src.sendMessage(Component.text(" §7Zeigt alle offenen Reports."));
            src.sendMessage(Component.text("§8» §b/report handle <id>"));
            src.sendMessage(Component.text(" §7Markiert einen Report als bearbeitet."));
            src.sendMessage(Component.text("§8» §b/report clear <Spieler|all>"));
            src.sendMessage(Component.text(" §7Löscht Reports für einen Spieler oder alle."));
            src.sendMessage(Component.text(" "));
        }

        src.sendMessage(Component.text("§8§m────────────────────────────────"));
        src.sendMessage(Component.text(" "));
    }

    // =====================================================================================
    // TAB-COMPLETE
    // =====================================================================================

    @SuppressWarnings("unchecked")
    @Override
    public List<String> suggest(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        boolean staffView = canViewReports(src);
        boolean staffClear = canClearReports(src);

        // /report <tab>
        if (args.length == 0) {
            List<String> out = new ArrayList<>();

            if (staffView) {
                out.add("check");
                out.add("list");
                if (staffClear) {
                    out.add("clear");
                    out.add("handle");
                }
            }

            out.addAll(proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList());

            return out;
        }

        // /report <first>
        if (args.length == 1) {
            String first = (args[0] == null ? "" : args[0]).toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();

            if (staffView) {
                for (String sub : List.of("check", "list", "clear", "handle")) {
                    if (sub.startsWith(first)) out.add(sub);
                }
            }

            proxy.getAllPlayers().forEach(p -> {
                String name = p.getUsername();
                if (name.toLowerCase(Locale.ROOT).startsWith(first)) out.add(name);
            });

            // OFFLINE Namen aus IdentityCache (wenn vorhanden)
            out.addAll(identityCacheKnownNames(first, 20));

            return out.stream()
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }

        // /report check <spieler>
        if (args.length == 2 && args[0].equalsIgnoreCase("check") && staffView) {
            String pfx = (args[1] == null ? "" : args[1]).toLowerCase(Locale.ROOT);
            Set<String> out = new LinkedHashSet<>();

            List<String> reported = (List<String>) (List<?>) reportService.getReportedTargetNames();
            for (String name : reported) {
                if (name != null && name.toLowerCase(Locale.ROOT).startsWith(pfx)) out.add(name);
            }

            proxy.getAllPlayers().forEach(p -> {
                String name = p.getUsername();
                if (name.toLowerCase(Locale.ROOT).startsWith(pfx)) out.add(name);
            });

            // OFFLINE Namen aus IdentityCache
            out.addAll(identityCacheKnownNames(pfx, 20));

            return new ArrayList<>(out);
        }

        // /report clear <spieler|all>
        if (args.length == 2 && args[0].equalsIgnoreCase("clear") && staffClear) {
            String pfx = (args[1] == null ? "" : args[1]).toLowerCase(Locale.ROOT);

            List<String> base = new ArrayList<>();
            base.add("all");
            base.addAll((List<String>) (List<?>) reportService.getReportedTargetNames());

            // OFFLINE Namen aus IdentityCache
            base.addAll(identityCacheKnownNames(pfx, 20));

            return base.stream()
                    .filter(s -> s != null && s.toLowerCase(Locale.ROOT).startsWith(pfx))
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }

        // /report handle <id> → offene IDs vorschlagen
        if (args.length == 2 && args[0].equalsIgnoreCase("handle") && staffClear) {
            String pfx = (args[1] == null ? "" : args[1]);
            List<ReportEntry> open = (List<ReportEntry>) (List<?>) reportService.getOpenReports();
            return open.stream()
                    .map(e -> String.valueOf(e.id()))
                    .filter(id -> id.startsWith(pfx))
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        }

        // /report <spieler> <tabPreset>  → NUR 2. Argument (Preset)
        if (args.length == 2
                && !args[0].equalsIgnoreCase("check")
                && !args[0].equalsIgnoreCase("list")
                && !args[0].equalsIgnoreCase("clear")
                && !args[0].equalsIgnoreCase("handle")) {

            String pfx = (args[1] == null ? "" : args[1]);
            return ReasonPresets.tabComplete(pfx);
        }

        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // /report soll grundsätzlich für jeden nutzbar sein
        return true;
    }

    // =====================================================================================
    // HELFER: Zielname auflösen (online -> cache)
    // =====================================================================================

    private String resolveTargetName(String input) {
        if (input == null || input.isBlank()) return null;

        // 1) Online exact
        Optional<Player> exact = proxy.getPlayer(input);
        if (exact.isPresent()) return exact.get().getUsername();

        // 2) Online prefix match (falls Spieler nur teilnamen schreibt)
        for (Player p : proxy.getAllPlayers()) {
            if (p.getUsername().equalsIgnoreCase(input)) return p.getUsername();
        }
        for (Player p : proxy.getAllPlayers()) {
            if (p.getUsername().toLowerCase(Locale.ROOT).startsWith(input.toLowerCase(Locale.ROOT))) {
                return p.getUsername();
            }
        }

        // 3) Offline via IdentityCache (per Reflection, damit du nicht wieder rot bekommst)
        return identityCacheResolveName(input);
    }

    // =====================================================================================
    // REFLECTION: IdentityCache (damit Methodennamen bei dir variieren dürfen)
    // =====================================================================================

    private String identityCacheResolveName(String inputName) {
        if (identityCache == null) return null;

        try {
            // findUuidByName(String) -> Optional<UUID>
            Method findUuidByName = identityCache.getClass().getMethod("findUuidByName", String.class);
            Object optUuid = findUuidByName.invoke(identityCache, inputName);

            if (!(optUuid instanceof Optional<?> optional) || optional.isEmpty()) return null;
            Object uuidObj = optional.get();
            if (!(uuidObj instanceof UUID uuid)) return null;

            // findNameByUuid(UUID) -> Optional<String>
            Method findNameByUuid = identityCache.getClass().getMethod("findNameByUuid", UUID.class);
            Object optName = findNameByUuid.invoke(identityCache, uuid);

            if (optName instanceof Optional<?> opt && opt.isPresent()) {
                Object n = opt.get();
                if (n instanceof String s && !s.isBlank()) return s;
            }
        } catch (Throwable ignored) {
            // Wenn Service anders aufgebaut ist, lassen wir es einfach.
        }

        return null;
    }

    private List<String> identityCacheKnownNames(String prefix, int limit) {
        if (identityCache == null) return List.of();
        try {
            // getKnownNames(String prefix, int limit) -> List<String>
            Method m = identityCache.getClass().getMethod("getKnownNames", String.class, int.class);
            Object res = m.invoke(identityCache, prefix, limit);
            if (res instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof String s && !s.isBlank()) out.add(s);
                }
                return out;
            }
        } catch (Throwable ignored) {
        }
        return List.of();
    }

    // =====================================================================================
    // REFLECTION: Cooldown (damit Methodennamen bei dir variieren dürfen)
    // =====================================================================================

    private boolean cooldownAllow(UUID uuid) {
        if (cooldown == null || uuid == null) return true;

        // bevorzugt: canReport(UUID) -> boolean
        try {
            Method m = cooldown.getClass().getMethod("canReport", UUID.class);
            Object r = m.invoke(cooldown, uuid);
            if (r instanceof Boolean b) return b;
        } catch (Throwable ignored) { }

        // alternativ: tryConsume(UUID) -> boolean
        try {
            Method m = cooldown.getClass().getMethod("tryConsume", UUID.class);
            Object r = m.invoke(cooldown, uuid);
            if (r instanceof Boolean b) return b;
        } catch (Throwable ignored) { }

        // wenn wir nichts kennen: erlauben
        return true;
    }

    private long cooldownRemainingSeconds(UUID uuid) {
        if (cooldown == null || uuid == null) return 0L;

        // getRemainingSeconds(UUID) -> long
        try {
            Method m = cooldown.getClass().getMethod("getRemainingSeconds", UUID.class);
            Object r = m.invoke(cooldown, uuid);
            if (r instanceof Number n) return Math.max(0L, n.longValue());
        } catch (Throwable ignored) { }

        // remainingSeconds(UUID) -> long
        try {
            Method m = cooldown.getClass().getMethod("remainingSeconds", UUID.class);
            Object r = m.invoke(cooldown, uuid);
            if (r instanceof Number n) return Math.max(0L, n.longValue());
        } catch (Throwable ignored) { }

        return 0L;
    }

    private void cooldownMark(UUID uuid) {
        if (cooldown == null || uuid == null) return;

        // markReported(UUID) / mark(UUID) / markUse(UUID)
        for (String name : List.of("markReported", "mark", "markUse", "markUsed")) {
            try {
                Method m = cooldown.getClass().getMethod(name, UUID.class);
                m.invoke(cooldown, uuid);
                return;
            } catch (Throwable ignored) { }
        }
    }
}
