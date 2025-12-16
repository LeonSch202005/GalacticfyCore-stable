package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.service.ReportService;
import de.galacticfy.core.service.ReportService.ReportEntry;
import de.galacticfy.core.service.ServerTeleportService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class ReportsCommand implements SimpleCommand {

    private static final String PERM_REPORTS = "galacticfy.report.staff";
    private static final String PERM_REPORTS_ADMIN = "galacticfy.report.admin";

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final ProxyServer proxy;
    private final GalacticfyPermissionService perms;
    private final ReportService reportService;
    private final ServerTeleportService teleportService;

    public ReportsCommand(ProxyServer proxy,
                          GalacticfyPermissionService perms,
                          ReportService reportService,
                          ServerTeleportService teleportService) {
        this.proxy = proxy;
        this.perms = perms;
        this.reportService = reportService;
        this.teleportService = teleportService;
    }

    private Component prefix() {
        return Component.text("§8[§dReports§8] §r");
    }

    private boolean hasPerm(CommandSource src) {
        if (src instanceof Player p) {
            if (perms != null) return perms.hasPluginPermission(p, PERM_REPORTS);
            return p.hasPermission(PERM_REPORTS);
        }
        return true;
    }

    private boolean isStaff(Player p) {
        if (perms != null) return perms.hasPluginPermission(p, PERM_REPORTS);
        return p.hasPermission(PERM_REPORTS);
    }

    private boolean isAdmin(CommandSource src) {
        if (!(src instanceof Player p)) return true;
        if (perms != null) return perms.hasPluginPermission(p, PERM_REPORTS_ADMIN);
        return p.hasPermission(PERM_REPORTS_ADMIN);
    }

    private String staffName(CommandSource src) {
        return (src instanceof Player p) ? p.getUsername() : "Konsole";
    }

    // ============================================================
    // EXECUTE
    // ============================================================

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!hasPerm(src)) {
            src.sendMessage(prefix().append(Component.text("§cKeine Berechtigung.")));
            return;
        }

        if (args.length == 0) {
            listOpenUnclaimed(src, 1);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "open", "list" -> listOpenUnclaimed(src, page(args, 1));
            case "openall" -> listOpenAll(src, page(args, 1));
            case "claimed" -> listOpenClaimed(src, page(args, 1));
            case "mine" -> listMine(src, page(args, 1));
            case "all" -> listAll(src, page(args, 1));
            case "info" -> info(src, arg(args, 1));
            case "claim" -> claim(src, arg(args, 1), false);
            case "claiminfo" -> claim(src, arg(args, 1), true);
            case "unclaim" -> unclaim(src, arg(args, 1));
            case "close" -> close(src, arg(args, 1));
            case "tp" -> tpToReportServer(src, arg(args, 1));
            case "take" -> take(src, arg(args, 1));
            default -> usage(src);
        }
    }

    private int page(String[] args, int index) {
        if (args.length <= index) return 1;
        try {
            return Math.max(1, Integer.parseInt(args[index]));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private String arg(String[] args, int index) {
        if (args.length <= index) return null;
        return args[index];
    }

    private void usage(CommandSource src) {
        src.sendMessage(prefix().append(Component.text(
                "§eBenutzung: §b/reports §7[open|openall|claimed|mine|all|info|claim|claiminfo|unclaim|close|tp|take]"
        )));
        src.sendMessage(prefix().append(Component.text(
                "§7Beispiel: §f/reports open §8| §f/reports info 12 §8| §f/reports take 12"
        )));
    }

    // ============================================================
    // INFO (AUTO-CLAIM wenn unclaimed)
    // ============================================================

    private void info(CommandSource src, String idArg) {
        long id = parseId(idArg);
        if (id <= 0) {
            src.sendMessage(prefix().append(Component.text("§cUngültige ID.")));
            return;
        }

        ReportEntry r = reportService.getReportById(id);
        if (r == null) {
            src.sendMessage(prefix().append(Component.text("§cReport nicht gefunden.")));
            return;
        }

        // Auto-claim wenn offen + unclaimed
        String staff = staffName(src);
        if (!r.handled() && (r.handledBy() == null || r.handledBy().isBlank())) {
            boolean ok = reportService.claimReport(id, staff);
            if (ok) {
                broadcastState("§egeclaimt", id, staff);
                r = reportService.getReportById(id);
            }
        }

        showInfo(src, r);
    }

    private void showInfo(CommandSource src, ReportEntry r) {
        String created = (r.createdAt() != null) ? DATE.format(r.createdAt()) : "-";
        String status = r.handled() ? "§aCLOSED" : "§eOPEN";
        String claim = (r.handledBy() == null || r.handledBy().isBlank()) ? "-" : r.handledBy();
        String preset = (r.presetKey() == null || r.presetKey().isBlank()) ? "-" : r.presetKey();
        String reason = (r.reason() == null || r.reason().isBlank()) ? "Kein Grund angegeben" : r.reason();
        String server = (r.serverName() == null || r.serverName().isBlank()) ? "-" : r.serverName();

        src.sendMessage(Component.text(" "));
        src.sendMessage(prefix().append(Component.text("§dReport Info §7(#" + r.id() + ")")));
        src.sendMessage(Component.text("§8§m────────────────────────────────"));
        src.sendMessage(Component.text("§7Status: " + status));
        src.sendMessage(Component.text("§7Ziel: §f" + r.targetName()));
        src.sendMessage(Component.text("§7Reporter: §f" + r.reporterName()));
        src.sendMessage(Component.text("§7Server: §f" + server));
        src.sendMessage(Component.text("§7Zeit: §f" + created));
        src.sendMessage(Component.text("§7Claim/Staff: §f" + claim));
        src.sendMessage(Component.text("§7Preset: §f" + preset));
        src.sendMessage(Component.text("§7Grund: §f" + reason));
        src.sendMessage(Component.text("§8§m────────────────────────────────"));

        Component actions = Component.text("§7Aktionen: ");

        Component tpBtn = Component.text("§8[§bTP§8]")
                .clickEvent(ClickEvent.runCommand("/reports tp " + r.id()))
                .hoverEvent(HoverEvent.showText(Component.text("§7Zum Server des Reports wechseln")));

        Component takeBtn = Component.text(" §8[§aTAKE§8]")
                .clickEvent(ClickEvent.runCommand("/reports take " + r.id()))
                .hoverEvent(HoverEvent.showText(Component.text("§7Claim + TP + Info")));

        Component closeBtn = Component.text(" §8[§aCLOSE§8]")
                .clickEvent(ClickEvent.runCommand("/reports close " + r.id()))
                .hoverEvent(HoverEvent.showText(Component.text("§7Report schließen")));

        Component unclaimBtn = Component.text(" §8[§6UNCLAIM§8]")
                .clickEvent(ClickEvent.runCommand("/reports unclaim " + r.id()))
                .hoverEvent(HoverEvent.showText(Component.text("§7Claim entfernen (nur eigener Claim, Admin kann immer)")));

        src.sendMessage(actions.append(tpBtn).append(takeBtn).append(closeBtn).append(unclaimBtn));
        src.sendMessage(Component.text(" "));
    }

    // ============================================================
    // TP (nutzt DEINEN ServerTeleportService)
    // ============================================================

    private void tpToReportServer(CommandSource src, String idArg) {
        if (!(src instanceof Player player)) {
            src.sendMessage(prefix().append(Component.text("§cNur ingame.")));
            return;
        }

        long id = parseId(idArg);
        if (id <= 0) {
            player.sendMessage(prefix().append(Component.text("§cUngültige ID.")));
            return;
        }

        ReportEntry r = reportService.getReportById(id);
        if (r == null) {
            player.sendMessage(prefix().append(Component.text("§cReport nicht gefunden.")));
            return;
        }

        String serverName = r.serverName();
        if (serverName == null || serverName.isBlank()) {
            player.sendMessage(prefix().append(Component.text("§cServer ist unbekannt.")));
            return;
        }

        // Für Staff: Cooldown ignorieren
        String displayName = serverName;
        teleportService.sendToServer(player, serverName, displayName, true);
    }

    // ============================================================
    // TAKE (Claim + TP + Info)
    // ============================================================

    private void take(CommandSource src, String idArg) {
        if (!(src instanceof Player player)) {
            src.sendMessage(prefix().append(Component.text("§cNur ingame.")));
            return;
        }

        long id = parseId(idArg);
        if (id <= 0) {
            player.sendMessage(prefix().append(Component.text("§cUngültige ID.")));
            return;
        }

        ReportEntry r = reportService.getReportById(id);
        if (r == null) {
            player.sendMessage(prefix().append(Component.text("§cReport nicht gefunden.")));
            return;
        }

        String staff = player.getUsername();

        // wenn offen & unclaimed -> claim
        if (!r.handled() && (r.handledBy() == null || r.handledBy().isBlank())) {
            boolean ok = reportService.claimReport(id, staff);
            if (ok) {
                broadcastState("§egeclaimt", id, staff);
                r = reportService.getReportById(id);
            } else {
                player.sendMessage(prefix().append(Component.text("§cKonnte Report nicht claimen.")));
                return;
            }
        }

        // TP wenn server vorhanden
        String serverName = r.serverName();
        if (serverName != null && !serverName.isBlank()) {
            teleportService.sendToServer(player, serverName, serverName, true);
        } else {
            player.sendMessage(prefix().append(Component.text("§cServer ist unbekannt.")));
        }

        // Info anzeigen (nutzt Auto-Claim nicht erneut, da schon geclaimt)
        showInfo(player, r);
    }

    // ============================================================
    // CLAIM / UNCLAIM / CLOSE
    // ============================================================

    private void claim(CommandSource src, String idArg, boolean thenInfo) {
        long id = parseId(idArg);
        if (id <= 0) {
            src.sendMessage(prefix().append(Component.text("§cUngültige ID.")));
            return;
        }

        String staff = staffName(src);

        if (reportService.isClaimedBy(id, staff)) {
            src.sendMessage(prefix().append(Component.text("§eReport §f#" + id + " §eist bereits von dir geclaimt.")));
            if (thenInfo) info(src, String.valueOf(id));
            return;
        }

        boolean ok = reportService.claimReport(id, staff);
        if (ok) {
            src.sendMessage(prefix().append(Component.text("§aReport §f#" + id + " §awurde von dir geclaimt.")));
            broadcastState("§egeclaimt", id, staff);
            if (thenInfo) info(src, String.valueOf(id));
        } else {
            src.sendMessage(prefix().append(Component.text("§cKonnte Report nicht claimen (nicht gefunden / bereits geclaimt / geschlossen / DB-Fehler).")));
        }
    }

    private void unclaim(CommandSource src, String idArg) {
        long id = parseId(idArg);
        if (id <= 0) {
            src.sendMessage(prefix().append(Component.text("§cUngültige ID.")));
            return;
        }

        String staff = staffName(src);
        boolean admin = isAdmin(src);

        boolean ok = reportService.unclaimReport(id, admin ? null : staff);
        if (ok) {
            src.sendMessage(prefix().append(Component.text("§aReport §f#" + id + " §awurde unclaimed.")));
            broadcastState("§6unclaimed", id, staff);
        } else {
            if (admin) {
                src.sendMessage(prefix().append(Component.text("§cKonnte Report nicht unclaimen (nicht gefunden / geschlossen / DB-Fehler).")));
            } else {
                src.sendMessage(prefix().append(Component.text("§cDu kannst diesen Report nicht unclaimen (nicht von dir geclaimt / nicht gefunden / geschlossen).")));
            }
        }
    }

    private void close(CommandSource src, String idArg) {
        long id = parseId(idArg);
        if (id <= 0) {
            src.sendMessage(prefix().append(Component.text("§cUngültige ID.")));
            return;
        }

        ReportEntry r = reportService.getReportById(id);
        if (r == null) {
            src.sendMessage(prefix().append(Component.text("§cReport nicht gefunden.")));
            return;
        }

        String staff = staffName(src);
        boolean admin = isAdmin(src);

        // Nur eigener Claim darf geschlossen werden (außer Admin)
        if (!admin) {
            if (r.handledBy() == null || !r.handledBy().equalsIgnoreCase(staff)) {
                src.sendMessage(prefix().append(Component.text(
                        "§cDu kannst nur Reports schließen, die von dir geclaimt sind."
                )));
                return;
            }
        }

        boolean ok = reportService.markReportHandled(id, staff);
        if (ok) {
            src.sendMessage(prefix().append(Component.text(
                    "§aReport §f#" + id + " §awurde geschlossen."
            )));
            broadcastState("§ageschlossen", id, staff);
        } else {
            src.sendMessage(prefix().append(Component.text(
                    "§cKonnte Report nicht schließen (DB-Fehler)."
            )));
        }
    }

    // ============================================================
    // LISTS
    // ============================================================

    private void listOpenUnclaimed(CommandSource src, int page) {
        List<ReportEntry> open = reportService.getOpenReports();
        List<ReportEntry> filtered = open.stream()
                .filter(r -> r.handledBy() == null || r.handledBy().isBlank())
                .collect(Collectors.toList());
        renderList(src, "Offene Reports (unclaimed)", filtered, page);
    }

    private void listOpenAll(CommandSource src, int page) {
        renderList(src, "Offene Reports (alle)", reportService.getOpenReports(), page);
    }

    private void listOpenClaimed(CommandSource src, int page) {
        List<ReportEntry> open = reportService.getOpenReports();
        List<ReportEntry> filtered = open.stream()
                .filter(r -> r.handledBy() != null && !r.handledBy().isBlank())
                .collect(Collectors.toList());
        renderList(src, "Offene Reports (geclaimt)", filtered, page);
    }

    private void listMine(CommandSource src, int page) {
        String me = staffName(src);
        List<ReportEntry> open = reportService.getOpenReports();
        List<ReportEntry> filtered = open.stream()
                .filter(r -> r.handledBy() != null && r.handledBy().equalsIgnoreCase(me))
                .collect(Collectors.toList());
        renderList(src, "Deine offenen Reports", filtered, page);
    }

    private void listAll(CommandSource src, int page) {
        renderList(src, "Alle Reports", reportService.getAllReports(), page);
    }

    private void renderList(CommandSource src, String title, List<ReportEntry> list, int page) {
        int perPage = 8;
        int totalPages = Math.max(1, (int) Math.ceil(list.size() / (double) perPage));
        page = Math.min(Math.max(1, page), totalPages);

        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, list.size());

        src.sendMessage(Component.text(" "));
        src.sendMessage(prefix().append(Component.text("§d" + title + " §7(Seite " + page + "§7/§f" + totalPages + "§7)")));
        src.sendMessage(Component.text("§8§m────────────────────────────────"));

        if (list.isEmpty()) {
            src.sendMessage(Component.text("§7Keine Einträge."));
        } else {
            for (ReportEntry r : list.subList(start, end)) {
                String status = r.handled() ? "§aCLOSED" : "§eOPEN";
                String claim = (r.handledBy() == null || r.handledBy().isBlank()) ? "-" : r.handledBy();
                String server = (r.serverName() == null || r.serverName().isBlank()) ? "-" : r.serverName();

                Component line = Component.text(
                        "§d#" + r.id() +
                                " §8| " + status +
                                " §8| §7Ziel: §f" + r.targetName() +
                                " §8| §7Von: §f" + r.reporterName() +
                                " §8| §7Server: §f" + server +
                                " §8| §7Claim: §f" + claim + " "
                );

                Component infoBtn = Component.text("§8[§dINFO§8]")
                        .clickEvent(ClickEvent.runCommand("/reports info " + r.id()))
                        .hoverEvent(HoverEvent.showText(Component.text("§7Info zu Report §f#" + r.id())));

                Component claimBtn = Component.text(" §8[§dCLAIMINFO§8]")
                        .clickEvent(ClickEvent.runCommand("/reports claiminfo " + r.id()))
                        .hoverEvent(HoverEvent.showText(Component.text("§7Claim + Info öffnen")));

                Component tpBtn = Component.text(" §8[§bTP§8]")
                        .clickEvent(ClickEvent.runCommand("/reports tp " + r.id()))
                        .hoverEvent(HoverEvent.showText(Component.text("§7Zum Server des Reports wechseln")));

                Component takeBtn = Component.text(" §8[§aTAKE§8]")
                        .clickEvent(ClickEvent.runCommand("/reports take " + r.id()))
                        .hoverEvent(HoverEvent.showText(Component.text("§7Claim + TP + Info")));

                src.sendMessage(line.append(infoBtn).append(claimBtn).append(tpBtn).append(takeBtn));
            }
        }

        src.sendMessage(Component.text("§8§m────────────────────────────────"));
        src.sendMessage(Component.text("§7Befehle: §f/reports info <id> §8| §f/reports claiminfo <id> §8| §f/reports tp <id> §8| §f/reports take <id> §8| §f/reports unclaim <id> §8| §f/reports close <id>"));
        src.sendMessage(Component.text(" "));
    }

    // ============================================================
    // BROADCAST
    // ============================================================

    private void broadcastState(String verbColored, long id, String staff) {
        Component base = prefix().append(Component.text(
                "§7Report §f#" + id + " §7wurde von §f" + staff + " §7" + verbColored + "§7. "
        ));

        Component infoBtn = Component.text("§8[§dINFO§8]")
                .clickEvent(ClickEvent.runCommand("/reports info " + id))
                .hoverEvent(HoverEvent.showText(Component.text("§7Details zu Report §f#" + id)));

        Component tpBtn = Component.text(" §8[§bTP§8]")
                .clickEvent(ClickEvent.runCommand("/reports tp " + id))
                .hoverEvent(HoverEvent.showText(Component.text("§7Zum Server des Reports wechseln")));

        Component takeBtn = Component.text(" §8[§aTAKE§8]")
                .clickEvent(ClickEvent.runCommand("/reports take " + id))
                .hoverEvent(HoverEvent.showText(Component.text("§7Claim + TP + Info")));

        Component msg = base.append(infoBtn).append(tpBtn).append(takeBtn);

        proxy.getAllPlayers().forEach(p -> {
            if (!isStaff(p)) return;
            if (p.getUsername().equalsIgnoreCase(staff)) return;
            p.sendMessage(msg);
        });
    }

    private long parseId(String s) {
        try {
            if (s == null) return -1;
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    // ============================================================
    // TAB COMPLETE
    // ============================================================

    @Override
    public boolean hasPermission(Invocation invocation) {
        return hasPerm(invocation.source());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!hasPerm(invocation.source())) return List.of();

        String[] args = invocation.arguments();

        List<String> subs = List.of(
                "open", "openall", "claimed", "mine", "all", "info",
                "claim", "claiminfo", "unclaim", "close", "tp", "take", "list"
        );

        if (args.length == 0) return subs;

        if (args.length == 1) {
            String p = (args[0] == null ? "" : args[0]).toLowerCase(Locale.ROOT);
            return subs.stream()
                    .filter(s -> s.startsWith(p))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            String sub = (args[0] == null ? "" : args[0]).toLowerCase(Locale.ROOT);

            if (sub.equals("info") || sub.equals("claim") || sub.equals("claiminfo")
                    || sub.equals("unclaim") || sub.equals("close") || sub.equals("tp") || sub.equals("take")) {
                String p = (args[1] == null ? "" : args[1]).toLowerCase(Locale.ROOT);
                return reportService.getOpenReports().stream()
                        .map(r -> String.valueOf(r.id()))
                        .filter(id -> id.startsWith(p))
                        .limit(50)
                        .collect(Collectors.toList());
            }

            if (sub.equals("open") || sub.equals("openall") || sub.equals("claimed")
                    || sub.equals("mine") || sub.equals("all") || sub.equals("list")) {
                return List.of("1", "2", "3", "4", "5");
            }
        }

        return List.of();
    }
}
