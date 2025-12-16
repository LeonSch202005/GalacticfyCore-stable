package de.galacticfy.core.service;

import de.galacticfy.core.database.DatabaseManager;
import org.slf4j.Logger;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Verwaltet den globalen Maintenance-Status, Timer,
 * Whitelist und pro-Server-Wartung.
 *
 * Jetzt mit Datenbank-Persistenz:
 *  - gf_maintenance_config
 *  - gf_maintenance_whitelist_players
 *  - gf_maintenance_whitelist_groups
 */
public class MaintenanceService {

    private final Logger logger;
    private final DatabaseManager db;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Globaler Wartungsmodus
    private volatile boolean maintenanceEnabled = false;
    private volatile Long maintenanceEndMillis = null; // kann null sein

    // Whitelist für Spieler (Namen in lowercase)
    private final Set<String> whitelistedPlayers = ConcurrentHashMap.newKeySet();
    // Whitelist für Gruppen/Rollen (Namen in lowercase)
    private final Set<String> whitelistedGroups = ConcurrentHashMap.newKeySet();

    // Pro-Server-Maintenance: Backend-Name in lowercase (nur in Memory)
    private final Set<String> serverMaintenance = ConcurrentHashMap.newKeySet();

    public MaintenanceService(Logger logger, DatabaseManager db) {
        this.logger = logger;
        this.db = db;

        // Beim Start Zustand & Whitelists aus DB laden
        loadFromDatabase();
    }

    // =====================================================================
    // LADEN AUS DER DATENBANK
    // =====================================================================

    private void loadFromDatabase() {
        loadMaintenanceConfig();
        loadWhitelists();
    }

    private void loadMaintenanceConfig() {
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT enabled, end_at FROM gf_maintenance_config WHERE id = 1"
             )) {

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    this.maintenanceEnabled = rs.getBoolean("enabled");
                    this.maintenanceEndMillis = readEpochMillisFlexible(rs, "end_at");
                } else {
                    // Default-Eintrag anlegen
                    try (PreparedStatement insert = con.prepareStatement(
                            "INSERT INTO gf_maintenance_config (id, enabled, end_at) VALUES (1, 0, NULL)"
                    )) {
                        insert.executeUpdate();
                    }
                    this.maintenanceEnabled = false;
                    this.maintenanceEndMillis = null;
                }
            }

            logger.info("MaintenanceService: Config aus DB geladen (enabled={}, endAt={}).",
                    maintenanceEnabled, maintenanceEndMillis != null ? Instant.ofEpochMilli(maintenanceEndMillis) : "null");

        } catch (SQLException e) {
            logger.error("Fehler beim Laden der Maintenance-Config aus der Datenbank", e);
        }

        // Falls ein Endzeitpunkt in der Zukunft gesetzt ist, könnte man hier
        // optional einen Auto-Ende-Task planen. Zur Sicherheit lassen wir
        // Maintenance einfach aktiv, bis ein Admin sie beendet.
    }

    /**
     * SQLite speichert bei uns teils Epoch-Millis als Zahl/String, während externe DBs TIMESTAMP liefern.
     * Diese Methode liest "end_at" vendor-safe.
     */
    private Long readEpochMillisFlexible(ResultSet rs, String column) throws SQLException {
        Object raw = rs.getObject(column);
        if (raw == null) return null;

        if (raw instanceof Timestamp ts) {
            return ts.toInstant().toEpochMilli();
        }
        if (raw instanceof java.util.Date d) {
            return d.getTime();
        }
        if (raw instanceof Number n) {
            long v = n.longValue();
            // Heuristik: Sekunden vs Millis
            return (v < 10_000_000_000L) ? (v * 1000L) : v;
        }
        if (raw instanceof String s) {
            s = s.trim();
            if (s.isEmpty() || s.equalsIgnoreCase("null")) return null;
            // nur Ziffern? => epoch seconds/millis
            if (s.chars().allMatch(Character::isDigit)) {
                long v = Long.parseLong(s);
                return (v < 10_000_000_000L) ? (v * 1000L) : v;
            }
            // ISO / SQL timestamp string fallback
            try {
                Timestamp ts = Timestamp.valueOf(s);
                return ts.toInstant().toEpochMilli();
            } catch (IllegalArgumentException ignored) {
                // ggf. als LocalDateTime parsebar?
                try {
                    LocalDateTime ldt = LocalDateTime.parse(s.replace('T', ' '));
                    return ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
                } catch (Exception ignored2) {
                    return null;
                }
            }
        }
        return null;
    }

    private void loadWhitelists() {
        whitelistedPlayers.clear();
        whitelistedGroups.clear();

        try (Connection con = db.getConnection();
             PreparedStatement psPlayers = con.prepareStatement(
                     "SELECT name FROM gf_maintenance_whitelist_players"
             );
             PreparedStatement psGroups = con.prepareStatement(
                     "SELECT group_name FROM gf_maintenance_whitelist_groups"
             )) {

            try (ResultSet rs = psPlayers.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    if (name != null && !name.isBlank()) {
                        whitelistedPlayers.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }

            try (ResultSet rs = psGroups.executeQuery()) {
                while (rs.next()) {
                    String group = rs.getString("group_name");
                    if (group != null && !group.isBlank()) {
                        whitelistedGroups.add(group.toLowerCase(Locale.ROOT));
                    }
                }
            }

            logger.info("MaintenanceService: Whitelists aus DB geladen (players={}, groups={}).",
                    whitelistedPlayers.size(), whitelistedGroups.size());

        } catch (SQLException e) {
            logger.error("Fehler beim Laden der Maintenance-Whitelists aus der Datenbank", e);
        }
    }

    private void saveConfigToDatabase() {
        try (Connection con = db.getConnection()) {
            Timestamp ts = (maintenanceEndMillis != null)
                    ? new Timestamp(maintenanceEndMillis)
                    : null;

            // UPDATE versuchen
            int updated;
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE gf_maintenance_config SET enabled = ?, end_at = ? WHERE id = 1"
            )) {
                ps.setBoolean(1, maintenanceEnabled);
                if (ts != null) {
                    ps.setTimestamp(2, ts);
                } else {
                    ps.setNull(2, Types.TIMESTAMP);
                }
                updated = ps.executeUpdate();
            }

            // Falls noch kein Eintrag existiert → INSERT
            if (updated == 0) {
                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO gf_maintenance_config (id, enabled, end_at) VALUES (1, ?, ?)"
                )) {
                    ps.setBoolean(1, maintenanceEnabled);
                    if (ts != null) {
                        ps.setTimestamp(2, ts);
                    } else {
                        ps.setNull(2, Types.TIMESTAMP);
                    }
                    ps.executeUpdate();
                }
            }

        } catch (SQLException e) {
            logger.error("Fehler beim Speichern der Maintenance-Config in der Datenbank", e);
        }
    }

    // =====================================================================
    // GLOBALER MAINTENANCE-STATUS
    // =====================================================================

    public boolean isMaintenanceEnabled() {
        return maintenanceEnabled;
    }

    /**
     * Schaltet Maintenance sofort an/aus, ohne Timer.
     */
    public synchronized void setMaintenanceEnabled(boolean enabled) {
        this.maintenanceEnabled = enabled;
        if (!enabled) {
            this.maintenanceEndMillis = null;
        }
        logger.info("Maintenance-Mode wurde {}.", enabled ? "aktiviert" : "deaktiviert");

        saveConfigToDatabase();
    }

    // =====================================================================
    // TIMER / AUTOMATISCHES ENDE
    // =====================================================================

    /**
     * Aktiviert Maintenance jetzt und deaktiviert sie nach durationMs.
     * onStart wird beim Aktivieren (sofort) ausgeführt.
     * onEnd wird beim automatischen Ende (Timer) ausgeführt.
     */
    public synchronized void enableForDuration(long durationMs, Runnable onStart, Runnable onEnd) {
        setMaintenanceEnabled(true);

        if (onStart != null) {
            try {
                onStart.run();
            } catch (Exception e) {
                logger.warn("Fehler im Maintenance-Start-Callback", e);
            }
        }

        if (durationMs > 0) {
            long endAt = System.currentTimeMillis() + durationMs;
            this.maintenanceEndMillis = endAt;
            saveConfigToDatabase();

            scheduler.schedule(() -> {
                logger.info("Maintenance-Zeitraum abgelaufen, deaktiviere Maintenance...");
                // Nur Auto-Ende, wenn noch aktiv (nicht manuell vorher beendet)
                if (isMaintenanceEnabled()) {
                    if (onEnd != null) {
                        try {
                            onEnd.run();
                        } catch (Exception e) {
                            logger.warn("Fehler im Maintenance-End-Callback", e);
                        }
                    }
                    setMaintenanceEnabled(false);
                }
            }, durationMs, TimeUnit.MILLISECONDS);
        } else {
            this.maintenanceEndMillis = null;
            saveConfigToDatabase();
        }
    }

    /**
     * Kompatibilitäts-Methode ohne End-Callback.
     */
    public synchronized void enableForDuration(long durationMs, Runnable onStart) {
        enableForDuration(durationMs, onStart, null);
    }

    /**
     * Plant eine Maintenance:
     *  - in delayMs wird Maintenance aktiviert
     *  - läuft dann durationMs lang
     */
    public void scheduleMaintenance(long delayMs, long durationMs, Runnable onStart, Runnable onEnd) {
        logger.info("Maintenance wird in {} ms gestartet (Dauer: {} ms).", delayMs, durationMs);
        scheduler.schedule(() -> enableForDuration(durationMs, onStart, onEnd),
                delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Kompatibilitäts-Methode ohne End-Callback.
     */
    public void scheduleMaintenance(long delayMs, long durationMs, Runnable onStart) {
        scheduleMaintenance(delayMs, durationMs, onStart, null);
    }

    public Long getRemainingMillis() {
        if (!maintenanceEnabled || maintenanceEndMillis == null) {
            return null;
        }
        long remaining = maintenanceEndMillis - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    // =====================================================================
    // RESTZEIT FÜR MOTD – FORMATIERTE VERSION
    // =====================================================================

    public String getRemainingTimeFormatted() {
        Long remaining = getRemainingMillis();
        if (remaining == null || remaining <= 0L) {
            return "0s";
        }
        return formatDuration(remaining);
    }

    private String formatDuration(long millis) {
        long totalSeconds = millis / 1000L;

        long days    = totalSeconds / 86400;
        long hours   = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();

        if (days > 0)    sb.append(days).append("d ");
        if (hours > 0)   sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0 || sb.isEmpty()) sb.append(seconds).append("s");

        return sb.toString().trim();
    }

    // =====================================================================
    // WHITELIST SPIELER / GRUPPEN (mit DB)
    // =====================================================================

    public boolean addWhitelistedPlayer(String name) {
        if (name == null || name.isBlank()) return false;
        String key = name.toLowerCase(Locale.ROOT);

        if (!whitelistedPlayers.add(key)) {
            return false; // schon drin
        }

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(db.isSQLite() ?
                     "INSERT OR IGNORE INTO gf_maintenance_whitelist_players (name) VALUES (?)" :
                     "INSERT INTO gf_maintenance_whitelist_players (name) VALUES (?) ON DUPLICATE KEY UPDATE name = name"
             )) {
            ps.setString(1, key);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.error("Fehler beim Hinzufügen des Whitelist-Spielers {}", key, e);
            return false;
        }
    }

    public boolean removeWhitelistedPlayer(String name) {
        if (name == null || name.isBlank()) return false;
        String key = name.toLowerCase(Locale.ROOT);

        if (!whitelistedPlayers.remove(key)) {
            return false;
        }

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "DELETE FROM gf_maintenance_whitelist_players WHERE name = ?"
             )) {
            ps.setString(1, key);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.error("Fehler beim Entfernen des Whitelist-Spielers {}", key, e);
            return false;
        }
    }

    public boolean isPlayerWhitelisted(String name) {
        if (name == null) return false;
        return whitelistedPlayers.contains(name.toLowerCase(Locale.ROOT));
    }

    public List<String> getWhitelistedPlayers() {
        return whitelistedPlayers.stream()
                .sorted()
                .toList();
    }

    public boolean addWhitelistedGroup(String group) {
        if (group == null || group.isBlank()) return false;
        String key = group.toLowerCase(Locale.ROOT);

        if (!whitelistedGroups.add(key)) {
            return false;
        }

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(db.isSQLite() ?
                     "INSERT OR IGNORE INTO gf_maintenance_whitelist_groups (group_name) VALUES (?)" :
                     "INSERT INTO gf_maintenance_whitelist_groups (group_name) VALUES (?) ON DUPLICATE KEY UPDATE group_name = group_name"
             )) {
            ps.setString(1, key);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.error("Fehler beim Hinzufügen der Whitelist-Gruppe {}", key, e);
            return false;
        }
    }

    public boolean removeWhitelistedGroup(String group) {
        if (group == null || group.isBlank()) return false;
        String key = group.toLowerCase(Locale.ROOT);

        if (!whitelistedGroups.remove(key)) {
            return false;
        }

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "DELETE FROM gf_maintenance_whitelist_groups WHERE group_name = ?"
             )) {
            ps.setString(1, key);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.error("Fehler beim Entfernen der Whitelist-Gruppe {}", key, e);
            return false;
        }
    }

    public boolean isGroupWhitelisted(String group) {
        if (group == null) return false;
        return whitelistedGroups.contains(group.toLowerCase(Locale.ROOT));
    }

    public Set<String> getWhitelistedGroups() {
        return Set.copyOf(whitelistedGroups);
    }

    // =====================================================================
    // PRO-SERVER-MAINTENANCE (weiterhin nur in Memory)
    // =====================================================================

    public void setServerMaintenance(String backend, boolean enabled) {
        if (backend == null) return;
        backend = backend.toLowerCase(Locale.ROOT);

        if (enabled) {
            serverMaintenance.add(backend);
            logger.info("Server-Maintenance für '{}' aktiviert.", backend);
        } else {
            serverMaintenance.remove(backend);
            logger.info("Server-Maintenance für '{}' deaktiviert.", backend);
        }
    }

    public boolean isServerInMaintenance(String backend) {
        if (backend == null) return false;
        return serverMaintenance.contains(backend.toLowerCase(Locale.ROOT));
    }

    public Set<String> getServersInMaintenance() {
        return Set.copyOf(serverMaintenance);
    }

    // =====================================================================
    // SHUTDOWN
    // =====================================================================

    public void shutdown() {
        logger.info("MaintenanceService: Scheduler wird heruntergefahren...");
        scheduler.shutdownNow();
    }
}
