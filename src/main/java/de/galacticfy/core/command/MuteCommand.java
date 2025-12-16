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

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.stream.Collectors;

public class MuteCommand implements SimpleCommand {

    private static final String PERM_MUTE = "galacticfy.punish.mute";
    private static final String PERM_PUNISH_PROTECT = "galacticfy.punish.protect";

    private final ProxyServer proxy;
    private final GalacticfyPermissionService perms;
    private final PunishmentService punishmentService;
    private final DiscordWebhookNotifier webhook;
    private final PlayerIdentityCacheService identityCache;

    public MuteCommand(ProxyServer proxy,
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

    private boolean hasMutePermission(CommandSource src) {
        if (src instanceof Player p) {
            if (perms != null) return perms.hasPluginPermission(p, PERM_MUTE);
            return p.hasPermission(PERM_MUTE);
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

        if (!hasMutePermission(src)) {
            src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
            return;
        }

        if (args.length < 2) {
            src.sendMessage(prefix().append(Component.text("§eBenutzung: §b/mute <Spieler> <Dauer|Preset> [Grund...]")));
            return;
        }

        String targetName = args[0];
        String durationOrPreset = args[1].toLowerCase(Locale.ROOT);

        Player target = proxy.getPlayer(targetName).orElse(null);

        // Selbst-Mute verhindern
        if (src instanceof Player staff) {
            if (target != null && staff.getUniqueId().equals(target.getUniqueId())) {
                staff.sendMessage(prefix().append(Component.text("§cDu kannst dich nicht selbst muten.")));
                return;
            }
            if (target == null && staff.getUsername().equalsIgnoreCase(targetName)) {
                staff.sendMessage(prefix().append(Component.text("§cDu kannst dich nicht selbst muten.")));
                return;
            }
        }

        // Mute-Schutz (nur online)
        if (target != null && isProtected(target)) {
            src.sendMessage(prefix().append(Component.text("§cDu kannst diesen Spieler nicht muten (Team-Schutz aktiv).")));
            return;
        }

        Long durationMs;
        String reason;
        String presetKeyUsed = null;

        Preset preset = ReasonPresets.find(durationOrPreset);
        if (preset != null) {
            durationMs = preset.defaultDurationMs();
            presetKeyUsed = preset.key();

            if (args.length > 2) {
                String extra = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                reason = preset.display() + " §8(§7" + extra + "§8)";
            } else {
                reason = preset.display();
            }
        } else {
            if (durationOrPreset.equals("perm") || durationOrPreset.equals("permanent")) {
                durationMs = null;
            } else {
                durationMs = punishmentService.parseDuration(durationOrPreset);
                if (durationMs == null) {
                    src.sendMessage(prefix().append(Component.text(
                            "§cUngültige Dauer oder Preset! Beispiele: §b10m§7, §b1h§7, §b7d§7, §bspam§7, §bbeleidigung§7, §bperm"
                    )));
                    return;
                }
            }

            if (args.length > 2) {
                reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            } else {
                reason = "Kein Grund angegeben";
            }
        }

        UUID uuid = null;
        String storedName = targetName;
        String ip = null;

        if (target != null) {
            uuid = target.getUniqueId();
            storedName = target.getUsername();

            Object remoteObj = target.getRemoteAddress();
            if (remoteObj instanceof InetSocketAddress isa && isa.getAddress() != null) {
                ip = isa.getAddress().getHostAddress();
            }
        } else {
            // Offline -> UUID+Name via Cache/DB
            if (identityCache != null) {
                uuid = identityCache.findUuidByName(targetName).orElse(null);
                if (uuid != null) {
                    storedName = identityCache.findNameByUuid(uuid).orElse(storedName);
                }
            }
            ip = punishmentService.getLastKnownIp(uuid, storedName);
        }

        String staffName = (src instanceof Player p) ? p.getUsername() : "Konsole";

        // Mute speichern (robust: mehrere mögliche Methodennamen)
        Punishment mute = invokeMute(punishmentService, uuid, storedName, ip, reason, staffName, durationMs);
        if (mute == null) {
            src.sendMessage(prefix().append(Component.text("§cKonnte Mute nicht speichern (DB-Fehler oder Methode fehlt).")));
            return;
        }

        String durText = (mute.expiresAt == null) ? "§cPermanent" : "§e" + punishmentService.formatRemaining(mute);

        src.sendMessage(Component.text(" "));
        src.sendMessage(Component.text(PunishDesign.BIG_HEADER_MUTE));
        src.sendMessage(Component.text(" "));
        src.sendMessage(Component.text("§7Spieler: §f" + storedName));
        src.sendMessage(Component.text("§7Grund: §f" + reason));
        src.sendMessage(Component.text("§7Dauer: " + durText));
        if (presetKeyUsed != null) {
            src.sendMessage(Component.text("§7Preset: §b" + presetKeyUsed));
        }
        src.sendMessage(Component.text("§7Von: §f" + staffName));
        src.sendMessage(Component.text(PunishDesign.LINE));
        src.sendMessage(Component.text(" "));

        // Optional: Spieler informieren (online)
        if (target != null) {
            String remaining = (mute.expiresAt == null) ? "§cPermanent" : "§e" + punishmentService.formatRemaining(mute);
            target.sendMessage(Component.text(
                    "§c§lGalacticfy §8» §cDu wurdest gemutet.\n" +
                            "§7Grund: §f" + reason + "\n" +
                            "§7Dauer: " + remaining + "\n" +
                            "§7Von: §b" + staffName
            ));
        }

        // Webhook optional
        if (webhook != null && webhook.isEnabled()) {
            trySendWebhookMute(mute);
        }
    }

    private Punishment invokeMute(PunishmentService svc,
                                  UUID uuid, String name, String ip,
                                  String reason, String staff,
                                  Long durationMs) {

        // Häufige Varianten:
        // mutePlayer(uuid, name, ip, reason, staff, durationMs)
        // mute(uuid, name, ip, reason, staff, durationMs)
        Object r;

        r = tryCallAny(svc, "mutePlayer",
                new Class[]{UUID.class, String.class, String.class, String.class, String.class, Long.class},
                new Object[]{uuid, name, ip, reason, staff, durationMs});
        if (r instanceof Punishment p) return p;

        r = tryCallAny(svc, "mute",
                new Class[]{UUID.class, String.class, String.class, String.class, String.class, Long.class},
                new Object[]{uuid, name, ip, reason, staff, durationMs});
        if (r instanceof Punishment p2) return p2;

        // Manche Services verwenden primitive long
        if (durationMs != null) {
            r = tryCallAny(svc, "mutePlayer",
                    new Class[]{UUID.class, String.class, String.class, String.class, String.class, long.class},
                    new Object[]{uuid, name, ip, reason, staff, durationMs.longValue()});
            if (r instanceof Punishment p3) return p3;

            r = tryCallAny(svc, "mute",
                    new Class[]{UUID.class, String.class, String.class, String.class, String.class, long.class},
                    new Object[]{uuid, name, ip, reason, staff, durationMs.longValue()});
            if (r instanceof Punishment p4) return p4;
        }

        return null;
    }

    private Object tryCallAny(Object target, String method, Class[] sig, Object[] args) {
        try {
            Method m = target.getClass().getMethod(method, sig);
            return m.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void trySendWebhookMute(Punishment mute) {
        // wenn du in DiscordWebhookNotifier bereits sendMute(...) hast, wird es genutzt.
        try {
            Method m = webhook.getClass().getMethod("sendMute", Punishment.class);
            m.invoke(webhook, mute);
        } catch (Throwable ignored) {
            // Falls du kein sendMute hast: einfach nichts tun.
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return hasMutePermission(invocation.source());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!hasMutePermission(invocation.source())) return List.of();

        String[] args = invocation.arguments();

        // /mute <name>
        if (args.length == 0) return suggestNames("");
        if (args.length == 1) return suggestNames(args[0]);

        // /mute <name> <dauer|preset>
        if (args.length == 2) {
            String prefix = (args[1] == null ? "" : args[1]).toLowerCase(Locale.ROOT);

            List<String> base = Arrays.asList("10m", "30m", "1h", "6h", "1d", "7d", "30d", "perm");
            ArrayList<String> out = new ArrayList<>();

            base.stream().filter(s -> s.startsWith(prefix)).forEach(out::add);
            out.addAll(ReasonPresets.tabComplete(prefix));

            return out;
        }

        return List.of();
    }

    private List<String> suggestNames(String prefixRaw) {
        String prefix = (prefixRaw == null ? "" : prefixRaw).toLowerCase(Locale.ROOT);

        LinkedHashSet<String> out = new LinkedHashSet<>();

        // Online
        proxy.getAllPlayers().forEach(p -> {
            String n = p.getUsername();
            if (n != null && n.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(n);
        });

        // DB bekannte Namen (wie bei /check)
        try {
            out.addAll(punishmentService.findKnownNames(prefix, 30));
        } catch (Throwable ignored) {}

        return out.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .limit(40)
                .collect(Collectors.toList());
    }
}
