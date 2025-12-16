package de.galacticfy.core.service;

import de.galacticfy.core.database.DatabaseManager;
import org.slf4j.Logger;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class SessionService {

    public record SessionInfo(
            UUID uuid,
            String name,
            Instant firstLogin,
            Instant lastLogin,
            Instant lastLogout,
            long totalPlaySeconds,
            String lastServer
    ) {}

    private final DatabaseManager db;
    private final Logger logger;

    public SessionService(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    public void onLogin(UUID uuid, String name, String serverName) {
        String select = """
                SELECT id, first_login, last_login, last_logout, total_play_seconds, last_server
                FROM gf_sessions
                WHERE uuid = ?
                """;

        String insert = """
                INSERT INTO gf_sessions
                (uuid, name, first_login, last_login, last_server, total_play_seconds)
                VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 0)
                """;

        String update = """
                UPDATE gf_sessions
                SET name = ?,
                    last_login = CURRENT_TIMESTAMP,
                    last_server = ?
                WHERE uuid = ?
                """;

        try (Connection con = db.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(select)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        // neuer Spieler
                        try (PreparedStatement ins = con.prepareStatement(insert)) {
                            ins.setString(1, uuid.toString());
                            ins.setString(2, name);
                            ins.setString(3, serverName);
                            ins.executeUpdate();
                        }
                    } else {
                        // bestehenden updaten
                        try (PreparedStatement upd = con.prepareStatement(update)) {
                            upd.setString(1, name);
                            upd.setString(2, serverName);
                            upd.setString(3, uuid.toString());
                            upd.executeUpdate();
                        }
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Fehler beim Session-Login-Update für {}", name, e);
        }
    }

    /**
     * Wird beim Logout aufgerufen.
     *
     * Aktualisiert:
     *  - last_logout
     *  - total_play_seconds += Sessiondauer
     *
     * @return gespielte Minuten dieser Session
     */
    public long onLogout(UUID uuid) {
        if (uuid == null) return 0L;

        // Vendor-safe: Keine DB-spezifischen Zeitfunktionen (TIMESTAMPDIFF/INTERVAL/etc.) verwenden,
        // da SQLite diese nicht unterstützt. Stattdessen last_login lesen und die Session-Dauer in Java berechnen.
        String select = """
                SELECT last_login
                FROM gf_sessions
                WHERE uuid = ?
                """;

        String update = """
                UPDATE gf_sessions
                SET last_logout = CURRENT_TIMESTAMP,
                    total_play_seconds = total_play_seconds + ?
                WHERE uuid = ?
                """;

        long sessionSeconds = 0L;

        try (Connection con = db.getConnection()) {

            // Sessiondauer ausrechnen (Java)
            try (PreparedStatement ps = con.prepareStatement(select)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Instant lastLogin = de.galacticfy.core.util.DbTimeUtil.readInstant(rs, 1);

                        if (lastLogin != null) {
                            sessionSeconds = Math.max(0L, Duration.between(lastLogin, Instant.now()).getSeconds());
                        }
                    }
                }
            }

            // Wenn wir keine Daten haben, einfach nichts updaten
            if (sessionSeconds <= 0) {
                return 0L;
            }

            // total_play_seconds erhöhen + last_logout setzen
            try (PreparedStatement ps = con.prepareStatement(update)) {
                ps.setLong(1, sessionSeconds);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }

        } catch (SQLException e) {
            logger.error("Fehler beim Session-Logout-Update für {}", uuid, e);
            return 0L;
        }

        long minutes = sessionSeconds / 60L;
        logger.debug("SessionService: Logout {} – SessionSekunden={}, Minuten={}",
                uuid, sessionSeconds, minutes);
        return minutes;
    }

    public SessionInfo getSession(UUID uuid) {
        String sql = """
                SELECT uuid, name, first_login, last_login, last_logout,
                       total_play_seconds, last_server
                FROM gf_sessions
                WHERE uuid = ?
                """;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, uuid.toString());

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                var first = de.galacticfy.core.util.DbTimeUtil.readInstant(rs, "first_login");
                var last = de.galacticfy.core.util.DbTimeUtil.readInstant(rs, "last_login");
                var lastLo = de.galacticfy.core.util.DbTimeUtil.readInstant(rs, "last_logout");
return new SessionInfo(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("name"),
                        first,
                        last,
                        lastLo,
                        rs.getLong("total_play_seconds"),
                        rs.getString("last_server")
                );
            }

        } catch (SQLException e) {
            logger.error("Fehler beim Laden der Session für {}", uuid, e);
            return null;
        }
    }

    public static String formatDuration(long totalSeconds) {
        Duration d = Duration.ofSeconds(totalSeconds);
        long hours = d.toHours();
        long minutes = d.minusHours(hours).toMinutes();
        long seconds = d.minusHours(hours).minusMinutes(minutes).getSeconds();

        if (hours > 0) {
            return String.format("%dh %02dmin %02ds", hours, minutes, seconds);
        }
        if (minutes > 0) {
            return String.format("%dmin %02ds", minutes, seconds);
        }
        return seconds + "s";
    }
}
