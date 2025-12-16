package de.galacticfy.core.service;

import de.galacticfy.core.database.DatabaseManager;
import org.slf4j.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class EconomyService {

    // Einheitlicher Datentyp für jeden Account
    public record Account(UUID uuid, String name, long balance, long stardust) {}

    private final DatabaseManager db;
    private final Logger logger;

    public EconomyService(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    // ============================================================
    // INTERN: Account anlegen / sicherstellen
    // ============================================================

    public void ensureAccount(UUID uuid, String name) throws SQLException {
    if (uuid == null) return;
    String safeName = (name == null ? "" : name);

    String sql;
    if (db.isSQLite()) {
        // SQLite UPSERT
        sql = "INSERT INTO gf_economy (uuid, name, balance, stardust) " +
                "VALUES (?, ?, 0, 0) " +
                "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name";
    } else {
        // MariaDB/MySQL UPSERT
        sql = "INSERT INTO gf_economy (uuid, name, balance, stardust) " +
                "VALUES (?, ?, 0, 0) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name)";
    }

    try (Connection con = db.getConnection();
         PreparedStatement ps = con.prepareStatement(sql)) {
        ps.setString(1, uuid.toString());
        ps.setString(2, safeName);
        ps.executeUpdate();
    }
}

    // ============================================================
    // GALAS (Normale Währung → balance)
    // ============================================================

    public long getBalance(UUID uuid) {
        if (uuid == null) return 0L;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT balance FROM gf_economy WHERE uuid = ?"
             )) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("balance");
                }
            }
        } catch (SQLException e) {
            logger.error("Fehler beim Laden des Kontostands für {}", uuid, e);
        }
        return 0L;
    }

    public boolean setBalance(UUID uuid, long amount) {
        if (uuid == null) return false;
        if (amount < 0) amount = 0;

        try (Connection con = db.getConnection()) {

            int updated;
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE gf_economy SET balance = ? WHERE uuid = ?"
            )) {
                ps.setLong(1, amount);
                ps.setString(2, uuid.toString());
                updated = ps.executeUpdate();
            }

            if (updated == 0) {
                try (PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO gf_economy (uuid, name, balance, stardust)
                        VALUES (?, ?, ?, 0)
                        """)) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, "Unknown");
                    ps.setLong(3, amount);
                    ps.executeUpdate();
                }
            }

            return true;
        } catch (SQLException e) {
            logger.error("Fehler beim Setzen des Kontostands für {}", uuid, e);
            return false;
        }
    }

    public boolean deposit(UUID uuid, long amount) {
        if (uuid == null || amount <= 0) return false;
        long current = getBalance(uuid);
        long next = current + amount;
        if (next < 0) next = Long.MAX_VALUE; // Overflow-Schutz
        return setBalance(uuid, next);
    }

    public boolean withdraw(UUID uuid, long amount) {
        if (uuid == null || amount <= 0) return false;
        long current = getBalance(uuid);
        if (current < amount) return false;
        return setBalance(uuid, current - amount);
    }

    public boolean transfer(UUID from, UUID to, long amount) {
        if (from == null || to == null) return false;
        if (amount <= 0) return false;
        if (from.equals(to)) return false;

        try (Connection con = db.getConnection()) {
            con.setAutoCommit(false);

            long fromBal;
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT balance FROM gf_economy WHERE uuid = ? FOR UPDATE"
            )) {
                ps.setString(1, from.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        con.rollback();
                        return false;
                    }
                    fromBal = rs.getLong("balance");
                }
            }

            if (fromBal < amount) {
                con.rollback();
                return false;
            }

            long toBal = 0;
            boolean toExists;

            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT balance FROM gf_economy WHERE uuid = ? FOR UPDATE"
            )) {
                ps.setString(1, to.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        toExists = true;
                        toBal = rs.getLong("balance");
                    } else {
                        toExists = false;
                    }
                }
            }

            // Absender updaten
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE gf_economy SET balance = ? WHERE uuid = ?"
            )) {
                ps.setLong(1, fromBal - amount);
                ps.setString(2, from.toString());
                ps.executeUpdate();
            }

            // Empfänger updaten / anlegen
            if (toExists) {
                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE gf_economy SET balance = ? WHERE uuid = ?"
                )) {
                    ps.setLong(1, toBal + amount);
                    ps.setString(2, to.toString());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO gf_economy (uuid, name, balance, stardust)
                        VALUES (?, ?, ?, 0)
                        """)) {
                    ps.setString(1, to.toString());
                    ps.setString(2, "Unknown");
                    ps.setLong(3, amount);
                    ps.executeUpdate();
                }
            }

            con.commit();
            return true;
        } catch (SQLException e) {
            logger.error("Fehler bei Transfer {} → {} ({} Galas)", from, to, amount, e);
            return false;
        }
    }

    // Alte simple Top-Liste (ohne Offset) – optional
    public List<Account> getTopBalances(int limit) {
        List<Account> list = new ArrayList<>();
        if (limit <= 0) limit = 10;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement("""
                     SELECT uuid, name, balance, stardust
                     FROM gf_economy
                     ORDER BY balance DESC
                     LIMIT ?
                     """)) {

            ps.setInt(1, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapAccount(rs));
                }
            }

        } catch (SQLException e) {
            logger.error("Fehler beim Laden der Top-Balances", e);
        }

        return list;
    }

    // ============================================================
    // STARDUST (Premium/Event-Währung)
    // ============================================================

    public long getStardust(UUID uuid) {
        if (uuid == null) return 0L;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT stardust FROM gf_economy WHERE uuid = ?"
             )) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("stardust");
                }
            }

        } catch (SQLException e) {
            logger.error("Fehler beim Laden von Stardust für {}", uuid, e);
        }
        return 0L;
    }

    public boolean setStardust(UUID uuid, long amount) {
        if (uuid == null) return false;
        if (amount < 0) amount = 0;

        try (Connection con = db.getConnection()) {

            int updated;
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE gf_economy SET stardust = ? WHERE uuid = ?"
            )) {
                ps.setLong(1, amount);
                ps.setString(2, uuid.toString());
                updated = ps.executeUpdate();
            }

            if (updated == 0) {
                try (PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO gf_economy (uuid, name, balance, stardust)
                        VALUES (?, ?, 0, ?)
                        """)) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, "Unknown");
                    ps.setLong(3, amount);
                    ps.executeUpdate();
                }
            }

            return true;
        } catch (SQLException e) {
            logger.error("Fehler beim Setzen von Stardust für {}", uuid, e);
            return false;
        }
    }

    public boolean addStardust(UUID uuid, long amount) {
        if (uuid == null || amount <= 0) return false;
        long current = getStardust(uuid);
        long next = current + amount;
        if (next < 0) next = Long.MAX_VALUE;
        return setStardust(uuid, next);
    }

    public boolean removeStardust(UUID uuid, long amount) {
        if (uuid == null || amount <= 0) return false;
        long current = getStardust(uuid);
        if (current < amount) return false;
        return setStardust(uuid, current - amount);
    }

    public List<Account> getTopStardust(int limit) {
        List<Account> list = new ArrayList<>();
        if (limit <= 0) limit = 10;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement("""
                     SELECT uuid, name, balance, stardust
                     FROM gf_economy
                     ORDER BY stardust DESC
                     LIMIT ?
                     """)) {

            ps.setInt(1, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapAccount(rs));
                }
            }

        } catch (SQLException e) {
            logger.error("Fehler beim Laden der Top-Stardust-Liste", e);
        }

        return list;
    }

    // ============================================================
    // Paginierte Rankings für /baltop
    // ============================================================

    // /baltop → sortiert nach Galas
    public List<Account> getTopAccounts(int limit, int offset) {
        List<Account> result = new ArrayList<>();
        if (limit <= 0) limit = 10;
        if (offset < 0) offset = 0;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement("""
                     SELECT uuid, name, balance, stardust
                     FROM gf_economy
                     ORDER BY balance DESC
                     LIMIT ? OFFSET ?
                     """)) {

            ps.setInt(1, limit);
            ps.setInt(2, offset);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapAccount(rs));
                }
            }

        } catch (SQLException e) {
            logger.error("Fehler beim Abrufen von Baltop (Galas)", e);
        }

        return result;
    }

    // /baltop stardust → sortiert nach Stardust
    public List<Account> getTopAccountsStardust(int limit, int offset) {
        List<Account> result = new ArrayList<>();
        if (limit <= 0) limit = 10;
        if (offset < 0) offset = 0;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement("""
                     SELECT uuid, name, balance, stardust
                     FROM gf_economy
                     ORDER BY stardust DESC
                     LIMIT ? OFFSET ?
                     """)) {

            ps.setInt(1, limit);
            ps.setInt(2, offset);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapAccount(rs));
                }
            }

        } catch (SQLException e) {
            logger.error("Fehler beim Abrufen von Stardust-Baltop", e);
        }

        return result;
    }

    // ============================================================
    // INTERN: Mapper
    // ============================================================

    private Account mapAccount(ResultSet rs) throws SQLException {
        UUID uuid = UUID.fromString(rs.getString("uuid"));
        String name = rs.getString("name");
        long balance = rs.getLong("balance");
        long stardust = rs.getLong("stardust");
        return new Account(uuid, name, balance, stardust);
    }
}
