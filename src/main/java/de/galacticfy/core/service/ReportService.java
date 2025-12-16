package de.galacticfy.core.service;

import de.galacticfy.core.database.DatabaseManager;
import org.slf4j.Logger;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ReportService {

    public record ReportEntry(
            long id,
            String targetName,
            String reporterName,
            String reason,
            String serverName,
            String presetKey,
            LocalDateTime createdAt,
            boolean handled,
            String handledBy,
            LocalDateTime handledAt
    ) {}

    private final DatabaseManager db;
    private final Logger logger;

    public ReportService(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    // ============================================================
    // NEUEN REPORT SPEICHERN
    // ============================================================

    public void addReport(String targetName, String reporterName, String reason, String serverName, String presetKey) {
        if (targetName == null || targetName.isBlank()) return;

        if (reporterName == null || reporterName.isBlank()) reporterName = "Unbekannt";
        if (reason == null || reason.isBlank()) reason = "Kein Grund angegeben";
        if (serverName == null || serverName.isBlank()) serverName = "Unbekannt";

        String sql = """
                INSERT INTO gf_reports
                (created_at, reporter_name, target_name, server_name, reason, preset_key, handled, handled_by, handled_at)
                VALUES (CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, 0, NULL, NULL)
                """;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, reporterName);
            ps.setString(2, targetName);
            ps.setString(3, serverName);
            ps.setString(4, reason);

            if (presetKey != null && !presetKey.isBlank()) {
                ps.setString(5, presetKey.toLowerCase(Locale.ROOT));
            } else {
                ps.setNull(5, Types.VARCHAR);
            }

            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Fehler beim Speichern eines Reports (target={}, reporter={})", targetName, reporterName, e);
        }
    }

    // ============================================================
    // REPORTS LADEN
    // ============================================================

    public List<ReportEntry> getReportsFor(String targetName) {
        List<ReportEntry> out = new ArrayList<>();
        if (targetName == null || targetName.isBlank()) return out;

        String sql = """
                SELECT id, created_at, reporter_name, target_name, server_name, reason, preset_key,
                       handled, handled_by, handled_at
                FROM gf_reports
                WHERE LOWER(target_name) = ?
                ORDER BY created_at DESC, id DESC
                """;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, targetName.toLowerCase(Locale.ROOT));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapEntry(rs));
                }
            }

        } catch (SQLException e) {
            logger.error("Fehler beim Laden der Reports für {}", targetName, e);
        }

        return out;
    }

    public List<ReportEntry> getAllReports() {
        List<ReportEntry> out = new ArrayList<>();

        String sql = """
                SELECT id, created_at, reporter_name, target_name, server_name, reason, preset_key,
                       handled, handled_by, handled_at
                FROM gf_reports
                ORDER BY created_at DESC, id DESC
                """;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                out.add(mapEntry(rs));
            }

        } catch (SQLException e) {
            logger.error("Fehler beim Laden aller Reports", e);
        }

        return out;
    }

    public List<ReportEntry> getOpenReports() {
        List<ReportEntry> out = new ArrayList<>();

        String sql = """
                SELECT id, created_at, reporter_name, target_name, server_name, reason, preset_key,
                       handled, handled_by, handled_at
                FROM gf_reports
                WHERE handled = 0
                ORDER BY created_at ASC, id ASC
                """;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                out.add(mapEntry(rs));
            }

        } catch (SQLException e) {
            logger.error("Fehler beim Laden der offenen Reports", e);
        }

        return out;
    }

    public ReportEntry getReportById(long id) {
        String sql = """
                SELECT id, created_at, reporter_name, target_name, server_name, reason, preset_key,
                       handled, handled_by, handled_at
                FROM gf_reports
                WHERE id = ?
                """;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setLong(1, id);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapEntry(rs);
                }
            }

        } catch (SQLException e) {
            logger.error("Fehler beim Laden eines Reports per ID (id={})", id, e);
        }

        return null;
    }

    // ============================================================
    // CLAIM / UNCLAIM
    // ============================================================

    public boolean claimReport(long id, String staffName) {
        if (staffName == null || staffName.isBlank()) staffName = "Unbekannt";

        // Claim nur wenn offen UND noch unclaimed
        String sql = """
                UPDATE gf_reports
                SET handled_by = ?
                WHERE id = ?
                  AND handled = 0
                  AND (handled_by IS NULL OR handled_by = '')
                """;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, staffName);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;

        } catch (SQLException e) {
            logger.error("Fehler beim Claim eines Reports (id={}, staff={})", id, staffName, e);
            return false;
        }
    }

    /**
     * Unclaim:
     * - Wenn staffName = null => Admin: unclaim immer (sofern offen)
     * - Wenn staffName != null => nur wenn genau dieser Staff geclaimt hat
     */
    public boolean unclaimReport(long id, String staffName) {
        String sqlAdmin = """
                UPDATE gf_reports
                SET handled_by = NULL
                WHERE id = ?
                  AND handled = 0
                """;

        String sqlSelf = """
                UPDATE gf_reports
                SET handled_by = NULL
                WHERE id = ?
                  AND handled = 0
                  AND handled_by = ?
                """;

        try (Connection con = db.getConnection()) {
            if (staffName == null) {
                try (PreparedStatement ps = con.prepareStatement(sqlAdmin)) {
                    ps.setLong(1, id);
                    return ps.executeUpdate() > 0;
                }
            } else {
                try (PreparedStatement ps = con.prepareStatement(sqlSelf)) {
                    ps.setLong(1, id);
                    ps.setString(2, staffName);
                    return ps.executeUpdate() > 0;
                }
            }
        } catch (SQLException e) {
            logger.error("Fehler beim Unclaim eines Reports (id={}, staff={})", id, staffName, e);
            return false;
        }
    }

    public boolean isClaimedBy(long id, String staffName) {
        if (staffName == null || staffName.isBlank()) return false;

        String sql = """
                SELECT handled_by
                FROM gf_reports
                WHERE id = ?
                """;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String handledBy = rs.getString(1);
                    return handledBy != null && handledBy.equalsIgnoreCase(staffName);
                }
            }
        } catch (SQLException e) {
            logger.error("Fehler bei isClaimedBy(id={}, staff={})", id, staffName, e);
        }

        return false;
    }

    // ============================================================
    // REPORT ALS BEARBEITET MARKIEREN (CLOSE)
    // ============================================================

    public boolean markReportHandled(long id, String staffName) {
        if (staffName == null || staffName.isBlank()) staffName = "Unbekannt";

        String sql = """
                UPDATE gf_reports
                SET handled = 1,
                    handled_by = ?,
                    handled_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND handled = 0
                """;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, staffName);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;

        } catch (SQLException e) {
            logger.error("Fehler beim Schließen eines Reports (id={})", id, e);
            return false;
        }
    }

    // ============================================================
    // COUNT + CLEAR
    // ============================================================

    public int countAllReports() {
        String sql = "SELECT COUNT(*) FROM gf_reports";
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            logger.error("Fehler bei countAllReports()", e);
        }
        return 0;
    }

    public int countOpenReports() {
        String sql = "SELECT COUNT(*) FROM gf_reports WHERE handled = 0";
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            logger.error("Fehler bei countOpenReports()", e);
        }
        return 0;
    }

    public boolean clearReportsFor(String targetName) {
        if (targetName == null || targetName.isBlank()) return false;

        String sql = "DELETE FROM gf_reports WHERE LOWER(target_name) = ?";
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, targetName.toLowerCase(Locale.ROOT));
            return ps.executeUpdate() > 0;

        } catch (SQLException e) {
            logger.error("Fehler beim Löschen der Reports für {}", targetName, e);
            return false;
        }
    }

    public int clearAll() {
        String sql = "DELETE FROM gf_reports";
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Fehler beim Löschen aller Reports", e);
            return 0;
        }
    }

    // ============================================================
    // TAB-COMPLETE HELPERS
    // ============================================================

    public List<String> getReportedTargetNames() {
        List<String> out = new ArrayList<>();

        String sql = """
                SELECT DISTINCT target_name
                FROM gf_reports
                ORDER BY target_name ASC
                """;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String name = rs.getString(1);
                if (name != null && !name.isBlank()) out.add(name);
            }

        } catch (SQLException e) {
            logger.error("Fehler beim Laden der Report-Zielnamen", e);
        }

        return out;
    }

    // ============================================================
    // INTERN
    // ============================================================

    private ReportEntry mapEntry(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");

        LocalDateTime createdAt = de.galacticfy.core.util.DbTimeUtil.readLocalDateTime(rs, "created_at");

        String reporter = rs.getString("reporter_name");
        String target = rs.getString("target_name");
        String server = rs.getString("server_name");
        String reason = rs.getString("reason");
        String presetKey = rs.getString("preset_key");

        boolean handled = rs.getBoolean("handled");
        String handledBy = rs.getString("handled_by");

        LocalDateTime handledAt = de.galacticfy.core.util.DbTimeUtil.readLocalDateTime(rs, "handled_at");

        return new ReportEntry(
                id,
                target,
                reporter,
                reason,
                server,
                presetKey,
                createdAt,
                handled,
                handledBy,
                handledAt
        );
    }
}
