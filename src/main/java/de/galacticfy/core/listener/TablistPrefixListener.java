package de.galacticfy.core.listener;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

import java.util.*;

/**
 * Tablist:
 *  - Header/Footer Design
 *  - Namen mit Prefix aus GalacticfyPermissionService
 *  - pro Viewer werden nur Spieler vom selben Server angezeigt
 */
public class TablistPrefixListener {

    private final ProxyServer proxy;
    private final GalacticfyPermissionService permissionService;
    private final Logger logger;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public TablistPrefixListener(ProxyServer proxy,
                                 GalacticfyPermissionService permissionService,
                                 Logger logger) {
        this.proxy = proxy;
        this.permissionService = permissionService;
        this.logger = logger;
    }

    // ============================================================
    // EVENTS → automatischer Reload
    // ============================================================

    @Subscribe(order = PostOrder.LAST)
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        logger.info("[Tablist] PostLogin für {}", player.getUsername());
        refreshAll();
    }

    @Subscribe(order = PostOrder.LAST)
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        logger.info("[Tablist] ServerPostConnect für {}", player.getUsername());
        refreshAll();
    }

    @Subscribe(order = PostOrder.LAST)
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        logger.info("[Tablist] Disconnect von {}", player.getUsername());
        refreshAll();
    }

    // Kann z.B. auch von /rank reload o.Ä. aufgerufen werden
    public void refreshAll() {
        Collection<Player> allPlayers = proxy.getAllPlayers();

        for (Player viewer : allPlayers) {
            updateForViewer(viewer, allPlayers);
        }
    }

    // ============================================================
    // pro Viewer Tablist aufbauen
    // ============================================================

    private void updateForViewer(Player viewer, Collection<Player> allPlayers) {
        updateHeaderFooter(viewer);

        TabList tabList = viewer.getTabList();
        String viewerServer = viewer.getCurrentServer()
                .map(cs -> cs.getServerInfo().getName())
                .orElse(null);

        // Alle Spieler, die in DIESE Tablist gehören
        Set<UUID> shouldShow = new HashSet<>();

        if (viewerServer != null) {
            for (Player target : allPlayers) {
                String targetServer = target.getCurrentServer()
                        .map(cs -> cs.getServerInfo().getName())
                        .orElse(null);

                if (viewerServer.equalsIgnoreCase(targetServer)) {
                    shouldShow.add(target.getUniqueId());
                    updateEntry(tabList, target);
                }
            }
        } else {
            // Fallback: Viewer ist noch keinem Server zugewiesen → nur sich selbst anzeigen
            shouldShow.add(viewer.getUniqueId());
            updateEntry(tabList, viewer);
        }

        // Alles entfernen, was NICHT mehr auf demselben Server ist
        for (TabListEntry entry : new ArrayList<>(tabList.getEntries())) {
            UUID id = entry.getProfile().getId();
            if (!shouldShow.contains(id)) {
                tabList.removeEntry(id);
            }
        }
    }

    // ============================================================
    // Header / Footer
    // ============================================================

    private void updateHeaderFooter(Player viewer) {
        Component header = mm.deserialize(
                "<gradient:#00E5FF:#C800FF><bold>✦ Galacticfy Netzwerk ✦</bold></gradient>\n" +
                        "<gray>Zwischen den Sternen beginnt dein Abenteuer.</gray>\n" +
                        "\n" +
                        "\n"
        );

        Component footer = mm.deserialize(
                "\n" +
                        "<yellow>Website:</yellow> <aqua>galacticfy.de</aqua>\n" +
                        "<yellow>Discord:</yellow> <aqua>discord.gg/galacticfy</aqua>\n"
        );

        viewer.getTabList().setHeaderAndFooter(header, footer);
    }

    // ============================================================
    // Eintrag für einen Ziel-Spieler bauen
    // ============================================================

    private void updateEntry(TabList tabList, Player target) {
        UUID uuid = target.getUniqueId();

        // Prefix aus deinem Rank-System
        Component rankComp = permissionService.getPrefixComponent(target);

        if (rankComp == null || rankComp.equals(Component.empty())) {
            rankComp = Component.text("Spieler", NamedTextColor.GRAY);
        }

        Component starComp = Component.text(" ✦ ", NamedTextColor.DARK_GRAY);
        Component nameComp = Component.text(target.getUsername(), NamedTextColor.GRAY);

        Component display = Component.empty()
                .append(rankComp)
                .append(starComp)
                .append(nameComp);

        tabList.getEntry(uuid).ifPresentOrElse(entry -> {
            entry.setDisplayName(display);
        }, () -> {
            TabListEntry entry = TabListEntry.builder()
                    .tabList(tabList)
                    .profile(target.getGameProfile())
                    .displayName(display)
                    .latency(1)
                    .gameMode(0)
                    .listed(true)
                    .build();
            tabList.addEntry(entry);
        });
    }
}
