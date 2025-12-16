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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BanIpCommand implements SimpleCommand {

    private static final String PERM_BANIP = "galacticfy.punish.banip";
    private static final String PERM_PUNISH_PROTECT = "galacticfy.punish.protect";

    // einfache IPv4 Prüfung (genug für Lobby/Proxy Usecase)
    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$"
    );

    private final ProxyServer proxy;
    private final GalacticfyPermissionService perms;
    private final PunishmentService punishmentService;
    private final DiscordWebhookNotifier webhook;
    private final PlayerIdentityCacheService identityCache;

    public BanIpCommand(ProxyServer proxy,
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

    private boolean hasBanIpPermission(CommandSource src) {
        if (src instanceof Player p) {
            if (perms != null) return perms.hasPluginPermission(p, PERM_BANIP);
            return p.hasPermission(PERM_BANIP);
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

        if (!hasBanIpPermission(src)) {
            src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
            return;
        }

        if (args.length < 2) {
            src.sendMessage(prefix().append(Component.text("§eBenutzung: §b/banip <Spieler|IP> <Dauer|Preset> [Grund...]")));
            return;
        }

        String targetOrIp = args[0];
        String durationOrPreset = args[1].toLowerCase(Locale.ROOT);

        // Dauer/Preset auflösen
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
                            "§cUngültige Dauer oder Preset! Beispiele: §b30m§7, §b1h§7, §b7d§7, §bspam§7, §bhackclient§7, §bperm"
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

        String staffName = (src instanceof Player p) ? p.getUsername() : "Konsole";

        // IP bestimmen: entweder direkt IP oder Spielername → IP (online/offline)
        String ip = null;
        UUID uuid = null;
        String storedName = targetOrIp;

        if (looksLikeIp(targetOrIp)) {
            ip = normalizeIp(targetOrIp);
            storedName = "-";
        } else {
            Player target = proxy.getPlayer(targetOrIp).orElse(null);

            if (target != null) {
                if (isProtected(target)) {
                    src.sendMessage(prefix().append(Component.text("§cDu kannst diesen Spieler nicht bannen (Team-Schutz aktiv).")));
                    return;
                }

                uuid = target.getUniqueId();
                storedName = target.getUsername();
                ip = ipOf(target);
            } else {
                // Offline: UUID+Name via Cache/DB
                if (identityCache != null) {
                    uuid = identityCache.findUuidByName(targetOrIp).orElse(null);
                    if (uuid != null) storedName = identityCache.findNameByUuid(uuid).orElse(storedName);
                }
                // Offline IP aus DB (wie /check)
                ip = punishmentService.getLastKnownIp(uuid, storedName);
            }
        }

        if (ip == null || ip.isBlank()) {
            src.sendMessage(prefix().append(Component.text("§cKeine IP gefunden. Spieler muss online sein oder eine LastKnownIp in der DB haben.")));
            return;
        }

        // IP-Ban in DB speichern (robust per Reflection)
        Punishment p = invokeIpBan(punishmentService, uuid, storedName, ip, reason, staffName, durationMs);
        if (p == null) {
            src.sendMessage(prefix().append(Component.text("§cKonnte IP-Ban nicht speichern (DB-Fehler oder Methode fehlt).")));
            return;
        }

        String durText = (p.expiresAt == null) ? "§cPermanent" : "§e" + punishmentService.formatRemaining(p);

        src.sendMessage(Component.text(" "));
        src.sendMessage(Component.text(getIpBanHeader()));
        src.sendMessage(Component.text(" "));
        src.sendMessage(Component.text("§7IP: §f" + ip));
        if (uuid != null) {
            src.sendMessage(Component.text("§7Spieler: §f" + storedName));
            src.sendMessage(Component.text("§7UUID: §f" + uuid));
        } else if (!storedName.equals("-")) {
            src.sendMessage(Component.text("§7Spieler: §f" + storedName));
        }
        src.sendMessage(Component.text("§7Grund: §f" + reason));
        src.sendMessage(Component.text("§7Dauer: " + durText));
        if (presetKeyUsed != null) {
            src.sendMessage(Component.text("§7Preset: §b" + presetKeyUsed));
        }
        src.sendMessage(Component.text("§7Von: §f" + staffName));
        src.sendMessage(Component.text(PunishDesign.LINE));
        src.sendMessage(Component.text(" "));

        // Alle Online-Spieler mit derselben IP disconnecten
        disconnectAllWithIp(ip, reason, staffName, p);

        // Webhook optional
        if (webhook != null && webhook.isEnabled()) {
            trySendWebhookIpBan(p);
        }
    }

    private void disconnectAllWithIp(String ip, String reason, String staffName, Punishment p) {
        String remaining = (p.expiresAt == null) ? "§cPermanent" : "§e" + punishmentService.formatRemaining(p);

        Component msg = Component.text(
                "§c§lGalacticfy §8» §cDu wurdest IP-gebannt.\n" +
                        "§7Grund: §f" + reason + "\n" +
                        "§7Dauer: " + remaining + "\n" +
                        "§7Von: §b" + staffName + "\n" +
                        " \n" +
                        "§7Falls du der Meinung bist, dass dieser Ban §cunberechtigt §7ist,\n" +
                        "§7kannst du einen §bEntbannungsantrag §7auf unserem Discord stellen."
        );

        proxy.getAllPlayers().forEach(pl -> {
            String otherIp = ipOf(pl);
            if (otherIp != null && otherIp.equals(ip)) {
                pl.disconnect(msg);
            }
        });
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

    private boolean looksLikeIp(String s) {
        if (s == null) return false;
        String x = s.trim();
        return IPV4.matcher(x).matches();
    }

    private String normalizeIp(String s) {
        return s.trim();
    }

    private Punishment invokeIpBan(PunishmentService svc,
                                   UUID uuid, String name, String ip,
                                   String reason, String staff,
                                   Long durationMs) {

        // Häufige Varianten:
        // banIp(uuid, name, ip, reason, staff, durationMs)
        // banIpPlayer(uuid, name, ip, reason, staff, durationMs)
        // ipBan(...)

        Object r;

        r = tryCallAny(svc, "banIp",
                new Class[]{UUID.class, String.class, String.class, String.class, String.class, Long.class},
                new Object[]{uuid, name, ip, reason, staff, durationMs});
        if (r instanceof Punishment p) return p;

        r = tryCallAny(svc, "banIpPlayer",
                new Class[]{UUID.class, String.class, String.class, String.class, String.class, Long.class},
                new Object[]{uuid, name, ip, reason, staff, durationMs});
        if (r instanceof Punishment p2) return p2;

        r = tryCallAny(svc, "ipBan",
                new Class[]{UUID.class, String.class, String.class, String.class, String.class, Long.class},
                new Object[]{uuid, name, ip, reason, staff, durationMs});
        if (r instanceof Punishment p3) return p3;

        // primitive long Varianten
        if (durationMs != null) {
            r = tryCallAny(svc, "banIp",
                    new Class[]{UUID.class, String.class, String.class, String.class, String.class, long.class},
                    new Object[]{uuid, name, ip, reason, staff, durationMs.longValue()});
            if (r instanceof Punishment p4) return p4;

            r = tryCallAny(svc, "banIpPlayer",
                    new Class[]{UUID.class, String.class, String.class, String.class, String.class, long.class},
                    new Object[]{uuid, name, ip, reason, staff, durationMs.longValue()});
            if (r instanceof Punishment p5) return p5;

            r = tryCallAny(svc, "ipBan",
                    new Class[]{UUID.class, String.class, String.class, String.class, String.class, long.class},
                    new Object[]{uuid, name, ip, reason, staff, durationMs.longValue()});
            if (r instanceof Punishment p6) return p6;
        }

        // Fallback: nur IP (wenn dein Service das so anbietet)
        r = tryCallAny(svc, "banIp",
                new Class[]{String.class, String.class, String.class, Long.class},
                new Object[]{ip, reason, staff, durationMs});
        if (r instanceof Punishment p7) return p7;

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

    private void trySendWebhookIpBan(Punishment ban) {
        // Wenn du sendIpBan(...) hast, wird es genutzt.
        try {
            Method m = webhook.getClass().getMethod("sendIpBan", Punishment.class);
            m.invoke(webhook, ban);
        } catch (Throwable ignored) {
            // Falls du kein sendIpBan hast: nichts tun.
        }
    }

    private String getIpBanHeader() {
        // Wenn du in PunishDesign z.B. BIG_HEADER_IPBAN hast, nutzen wir es.
        // Sonst fallback auf BIG_HEADER_BAN.
        try {
            Field f = PunishDesign.class.getDeclaredField("BIG_HEADER_IPBAN");
            Object v = f.get(null);
            if (v instanceof String s && !s.isBlank()) return s;
        } catch (Throwable ignored) {}
        return PunishDesign.BIG_HEADER_BAN;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return hasBanIpPermission(invocation.source());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!hasBanIpPermission(invocation.source())) return List.of();

        String[] args = invocation.arguments();

        // /banip <spieler|ip>
        if (args.length == 0) return suggestTarget("");
        if (args.length == 1) return suggestTarget(args[0]);

        // /banip <spieler|ip> <dauer|preset>
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

    private List<String> suggestTarget(String prefixRaw) {
        String prefix = (prefixRaw == null ? "" : prefixRaw).toLowerCase(Locale.ROOT);

        LinkedHashSet<String> out = new LinkedHashSet<>();

        // Online Spieler
        proxy.getAllPlayers().forEach(p -> {
            String n = p.getUsername();
            if (n != null && n.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(n);
        });

        // bekannte Namen aus DB (wie /check)
        try {
            out.addAll(punishmentService.findKnownNames(prefix, 25));
        } catch (Throwable ignored) {}

        // Hinweis: IPs tabben wir bewusst nicht aus DB (zu sensibel + unnötig)

        return out.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .limit(40)
                .collect(Collectors.toList());
    }
}
