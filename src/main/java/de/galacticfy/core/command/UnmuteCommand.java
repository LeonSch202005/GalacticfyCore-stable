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

public class UnmuteCommand implements SimpleCommand {

    private static final String PERM_UNMUTE = "galacticfy.punish.unmute";

    private final PunishmentService punishmentService;
    private final GalacticfyPermissionService perms;
    private final ProxyServer proxy;
    private final PlayerIdentityCacheService identityCache;

    public UnmuteCommand(PunishmentService punishmentService,
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

    private boolean hasUnmutePermission(CommandSource src) {
        if (src instanceof Player p) {
            if (perms != null) return perms.hasPluginPermission(p, PERM_UNMUTE);
            return p.hasPermission(PERM_UNMUTE);
        }
        return true;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!hasUnmutePermission(src)) {
            src.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
            return;
        }

        if (args.length < 1) {
            src.sendMessage(prefix().append(Component.text("§eBenutzung: §b/unmute <spieler>")));
            return;
        }

        String inputName = args[0];
        String staffName = (src instanceof Player p) ? p.getUsername() : "Konsole";

        UUID uuid = null;
        String storedName = inputName;

        // Offline-Resolution über Cache/DB
        if (identityCache != null) {
            Optional<UUID> resolved = identityCache.findUuidByName(inputName);
            if (resolved.isPresent()) {
                uuid = resolved.get();
                storedName = identityCache.findNameByUuid(uuid).orElse(storedName);
            }
        }

        boolean ok = invokeUnmute(punishmentService, uuid, storedName, staffName);

        if (ok) {
            src.sendMessage(prefix().append(Component.text("§a" + storedName + " wurde entmutet.")));
            // Spieler informieren wenn online
            proxy.getPlayer(storedName).ifPresent(p ->
                    p.sendMessage(Component.text("§aDu wurdest entmutet.")));
        } else {
            src.sendMessage(prefix().append(Component.text("§cKonnte " + storedName + " nicht entmuten (nicht gefunden oder DB-Fehler).")));
        }
    }

    private boolean invokeUnmute(PunishmentService svc, UUID uuid, String name, String staff) {
        // Varianten:
        // unmute(uuid, name, staff)
        // unmutePlayer(uuid, name, staff)
        // unmuteByName(name, staff)
        // unmute(name, staff)
        Boolean r;

        if (uuid != null) {
            r = tryCallBoolean(svc, "unmute", new Class[]{UUID.class, String.class, String.class}, new Object[]{uuid, name, staff});
            if (r != null) return r;

            r = tryCallBoolean(svc, "unmutePlayer", new Class[]{UUID.class, String.class, String.class}, new Object[]{uuid, name, staff});
            if (r != null) return r;

            r = tryCallBoolean(svc, "unmute", new Class[]{UUID.class, String.class}, new Object[]{uuid, staff});
            if (r != null) return r;

            r = tryCallBoolean(svc, "unmutePlayer", new Class[]{UUID.class, String.class}, new Object[]{uuid, staff});
            if (r != null) return r;
        }

        r = tryCallBoolean(svc, "unmuteByName", new Class[]{String.class, String.class}, new Object[]{name, staff});
        if (r != null) return r;

        r = tryCallBoolean(svc, "unmute", new Class[]{String.class, String.class}, new Object[]{name, staff});
        if (r != null) return r;

        r = tryCallBoolean(svc, "unmutePlayer", new Class[]{String.class, String.class}, new Object[]{name, staff});
        if (r != null) return r;

        r = tryCallBoolean(svc, "unmuteByName", new Class[]{String.class}, new Object[]{name});
        if (r != null) return r;

        r = tryCallBoolean(svc, "unmute", new Class[]{String.class}, new Object[]{name});
        if (r != null) return r;

        r = tryCallBoolean(svc, "unmutePlayer", new Class[]{String.class}, new Object[]{name});
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
        return hasUnmutePermission(invocation.source());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!hasUnmutePermission(invocation.source())) return List.of();

        String[] args = invocation.arguments();
        if (args.length == 0) return suggestNames("");
        if (args.length == 1) return suggestNames(args[0]);
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

        // Versuche: aktive mute namen aus Service (falls vorhanden)
        out.addAll(tryGetActiveMutedNames(prefix, 25));

        // Fallback: bekannte Namen
        try {
            out.addAll(punishmentService.findKnownNames(prefix, 25));
        } catch (Throwable ignored) {}

        return out.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .limit(40)
                .collect(Collectors.toList());
    }

    private List<String> tryGetActiveMutedNames(String prefix, int limit) {
        // mögliche Methodennamen:
        // getActiveMutedNames(prefix, limit)
        // getActiveMuteNames(prefix, limit)
        Object r;

        r = tryCallAny(punishmentService, "getActiveMutedNames",
                new Class[]{String.class, int.class}, new Object[]{prefix, limit});
        if (r instanceof List list) return toNameList(list);

        r = tryCallAny(punishmentService, "getActiveMuteNames",
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
}
