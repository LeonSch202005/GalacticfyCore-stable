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

public class UnbanCommand implements SimpleCommand {

    private static final String PERM_UNBAN = "galacticfy.punish.unban";

    private final PunishmentService punishmentService;
    private final GalacticfyPermissionService perms;
    private final ProxyServer proxy;
    private final PlayerIdentityCacheService identityCache;

    // Kompatibilität: alter Konstruktor bleibt (falls du ihn noch nutzt)
    public UnbanCommand(PunishmentService punishmentService,
                        GalacticfyPermissionService perms,
                        ProxyServer proxy) {
        this(punishmentService, perms, proxy, null);
    }

    // Neuer Konstruktor (mit Cache)
    public UnbanCommand(PunishmentService punishmentService,
                        GalacticfyPermissionService perms,
                        ProxyServer proxy,
                        PlayerIdentityCacheService identityCache) {
        this.punishmentService = punishmentService;
        this.perms = perms;
        this.proxy = proxy;
        this.identityCache = identityCache;
    }

    private Component prefix() {
        return Component.text("§8[§bGalacticfy§8] §r");
    }

    private boolean hasUnbanPermission(CommandSource src) {
        if (src instanceof Player p) {
            if (perms != null) return perms.hasPluginPermission(p, PERM_UNBAN);
            return p.hasPermission(PERM_UNBAN);
        }
        return true;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!hasUnbanPermission(src)) {
            src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
            return;
        }

        if (args.length < 1) {
            src.sendMessage(prefix().append(Component.text("§eBenutzung: §b/unban <spieler>")));
            return;
        }

        String inputName = args[0];
        String staffName = (src instanceof Player p) ? p.getUsername() : "Konsole";

        UUID uuid = null;
        String storedName = inputName;

        // Offline-Resolution über Cache/DB (gf_sessions)
        if (identityCache != null) {
            Optional<UUID> resolved = identityCache.findUuidByName(inputName);
            if (resolved.isPresent()) {
                uuid = resolved.get();
                storedName = identityCache.findNameByUuid(uuid).orElse(storedName);
            }
        }

        boolean ok = invokeUnban(punishmentService, uuid, storedName, staffName);

        if (ok) {
            src.sendMessage(prefix().append(Component.text("§a" + storedName + " wurde entbannt.")));
        } else {
            src.sendMessage(prefix().append(Component.text("§cKonnte " + storedName + " nicht entbannen (nicht gefunden oder DB-Fehler).")));
        }
    }

    /**
     * Versucht mehrere mögliche Service-Methoden aufzurufen.
     */
    private boolean invokeUnban(PunishmentService svc, UUID uuid, String name, String staff) {
        if (uuid != null) {
            Boolean r;

            r = tryCallBoolean(svc, "unban", new Class[]{UUID.class, String.class, String.class}, new Object[]{uuid, name, staff});
            if (r != null) return r;

            r = tryCallBoolean(svc, "unbanPlayer", new Class[]{UUID.class, String.class, String.class}, new Object[]{uuid, name, staff});
            if (r != null) return r;

            r = tryCallBoolean(svc, "unban", new Class[]{UUID.class, String.class}, new Object[]{uuid, staff});
            if (r != null) return r;

            r = tryCallBoolean(svc, "unbanPlayer", new Class[]{UUID.class, String.class}, new Object[]{uuid, staff});
            if (r != null) return r;

            r = tryCallBoolean(svc, "unban", new Class[]{UUID.class, String.class}, new Object[]{uuid, name});
            if (r != null) return r;

            r = tryCallBoolean(svc, "unbanPlayer", new Class[]{UUID.class, String.class}, new Object[]{uuid, name});
            if (r != null) return r;
        }

        Boolean r;

        r = tryCallBoolean(svc, "unbanByName", new Class[]{String.class, String.class}, new Object[]{name, staff});
        if (r != null) return r;

        r = tryCallBoolean(svc, "unban", new Class[]{String.class, String.class}, new Object[]{name, staff});
        if (r != null) return r;

        r = tryCallBoolean(svc, "unbanPlayer", new Class[]{String.class, String.class}, new Object[]{name, staff});
        if (r != null) return r;

        r = tryCallBoolean(svc, "unbanByName", new Class[]{String.class}, new Object[]{name});
        if (r != null) return r;

        r = tryCallBoolean(svc, "unban", new Class[]{String.class}, new Object[]{name});
        if (r != null) return r;

        r = tryCallBoolean(svc, "unbanPlayer", new Class[]{String.class}, new Object[]{name});
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

    // =========================
    // TAB COMPLETE (Velocity)
    // =========================
    @Override
    public List<String> suggest(Invocation invocation) {
        CommandSource src = invocation.source();
        if (!hasUnbanPermission(src)) return List.of();

        String[] args = invocation.arguments();

        // /unban <name>
        if (args.length == 0) {
            return suggestNames("");
        }
        if (args.length == 1) {
            String prefix = args[0] == null ? "" : args[0];
            return suggestNames(prefix);
        }

        return List.of();
    }

    private List<String> suggestNames(String prefixRaw) {
        String prefix = (prefixRaw == null ? "" : prefixRaw).toLowerCase(Locale.ROOT);

        LinkedHashSet<String> out = new LinkedHashSet<>();

        // 1) Online Spieler (schnell)
        if (proxy != null) {
            proxy.getAllPlayers().forEach(p -> {
                String n = p.getUsername();
                if (n != null && n.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(n);
                }
            });
        }

        // 2) Aktiv gebannte Namen (falls Service sowas hat)
        out.addAll(tryGetActiveBannedNames(prefix, 25));

        // 3) Fallback: bekannte Namen aus DB (die du schon bei /check nutzt)
        out.addAll(tryFindKnownNames(prefix, 25));

        // Sortiert zurückgeben
        return out.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .limit(40)
                .collect(Collectors.toList());
    }

    private List<String> tryGetActiveBannedNames(String prefix, int limit) {
        // Wir versuchen verschiedene Methodennamen:
        // - getActiveBannedNames(prefix, limit)
        // - getActiveBansNames(prefix, limit)
        // - getBannedNames(prefix, limit)
        Object r;

        r = tryCallAny(punishmentService, "getActiveBannedNames",
                new Class[]{String.class, int.class}, new Object[]{prefix, limit});
        if (r instanceof List list) return toNameList(list);

        r = tryCallAny(punishmentService, "getActiveBanNames",
                new Class[]{String.class, int.class}, new Object[]{prefix, limit});
        if (r instanceof List list) return toNameList(list);

        r = tryCallAny(punishmentService, "getBannedNames",
                new Class[]{String.class, int.class}, new Object[]{prefix, limit});
        if (r instanceof List list) return toNameList(list);

        return List.of();
    }

    private List<String> tryFindKnownNames(String prefix, int limit) {
        Object r = tryCallAny(punishmentService, "findKnownNames",
                new Class[]{String.class, int.class}, new Object[]{prefix, limit});

        if (r instanceof List list) return toNameList(list);

        // Optionaler Fallback-Name
        r = tryCallAny(punishmentService, "getKnownNames",
                new Class[]{String.class, int.class}, new Object[]{prefix, limit});

        if (r instanceof List list2) return toNameList(list2);

        return List.of();
    }

    private Object tryCallAny(Object target, String method, Class[] sig, Object[] args) {
        try {
            Method m = target.getClass().getMethod(method, sig);
            return m.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private List<String> toNameList(List list) {
        ArrayList<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o == null) continue;
            String s = String.valueOf(o);
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return hasUnbanPermission(invocation.source());
    }
}
