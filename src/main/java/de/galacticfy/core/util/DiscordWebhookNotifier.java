package de.galacticfy.core.util;

import de.galacticfy.core.service.PunishmentService;
import org.slf4j.Logger;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Universal-Discord-Webhook-Sender:
 *
 * Enthält:
 *  - Maintenance geplant
 *  - Maintenance gestartet
 *  - Maintenance beendet
 *  - Ban / Mute / Kick / IP-Ban / Warn
 *  - Reports (/report)
 */
public class DiscordWebhookNotifier {

    private final String webhookUrl;
    private final Logger logger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public DiscordWebhookNotifier(Logger logger, String webhookUrl) {
        this.logger = logger;
        this.webhookUrl = webhookUrl;
    }

    public boolean isEnabled() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    // =====================================================================
    // MAINTENANCE WEBHOOKS
    // =====================================================================

    public void sendMaintenancePlanned(String by, String duration, String startsIn) {
        if (!isEnabled()) return;

        String safeBy = safe(by);
        String safeDuration = safe(duration);
        String safeStartsIn = safe(startsIn);

        String title = "🟧 Maintenance geplant";
        String description = "Eine geplante Wartung wurde eingerichtet.\n"
                + "Das **Galacticfy** Netzwerk wird in Kürze gewartet.";

        String json = buildMaintenanceEmbed(
                title,
                description,
                safeBy,
                safeDuration,
                safeStartsIn,
                "GalacticfyCore • Maintenance",
                Instant.now().toString(),
                0xFAA61A
        );

        sendAsync(json);
    }

    public void sendMaintenanceStarted(String by, String duration) {
        if (!isEnabled()) return;

        String safeBy = safe(by);
        String safeDuration = safe(duration);

        String json = buildMaintenanceEmbed(
                "🟩 Maintenance jetzt aktiv",
                "Die Wartung hat begonnen.\n"
                        + "Das **Galacticfy** Netzwerk befindet sich nun im Wartungsmodus.",
                safeBy,
                safeDuration,
                null,
                "GalacticfyCore • Maintenance",
                Instant.now().toString(),
                0x57F287
        );

        sendAsync(json);
    }

    public void sendMaintenanceEnd(String by) {
        if (!isEnabled()) return;

        String safeBy = safe(by);

        String json = buildMaintenanceEmbed(
                "✅ Maintenance beendet",
                "Die Wartung ist abgeschlossen.\n"
                        + "Das **Galacticfy** Netzwerk ist wieder verfügbar.",
                safeBy,
                null,
                null,
                "GalacticfyCore • Maintenance",
                Instant.now().toString(),
                0x57F287
        );

        sendAsync(json);
    }

    private String buildMaintenanceEmbed(
            String title,
            String description,
            String by,
            String duration,
            String startsIn,
            String footerText,
            String timestamp,
            int color
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"embeds\":[{");

        sb.append("\"title\":\"").append(escapeJson(title)).append("\",");
        sb.append("\"description\":\"").append(escapeJson(description)).append("\",");
        sb.append("\"color\":").append(color).append(",");

        sb.append("\"fields\":[");

        // by
        sb.append("{\"name\":\"👤 Ausgeführt von\",")
                .append("\"value\":\"").append(escapeJson("• `" + by + "`")).append("\",")
                .append("\"inline\":false},");

        if (duration != null) {
            sb.append("{\"name\":\"⏱ Dauer\",")
                    .append("\"value\":\"").append(escapeJson("• " + duration)).append("\",")
                    .append("\"inline\":true},");
        }

        if (startsIn != null) {
            sb.append("{\"name\":\"⏰ Beginn in\",")
                    .append("\"value\":\"").append(escapeJson("• " + startsIn)).append("\",")
                    .append("\"inline\":true}");
        } else {
            // letztes Komma entfernen, wenn kein startsIn-Feld folgt
            if (sb.charAt(sb.length() - 1) == ',') {
                sb.setLength(sb.length() - 1);
            }
        }

        sb.append("],");

        sb.append("\"footer\":{\"text\":\"").append(escapeJson(footerText)).append("\"},");
        sb.append("\"timestamp\":\"").append(escapeJson(timestamp)).append("\"");

        sb.append("}]}");
        return sb.toString();
    }

    // =====================================================================
    // MODERATION WEBHOOKS (BAN / MUTE / KICK / BAN-IP / WARN)
    // =====================================================================

    public void sendBan(PunishmentService.Punishment p) {
        if (!isEnabled() || p == null) return;
        sendAsync(buildPunishEmbed("⛔ Spieler gebannt", "Ein Spieler wurde gebannt.", p, 0xFF4444));
    }

    public void sendMute(PunishmentService.Punishment p) {
        if (!isEnabled() || p == null) return;
        sendAsync(buildPunishEmbed("🔇 Spieler gemutet", "Ein Spieler wurde gemutet.", p, 0xFFAA00));
    }

    public void sendKick(PunishmentService.Punishment p) {
        if (!isEnabled() || p == null) return;
        sendAsync(buildPunishEmbed("👢 Spieler gekickt", "Ein Spieler wurde vom Server gekickt.", p, 0xFFFF55));
    }

    public void sendBanIp(PunishmentService.Punishment p) {
        if (!isEnabled() || p == null) return;
        sendAsync(buildPunishEmbed("🖥 IP-Adresse gebannt", "Eine IP-Adresse wurde gebannt.", p, 0xAA00FF));
    }

    /** Webhook für Verwarnungen (/warn) */
    public void sendWarn(PunishmentService.Punishment p) {
        if (!isEnabled() || p == null) return;
        sendAsync(buildPunishEmbed("⚠ Spieler verwarnt", "Ein Spieler wurde verwarnt.", p, 0xFFCC00));
    }

    // =====================================================================
    // REPORT WEBHOOKS (/report)
    // =====================================================================

    public void sendReport(String reporter, String target, String reason, String server) {
        if (!isEnabled()) return;

        String safeReporter = safe(reporter);
        String safeTarget = safe(target);
        String safeReason = safe(reason);
        String safeServer = safe(server);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"embeds\":[{");
        sb.append("\"title\":\"").append(escapeJson("📢 Neuer Report")).append("\",");
        sb.append("\"description\":\"").append(escapeJson("Ein Spieler wurde gemeldet.")).append("\",");
        sb.append("\"color\":").append(0x3498DB).append(",");

        sb.append("\"fields\":[");
        sb.append("{\"name\":\"Reporter\",")
                .append("\"value\":\"").append(escapeJson("`" + safeReporter + "`")).append("\",")
                .append("\"inline\":true},");
        sb.append("{\"name\":\"Ziel\",")
                .append("\"value\":\"").append(escapeJson("`" + safeTarget + "`")).append("\",")
                .append("\"inline\":true},");
        sb.append("{\"name\":\"Server\",")
                .append("\"value\":\"").append(escapeJson("`" + safeServer + "`")).append("\",")
                .append("\"inline\":true},");
        sb.append("{\"name\":\"Grund\",")
                .append("\"value\":\"").append(escapeJson(safeReason)).append("\",")
                .append("\"inline\":false}");
        sb.append("],");

        sb.append("\"footer\":{\"text\":\"GalacticfyCore • Reports\"},");
        sb.append("\"timestamp\":\"").append(escapeJson(Instant.now().toString())).append("\"");

        sb.append("}]}");

        sendAsync(sb.toString());
    }

    // =====================================================================
    // GENERISCHER PUNISH-EMBED BUILDER
    // =====================================================================

    private String buildPunishEmbed(
            String title,
            String description,
            PunishmentService.Punishment p,
            int color
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("{\"embeds\":[{");
        sb.append("\"title\":\"").append(escapeJson(title)).append("\",");
        sb.append("\"description\":\"").append(escapeJson(description)).append("\",");
        sb.append("\"color\":").append(color).append(",");

        sb.append("\"fields\":[");

        // Spieler
        sb.append("{\"name\":\"Spieler\",")
                .append("\"value\":\"").append(escapeJson("`" + p.name + "`")).append("\",")
                .append("\"inline\":false},");

        // Staff
        sb.append("{\"name\":\"Ausgeführt von\",")
                .append("\"value\":\"").append(escapeJson("`" + p.staff + "`")).append("\",")
                .append("\"inline\":true},");

        // Grund
        sb.append("{\"name\":\"Grund\",")
                .append("\"value\":\"").append(escapeJson("`" + p.reason + "`")).append("\",")
                .append("\"inline\":true},");

        // Aktion
        sb.append("{\"name\":\"Aktion\",")
                .append("\"value\":\"").append(escapeJson("`" + p.type.name() + "`")).append("\",")
                .append("\"inline\":true},");

        // Dauer
        String duration = (p.expiresAt == null)
                ? "Permanent"
                : "<t:" + p.expiresAt.getEpochSecond() + ":R>";

        sb.append("{\"name\":\"Dauer\",")
                .append("\"value\":\"").append(escapeJson(duration)).append("\",")
                .append("\"inline\":true}");

        sb.append("],");

        sb.append("\"footer\":{\"text\":\"GalacticfyCore • Moderation\"},");
        sb.append("\"timestamp\":\"").append(escapeJson(Instant.now().toString())).append("\"");

        sb.append("}]}");

        return sb.toString();
    }

    // =====================================================================
    // ASYNC HTTP SENDER
    // =====================================================================

    private void sendAsync(String json) {
        executor.submit(() -> {
            try {
                URL url = new URL(webhookUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");

                byte[] out = json.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(out.length);
                conn.connect();

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(out);
                }

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    logger.warn("Discord Webhook antwortete mit HTTP {}", code);
                }

            } catch (Exception e) {
                logger.warn("Webhook fehlgeschlagen", e);
            }
        });
    }

    // =====================================================================
    // HILFSMETHODEN
    // =====================================================================

    private String safe(String s) {
        return (s == null || s.isBlank()) ? "Unbekannt" : s;
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
