package de.galacticfy.core.service;

import de.galacticfy.core.database.DatabaseManager;
import org.slf4j.Logger;

import java.sql.*;
import java.time.LocalDate;
import java.util.UUID;

public class DailyRewardService {

    public record ClaimResult(
            boolean success,
            boolean alreadyClaimed,
            int streak,
            long galasReward,
            long stardustReward,
            LocalDate lastClaimDate
    ) {}

    private final DatabaseManager db;
    private final EconomyService economy;
    private final Logger logger;

    public DailyRewardService(DatabaseManager db, EconomyService economy, Logger logger) {
        this.db = db;
        this.economy = economy;
        this.logger = logger;
    }

    /**
     * /daily abholen
     */
    public ClaimResult claimDaily(UUID uuid, String name) {
        if (uuid == null) {
            return new ClaimResult(false, false, 0, 0, 0, null);
        }
        if (name == null || name.isBlank()) name = "Unknown";

        LocalDate today = LocalDate.now();
        LocalDate last = null;
        int oldStreak = 0;
        boolean exists = false;

        try (Connection con = db.getConnection()) {

            // alten Datensatz lesen
            try (PreparedStatement ps = con.prepareStatement("""
                    SELECT last_claim_date, streak
                    FROM gf_daily_rewards
                    WHERE uuid = ?
                    """)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        exists = true;
                        Date d = rs.getDate("last_claim_date");
                        if (d != null) {
                            last = d.toLocalDate();
                        }
                        oldStreak = rs.getInt("streak");
                    }
                }
            }

            if (last != null && last.equals(today)) {
                // heute schon abgeholt
                return new ClaimResult(false, true, oldStreak, 0, 0, last);
            }

            int newStreak;
            if (last != null && last.plusDays(1).equals(today)) {
                newStreak = oldStreak + 1;
            } else {
                newStreak = 1;
            }

            long galasReward = calcGalasReward(newStreak);
            long stardustReward = calcStardustReward(newStreak);

            // DB updaten / einfügen
            if (exists) {
                try (PreparedStatement ps = con.prepareStatement("""
                        UPDATE gf_daily_rewards
                        SET name = ?, last_claim_date = ?, streak = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE uuid = ?
                        """)) {
                    ps.setString(1, name);
                    ps.setDate(2, Date.valueOf(today));
                    ps.setInt(3, newStreak);
                    ps.setString(4, uuid.toString());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO gf_daily_rewards (uuid, name, last_claim_date, streak)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, name);
                    ps.setDate(3, Date.valueOf(today));
                    ps.setInt(4, newStreak);
                    ps.executeUpdate();
                }
            }

            // Belohnungen gutschreiben
            if (galasReward > 0) {
                economy.deposit(uuid, galasReward);
            }
            if (stardustReward > 0) {
                economy.addStardust(uuid, stardustReward);
            }

            return new ClaimResult(true, false, newStreak, galasReward, stardustReward, today);

        } catch (SQLException e) {
            logger.error("Fehler bei /daily für {} ({})", name, uuid, e);
            return new ClaimResult(false, false, 0, 0, 0, last);
        }
    }

    /**
     * Admin-Reset für einen Spieler (/daily reset <Spieler>)
     */
    public boolean resetDaily(UUID uuid) {
        if (uuid == null) return false;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "DELETE FROM gf_daily_rewards WHERE uuid = ?"
             )) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.error("Fehler bei resetDaily für {}", uuid, e);
            return false;
        }
    }

    private long calcGalasReward(int streak) {
        // Beispiel: Tag 1 = 500, dann +100 pro Tag, max 2000
        long base = 500 + (long) (streak - 1) * 100;
        return Math.min(base, 2000);
    }

    private long calcStardustReward(int streak) {
        // z.B. alle 7 Tage 1✧ Stardust
        if (streak > 0 && streak % 7 == 0) {
            return 1;
        }
        return 0;
    }
}
