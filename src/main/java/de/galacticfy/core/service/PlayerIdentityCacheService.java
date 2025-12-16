package de.galacticfy.core.service;

import de.galacticfy.core.database.DatabaseManager;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerIdentityCacheService {

    private final DatabaseManager db;
    private final Logger logger;

    // In-memory Cache (schnell)
    private final Map<String, UUID> nameToUuid = new ConcurrentHashMap<>(); // lower(name) -> uuid
    private final Map<UUID, String> uuidToName = new ConcurrentHashMap<>(); // uuid -> lastName

    public PlayerIdentityCacheService(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
        ensureTable();
        warmupCache(2000); // lädt letzte 2000 Einträge (kein Muss, aber hilfreich)
    }

    // ============================================================
    // PUBLIC API
    // ============================================================

    /**
     * Upsert: Speichere/aktualisiere (uuid, name) bei Join.
     */
    public void upsert(UUID uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) return;

        String clean = name.trim();
        String key = clean.toLowerCase(Locale.ROOT);

        // memory
        nameToUuid.put(key, uuid);
        uuidToName.put(uuid, clean);
// db
try (Connection c = db.getConnection()) {
    String sql;
    if (db.isSQLite()) {
        sql = "INSERT INTO gf_identity_cache (uuid, name, last_seen) " +
                "VALUES (?, ?, CURRENT_TIMESTAMP) " +
                "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, last_seen = CURRENT_TIMESTAMP";
    } else {
        sql = "INSERT INTO gf_identity_cache (uuid, name, last_seen) " +
                "VALUES (?, ?, CURRENT_TIMESTAMP) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), last_seen = CURRENT_TIMESTAMP";
    }

    try (PreparedStatement ps = c.prepareStatement(sql)) {
        ps.setString(1, uuid.toString());
        ps.setString(2, clean);
        ps.executeUpdate();
    }
} catch (Exception e) {
    if (logger != null) logger.warn("IdentityCache upsert failed for {} / {}: {}", uuid, clean, e.toString());
}
    }

    /**
     * Finde UUID anhand Name (case-insensitive).
     */
    public Optional<UUID> findUuidByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String key = name.trim().toLowerCase(Locale.ROOT);

        UUID cached = nameToUuid.get(key);
        if (cached != null) return Optional.of(cached);

        // DB fallback
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT uuid FROM gf_identity_cache WHERE LOWER(name)=LOWER(?) LIMIT 1"
             )) {
            ps.setString(1, name.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    nameToUuid.put(key, uuid);
                    // optional auch uuid->name nachziehen
                    uuidToName.putIfAbsent(uuid, name.trim());
                    return Optional.of(uuid);
                }
            }
        } catch (Exception e) {
            if (logger != null) logger.warn("IdentityCache findUuidByName failed for {}: {}", name, e.toString());
        }

        return Optional.empty();
    }

    /**
     * Finde letzten bekannten Namen anhand UUID.
     */
    public Optional<String> findNameByUuid(UUID uuid) {
        if (uuid == null) return Optional.empty();

        String cached = uuidToName.get(uuid);
        if (cached != null && !cached.isBlank()) return Optional.of(cached);

        // DB fallback
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name FROM gf_identity_cache WHERE uuid=? LIMIT 1"
             )) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String name = rs.getString("name");
                    if (name != null && !name.isBlank()) {
                        uuidToName.put(uuid, name);
                        nameToUuid.putIfAbsent(name.toLowerCase(Locale.ROOT), uuid);
                        return Optional.of(name);
                    }
                }
            }
        } catch (Exception e) {
            if (logger != null) logger.warn("IdentityCache findNameByUuid failed for {}: {}", uuid, e.toString());
        }

        return Optional.empty();
    }

    /**
     * Tabcomplete-Hilfe: gib bekannte Namen zurück, die mit prefix starten.
     */
    public List<String> getKnownNames(String prefix, int limit) {
        int lim = Math.max(1, Math.min(limit, 100));
        String pfx = (prefix == null) ? "" : prefix.trim().toLowerCase(Locale.ROOT);

        // 1) erst aus Memory
        List<String> fromMem = uuidToName.values().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(pfx))
                .distinct()
                .limit(lim)
                .toList();

        if (fromMem.size() >= lim) return fromMem;

        // 2) DB ergänzen
        List<String> out = new ArrayList<>(fromMem);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name FROM gf_identity_cache WHERE LOWER(name) LIKE ? ORDER BY last_seen DESC LIMIT ?"
             )) {
            ps.setString(1, pfx + "%");
            ps.setInt(2, lim * 2); // etwas mehr ziehen und dann distinct/limit
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && out.size() < lim) {
                    String n = rs.getString("name");
                    if (n == null) continue;

                    n = n.trim();
                    if (n.isBlank()) continue;

                    final String nameFinal = n; // <- jetzt effektiv final für Lambda
                    if (out.stream().anyMatch(x -> x.equalsIgnoreCase(nameFinal))) continue;

                    out.add(nameFinal);
                }
            }
        } catch (Exception e) {
            if (logger != null) logger.warn("IdentityCache getKnownNames failed: {}", e.toString());
        }

        return out;
    }

    // ============================================================
    // INTERNAL: TABLE + WARMUP
    // ============================================================

    private void ensureTable() {
        try (Connection c = db.getConnection()) {

            if (db.isSQLite()) {
                // SQLite: indices must be created separately (no inline INDEX in CREATE TABLE).
                try (PreparedStatement ps = c.prepareStatement(
                        "CREATE TABLE IF NOT EXISTS gf_identity_cache (" +
                                "uuid TEXT NOT NULL PRIMARY KEY," +
                                "name TEXT NOT NULL," +
                                "last_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                                ")"
                )) {
                    ps.execute();
                }
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_identity_name ON gf_identity_cache(name);");
                    st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_identity_last_seen ON gf_identity_cache(last_seen);");
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "CREATE TABLE IF NOT EXISTS gf_identity_cache (" +
                                "uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
                                "name VARCHAR(16) NOT NULL," +
                                "last_seen TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                                ")"
                )) {
                    ps.execute();
                }
                // External DB: create indices best-effort.
                try (Statement st = c.createStatement()) {
                    try { st.executeUpdate("CREATE INDEX idx_identity_name ON gf_identity_cache(name);"); } catch (SQLException ignored) {}
                    try { st.executeUpdate("CREATE INDEX idx_identity_last_seen ON gf_identity_cache(last_seen);"); } catch (SQLException ignored) {}
                }
            }
        } catch (Exception e) {
            if (logger != null) logger.warn("IdentityCache ensureTable failed: {}", e.toString());
        }
    }

    private void warmupCache(int maxRows) {
        int lim = Math.max(0, Math.min(maxRows, 10000));
        if (lim <= 0) return;

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT uuid, name FROM gf_identity_cache ORDER BY last_seen DESC LIMIT ?"
             )) {
            ps.setInt(1, lim);
            try (ResultSet rs = ps.executeQuery()) {
                int loaded = 0;
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String name = rs.getString("name");
                    if (name == null || name.isBlank()) continue;

                    uuidToName.put(uuid, name);
                    nameToUuid.put(name.toLowerCase(Locale.ROOT), uuid);
                    loaded++;
                }
                if (logger != null) logger.info("IdentityCache warmup: {} entries loaded.", loaded);
            }
        } catch (Exception e) {
            if (logger != null) logger.warn("IdentityCache warmup failed: {}", e.toString());
        }
    }
    public void update(UUID uuid, String name) {
        if (uuid == null || name == null) return;

        String n = name.trim();
        if (n.isBlank()) return;

        // Wenn du bereits eine Methode hast wie "upsert(uuid, n)" oder "store(uuid, n)" -> die hier aufrufen:
        upsert(uuid, n);

        // Optional: Cache-Strukturen (Maps) aktualisieren, falls vorhanden:
        // uuidToName.put(uuid, n);
        // nameToUuid.put(n.toLowerCase(Locale.ROOT), uuid);
    }

}
