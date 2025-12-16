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
import de.galacticfy.core.service.PunishmentService;
import de.galacticfy.core.service.PunishmentService.Punishment;
import de.galacticfy.core.util.DiscordWebhookNotifier;
import net.kyori.adventure.text.Component;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.stream.Collectors;

public class WarnCommand implements SimpleCommand {

    private static final String PERM_WARN = "galacticfy.punish.warn";
    private static final String PERM_PUNISH_PROTECT = "galacticfy.punish.protect";

    private final ProxyServer proxy;
    private final GalacticfyPermissionService perms;
    private final PunishmentService punishmentService;
    private final DiscordWebhookNotifier webhook;
    private final PlayerIdentityCacheService identityCache;

    public WarnCommand(ProxyServer proxy,
                       GalacticfyPermissionService perms,
                       PunishmentService punishmentService,
                       DiscordWebhookNotifier webhook,
                       PlayerIdentityCacheService identityCache) {
        this.proxy = proxy;
        this.perms = perms;
        this.punishmentService = punishmentService;
        this.webhook = webhook;
        this.identityCache = identityCache;
    }

    private Component prefix() {
        return Component.text("§8[§bGalacticfy§8] §r");
    }

    private boolean hasWarnPermission(CommandSource src) {
        if (src instanceof Player p) {
            if (perms != null) return perms.hasPluginPermission(p, PERM_WARN);
            return p.hasPermission(PERM_WARN);
        }
        return true;
    }

    private boolean isProtected(Player target) {
        if (perms != null) return perms.hasPluginPermission(target, PERM_PUNISH_PROTECT);
        return target.hasPermission(PERM_PUNISH_PROTECT);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!hasWarnPermission(src)) {
            src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
            return;
        }

        if (args.length < 2) {
            src.sendMessage(prefix().append(Component.text("§eBenutzung: §b/warn <spieler> <preset|grund...>")));
            return;
        }

        String targetInput = args[0];
        String reasonOrPreset = args[1];

        Player target = proxy.getPlayer(targetInput).orElse(null);
        if (target != null && isProtected(target)) {
            src.sendMessage(prefix().append(Component.text("§cDu kannst diesen Spieler nicht warnen (Team-Schutz aktiv).")));
            return;
        }

        String staffName = (src instanceof Player p) ? p.getUsername() : "Konsole";

        UUID uuid = null;
        String storedName = targetInput;

        if (target != null) {
            uuid = target.getUniqueId();
            storedName = target.getUsername();
        } else {
            if (identityCache != null) {
                uuid = identityCache.findUuidByName(targetInput).orElse(null);
                if (uuid != null) storedName = identityCache.findNameByUuid(uuid).orElse(storedName);
            }
        }

        // Grund/Preset auflösen
        String reason;
        Preset preset = ReasonPresets.find(reasonOrPreset.toLowerCase(Locale.ROOT));
        if (preset != null) {
            if (args.length > 2) {
                String extra = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                reason = preset.display() + " §8(§7" + extra + "§8)";
            } else {
                reason = preset.display();
            }
        } else {
            reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        }

        // IP besorgen (WICHTIG: dein PunishmentService.warnPlayer braucht ip)
        String ip = null;
        if (target != null) {
            ip = ipOf(target);
        }
        if (ip == null || ip.isBlank()) {
            // Offline / kein RemoteAddress -> DB-Fallback
            ip = punishmentService.getLastKnownIp(uuid, storedName);
        }
        if (ip == null || ip.isBlank()) {
            // Notfalls leer speichern (dein createPunishment setzt ip dann NULL)
            ip = null;
        }

        Punishment warn = punishmentService.warnPlayer(uuid, storedName, ip, reason, staffName);
        if (warn == null) {
            src.sendMessage(prefix().append(Component.text("§cKonnte Warn nicht speichern (DB-Fehler).")));
            return;
        }

        src.sendMessage(Component.text(" "));
        src.sendMessage(Component.text(PunishDesign.BIG_HEADER_WARN));
        src.sendMessage(Component.text(" "));
        src.sendMessage(Component.text("§7Spieler: §f" + storedName));
        src.sendMessage(Component.text("§7Grund: §f" + reason));
        src.sendMessage(Component.text("§7Von: §f" + staffName));
        src.sendMessage(Component.text(PunishDesign.LINE));
        src.sendMessage(Component.text(" "));

        if (target != null) {
            target.sendMessage(Component.text(
                    "§e§lGalacticfy §8» §eDu hast eine Verwarnung erhalten.\n" +
                            "§7Grund: §f" + reason + "\n" +
                            "§7Von: §b" + staffName
            ));
        }

        // optional webhook
        if (webhook != null && webhook.isEnabled()) {
            try {
                webhook.getClass().getMethod("sendWarn", Punishment.class).invoke(webhook, warn);
            } catch (Throwable ignored) {}
        }
    }

    private String ipOf(Player player) {
        try {
            Object remoteObj = player.getRemoteAddress();
            if (remoteObj instanceof InetSocketAddress isa) {
                InetAddress addr = isa.getAddress();
                if (addr != null) return addr.getHostAddress();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return hasWarnPermission(invocation.source());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!hasWarnPermission(invocation.source())) return List.of();

        String[] args = invocation.arguments();

        if (args.length == 0) return suggestNames("");
        if (args.length == 1) return suggestNames(args[0]);

        if (args.length == 2) {
            String prefix = (args[1] == null ? "" : args[1]).toLowerCase(Locale.ROOT);
            return ReasonPresets.tabComplete(prefix);
        }

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

        return out.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .limit(40)
                .collect(Collectors.toList());
    }
}
