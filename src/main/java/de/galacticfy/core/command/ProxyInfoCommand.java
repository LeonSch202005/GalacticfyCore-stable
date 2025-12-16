package de.galacticfy.core.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Locale;

public class ProxyInfoCommand implements SimpleCommand {

    private static final String PERM_PROXYINFO = "galacticfy.core.proxyinfo";

    private final ProxyServer proxy;

    public ProxyInfoCommand(ProxyServer proxy) {
        this.proxy = proxy;
    }

    private Component prefix() {
        return Component.text("§8[§bGalacticfy§8] §r");
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (!hasPermission(invocation)) {
            source.sendMessage(prefix().append(Component.text("§cDazu hast du keine Berechtigung.")));
            return;
        }

        boolean verbose = args.length > 0 && args[0].equalsIgnoreCase("full");

        int playerCount = proxy.getPlayerCount();
        int serverCount = proxy.getAllServers().size();

        Runtime rt = Runtime.getRuntime();
        long maxMem = rt.maxMemory() / (1024 * 1024);
        long totalMem = rt.totalMemory() / (1024 * 1024);
        long freeMem = rt.freeMemory() / (1024 * 1024);
        long usedMem = totalMem - freeMem;

        source.sendMessage(prefix().append(Component.text("§7Proxy-Informationen:")));
        source.sendMessage(Component.text("§8» §7Spieler online: §b" + playerCount));
        source.sendMessage(Component.text("§8» §7Server registriert: §b" + serverCount));
        source.sendMessage(Component.text("§8» §7RAM genutzt: §b" + usedMem + " §7MB"));
        source.sendMessage(Component.text("§8» §7RAM insgesamt: §b" + maxMem + " §7MB"));

        if (verbose) {
            source.sendMessage(Component.text("§8» §7JVM-Threads: §b" + Thread.activeCount()));
            source.sendMessage(Component.text("§8» §7Java-Version: §b" + System.getProperty("java.version")));
            source.sendMessage(Component.text("§8» §7OS: §b" + System.getProperty("os.name") +
                    " " + System.getProperty("os.arch")));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERM_PROXYINFO);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        // /proxyinfo <TAB>  → args kann [] oder [""] sein
        if (args.length == 0) {
            return List.of("full");
        }

        if (args.length == 1) {
            String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
            if ("full".startsWith(prefix)) {
                return List.of("full");
            }
        }

        return List.of();
    }
}
