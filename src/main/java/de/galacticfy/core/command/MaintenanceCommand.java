package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.service.MaintenanceService;
import de.galacticfy.core.util.DiscordWebhookNotifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * /maintenance & /wartung – globales Wartungssystem für Galacticfy
 *
 * Features:
 *  - global on/off/toggle/status/help
 *  - zeitgesteuerte Wartungen
 *  - Whitelist für Spieler & Gruppen (nur Namen, kein LuckPerms)
 *  - Pro-Server-Maintenance: /maintenance server <Backend> on/off/status
 *  - 1-Minuten-Countdown pro Server
 *  - Ingame-Countdown + Title + Actionbar
 *  - Discord-Webhooks (geplant, gestartet, beendet)
 *  - Fallback-Lobby-System (Lobby-1, Lobby-2, Lobby-Backup, Hub-1)
 *
 * Unterschied:
 *  - wartungLayout = false  → /maintenance (Tech-Layout)
 *  - wartungLayout = true   → /wartung    (deutsches Layout / anderes Design)
 */
public class MaintenanceCommand implements SimpleCommand {

    private final MaintenanceService maintenanceService;
    private final ProxyServer proxy;
    private final DiscordWebhookNotifier discordNotifier;

    // eigenes Rollen-/Permission-System (mit * usw.)
    private final GalacticfyPermissionService permissionService;

    // welches Layout?
    private final boolean wartungLayout;

    private static final ScheduledExecutorService COUNTDOWN_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor();

    private final MiniMessage mm = MiniMessage.miniMessage();

    public MaintenanceCommand(
            MaintenanceService maintenanceService,
            ProxyServer proxy,
            DiscordWebhookNotifier discordNotifier,
            boolean wartungLayout,
            GalacticfyPermissionService permissionService
    ) {
        this.maintenanceService = maintenanceService;
        this.proxy = proxy;
        this.discordNotifier = discordNotifier;
        this.wartungLayout = wartungLayout;
        this.permissionService = permissionService;
    }

    // ------------------------------------------------------------
    // Layout-abhängige Helper
    // ------------------------------------------------------------

    private Component prefix() {
        if (wartungLayout) {
            // /wartung → eher „Admin / Deutsch“-Style
            return Component.text("§8[§eWartung§8] §r");
        } else {
            // /maintenance → Standard Galacticfy-Tech-Style
            return Component.text("§8[§bGalacticfy§8] §r");
        }
    }

    private String labelMaintenance() {
        return wartungLayout ? "Wartungsmodus" : "Maintenance";
    }

    private String labelMaintenanceShort() {
        return wartungLayout ? "Wartung" : "Maintenance";
    }

    private String labelMaintenanceStatusTitle() {
        return wartungLayout ? "Wartungs-Status" : "Maintenance-Status";
    }

    private String labelMaintenanceHeader() {
        return wartungLayout ? "Wartung" : "Maintenance";
    }

    private String sourceName(CommandSource source) {
        if (source instanceof Player player) {
            return player.getUsername();
        }
        return "Konsole";
    }

    // ------------------------------------------------------------
    // Permission-Helper (hier greift auch "*")
    // ------------------------------------------------------------

    private boolean hasMaintPerm(CommandSource source, String subNode) {
        // Konsole darf immer
        if (!(source instanceof Player player)) {
            return true;
        }

        String full = "galacticfy.maintenance." + subNode;

        if (permissionService != null) {
            // benutzt dein Rank-System (inkl. "*") + Fallback
            return permissionService.hasPluginPermission(source, full);
        }

        // falls aus irgendeinem Grund kein permissionService da ist
        return player.hasPermission(full);
    }

    private void noPerm(CommandSource source) {
        source.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
    }

    private boolean hasAnyMaintenancePermission(CommandSource source) {
        return hasMaintPerm(source, "view")
                || hasMaintPerm(source, "simple")
                || hasMaintPerm(source, "advanced")
                || hasMaintPerm(source, "whitelist")
                || hasMaintPerm(source, "perserver");
    }

    /**
     * Zentraler Bypass-Check für Maintenance:
     *  - Rollen-Flag (maintenance_bypass)
     *  - Plugin-Permissions aus deinem System (inkl. Wildcards)
     *  - Fallback: normale Velocity-/LP-Permissions
     */
    private boolean isBypassedForMaintenance(Player player) {
        if (permissionService != null) {
            // 1) Rollenflag aus deinem Ranksystem
            if (permissionService.hasMaintenanceBypass(player)) {
                return true;
            }

            // 2) Konkrete Bypass-Permission über dein Ranksystem
            if (permissionService.hasPluginPermission(player, "galacticfy.maintenance.bypass")) {
                return true;
            }

            // 3) Wildcards aus deinem System
            if (permissionService.hasPluginPermission(player, "galacticfy.*")) {
                return true;
            }

            if (permissionService.hasPluginPermission(player, "*")) {
                return true;
            }
        }

        // 4) Fallback: andere Plugins / OP / Velocity
        return player.hasPermission("galacticfy.maintenance.bypass")
                || player.hasPermission("galacticfy.*")
                || player.hasPermission("*");
    }

    // =====================================================================
    // EXECUTE
    // =====================================================================

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            if (!hasAnyMaintenancePermission(source)) {
                noPerm(source);
                return;
            }
            sendUsage(source);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("help")) {
            if (!hasAnyMaintenancePermission(source)) {
                noPerm(source);
                return;
            }
            sendUsage(source);
            return;
        }

        if (sub.equals("whitelist") || sub.equals("wl")) {
            handleWhitelist(source, args);
            return;
        }

        if (sub.equals("server")) {
            if (!hasMaintPerm(source, "perserver")) {
                noPerm(source);
                return;
            }
            handleServerMaintenance(source, args);
            return;
        }

        switch (sub) {
            case "on" -> {
                if (!hasMaintPerm(source, "simple")
                        && !hasMaintPerm(source, "advanced")) {
                    noPerm(source);
                    return;
                }
                handleOn(source, args);
            }
            case "off" -> {
                if (!hasMaintPerm(source, "simple")
                        && !hasMaintPerm(source, "advanced")) {
                    noPerm(source);
                    return;
                }
                String by = sourceName(source);
                maintenanceService.setMaintenanceEnabled(false);
                source.sendMessage(prefix().append(Component.text("§a" + labelMaintenanceShort() + " wurde §cdeaktiviert§a.")));
                proxy.sendMessage(prefix().append(Component.text("§aDie Wartungsarbeiten wurden beendet.")));

                if (discordNotifier != null && discordNotifier.isEnabled()) {
                    discordNotifier.sendMaintenanceEnd(by);
                }
            }
            case "toggle" -> {
                if (!hasMaintPerm(source, "simple")
                        && !hasMaintPerm(source, "advanced")) {
                    noPerm(source);
                    return;
                }
                String by = sourceName(source);
                boolean now = !maintenanceService.isMaintenanceEnabled();
                maintenanceService.setMaintenanceEnabled(now);
                source.sendMessage(prefix().append(
                        Component.text("§7Status: " + (now ? "§cAN" : "§aAUS"))
                ));

                if (now) {
                    proxy.sendMessage(prefix().append(
                            Component.text("§eWartungsarbeiten wurden von §b" + by + " §caktiviert§e.")
                    ));
                    if (discordNotifier != null && discordNotifier.isEnabled()) {
                        discordNotifier.sendMaintenanceStarted(by, "unbekannt");
                    }
                    kickNonBypassPlayers();
                } else {
                    proxy.sendMessage(prefix().append(
                            Component.text("§aDie Wartungsarbeiten wurden von §b" + by + " §abeendet.")
                    ));
                    if (discordNotifier != null && discordNotifier.isEnabled()) {
                        discordNotifier.sendMaintenanceEnd(by);
                    }
                }
            }
            case "status" -> {
                if (!hasMaintPerm(source, "view")
                        && !hasMaintPerm(source, "simple")
                        && !hasMaintPerm(source, "advanced")) {
                    noPerm(source);
                    return;
                }

                boolean enabled = maintenanceService.isMaintenanceEnabled();
                Long remaining = maintenanceService.getRemainingMillis();

                source.sendMessage(Component.text(" "));
                source.sendMessage(prefix().append(Component.text("§b" + labelMaintenanceStatusTitle())));
                source.sendMessage(Component.text("§8────────────────────────────"));

                if (!enabled) {
                    source.sendMessage(Component.text("§7Status: §aAUS"));
                } else if (remaining == null) {
                    source.sendMessage(Component.text("§7Status: §cAN §8(§7kein automatisches Ende§8)"));
                } else {
                    long seconds = remaining / 1000;
                    long minutes = seconds / 60;
                    long hours = minutes / 60;
                    long days = hours / 24;
                    long s = seconds % 60;
                    long m = minutes % 60;
                    long h = hours % 24;
                    String rest = String.format("§e%sd %sh %sm %ss", days, h, m, s);
                    source.sendMessage(Component.text("§7Status: §cAN"));
                    source.sendMessage(Component.text("§7Verbleibende Zeit: " + rest));
                }

                var servers = maintenanceService.getServersInMaintenance();
                if (!servers.isEmpty()) {
                    source.sendMessage(Component.text(" "));
                    source.sendMessage(Component.text("§7Server im Wartungsmodus: §e" +
                            String.join("§7, §e", servers)));
                }

                source.sendMessage(Component.text("§8────────────────────────────"));
                source.sendMessage(Component.text(" "));
            }
            default -> sendUsage(source);
        }
    }

    // =====================================================================
    // SERVER-MAINTENANCE (+1-Minuten-Countdown)
    // =====================================================================

    private void handleServerMaintenance(CommandSource source, String[] args) {
        if (args.length < 3) {
            source.sendMessage(prefix().append(Component.text(
                    "§eBenutzung: /" + (wartungLayout ? "wartung" : "maintenance") + " server <Backend> <on/off/status>"
            )));
            return;
        }

        String backend = args[1];
        String action = args[2].toLowerCase(Locale.ROOT);

        switch (action) {
            case "on" -> {
                long delayMs = 60_000L; // 1 Minute
                source.sendMessage(prefix().append(Component.text(
                        "§aServer §e" + backend + " §awird in §e1 Minute §ain den Wartungsmodus gesetzt."
                )));

                scheduleServerMaintenanceCountdown(backend, delayMs);
            }
            case "off" -> {
                maintenanceService.setServerMaintenance(backend, false);
                source.sendMessage(prefix().append(Component.text(
                        "§aWartungsmodus für §e" + backend + " §awurde deaktiviert."
                )));
            }
            case "status" -> {
                boolean inMaint = maintenanceService.isServerInMaintenance(backend);
                source.sendMessage(prefix().append(Component.text(
                        "§7Server §e" + backend + " §7Status: " + (inMaint ? "§cMAINTENANCE" : "§aONLINE")
                )));
            }
            default -> source.sendMessage(prefix().append(Component.text(
                    "§eBenutzung: /" + (wartungLayout ? "wartung" : "maintenance") + " server <Backend> <on/off/status>"
            )));
        }
    }

    // =====================================================================
    // GLOBAL ON / TIMER / DISCORD
    // =====================================================================

    private void handleOn(CommandSource source, String[] args) {
        String by = sourceName(source);

        if (args.length == 1) {
            maintenanceService.setMaintenanceEnabled(true);
            source.sendMessage(prefix().append(Component.text("§a" + labelMaintenanceShort() + " wurde §caktiviert§a.")));
            proxy.sendMessage(prefix().append(
                    Component.text("§eWartungsarbeiten wurden von §b" + by + " §csofort gestartet§e.")
            ));

            if (discordNotifier != null && discordNotifier.isEnabled()) {
                discordNotifier.sendMaintenanceStarted(by, "unbekannt");
            }

            kickNonBypassPlayers();
            return;
        }

        if (args.length >= 3 && args[1].equalsIgnoreCase("time")) {
            if (!hasMaintPerm(source, "advanced")) {
                source.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung (Advanced-Timer).")));
                return;
            }

            List<String> durationTokens = new ArrayList<>();
            List<String> delayTokens = new ArrayList<>();

            boolean inDelayPart = false;
            for (int i = 2; i < args.length; i++) {
                String token = args[i];

                if (token.equalsIgnoreCase("start")) {
                    inDelayPart = true;
                    continue;
                }

                if (!inDelayPart) {
                    durationTokens.add(token);
                } else {
                    delayTokens.add(token);
                }
            }

            Long durationMs = parseDuration(durationTokens);
            if (durationMs == null) {
                source.sendMessage(prefix().append(Component.text("§cUngültige Zeitangabe bei der Dauer.")));
                return;
            }

            Long delayMs = null;
            if (!delayTokens.isEmpty()) {
                delayMs = parseDuration(delayTokens);
                if (delayMs == null) {
                    source.sendMessage(prefix().append(Component.text("§cUngültige Zeitangabe bei der Start-Verzögerung.")));
                    return;
                }
            }

            String durationDisplay = String.join(" ", durationTokens);
            if (durationDisplay.isEmpty()) durationDisplay = "unbekannt";

            String delayDisplay = delayTokens.isEmpty()
                    ? "sofort"
                    : String.join(" ", delayTokens);

            if (delayMs == null || delayMs <= 0) {
                String finalDurationDisplay = durationDisplay;

                Runnable onStart = () -> {
                    proxy.sendMessage(prefix().append(
                            Component.text("§cWartungsarbeiten starten jetzt§e.")
                    ));
                    kickNonBypassPlayers();

                    if (discordNotifier != null && discordNotifier.isEnabled()) {
                        discordNotifier.sendMaintenanceStarted(by, finalDurationDisplay);
                    }
                };

                Runnable onEnd = () -> {
                    if (discordNotifier != null && discordNotifier.isEnabled()) {
                        discordNotifier.sendMaintenanceEnd(by);
                    }
                };

                maintenanceService.enableForDuration(durationMs, onStart, onEnd);

                source.sendMessage(prefix().append(Component.text(
                        "§a" + labelMaintenanceShort() + " wurde §caktiviert§a."
                )));
            } else {
                String delayText = formatCountdownText(delayMs);

                scheduleCountdowns(delayMs);

                String finalDurationDisplay = durationDisplay;

                Runnable onStart = () -> {
                    proxy.sendMessage(prefix().append(
                            Component.text("§cWartungsarbeiten starten jetzt§e.")
                    ));
                    kickNonBypassPlayers();

                    if (discordNotifier != null && discordNotifier.isEnabled()) {
                        discordNotifier.sendMaintenanceStarted(by, finalDurationDisplay);
                    }
                };

                Runnable onEnd = () -> {
                    if (discordNotifier != null && discordNotifier.isEnabled()) {
                        discordNotifier.sendMaintenanceEnd(by);
                    }
                };

                maintenanceService.scheduleMaintenance(delayMs, durationMs, onStart, onEnd);

                source.sendMessage(prefix().append(Component.text(
                        "§a" + labelMaintenanceShort() + " wird in §e" + delayText + " §agestartet."
                )));

                if (discordNotifier != null && discordNotifier.isEnabled()) {
                    discordNotifier.sendMaintenancePlanned(by, durationDisplay, delayDisplay);
                }
            }

            return;
        }

        sendUsage(source);
    }

    // =====================================================================
    // WHITELIST SUBCOMMANDS
    // =====================================================================

    private void handleWhitelist(CommandSource source, String[] args) {
        if (!hasMaintPerm(source, "whitelist")) {
            noPerm(source);
            return;
        }

        if (args.length == 1) {
            sendWhitelistInfo(source);
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);

        switch (action) {
            case "list" -> sendWhitelistInfo(source);

            case "addplayer" -> {
                if (args.length < 3) {
                    source.sendMessage(prefix().append(Component.text("§eBenutzung: §b/" + (wartungLayout ? "wartung" : "maintenance") + " whitelist addplayer <Name>")));
                    return;
                }
                String name = args[2];
                boolean added = maintenanceService.addWhitelistedPlayer(name);
                if (added) {
                    source.sendMessage(prefix().append(Component.text("§aSpieler §e" + name + " §awurde zur Maintenance-Whitelist hinzugefügt.")));
                } else {
                    source.sendMessage(prefix().append(Component.text("§eSpieler §e" + name + " §eist bereits auf der Whitelist.")));
                }
            }

            case "removeplayer" -> {
                if (args.length < 3) {
                    source.sendMessage(prefix().append(Component.text("§eBenutzung: §b/" + (wartungLayout ? "wartung" : "maintenance") + " whitelist removeplayer <Name>")));
                    return;
                }
                String name = args[2];
                boolean removed = maintenanceService.removeWhitelistedPlayer(name);
                if (removed) {
                    source.sendMessage(prefix().append(Component.text("§aSpieler §e" + name + " §awurde von der Maintenance-Whitelist entfernt.")));
                    if (maintenanceService.isMaintenanceEnabled()) {
                        kickNonBypassPlayers();
                    }
                } else {
                    source.sendMessage(prefix().append(Component.text("§eSpieler §e" + name + " §ewar nicht auf der Whitelist.")));
                }
            }

            case "addgroup" -> {
                if (args.length < 3) {
                    source.sendMessage(prefix().append(Component.text("§eBenutzung: §b/" + (wartungLayout ? "wartung" : "maintenance") + " whitelist addgroup <Gruppe>")));
                    return;
                }
                String group = args[2];
                boolean added = maintenanceService.addWhitelistedGroup(group);
                if (added) {
                    source.sendMessage(prefix().append(Component.text("§aGruppe §e" + group + " §awurde zur Maintenance-Whitelist hinzugefügt.")));
                } else {
                    source.sendMessage(prefix().append(Component.text("§eGruppe §e" + group + " §eist bereits auf der Whitelist.")));
                }
            }

            case "removegroup" -> {
                if (args.length < 3) {
                    source.sendMessage(prefix().append(Component.text("§eBenutzung: §b/" + (wartungLayout ? "wartung" : "maintenance") + " whitelist removegroup <Gruppe>")));
                    return;
                }
                String group = args[2];
                boolean removed = maintenanceService.removeWhitelistedGroup(group);
                if (removed) {
                    source.sendMessage(prefix().append(Component.text("§aGruppe §e" + group + " §awurde von der Maintenance-Whitelist entfernt.")));
                    if (maintenanceService.isMaintenanceEnabled()) {
                        kickNonBypassPlayers();
                    }
                } else {
                    source.sendMessage(prefix().append(Component.text("§eGruppe §e" + group + " §ewar nicht auf der Whitelist.")));
                }
            }

            default -> {
                source.sendMessage(prefix().append(Component.text("§eBenutzung:")));
                source.sendMessage(Component.text("§8» §b/" + (wartungLayout ? "wartung" : "maintenance") + " whitelist list"));
                source.sendMessage(Component.text("§8» §b/" + (wartungLayout ? "wartung" : "maintenance") + " whitelist addplayer <Name>"));
                source.sendMessage(Component.text("§8» §b/" + (wartungLayout ? "wartung" : "maintenance") + " whitelist removeplayer <Name>"));
                source.sendMessage(Component.text("§8» §b/" + (wartungLayout ? "wartung" : "maintenance") + " whitelist addgroup <Gruppe>"));
                source.sendMessage(Component.text("§8» §b/" + (wartungLayout ? "wartung" : "maintenance") + " whitelist removegroup <Gruppe>"));
            }
        }
    }

    private void sendWhitelistInfo(CommandSource source) {
        var players = maintenanceService.getWhitelistedPlayers();
        var groups = maintenanceService.getWhitelistedGroups();

        source.sendMessage(Component.text(" "));

        if (wartungLayout) {
            source.sendMessage(Component.text("§8§m──────§r §eWartung §7| §eWhitelist §8§m──────"));
        } else {
            source.sendMessage(Component.text("§8§m──────§r §bGalacticfy §7| §bMaintenance-Whitelist §8§m──────"));
        }

        source.sendMessage(Component.text(" "));

        String playerLine = players.isEmpty()
                ? "§8<leer>"
                : "§a" + String.join("§7, §a", players);

        String groupLine = groups.isEmpty()
                ? "§8<leer>"
                : "§a" + String.join("§7, §a", groups);

        source.sendMessage(Component.text("§b§lAktiv"));
        source.sendMessage(Component.text("§7Spieler: §r" + playerLine));
        source.sendMessage(Component.text("§7Gruppen: §r" + groupLine));
        source.sendMessage(Component.text(" "));

        source.sendMessage(Component.text("§b§lBefehle"));
        source.sendMessage(Component.text("§8» §b/" + (wartungLayout ? "wartung" : "maintenance") + " whitelist addplayer <Name>"));
        source.sendMessage(Component.text("   §7Spieler zur Whitelist hinzufügen."));
        source.sendMessage(Component.text("§8» §b/" + (wartungLayout ? "wartung" : "maintenance") + " whitelist removeplayer <Name>"));
        source.sendMessage(Component.text("   §7Spieler von der Whitelist entfernen."));
        source.sendMessage(Component.text("§8» §b/" + (wartungLayout ? "wartung" : "maintenance") + " whitelist addgroup <Gruppe>"));
        source.sendMessage(Component.text("   §7Gruppe hinzufügen (nur Name, kein LP)."));
        source.sendMessage(Component.text("§8» §b/" + (wartungLayout ? "wartung" : "maintenance") + " whitelist removegroup <Gruppe>"));
        source.sendMessage(Component.text("   §7Gruppe entfernen."));
        source.sendMessage(Component.text(" "));

        source.sendMessage(Component.text("§8§m────────────────────────────────"));
        source.sendMessage(Component.text(" "));
    }

    // =====================================================================
    // KICKS & FALLBACK
    // =====================================================================

    private void kickNonBypassPlayers() {
        Component kickMessage = mm.deserialize(
                "\n" +
                        "<gradient:#00E5FF:#7A00FF><bold>Galacticfy</bold></gradient> <gray>|</gray> <red><bold>Wartungsmodus aktiv</bold></red>\n" +
                        "<gray>Das Netzwerk befindet sich derzeit in Wartungsarbeiten.</gray>\n" +
                        "<gray>Bitte versuche es später erneut.</gray>\n" +
                        "\n" +
                        "<gold><bold>Weitere Infos:</bold></gold>\n" +
                        "<yellow>• Website:</yellow> <aqua>https://galacticfy.de</aqua>\n" +
                        "<yellow>• Discord:</yellow> <aqua>discord.gg/galacticfy</aqua>"
        );

        for (Player player : proxy.getAllPlayers()) {
            if (isBypassedForMaintenance(player)) {
                continue;
            }
            if (maintenanceService.isPlayerWhitelisted(player.getUsername())) {
                continue;
            }

            // Gruppen-Whitelist
            if (permissionService != null) {
                var role = permissionService.getRoleFor(player.getUniqueId());
                if (role != null && maintenanceService.isGroupWhitelisted(role.name)) {
                    continue;
                }
            }

            player.disconnect(kickMessage);
        }
    }

    private Optional<RegisteredServer> findFallbackLobby() {
        List<String> fallbackOrder = List.of(
                "Lobby-1",
                "Lobby-2",
                "Lobby-Backup",
                "Hub-1"
        );

        for (String name : fallbackOrder) {
            Optional<RegisteredServer> srv = proxy.getServer(name);
            if (srv.isPresent()) {
                return srv;
            }
        }

        return Optional.empty();
    }

    private void movePlayersOffServer(String backend) {
        if (backend == null || backend.isBlank()) return;

        final String backendLower = backend.toLowerCase(Locale.ROOT);

        var lobbyOpt = findFallbackLobby();

        if (lobbyOpt.isEmpty()) {
            Component kickMessage = mm.deserialize(
                    "\n" +
                            "<gradient:#00E5FF:#7A00FF><bold>Galacticfy Netzwerk</bold></gradient>\n" +
                            "<red><bold>Keine Lobby verfügbar</bold></red>\n" +
                            "<gray>Der Server befindet sich in Wartungsarbeiten und es steht</gray>\n" +
                            "<gray>derzeit kein Fallback-Server zur Verfügung.</gray>\n" +
                            "\n" +
                            "<yellow>Bitte versuche es später erneut.</yellow>"
            );

            for (Player player : proxy.getAllPlayers()) {
                if (isBypassedForMaintenance(player)) {
                    continue;
                }
                if (maintenanceService.isPlayerWhitelisted(player.getUsername())) {
                    continue;
                }

                // Gruppen-Whitelist
                if (permissionService != null) {
                    var role = permissionService.getRoleFor(player.getUniqueId());
                    if (role != null && maintenanceService.isGroupWhitelisted(role.name)) {
                        continue;
                    }
                }

                player.getCurrentServer().ifPresent(conn -> {
                    String currentName = conn.getServerInfo().getName();
                    if (currentName.equalsIgnoreCase(backendLower)) {
                        player.disconnect(kickMessage);
                    }
                });
            }
            return;
        }

        var lobby = lobbyOpt.get();

        for (Player player : proxy.getAllPlayers()) {

            if (isBypassedForMaintenance(player)
                    || maintenanceService.isPlayerWhitelisted(player.getUsername())) {
                continue;
            }

            // Gruppen-Whitelist
            if (permissionService != null) {
                var role = permissionService.getRoleFor(player.getUniqueId());
                if (role != null && maintenanceService.isGroupWhitelisted(role.name)) {
                    continue;
                }
            }

            player.getCurrentServer().ifPresent(conn -> {
                String currentName = conn.getServerInfo().getName();

                if (currentName.equalsIgnoreCase(backendLower)) {
                    player.sendMessage(Component.text(
                            "§cDieser Server befindet sich jetzt im Wartungsmodus.§7 Du wirst in die Lobby geschickt."
                    ));
                    player.createConnectionRequest(lobby).connect();
                }
            });
        }
    }

    // =====================================================================
    // COUNTDOWNS
    // =====================================================================

    private void scheduleCountdowns(long delayMs) {
        long[] marks = new long[]{
                30L * 60_000L,
                20L * 60_000L,
                10L * 60_000L,
                5L * 60_000L,
                60_000L,
                30_000L,
                20_000L,
                10_000L,
                5_000L,
                4_000L,
                3_000L,
                2_000L,
                1_000L
        };

        for (long mark : marks) {
            if (delayMs >= mark) {
                long when = delayMs - mark;

                COUNTDOWN_SCHEDULER.schedule(() -> {
                    String text = formatCountdownText(mark);

                    proxy.sendMessage(prefix().append(
                            Component.text("§eWartungsarbeiten beginnen in §c" + text + "§e.")
                    ));

                    long secondsLeft = mark / 1000L;
                    if (secondsLeft <= 30) {

                        String color;
                        if (secondsLeft > 20) {
                            color = "§6";
                        } else if (secondsLeft > 10) {
                            color = "§e";
                        } else if (secondsLeft > 5) {
                            color = "§6";
                        } else {
                            color = "§c";
                        }

                        for (Player player : proxy.getAllPlayers()) {
                            String secText = (secondsLeft == 1)
                                    ? "1 Sekunde"
                                    : secondsLeft + " Sekunden";

                            player.sendActionBar(
                                    Component.text(color + "Wartung beginnt in §f" + secText + color + ".")
                            );

                            Title title;

                            if (secondsLeft <= 3) {
                                title = Title.title(
                                        Component.text(color + "" + secondsLeft),
                                        Component.text("§fWartung startet gleich!"),
                                        Title.Times.times(
                                                Duration.ofMillis(150),
                                                Duration.ofMillis(600),
                                                Duration.ofMillis(150)
                                        )
                                );
                            } else {
                                title = Title.title(
                                        Component.text(color + "Wartung"),
                                        Component.text("§fBeginnt in " + color + secondsLeft + "§f Sekunde"
                                                + (secondsLeft == 1 ? "" : "n") + "."),

                                        Title.Times.times(
                                                Duration.ofMillis(250),
                                                Duration.ofSeconds(1),
                                                Duration.ofMillis(250)
                                        )
                                );
                            }

                            player.showTitle(title);
                        }
                    }
                }, when, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void scheduleServerMaintenanceCountdown(String backend, long delayMs) {
        final String backendLower = backend.toLowerCase(Locale.ROOT);

        long[] marks = new long[]{
                60_000L,
                30_000L,
                20_000L,
                10_000L,
                5_000L,
                4_000L,
                3_000L,
                2_000L,
                1_000L
        };

        for (long mark : marks) {
            if (delayMs >= mark) {
                long when = delayMs - mark;

                COUNTDOWN_SCHEDULER.schedule(() -> {
                    long secondsLeft = mark / 1000L;

                    String color;
                    if (secondsLeft > 20) {
                        color = "§6";
                    } else if (secondsLeft > 10) {
                        color = "§e";
                    } else if (secondsLeft > 5) {
                        color = "§6";
                    } else {
                        color = "§c";
                    }

                    String textKurz = formatCountdownText(mark);

                    for (Player player : proxy.getAllPlayers()) {
                        player.getCurrentServer().ifPresent(conn -> {
                            String currentName = conn.getServerInfo().getName();
                            if (!currentName.equalsIgnoreCase(backendLower)) {
                                return;
                            }

                            player.sendActionBar(
                                    Component.text(color + "Wartung auf §e" + backend
                                            + color + " beginnt in §f" + textKurz + color + ".")
                            );

                            Title title;
                            if (secondsLeft <= 3) {
                                title = Title.title(
                                        Component.text(color + "" + secondsLeft),
                                        Component.text("§fWartung auf §e" + backend + " §fstartet gleich!"),
                                        Title.Times.times(
                                                Duration.ofMillis(150),
                                                Duration.ofMillis(600),
                                                Duration.ofMillis(150)
                                        )
                                );
                            } else {
                                title = Title.title(
                                        Component.text(color + "Wartung §7(" + backend + ")"),
                                        Component.text("§fBeginnt in " + color + secondsLeft + "§f Sekunde"
                                                + (secondsLeft == 1 ? "" : "n") + "."),

                                        Title.Times.times(
                                                Duration.ofMillis(250),
                                                Duration.ofSeconds(1),
                                                Duration.ofMillis(250)
                                        )
                                );
                            }

                            player.showTitle(title);
                        });
                    }
                }, when, TimeUnit.MILLISECONDS);
            }
        }

        COUNTDOWN_SCHEDULER.schedule(() -> {
            maintenanceService.setServerMaintenance(backend, true);

            proxy.sendMessage(prefix().append(
                    Component.text("§eWartungsmodus für §b" + backend + " §ewurde §caktiviert§e.")
            ));

            movePlayersOffServer(backend);

        }, delayMs, TimeUnit.MILLISECONDS);
    }

    // =====================================================================
    // HILFE / FORMAT
    // =====================================================================

    private void sendUsage(CommandSource source) {
        source.sendMessage(Component.text(" "));

        if (wartungLayout) {
            source.sendMessage(Component.text("§8§m──────§r §eWartung §7| §eVerwaltung §8§m──────"));
        } else {
            source.sendMessage(Component.text("§8§m──────§r §bGalacticfy §7| §bMaintenance §8§m──────"));
        }

        source.sendMessage(Component.text(" "));

        source.sendMessage(Component.text("§b§lBasis-Befehle"));
        source.sendMessage(Component.text("§8» §b/" + (wartungLayout ? "wartung" : "maintenance") + " help"));
        source.sendMessage(Component.text("   §7Zeigt diese Hilfe an."));
        source.sendMessage(Component.text("§8» §b/" + (wartungLayout ? "wartung" : "maintenance") + " status"));
        source.sendMessage(Component.text("   §7Zeigt den aktuellen Status."));
        source.sendMessage(Component.text(" "));

        source.sendMessage(Component.text("§b§l" + labelMaintenanceShort() + " (global)"));
        source.sendMessage(Component.text("§8» §b/" + (wartungLayout ? "wartung" : "maintenance") + " on"));
        source.sendMessage(Component.text("   §7" + labelMaintenanceShort() + " §aSOFORT §7aktivieren."));
        source.sendMessage(Component.text("§8» §b/" + (wartungLayout ? "wartung" : "maintenance") + " off"));
        source.sendMessage(Component.text("   §7" + labelMaintenanceShort() + " §cdeaktivieren§7."));
        source.sendMessage(Component.text("§8» §b/" + (wartungLayout ? "wartung" : "maintenance") + " toggle"));
        source.sendMessage(Component.text("   §7Zwischen §aAUS §7und §cAN §7umschalten."));
        source.sendMessage(Component.text(" "));

        source.sendMessage(Component.text("§b§lZeitgesteuerte " + labelMaintenanceShort()));
        source.sendMessage(Component.text("§8» §b/" + (wartungLayout ? "wartung" : "maintenance") + " on time <Dauer>"));
        source.sendMessage(Component.text("   §7Startet sofort, endet automatisch."));
        source.sendMessage(Component.text("§8» §b/" + (wartungLayout ? "wartung" : "maintenance") + " on time <Dauer> start <Verzögerung>"));
        source.sendMessage(Component.text("   §7Start nach einer Verzögerung."));
        source.sendMessage(Component.text("   §7Einheiten: §f30m§7, §f1h§7, §f1d§7, §f1woche§7..."));
        source.sendMessage(Component.text(" "));

        source.sendMessage(Component.text("§b§lPro-Server-" + labelMaintenanceShort()));
        source.sendMessage(Component.text("§8» §b/" + (wartungLayout ? "wartung" : "maintenance") + " server <Backend> on/off/status"));
        source.sendMessage(Component.text("   §7Nur dieser Backend-Server im Wartungsmodus."));
        source.sendMessage(Component.text(" "));

        source.sendMessage(Component.text("§b§lWhitelist"));
        source.sendMessage(Component.text("§8» §b/" + (wartungLayout ? "wartung" : "maintenance") + " whitelist ..."));
        source.sendMessage(Component.text("   §7Spieler & Gruppen vom Kick ausnehmen."));
        source.sendMessage(Component.text(" "));

        source.sendMessage(Component.text("§8§m────────────────────────────────"));
        source.sendMessage(Component.text(" "));
    }

    private Long parseDuration(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return null;

        long totalMs = 0L;

        for (String raw : tokens) {
            if (raw == null || raw.isEmpty()) continue;

            String s = raw.toLowerCase(Locale.ROOT).trim();

            int split = 0;
            while (split < s.length() && Character.isDigit(s.charAt(split))) {
                split++;
            }
            if (split == 0 || split == s.length()) {
                return null;
            }

            String numberPart = s.substring(0, split);
            String unitPart = s.substring(split);

            long value;
            try {
                value = Long.parseLong(numberPart);
            } catch (NumberFormatException e) {
                return null;
            }

            long factor;

            if (unitPart.equals("y") || unitPart.equals("jahr") || unitPart.equals("jahre")) {
                factor = 365L * 24L * 60L * 60L * 1000L;
            } else if (unitPart.equals("mo") || unitPart.equals("monat") || unitPart.equals("monate")) {
                factor = 30L * 24L * 60L * 60L * 1000L;
            } else if (unitPart.equals("w") || unitPart.equals("woche") || unitPart.equals("wochen")) {
                factor = 7L * 24L * 60L * 60L * 1000L;
            } else if (unitPart.equals("d") || unitPart.equals("tag") || unitPart.equals("tage")) {
                factor = 24L * 60L * 60L * 1000L;
            } else if (unitPart.equals("h") || unitPart.equals("std") || unitPart.equals("stunde") || unitPart.equals("stunden")) {
                factor = 60L * 60L * 1000L;
            } else if (unitPart.equals("m") || unitPart.equals("min") || unitPart.equals("minute") || unitPart.equals("minuten")) {
                factor = 60L * 1000L;
            } else if (unitPart.equals("s") || unitPart.equals("sek") || unitPart.equals("sekunde") || unitPart.equals("sekunden")) {
                factor = 1000L;
            } else {
                return null;
            }

            totalMs += value * factor;
        }

        if (totalMs <= 0) return null;
        return totalMs;
    }

    private String formatCountdownText(long ms) {
        long seconds = ms / 1000;
        if (seconds >= 60) {
            long minutes = seconds / 60;
            if (minutes == 1) {
                return "1 Minute";
            }
            return minutes + " Minuten";
        } else {
            if (seconds == 1) {
                return "1 Sekunde";
            }
            return seconds + " Sekunden";
        }
    }

    // =====================================================================
    // TAB-COMPLETE
    // =====================================================================

    @Override
    public boolean hasPermission(Invocation invocation) {
        return hasAnyMaintenancePermission(invocation.source());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!hasAnyMaintenancePermission(source)) {
            return List.of();
        }

        String[] args = invocation.arguments();
        List<String> baseSubs = List.of("help", "on", "off", "toggle", "status", "whitelist", "server");

        if (args.length == 0) {
            return baseSubs;
        }

        if (args.length == 1) {
            String first = args[0].toLowerCase(Locale.ROOT);
            return baseSubs.stream()
                    .filter(opt -> opt.startsWith(first))
                    .toList();
        }

        if (args[0].equalsIgnoreCase("whitelist") || args[0].equalsIgnoreCase("wl")) {
            if (!hasMaintPerm(source, "whitelist")) {
                return List.of();
            }

            if (args.length == 2) {
                return List.of("list", "addplayer", "removeplayer", "addgroup", "removegroup")
                        .stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .toList();
            }

            if (args.length == 3) {
                String action = args[1].toLowerCase(Locale.ROOT);
                String prefix = args[2].toLowerCase(Locale.ROOT);

                switch (action) {
                    case "addplayer" -> {
                        return proxy.getAllPlayers().stream()
                                .map(Player::getUsername)
                                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                                .toList();
                    }
                    case "removeplayer" -> {
                        return maintenanceService.getWhitelistedPlayers().stream()
                                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                                .toList();
                    }
                    case "addgroup" -> {
                        // keine externe Source → einfach nichts vorschlagen
                        return List.of();
                    }
                    case "removegroup" -> {
                        var groups = maintenanceService.getWhitelistedGroups();
                        return groups.stream()
                                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                                .toList();
                    }
                }
            }

            return List.of();
        }

        if (args[0].equalsIgnoreCase("server")) {
            if (!hasMaintPerm(source, "perserver")) {
                return List.of();
            }

            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return proxy.getAllServers().stream()
                        .map(s -> s.getServerInfo().getName())
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }

            if (args.length == 3) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                return List.of("on", "off", "status").stream()
                        .filter(opt -> opt.startsWith(prefix))
                        .toList();
            }

            return List.of();
        }

        if (args[0].equalsIgnoreCase("on")) {
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                List<String> opts = List.of("time");
                if (prefix.isEmpty()) return opts;
                return opts.stream()
                        .filter(o -> o.startsWith(prefix))
                        .toList();
            }

            if (args.length >= 3 && args[1].equalsIgnoreCase("time")) {
                List<String> durationExamples = List.of(
                        "30m", "1h", "2h", "6h", "12h",
                        "1d", "3d",
                        "1woche",
                        "1monat",
                        "1jahr"
                );

                String last = args[args.length - 1].toLowerCase(Locale.ROOT);
                boolean hasStart = false;
                int startIndex = -1;

                for (int i = 0; i < args.length; i++) {
                    if (args[i].equalsIgnoreCase("start")) {
                        hasStart = true;
                        startIndex = i;
                        break;
                    }
                }

                if (!hasStart) {
                    List<String> suggestions = new ArrayList<>(durationExamples);
                    suggestions.add("start");

                    if (last.isEmpty()) {
                        return suggestions;
                    }

                    return suggestions.stream()
                            .filter(opt -> opt.toLowerCase(Locale.ROOT).startsWith(last))
                            .toList();
                }

                if (startIndex == args.length - 1) {
                    return List.of();
                }

                if (args.length - 1 == startIndex + 1) {
                    String prefix = last;
                    if (prefix.isEmpty()) {
                        return durationExamples;
                    }
                    return durationExamples.stream()
                            .filter(opt -> opt.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .toList();
                }

                return List.of();
            }
        }

        return List.of();
    }
}
