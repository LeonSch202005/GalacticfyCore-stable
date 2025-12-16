package de.galacticfy.core.motd;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.server.ServerPing;
import de.galacticfy.core.service.MaintenanceService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Zentrales MOTD-System für das Galacticfy-Netzwerk.
 *
 * - Normale MOTD mit rotierenden Untertiteln
 * - Wartungs-MOTD mit klar sichtbarer Restzeit in Zeile 2
 * - Kein <center>, nur einfache Zeilen
 */
public class GalacticfyMotdProvider {

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final MaintenanceService maintenanceService;

    // Rotierende Subtitel für die normale MOTD (Zeile 2)
    private final List<String> normalSubtitles = List.of(
            "<gray><italic>Im Schatten der Sterne beginnt deine Geschichte…</italic></gray>",
            "<gray><italic>Verbinde Welten. Baue deine Galaxie.</italic></gray>",
            "<gray><italic>Citybuild, Skyblock & mehr – alles in einem Universum.</italic></gray>",
            "<gray><italic>Schließe dich der Crew an und erobere den Kosmos.</italic></gray>"
    );

    // Optional: Subtitel für Wartung (Zeile 3, falls der Client sie anzeigt)
    private final List<String> maintenanceSubtitles = List.of(
            "<gray><italic>Wir verbessern das Netzwerk für dich…</italic></gray>",
            "<gray><italic>Updates werden eingespielt…</italic></gray>",
            "<gray><italic>Optimierungen laufen im Hintergrund…</italic></gray>",
            "<gray><italic>Bitte einen Moment Geduld…</italic></gray>"
    );

    public GalacticfyMotdProvider(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        ServerPing original = event.getPing();
        ServerPing.Builder builder = original.asBuilder();

        if (maintenanceService.isMaintenanceEnabled()) {
            handleMaintenanceMotd(builder);
        } else {
            handleNormalMotd(builder, original);
        }

        event.setPing(builder.build());
    }

    // =========================================================
    // WARTUNGS-MOTD – Fokus auf ZEIT in Zeile 2
    // =========================================================
    private void handleMaintenanceMotd(ServerPing.Builder builder) {
        // Restzeit, z.B. "2h 31m"
        String time = maintenanceService.getRemainingTimeFormatted();

        // Betroffene Server (für Zeile 3/4, falls Platz)
        List<String> maintServers = new ArrayList<>(maintenanceService.getServersInMaintenance());
        String affectedShort;
        if (maintServers.isEmpty()) {
            affectedShort = "Gesamtes Netzwerk";
        } else if (maintServers.size() <= 3) {
            affectedShort = String.join(", ", maintServers);
        } else {
            affectedShort = maintServers.get(0) + ", " + maintServers.get(1) + " + mehr";
        }

        // Rotierender Wartungs-Subtitel (Zeile 3, optional sichtbar)
        String subtitle = pickRandomMaintenanceSubtitle();

        // WICHTIG:
        // Zeile 1 = Titel
        // Zeile 2 = Wartung + ZEIT (kurz gehalten, damit alles sichtbar bleibt)
        // Zeile 3 = optionaler Text
        // Zeile 4 = betroffene Server (falls Client sie zeigt)
        Component motd = mm.deserialize(
                "<gradient:#00E5FF:#7A00FF><bold>✦ Galacticfy Netzwerk ✦</bold></gradient>\n" +
                        "<red><bold>⚠ Wartung</bold></red> <gray>• Online in</gray> <gold><bold>" + time + "</bold></gold>\n" +
                        subtitle + "\n" +
                        "<gray>🛰 Betroffen:</gray> <aqua>" + affectedShort + "</aqua>"
        );

        builder.description(motd);

        // Rechts oben in der Server-Liste
        builder.version(new ServerPing.Version(
                0,
                "Wartung ✘"
        ));
    }

    // =========================================================
    // NORMALE MOTD
    // =========================================================
    private void handleNormalMotd(ServerPing.Builder builder, ServerPing original) {
        String subtitle = pickRandomSubtitle();

        int online = original.getPlayers().map(p -> p.getOnline()).orElse(0);
        int max = original.getPlayers().map(p -> p.getMax()).orElse(0);

        String playersLine = "<gray>Online:</gray> <aqua>" + online + "</aqua><gray>/</gray><aqua>" + max + "</aqua>";

        Component motd = mm.deserialize(
                "<gradient:#00E5FF:#C800FF><bold>✦ Galacticfy Netzwerk ✦</bold></gradient>\n" +
                        subtitle + "\n" +
                        playersLine
        );

        builder.description(motd);

        builder.version(new ServerPing.Version(
                original.getVersion().getProtocol(),
                "Galacticfy • 1.20.x"
        ));
    }

    // =========================================================
    // HILFSMETHODEN
    // =========================================================
    private String pickRandomSubtitle() {
        if (normalSubtitles.isEmpty()) {
            return "<gray><italic>Willkommen im Galacticfy Netzwerk.</italic></gray>";
        }
        int idx = ThreadLocalRandom.current().nextInt(normalSubtitles.size());
        return normalSubtitles.get(idx);
    }

    private String pickRandomMaintenanceSubtitle() {
        if (maintenanceSubtitles.isEmpty()) {
            return "<gray><italic>Wartungsarbeiten laufen…</italic></gray>";
        }
        int idx = ThreadLocalRandom.current().nextInt(maintenanceSubtitles.size());
        return maintenanceSubtitles.get(idx);
    }
}
