package de.galacticfy.core.service;

import de.galacticfy.core.database.DatabaseManager;
import org.slf4j.Logger;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class PunishmentService {

    // ============================================================
    // ENUMS & DATENKLASSE
    // ============================================================

    public enum PunishmentType {
        BAN,
        IP_BAN,
        MUTE,
        KICK,
        WARN
    }

    public static class Punishment {
        public final int id;
        public final UUID uuid;
        public final String name;
        public final String ip;
        public final PunishmentType type;
        public final String reason;
        public final String staff;
        public final Instant createdAt;
        public final Instant expiresAt;
        public final boolean active;

        public Punishment(int id,
                          UUID uuid,
                          String name,
                          String ip,
                          PunishmentType type,
                          String reason,
                          String staff,
                          Instant createdAt,
                          Instant expiresAt,
                          boolean active) {
            this.id = id;
            this.uuid = uuid;
            this.name = name;
            this.ip = ip;
            this.type = type;
            this.reason = reason;
            this.staff = staff;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.active = active;
        }
    }

    // ============================================================
    // FELDER
    // ============================================================

    private final DatabaseManager db;
    private final Logger logger;

    public PunishmentService(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    // ============================================================
    // BAN (Account-Ban über UUID/Name)
    // ============================================================

    public Punishment banPlayer(UUID uuid,
                                String name,
                                String ip,
                                String reason,
                                String staff,
                                Long durationMs) {
        return createPunishment(uuid, name, ip, PunishmentType.BAN, reason, staff, durationMs);
    }

    public boolean unbanPlayer(UUID uuid) {
        if (uuid == null) return false;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE gf_punishments SET active = 0 " +
                             "WHERE uuid = ? AND type = 'BAN' AND active = 1"
             )) {
            ps.setString(1, uuid.toString());
            int updated = ps.executeUpdate();
            return updated > 0;
        } catch (SQLException e) {
            logger.error("Fehler beim Unbannen von {}", uuid, e);
            return false;
        }
    }

    /** Alte Variante (nur boolean), bleibt für Kompatibilität drin. */
    public boolean unbanByName(String name) {
        if (name == null || name.isBlank()) return false;
        String key = name.toLowerCase(Locale.ROOT);

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE gf_punishments SET active = 0 " +
                             "WHERE LOWER(name) = ? AND type = 'BAN' AND active = 1"
             )) {
            ps.setString(1, key);
            int updated = ps.executeUpdate();
            return updated > 0;
        } catch (SQLException e) {
            logger.error("Fehler beim Unbannen von Name {}", name, e);
            return false;
        }
    }

    // Neue Variante für /unban-Command: gibt das letzte Ban-Objekt zurück
    public Punishment unbanByName(String name, String staffName) {
        if (name == null || name.isBlank()) return null;

        String key = name.toLowerCase(Locale.ROOT);

        try (Connection con = db.getConnection()) {

            // 1) Letzten aktiven BAN holen
            Punishment lastBan = null;
            String selectSql = """
                    SELECT *
                    FROM gf_punishments
                    WHERE LOWER(name) = ? AND type = 'BAN' AND active = 1
                    ORDER BY created_at DESC, id DESC
                    LIMIT 1
                    """;

            try (PreparedStatement psSel = con.prepareStatement(selectSql)) {
                psSel.setString(1, key);
                try (ResultSet rs = psSel.executeQuery()) {
                    if (rs.next()) {
                        lastBan = mapPunishment(rs);
                    }
                }
            }

            if (lastBan == null) {
                return null;
            }

            // 2) Alle aktiven BANs für diesen Namen deaktivieren
            try (PreparedStatement psUpd = con.prepareStatement(
                    "UPDATE gf_punishments SET active = 0 " +
                            "WHERE LOWER(name) = ? AND type = 'BAN' AND active = 1"
            )) {
                psUpd.setString(1, key);
                psUpd.executeUpdate();
            }

            return lastBan;

        } catch (SQLException e) {
            logger.error("Fehler bei unbanByName(name={}, staff={})", name, staffName, e);
            return null;
        }
    }

    // IP-Unban für /unban <IP>
    public Punishment unbanByIp(String ip, String staffName) {
        if (ip == null || ip.isBlank()) return null;

        try (Connection con = db.getConnection()) {

            // 1) Letzten aktiven IP_BAN holen
            Punishment last = null;
            String selectSql = """
                    SELECT *
                    FROM gf_punishments
                    WHERE ip = ? AND type = 'IP_BAN' AND active = 1
                    ORDER BY created_at DESC, id DESC
                    LIMIT 1
                    """;

            try (PreparedStatement psSel = con.prepareStatement(selectSql)) {
                psSel.setString(1, ip);
                try (ResultSet rs = psSel.executeQuery()) {
                    if (rs.next()) {
                        last = mapPunishment(rs);
                    }
                }
            }

            if (last == null) {
                return null;
            }

            // 2) Alle aktiven IP_BANs auf dieser IP deaktivieren
            try (PreparedStatement psUpd = con.prepareStatement(
                    "UPDATE gf_punishments SET active = 0 " +
                            "WHERE ip = ? AND type = 'IP_BAN' AND active = 1"
            )) {
                psUpd.setString(1, ip);
                psUpd.executeUpdate();
            }

            return last;

        } catch (SQLException e) {
            logger.error("Fehler bei unbanByIp(ip={}, staff={})", ip, staffName, e);
            return null;
        }
    }

    // ============================================================
    // IP-BAN
    // ============================================================

    public Punishment banIp(String ip,
                            String reason,
                            String staff,
                            Long durationMs) {
        if (ip == null || ip.isBlank()) {
            logger.warn("banIp aufgerufen ohne gültige IP.");
            return null;
        }

        String name = "IP " + ip;
        return createPunishment(null, name, ip, PunishmentType.IP_BAN, reason, staff, durationMs);
    }

    // ============================================================
    // MUTE
    // ============================================================

    public Punishment mutePlayer(UUID uuid,
                                 String name,
                                 String ip,
                                 String reason,
                                 String staff,
                                 Long durationMs) {
        return createPunishment(uuid, name, ip, PunishmentType.MUTE, reason, staff, durationMs);
    }

    public boolean unmutePlayer(UUID uuid) {
        if (uuid == null) return false;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE gf_punishments SET active = 0 " +
                             "WHERE uuid = ? AND type = 'MUTE' AND active = 1"
             )) {
            ps.setString(1, uuid.toString());
            int updated = ps.executeUpdate();
            return updated > 0;
        } catch (SQLException e) {
            logger.error("Fehler beim Unmuten von {}", uuid, e);
            return false;
        }
    }

    /** Alte Variante (nur boolean), bleibt drin. */
    public boolean unmuteByName(String name) {
        if (name == null || name.isBlank()) return false;
        String key = name.toLowerCase(Locale.ROOT);

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE gf_punishments SET active = 0 " +
                             "WHERE LOWER(name) = ? AND type = 'MUTE' AND active = 1"
             )) {
            ps.setString(1, key);
            int updated = ps.executeUpdate();
            return updated > 0;
        } catch (SQLException e) {
            logger.error("Fehler beim Unmuten von Name {}", name, e);
            return false;
        }
    }

    // Neue Variante für /unmute: gibt das letzte Mute-Objekt zurück
    public Punishment unmuteByName(String name, String staffName) {
        if (name == null || name.isBlank()) return null;
        String key = name.toLowerCase(Locale.ROOT);

        try (Connection con = db.getConnection()) {

            Punishment lastMute = null;
            String selectSql = """
                    SELECT *
                    FROM gf_punishments
                    WHERE LOWER(name) = ? AND type = 'MUTE' AND active = 1
                    ORDER BY created_at DESC, id DESC
                    LIMIT 1
                    """;

            try (PreparedStatement psSel = con.prepareStatement(selectSql)) {
                psSel.setString(1, key);
                try (ResultSet rs = psSel.executeQuery()) {
                    if (rs.next()) {
                        lastMute = mapPunishment(rs);
                    }
                }
            }

            if (lastMute == null) {
                return null;
            }

            try (PreparedStatement psUpd = con.prepareStatement(
                    "UPDATE gf_punishments SET active = 0 " +
                            "WHERE LOWER(name) = ? AND type = 'MUTE' AND active = 1"
            )) {
                psUpd.setString(1, key);
                psUpd.executeUpdate();
            }

            return lastMute;

        } catch (SQLException e) {
            logger.error("Fehler bei unmuteByName(name={}, staff={})", name, staffName, e);
            return null;
        }
    }

    // ============================================================
    // WARN
    // ============================================================

    /** einfache Verwarnung, keine Ablaufzeit */
    public Punishment warnPlayer(UUID uuid,
                                 String name,
                                 String ip,
                                 String reason,
                                 String staff) {
        // durationMs = 0 → keine expiresAt
        return createPunishment(uuid, name, ip, PunishmentType.WARN, reason, staff, 0L);
    }

    /** Anzahl aller aktiven WARNs (active = 1) für Spieler */
    public int countWarns(UUID uuid, String name) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM gf_punishments WHERE type = 'WARN' AND active = 1 "
        );
        List<Object> params = new ArrayList<>();

        if (uuid != null) {
            sql.append("AND uuid = ? ");
            params.add(uuid.toString());
        } else if (name != null && !name.isBlank()) {
            sql.append("AND LOWER(name) = ? ");
            params.add(name.toLowerCase(Locale.ROOT));
        } else {
            return 0;
        }

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, (String) params.get(i));
            }

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }

        } catch (SQLException e) {
            logger.error("Fehler beim Zählen der Warns", e);
        }
        return 0;
    }

    /** Letzte aktive Warnung deaktivieren (alte Variante, nur boolean) */
    public boolean unwarnLast(UUID uuid, String name) {
        StringBuilder select = new StringBuilder(
                "SELECT id FROM gf_punishments WHERE type = 'WARN' AND active = 1 "
        );
        List<Object> params = new ArrayList<>();

        if (uuid != null) {
            select.append("AND uuid = ? ");
            params.add(uuid.toString());
        } else if (name != null && !name.isBlank()) {
            select.append("AND LOWER(name) = ? ");
            params.add(name.toLowerCase(Locale.ROOT));
        } else {
            return false;
        }

        select.append("ORDER BY created_at DESC, id DESC LIMIT 1");

        try (Connection con = db.getConnection();
             PreparedStatement psSel = con.prepareStatement(select.toString())) {

            for (int i = 0; i < params.size(); i++) {
                psSel.setString(i + 1, (String) params.get(i));
            }

            int id = -1;
            try (ResultSet rs = psSel.executeQuery()) {
                if (rs.next()) {
                    id = rs.getInt("id");
                }
            }

            if (id == -1) {
                return false;
            }

            try (PreparedStatement psUpd = con.prepareStatement(
                    "UPDATE gf_punishments SET active = 0 WHERE id = ?"
            )) {
                psUpd.setInt(1, id);
                return psUpd.executeUpdate() > 0;
            }

        } catch (SQLException e) {
            logger.error("Fehler bei unwarnLast", e);
            return false;
        }
    }

    /** Neue Variante für /unwarn: letzte Warn zurückgeben */
    public Punishment clearLastWarn(UUID uuid, String name, String staffName) {
        StringBuilder select = new StringBuilder(
                "SELECT * FROM gf_punishments WHERE type = 'WARN' AND active = 1 "
        );
        List<Object> params = new ArrayList<>();

        if (uuid != null) {
            select.append("AND uuid = ? ");
            params.add(uuid.toString());
        } else if (name != null && !name.isBlank()) {
            select.append("AND LOWER(name) = ? ");
            params.add(name.toLowerCase(Locale.ROOT));
        } else {
            return null;
        }

        select.append("ORDER BY created_at DESC, id DESC LIMIT 1");

        try (Connection con = db.getConnection();
             PreparedStatement psSel = con.prepareStatement(select.toString())) {

            for (int i = 0; i < params.size(); i++) {
                psSel.setString(i + 1, (String) params.get(i));
            }

            Punishment lastWarn = null;
            int id = -1;

            try (ResultSet rs = psSel.executeQuery()) {
                if (rs.next()) {
                    lastWarn = mapPunishment(rs);
                    id = rs.getInt("id");
                }
            }

            if (id == -1 || lastWarn == null) {
                return null;
            }

            try (PreparedStatement psUpd = con.prepareStatement(
                    "UPDATE gf_punishments SET active = 0 WHERE id = ?"
            )) {
                psUpd.setInt(1, id);
                psUpd.executeUpdate();
            }

            return lastWarn;

        } catch (SQLException e) {
            logger.error("Fehler bei clearLastWarn(uuid={}, name={}, staff={})", uuid, name, staffName, e);
            return null;
        }
    }

    /** Alle aktiven Warns deaktivieren, gibt Anzahl der entfernten Warns zurück. */
    public int clearAllWarns(UUID uuid, String name, String staffName) {
        StringBuilder sql = new StringBuilder(
                "UPDATE gf_punishments SET active = 0 " +
                        "WHERE type = 'WARN' AND active = 1 "
        );
        List<Object> params = new ArrayList<>();

        if (uuid != null) {
            sql.append("AND uuid = ? ");
            params.add(uuid.toString());
        } else if (name != null && !name.isBlank()) {
            sql.append("AND LOWER(name) = ? ");
            params.add(name.toLowerCase(Locale.ROOT));
        } else {
            return 0;
        }

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, (String) params.get(i));
            }

            return ps.executeUpdate();

        } catch (SQLException e) {
            logger.error("Fehler bei clearAllWarns(uuid={}, name={}, staff={})", uuid, name, staffName, e);
        }
        return 0;
    }

    /** Liste der letzten Warns (alte API, von dir) */
    public List<Punishment> getWarnings(UUID uuid, String name, int limit) {
        List<Punishment> list = new ArrayList<>();

        StringBuilder sql = new StringBuilder(
                "SELECT * FROM gf_punishments WHERE type = 'WARN' "
        );
        List<Object> params = new ArrayList<>();

        if (uuid != null) {
            sql.append("AND uuid = ? ");
            params.add(uuid.toString());
        } else if (name != null && !name.isBlank()) {
            sql.append("AND LOWER(name) = ? ");
            params.add(name.toLowerCase(Locale.ROOT));
        } else {
            return list;
        }

        sql.append("ORDER BY created_at DESC, id DESC LIMIT ?");
        params.add(limit);

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof String s) {
                    ps.setString(i + 1, s);
                } else if (p instanceof Integer in) {
                    ps.setInt(i + 1, in);
                }
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapPunishment(rs));
                }
            }

        } catch (SQLException e) {
            logger.error("Fehler beim Laden der Warnungs-Liste", e);
        }

        return list;
    }

    /** Neue API für /warnings-Command – einfacher Name. */
    public List<Punishment> getWarns(UUID uuid, String name, int limit) {
        return getWarnings(uuid, name, limit);
    }

    // ============================================================
    // KICK (nur History)
    // ============================================================

    public Punishment logKick(UUID uuid,
                              String name,
                              String ip,
                              String reason,
                              String staff) {
        return createPunishment(uuid, name, ip, PunishmentType.KICK, reason, staff, null);
    }

    // ============================================================
    // INTERN: Eintrag erstellen
    // ============================================================

    private Punishment createPunishment(UUID uuid,
                                        String name,
                                        String ip,
                                        PunishmentType type,
                                        String reason,
                                        String staff,
                                        Long durationMs) {
        if (name == null || name.isBlank()) {
            if (ip != null && !ip.isBlank()) {
                name = "IP " + ip;
            } else {
                name = "Unknown";
            }
        }

        Timestamp expires = null;
        if (durationMs != null && durationMs > 0) {
            long end = System.currentTimeMillis() + durationMs;
            expires = Timestamp.from(Instant.ofEpochMilli(end));
        }

        Instant now = Instant.now();

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO gf_punishments " +
                             "(uuid, name, ip, type, reason, staff, created_at, expires_at, active) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)",
                     Statement.RETURN_GENERATED_KEYS
             )) {

            if (uuid != null) {
                ps.setString(1, uuid.toString());
            } else {
                ps.setNull(1, Types.CHAR);
            }

            ps.setString(2, name);

            if (ip != null && !ip.isBlank()) {
                ps.setString(3, ip);
            } else {
                ps.setNull(3, Types.VARCHAR);
            }

            ps.setString(4, type.name());
            ps.setString(5, reason != null ? reason : "Kein Grund angegeben");
            ps.setString(6, staff != null ? staff : "Konsole");
            ps.setTimestamp(7, Timestamp.from(now));

            if (expires != null) {
                ps.setTimestamp(8, expires);
            } else {
                ps.setNull(8, Types.TIMESTAMP);
            }

            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    return new Punishment(
                            id,
                            uuid,
                            name,
                            ip,
                            type,
                            reason,
                            staff,
                            now,
                            expires != null ? expires.toInstant() : null,
                            true
                    );
                }
            }

        } catch (SQLException e) {
            logger.error("Fehler beim Erstellen eines {}-Punishments für {}",
                    type, uuid != null ? uuid : name, e);
        }

        return null;
    }

    // ============================================================
    // ACTIVE BAN/MUTE ABFRAGEN (Login / Chat)
    // ============================================================

    public Punishment getActiveBan(UUID uuid, String ip) {
        // 1) normaler Account-Ban
        Punishment ban = getActivePunishment(PunishmentType.BAN, uuid, null);
        if (ban != null) return ban;

        // 2) IP-Ban
        if (ip != null && !ip.isBlank()) {
            return getActivePunishment(PunishmentType.IP_BAN, null, ip);
        }

        return null;
    }

    public Punishment getActiveMute(UUID uuid) {
        return getActivePunishment(PunishmentType.MUTE, uuid, null);
    }

    // ============================================================
    // ACTIVE BAN/MUTE für /check (UUID + Name + IP)
    // ============================================================

    public Punishment getActiveBanForCheck(UUID uuid, String name, String ip) {
        return getActivePunishmentExtended(PunishmentType.BAN, uuid, name, ip);
    }

    public Punishment getActiveMuteForCheck(UUID uuid, String name, String ip) {
        return getActivePunishmentExtended(PunishmentType.MUTE, uuid, name, ip);
    }

    private Punishment getActivePunishmentExtended(PunishmentType type,
                                                   UUID uuid,
                                                   String name,
                                                   String ip) {
        try (Connection con = db.getConnection()) {

            Punishment p = null;

            // 1) UUID (am genauesten)
            if (uuid != null) {
                p = querySingleActive(con, type, "uuid = ?", uuid.toString());
            }

            // 2) Name (falls UUID nicht bekannt / Spieler offline)
            if (p == null && name != null && !name.isBlank()) {
                p = querySingleActive(con, type, "LOWER(name) = ?", name.toLowerCase(Locale.ROOT));
            }

            // 3) IP (Fallback, z.B. wenn du alte Daten hast)
            if (p == null && ip != null && !ip.isBlank()) {
                p = querySingleActive(con, type, "ip = ?", ip);
            }

            if (p == null) {
                return null;
            }

            // Ablauf prüfen
            if (isExpired(p)) {
                deactivateById(p.id);
                return null;
            }

            return p;

        } catch (SQLException e) {
            logger.error("Fehler beim Abfragen von {} (extended) für uuid={}, name={}, ip={}",
                    type, uuid, name, ip, e);
        }
        return null;
    }

    private Punishment getActivePunishment(PunishmentType type, UUID uuid, String ip) {
        try (Connection con = db.getConnection()) {

            Punishment p = null;

            if (uuid != null) {
                p = querySingleActive(con, type, "uuid = ?", uuid.toString());
            }

            if (p == null && ip != null && !ip.isBlank()) {
                p = querySingleActive(con, type, "ip = ?", ip);
            }

            if (p == null) return null;

            if (isExpired(p)) {
                deactivateById(p.id);
                return null;
            }

            return p;

        } catch (SQLException e) {
            logger.error("Fehler beim Abfragen von {} für uuid={}, ip={}", type, uuid, ip, e);
        }
        return null;
    }

    private Punishment querySingleActive(Connection con,
                                         PunishmentType type,
                                         String where,
                                         String value) throws SQLException {

        String sql = "SELECT * FROM gf_punishments " +
                "WHERE " + where + " AND type = ? AND active = 1 " +
                "ORDER BY id DESC LIMIT 1";

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, value);
            ps.setString(2, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapPunishment(rs);
                }
            }
        }
        return null;
    }

    private boolean isExpired(Punishment p) {
        if (p.expiresAt == null) return false;
        return p.expiresAt.toEpochMilli() <= System.currentTimeMillis();
    }

    private void deactivateById(int id) {
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE gf_punishments SET active = 0 WHERE id = ?"
             )) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Fehler beim Deaktivieren von Punishment id={}", id, e);
        }
    }

    private Punishment mapPunishment(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        String uuidStr = rs.getString("uuid");
        UUID uuid = null;

        if (uuidStr != null) {
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException ignored) {
            }
        }

        String name = rs.getString("name");
        String ip = rs.getString("ip");
        String typeStr = rs.getString("type");
        PunishmentType type = PunishmentType.valueOf(typeStr.toUpperCase(Locale.ROOT));
        String reason = rs.getString("reason");
        String staff = rs.getString("staff");
        // NOTE: We support multiple storage formats across DB vendors.
        // - MariaDB/MySQL often return proper TIMESTAMP values.
        // - SQLite setups in this project store epoch millis (as INTEGER or numeric TEXT).
        java.time.Instant createdAt = readInstant(rs, "created_at");
        java.time.Instant expiresAt = readInstant(rs, "expires_at");
        boolean active = rs.getBoolean("active");

        return new Punishment(
                id,
                uuid,
                name,
                ip,
                type,
                reason,
                staff,
                createdAt,
                expiresAt,
                active
        );
    }

    private static java.time.Instant readInstant(ResultSet rs, String column) throws SQLException {
        Object raw = rs.getObject(column);
        if (raw == null) return null;

        if (raw instanceof Timestamp ts) {
            return ts.toInstant();
        }

        if (raw instanceof Number n) {
            long v = n.longValue();
            // Heuristic: values < 10^11 are likely epoch seconds, otherwise epoch millis.
            if (v > 0 && v < 100_000_000_000L) {
                return java.time.Instant.ofEpochSecond(v);
            }
            return java.time.Instant.ofEpochMilli(v);
        }

        if (raw instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) return null;

            // Numeric string? treat as epoch (seconds/millis)
            boolean numeric = t.chars().allMatch(Character::isDigit);
            if (numeric) {
                try {
                    long v = Long.parseLong(t);
                    if (v > 0 && v < 100_000_000_000L) {
                        return java.time.Instant.ofEpochSecond(v);
                    }
                    return java.time.Instant.ofEpochMilli(v);
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }

            // ISO timestamp string fallback
            try {
                return java.time.Instant.parse(t);
            } catch (Exception ignored) {
                // SQLite JDBC expects 'yyyy-MM-dd HH:mm:ss.SSS' for Timestamp parsing.
                try {
                    return Timestamp.valueOf(t).toInstant();
                } catch (Exception ignored2) {
                    throw new SQLException("Unsupported timestamp format in column '" + column + "': " + t);
                }
            }
        }

        throw new SQLException("Unsupported timestamp type in column '" + column + "': " + raw.getClass());
    }

    // ============================================================
    // HISTORY
    // ============================================================

    public List<Punishment> getHistory(UUID uuid, String name, int limit) {
        List<Punishment> list = new ArrayList<>();

        StringBuilder sql = new StringBuilder(
                "SELECT * FROM gf_punishments WHERE 1=1 "
        );
        List<Object> params = new ArrayList<>();

        if (uuid != null) {
            sql.append("AND uuid = ? ");
            params.add(uuid.toString());
        } else if (name != null && !name.isBlank()) {
            sql.append("AND LOWER(name) = ? ");
            params.add(name.toLowerCase(Locale.ROOT));
        } else {
            return list;
        }

        sql.append("ORDER BY created_at DESC, id DESC LIMIT ?");
        params.add(limit);

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof String s) {
                    ps.setString(i + 1, s);
                } else if (p instanceof Integer in) {
                    ps.setInt(i + 1, in);
                }
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapPunishment(rs));
                }
            }

        } catch (SQLException e) {
            logger.error("Fehler beim Laden der Punishment-History", e);
        }

        return list;
    }

    // ============================================================
    // LAST KNOWN IP
    // ============================================================

    /**
     * Holt die letzte bekannte IP eines Spielers aus gf_punishments.
     * Nutzt zuerst uuid, sonst name (LOWER(name)).
     */
    public String getLastKnownIp(UUID uuid, String name) {
        StringBuilder sql = new StringBuilder(
                "SELECT ip FROM gf_punishments WHERE ip IS NOT NULL "
        );
        List<Object> params = new ArrayList<>();

        if (uuid != null) {
            sql.append("AND uuid = ? ");
            params.add(uuid.toString());
        } else if (name != null && !name.isBlank()) {
            sql.append("AND LOWER(name) = ? ");
            params.add(name.toLowerCase(Locale.ROOT));
        } else {
            return null;
        }

        sql.append("ORDER BY created_at DESC, id DESC LIMIT 1");

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, (String) params.get(i));
            }

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("ip");
                }
            }

        } catch (SQLException e) {
            logger.error("Fehler bei getLastKnownIp", e);
        }
        return null;
    }

    // ============================================================
    // FORMAT-HILFEN
    // ============================================================

    public String formatRemaining(Punishment p) {
        if (p == null || p.expiresAt == null) {
            return "permanent";
        }
        long ms = p.expiresAt.toEpochMilli() - System.currentTimeMillis();
        if (ms <= 0) return "0s";
        return formatDuration(ms);
    }

    public String formatDuration(long millis) {
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

    /**
     * Simple Dauer-Parser (30m, 1h, 7d, 1w, 1mo, 1y)
     */
    public Long parseDuration(String input) {
        if (input == null || input.isBlank()) return null;

        input = input.toLowerCase(Locale.ROOT).trim();

        long multiplier;
        if (input.endsWith("m"))      { multiplier = 60_000L;        input = input.replace("m", ""); }
        else if (input.endsWith("h")) { multiplier = 3_600_000L;     input = input.replace("h", ""); }
        else if (input.endsWith("d")) { multiplier = 86_400_000L;    input = input.replace("d", ""); }
        else if (input.endsWith("w")) { multiplier = 604_800_000L;   input = input.replace("w", ""); }
        else if (input.endsWith("mo")){ multiplier = 2_592_000_000L; input = input.replace("mo", ""); }
        else if (input.endsWith("y")) { multiplier = 31_536_000_000L;input = input.replace("y", ""); }
        else return null;

        try {
            long num = Long.parseLong(input);
            return num * multiplier;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ============================================================
    // TAB-HILFEN
    // ============================================================

    public List<String> getAllPunishedNames() {
        List<String> list = new ArrayList<>();
        String sql = "SELECT DISTINCT name FROM gf_punishments ORDER BY name ASC";

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String name = rs.getString("name");
                if (name != null && !name.isBlank()) {
                    list.add(name);
                }
            }

        } catch (SQLException e) {
            logger.error("Fehler beim Laden aller Punishment-Namen", e);
        }

        return list;
    }

    public List<String> findKnownNames(String prefix, int limit) {
        List<String> list = new ArrayList<>();
        if (limit <= 0) limit = 30;
        if (prefix == null) prefix = "";
        String key = prefix.toLowerCase(Locale.ROOT) + "%";

        String sql = "SELECT DISTINCT name FROM gf_punishments " +
                "WHERE LOWER(name) LIKE ? ORDER BY name ASC LIMIT ?";

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, key);
            ps.setInt(2, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    if (name != null && !name.isBlank()) {
                        list.add(name);
                    }
                }
            }

        } catch (SQLException e) {
            logger.error("Fehler bei findKnownNames(prefix={})", prefix, e);
        }

        return list;
    }

    public List<String> getActiveBannedNames() {
        return getActiveNamesByType(PunishmentType.BAN);
    }

    public List<String> getActiveMutedNames() {
        return getActiveNamesByType(PunishmentType.MUTE);
    }

    private List<String> getActiveNamesByType(PunishmentType type) {
        List<String> list = new ArrayList<>();
        String sql = "SELECT DISTINCT name FROM gf_punishments " +
                "WHERE type = ? AND active = 1 ORDER BY name ASC";

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    if (name != null && !name.isBlank()) {
                        list.add(name);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Fehler beim Laden aktiver Namen für Typ {}", type, e);
        }

        return list;
    }

    // ============================================================
    // ALT-CHECK: aktive Bans über IP
    // ============================================================

    /**
     * Findet aktive Bans (BAN + IP_BAN) auf derselben IP.
     *
     * @param ip          IP, die geprüft werden soll
     * @param excludeUuid optional: diesen Spieler ausklammern (eigener Login)
     * @param limit       max. Anzahl Zeilen
     */
    public List<Punishment> findActiveBansByIp(String ip, UUID excludeUuid, int limit) {
        List<Punishment> list = new ArrayList<>();
        if (ip == null || ip.isBlank()) return list;
        if (limit <= 0) limit = 10;

        String sql =
                "SELECT * FROM gf_punishments " +
                        "WHERE type IN ('BAN','IP_BAN') " +
                        "AND active = 1 " +
                        "AND ip = ? " +
                        (excludeUuid != null ? "AND (uuid IS NULL OR uuid <> ?) " : "") +
                        "ORDER BY created_at DESC, id DESC LIMIT ?";

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            int idx = 1;
            ps.setString(idx++, ip);
            if (excludeUuid != null) {
                ps.setString(idx++, excludeUuid.toString());
            }
            ps.setInt(idx, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapPunishment(rs));
                }
            }

        } catch (SQLException e) {
            logger.error("Fehler bei findActiveBansByIp(ip={})", ip, e);
        }

        return list;
    }

    // ============================================================
    // ALT-CHECK: alle Namen über IP (History)
    // ============================================================

    /**
     * Findet unterschiedliche Spielernamen, die in gf_punishments mit derselben IP
     * eingetragen sind (egal ob der Punishment noch aktiv ist oder nicht).
     *
     * @param ip          IP, die geprüft werden soll
     * @param excludeUuid optional: diesen Spieler ausklammern (z.B. der, den du gerade checkst)
     * @param limit       max. Anzahl Namen
     */
    public List<String> findAltsByIp(String ip, UUID excludeUuid, int limit) {
        List<String> result = new ArrayList<>();
        if (ip == null || ip.isBlank()) return result;
        if (limit <= 0) limit = 20;

        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT name FROM gf_punishments WHERE ip = ? "
        );

        // Wenn du den aktuellen Spieler rausfiltern willst
        if (excludeUuid != null) {
            sql.append("AND (uuid IS NULL OR uuid <> ?) ");
        }

        sql.append("ORDER BY name ASC LIMIT ?");

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {

            int i = 1;
            ps.setString(i++, ip);
            if (excludeUuid != null) {
                ps.setString(i++, excludeUuid.toString());
            }
            ps.setInt(i, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    if (name != null && !name.isBlank()) {
                        result.add(name);
                    }
                }
            }

        } catch (SQLException e) {
            logger.error("Fehler bei findAltsByIp(ip={})", ip, e);
        }

        return result;
    }

    // ============================================================
    // SHUTDOWN
    // ============================================================

    public void shutdown() {
        logger.info("PunishmentService: Shutdown aufgerufen.");
    }
}
