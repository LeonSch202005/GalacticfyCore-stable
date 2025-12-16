package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.service.PlayerIdentityCacheService;
import de.galacticfy.core.service.SessionService;
import de.galacticfy.core.service.SessionService.SessionInfo;
import net.kyori.adventure.text.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public class SeenCommand implements SimpleCommand {

    private static final String PERM_SEEN = "galacticfy.core.seen";

    private final ProxyServer proxy;
    private final GalacticfyPermissionService perms;
    private final SessionService sessions;
    private final PlayerIdentityCacheService identityCache;

    private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
            .withLocale(Locale.GERMANY)
            .withZone(ZoneId.systemDefault());

    public SeenCommand(ProxyServer proxy,
                       GalacticfyPermissionService perms,
                       SessionService sessions,
                       PlayerIdentityCacheService identityCache) {
        this.proxy = proxy;
        this.perms = perms;
        this.sessions = sessions;
        this.identityCache = identityCache;
    }

    private Component prefix() {
        return Component.text("§8[§bGalacticfy§8] §r");
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!hasPermission(invocation)) {
            src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
            return;
        }

        if (args.length < 1) {
            src.sendMessage(prefix().append(Component.text("§eBenutzung: §b/seen <spieler>")));
            return;
        }

        String targetName = args[0];

        // 1) Online-Check
        Optional<Player> online = proxy.getPlayer(targetName);
        UUID uuid;
        String displayName;

        if (online.isPresent()) {
            Player p = online.get();
            uuid = p.getUniqueId();
            displayName = p.getUsername();
        } else {
            // 2) Offline-Resolution via Cache/DB
            if (identityCache == null) {
                src.sendMessage(prefix().append(Component.text(
                        "§cOffline-Lookup nicht verfügbar (identityCache ist null)."
                )));
                return;
            }

            Optional<UUID> resolved = identityCache.findUuidByName(targetName);
            if (resolved.isEmpty()) {
                src.sendMessage(prefix().append(Component.text(
                        "§7Der Spieler §e" + targetName + " §7war entweder noch nie online oder ist nicht in der Datenbank."
                )));
                return;
            }

            uuid = resolved.get();
            displayName = identityCache.findNameByUuid(uuid).orElse(targetName);
        }

        SessionInfo info = sessions.getSession(uuid);

        src.sendMessage(Component.text(" "));
        src.sendMessage(prefix().append(Component.text("§b/seen §7Informationen für §f" + displayName)));
        if (info == null) {
            src.sendMessage(Component.text("§7Keine Session-Daten gefunden."));
            src.sendMessage(Component.text(" "));
            return;
        }

        src.sendMessage(Component.text("§8» §7UUID: §f" + info.uuid()));
        src.sendMessage(Component.text("§8» §7Erster Login: §f" + (info.firstLogin() != null ? fmt.format(info.firstLogin()) : "unbekannt")));
        src.sendMessage(Component.text("§8» §7Letzter Login: §f" + (info.lastLogin() != null ? fmt.format(info.lastLogin()) : "unbekannt")));
        src.sendMessage(Component.text("§8» §7Letzter Logout: §f" + (info.lastLogout() != null ? fmt.format(info.lastLogout()) : "unbekannt")));
        src.sendMessage(Component.text("§8» §7Gesamtspielzeit: §f" + SessionService.formatDuration(info.totalPlaySeconds())));
        src.sendMessage(Component.text("§8» §7Letzter Server: §f" + (info.lastServer() != null ? info.lastServer() : "unbekannt")));
        src.sendMessage(Component.text(" "));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        CommandSource src = invocation.source();
        if (src instanceof Player p) {
            return perms != null ? perms.hasPluginPermission(p, PERM_SEEN) : p.hasPermission(PERM_SEEN);
        }
        return true;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        // /seen <tab> => online Spieler (wie vorher)
        if (args.length == 0) {
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }

        if (args.length == 1) {
            String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }

        return List.of();
    }
}
