package de.galacticfy.core.util;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Vendor-safe timestamp parsing helper.
 *
 * This utility exists because different JDBC drivers / schemas may store time values as:
 * - {@link java.sql.Timestamp} (typical for MariaDB/MySQL/Postgres TIMESTAMP columns)
 * - epoch milliseconds/seconds as numeric types or digit strings (common in SQLite setups)
 * - SQLite CURRENT_TIMESTAMP text ("yyyy-MM-dd HH:mm:ss")
 */
public final class DbTimeUtil {

    private static final long MILLIS_THRESHOLD = 1_000_000_000_000L; // ~2001-09-09 in ms

    private DbTimeUtil() {
    }

    public static Instant readInstant(ResultSet rs, String column) throws SQLException {
        Object obj = rs.getObject(column);
        return toInstant(obj);
    }

    public static Instant readInstant(ResultSet rs, int columnIndex) throws SQLException {
        Object obj = rs.getObject(columnIndex);
        return toInstant(obj);
    }

    public static LocalDateTime readLocalDateTime(ResultSet rs, String column) throws SQLException {
        Instant i = readInstant(rs, column);
        return i != null ? LocalDateTime.ofInstant(i, ZoneId.systemDefault()) : null;
    }

    public static LocalDateTime readLocalDateTime(ResultSet rs, int columnIndex) throws SQLException {
        Instant i = readInstant(rs, columnIndex);
        return i != null ? LocalDateTime.ofInstant(i, ZoneId.systemDefault()) : null;
    }

    public static Long readEpochMillis(ResultSet rs, String column) throws SQLException {
        Instant i = readInstant(rs, column);
        return i != null ? i.toEpochMilli() : null;
    }

    private static Instant toInstant(Object obj) {
        if (obj == null) return null;

        if (obj instanceof Instant instant) {
            return instant;
        }

        if (obj instanceof Timestamp ts) {
            return ts.toInstant();
        }

        if (obj instanceof java.util.Date d) {
            return Instant.ofEpochMilli(d.getTime());
        }

        if (obj instanceof Number n) {
            long v = n.longValue();
            return (v >= MILLIS_THRESHOLD) ? Instant.ofEpochMilli(v) : Instant.ofEpochSecond(v);
        }

        if (obj instanceof CharSequence cs) {
            String s = cs.toString().trim();
            if (s.isEmpty()) return null;

            // Digit-only strings -> epoch seconds/millis
            if (s.matches("\\d+")) {
                try {
                    long v = Long.parseLong(s);
                    return (v >= MILLIS_THRESHOLD) ? Instant.ofEpochMilli(v) : Instant.ofEpochSecond(v);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }

            // SQLite CURRENT_TIMESTAMP: "YYYY-MM-DD HH:MM:SS" (optionally with .SSS)
            // Timestamp.valueOf expects "YYYY-[M]M-[D]D hh:mm:ss[.f...]".
            try {
                return Timestamp.valueOf(s).toInstant();
            } catch (IllegalArgumentException ignored) {
                // Not that format
            }

            // ISO-8601
            try {
                return Instant.parse(s);
            } catch (Exception ignored) {
                return null;
            }
        }

        // Unknown type
        return null;
    }
}
