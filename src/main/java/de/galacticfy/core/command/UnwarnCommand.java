package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.service.PlayerIdentityCacheService;
import de.galacticfy.core.service.PunishmentService;
import net.kyori.adventure.text.Component;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

public class UnwarnCommand implements SimpleCommand {

    private static final String PERM_UNWARN = "galacticfy.punish.unwarn";

    private final ProxyServer proxy;
    private final PunishmentService punishmentService;
    private final GalacticfyPermissionService perms;
    private final PlayerIdentityCacheService identityCache;

    public UnwarnCommand(ProxyServer proxy,
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

    private boolean hasUnwarnPermission(CommandSource src) {
        if (src instanceof Player p) {
            if (perms != null) return perms.hasPluginPermission(p, PERM_UNWARN);
            return p.hasPermission(PERM_UNWARN);
        }
        return true;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!hasUnwarnPermission(src)) {
            src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
            return;
        }

        if (args.length < 2) {
            src.sendMessage(prefix().append(Component.text("§eBenutzung: §b/unwarn <spieler> <id|letzte>")));
            return;
        }

        String targetName = args[0];
        String idArg = args[1];

        UUID uuid = null;
        String storedName = targetName;

        // UUID resolve
        proxy.getPlayer(targetName).ifPresent(p -> {
            // noop: wir lesen unten
        });

        Player online = proxy.getPlayer(targetName).orElse(null);
        if (online != null) {
            uuid = online.getUniqueId();
            storedName = online.getUsername();
        } else {
            if (identityCache != null) {
                uuid = identityCache.findUuidByName(targetName).orElse(null);
                if (uuid != null) storedName = identityCache.findNameByUuid(uuid).orElse(storedName);
            }
        }

        String staffName = (src instanceof Player p) ? p.getUsername() : "Konsole";

        boolean ok;
        if (idArg.equalsIgnoreCase("letzte") || idArg.equalsIgnoreCase("last")) {
            ok = invokeUnwarnLast(punishmentService, uuid, storedName, staffName);
        } else {
            long id;
            try {
                id = Long.parseLong(idArg);
            } catch (NumberFormatException ex) {
                src.sendMessage(prefix().append(Component.text("§cUngültige ID. Nutze eine Zahl oder 'letzte'.")));
                return;
            }
            ok = invokeUnwarnById(punishmentService, uuid, storedName, id, staffName);
        }

        if (ok) {
            src.sendMessage(prefix().append(Component.text("§aWarn wurde entfernt für §f" + storedName + "§a.")));
        } else {
            src.sendMessage(prefix().append(Component.text("§cKonnte Warn nicht entfernen (nicht gefunden oder DB-Fehler).")));
        }
    }

    private boolean invokeUnwarnById(PunishmentService svc, UUID uuid, String name, long id, String staff) {
        // Varianten:
        // unwarn(uuid, name, id, staff)
        // unwarnById(uuid, id, staff)
        // removeWarn(uuid, id, staff)
        // unwarn(name, id, staff) etc.
        Boolean r;

        if (uuid != null) {
            r = tryCallBoolean(svc, "unwarn",
                    new Class[]{UUID.class, String.class, long.class, String.class},
                    new Object[]{uuid, name, id, staff});
            if (r != null) return r;

            r = tryCallBoolean(svc, "unwarnById",
                    new Class[]{UUID.class, long.class, String.class},
                    new Object[]{uuid, id, staff});
            if (r != null) return r;

            r = tryCallBoolean(svc, "removeWarn",
                    new Class[]{UUID.class, long.class, String.class},
                    new Object[]{uuid, id, staff});
            if (r != null) return r;
        }

        r = tryCallBoolean(svc, "unwarn",
                new Class[]{String.class, long.class, String.class},
                new Object[]{name, id, staff});
        if (r != null) return r;

        r = tryCallBoolean(svc, "unwarnById",
                new Class[]{String.class, long.class, String.class},
                new Object[]{name, id, staff});
        if (r != null) return r;

        r = tryCallBoolean(svc, "removeWarn",
                new Class[]{String.class, long.class, String.class},
                new Object[]{name, id, staff});
        if (r != null) return r;

        return false;
    }

    private boolean invokeUnwarnLast(PunishmentService svc, UUID uuid, String name, String staff) {
        // Varianten:
        // unwarnLast(uuid, name, staff)
        // removeLastWarn(uuid, staff)
        // unwarnLast(name, staff)
        Boolean r;

        if (uuid != null) {
            r = tryCallBoolean(svc, "unwarnLast",
                    new Class[]{UUID.class, String.class, String.class},
                    new Object[]{uuid, name, staff});
            if (r != null) return r;

            r = tryCallBoolean(svc, "removeLastWarn",
                    new Class[]{UUID.class, String.class},
                    new Object[]{uuid, staff});
            if (r != null) return r;
        }

        r = tryCallBoolean(svc, "unwarnLast",
                new Class[]{String.class, String.class},
                new Object[]{name, staff});
        if (r != null) return r;

        r = tryCallBoolean(svc, "removeLastWarn",
                new Class[]{String.class, String.class},
                new Object[]{name, staff});
        if (r != null) return r;

        return false;
    }

    private Boolean tryCallBoolean(Object target, String method, Class[] sig, Object[] args) {
        try {
            Method m = target.getClass().getMethod(method, sig);
            Object out = m.invoke(target, args);

            if (out == null) return false;
            if (out instanceof Boolean b) return b;
            if (out instanceof Integer i) return i > 0;
            if (out instanceof Long l) return l > 0L;

            if (out instanceof String s) {
                String x = s.toLowerCase(Locale.ROOT);
                return x.contains("ok") || x.contains("success") || x.contains("true");
            }
            return true;

        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return hasUnwarnPermission(invocation.source());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!hasUnwarnPermission(invocation.source())) return List.of();

        String[] args = invocation.arguments();

        // /unwarn <name>
        if (args.length == 0) return suggestNames("");
        if (args.length == 1) return suggestNames(args[0]);

        // /unwarn <name> <id|letzte>
        if (args.length == 2) {
            String p = (args[1] == null ? "" : args[1]).toLowerCase(Locale.ROOT);
            List<String> base = List.of("letzte");
            if (p.isEmpty()) return base;
            return base.stream().filter(x -> x.startsWith(p)).collect(Collectors.toList());
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

        return out.stream().sorted(String.CASE_INSENSITIVE_ORDER).limit(40).collect(Collectors.toList());
    }
}
