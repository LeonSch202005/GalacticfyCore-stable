package de.galacticfy.core.database;

import org.slf4j.Logger;

import java.sql.*;

/**
 * Creates/updates the schema for all GalacticfyCore modules.
 *
 * IMPORTANT: This plugin supports multiple database backends.
 * - External: MariaDB/MySQL/Postgres
 * - Fallback: SQLite (file/in-memory)
 *
 * Therefore, DDL must be vendor-compatible. SQLite is the strictest dialect
 * (no ENGINE clauses, no inline INDEX, no ON UPDATE CURRENT_TIMESTAMP, ...).
 */
public class DatabaseMigrationService {

    private final DatabaseManager db;
    private final Logger logger;

    public DatabaseMigrationService(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    public void runMigrations() {
        try (Connection con = db.getConnection(); Statement st = con.createStatement()) {

            final boolean sqlite = db.isSQLite();

            if (sqlite) {
                // SQLite: keep schema simple and portable. Indices must be created separately.
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_roles (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            name TEXT NOT NULL UNIQUE,
                            display_name TEXT NOT NULL,
                            color_hex TEXT NULL,
                            prefix TEXT NULL,
                            suffix TEXT NULL,
                            is_staff INTEGER NOT NULL DEFAULT 0,
                            maintenance_bypass INTEGER NOT NULL DEFAULT 0,
                            join_priority INTEGER NOT NULL DEFAULT 0
                        )
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_user_roles (
                            uuid TEXT NOT NULL PRIMARY KEY,
                            name TEXT NOT NULL,
                            role_id INTEGER NOT NULL,
                            expires_at TEXT NULL
                        )
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_role_permissions (
                            role_id INTEGER NOT NULL,
                            permission TEXT NOT NULL,
                            server_scope TEXT NOT NULL DEFAULT 'GLOBAL',
                            PRIMARY KEY (role_id, permission, server_scope)
                        )
                        """);

                // Role inheritance (role -> parent role)
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_role_inherits (
                            role_id INTEGER NOT NULL,
                            parent_role_id INTEGER NOT NULL,
                            PRIMARY KEY (role_id, parent_role_id)
                        )
                        """);
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_role_inherits_role ON gf_role_inherits(role_id);");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_role_inherits_parent ON gf_role_inherits(parent_role_id);");

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_sessions (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            uuid TEXT NOT NULL,
                            name TEXT NOT NULL,
                            first_login TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            last_login TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            last_logout TEXT NULL,
                            total_play_seconds INTEGER NOT NULL DEFAULT 0,
                            last_server TEXT NULL
                        )
                        """);
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_uuid ON gf_sessions(uuid);");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_name ON gf_sessions(name);");

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_economy (
                            uuid TEXT NOT NULL PRIMARY KEY,
                            name TEXT NOT NULL,
                            balance INTEGER NOT NULL DEFAULT 0,
                            stardust INTEGER NOT NULL DEFAULT 0,
                            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_daily_rewards (
                            uuid TEXT NOT NULL PRIMARY KEY,
                            name TEXT NOT NULL,
                            last_claim_date TEXT NOT NULL,
                            streak INTEGER NOT NULL DEFAULT 1,
                            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_maintenance_config (
                            id INTEGER PRIMARY KEY,
                            enabled INTEGER NOT NULL DEFAULT 0,
                            end_at TEXT NULL
                        )
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_maintenance_whitelist_players (
                            name TEXT NOT NULL PRIMARY KEY
                        )
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_maintenance_whitelist_groups (
                            group_name TEXT NOT NULL PRIMARY KEY
                        )
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_quests (
                            quest_key TEXT NOT NULL PRIMARY KEY,
                            title TEXT NOT NULL,
                            description TEXT NOT NULL,
                            type TEXT NOT NULL,
                            goal INTEGER NOT NULL,
                            reward_galas INTEGER NOT NULL DEFAULT 0,
                            reward_stardust INTEGER NOT NULL DEFAULT 0,
                            active INTEGER NOT NULL DEFAULT 1,
                            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_quest_progress (
                            uuid TEXT NOT NULL,
                            quest_key TEXT NOT NULL,
                            progress INTEGER NOT NULL DEFAULT 0,
                            completed INTEGER NOT NULL DEFAULT 0,
                            completed_at TEXT NULL,
                            reward_claimed INTEGER NOT NULL DEFAULT 0,
                            last_update TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            PRIMARY KEY (uuid, quest_key)
                        )
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_punishments (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            uuid TEXT NULL,
                            name TEXT NOT NULL,
                            ip TEXT NULL,
                            type TEXT NOT NULL,
                            reason TEXT NOT NULL,
                            staff TEXT NOT NULL,
                            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            expires_at TEXT NULL,
                            active INTEGER NOT NULL DEFAULT 1
                        )
                        """);
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_punish_uuid_type_active ON gf_punishments(uuid, type, active);");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_punish_ip_type_active ON gf_punishments(ip, type, active);");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_punish_name ON gf_punishments(name);");

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_reports (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            reporter_name TEXT NOT NULL,
                            target_name TEXT NOT NULL,
                            server_name TEXT NULL,
                            reason TEXT NOT NULL,
                            preset_key TEXT NULL,
                            handled INTEGER NOT NULL DEFAULT 0,
                            handled_by TEXT NULL,
                            handled_at TEXT NULL
                        )
                        """);
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_reports_target ON gf_reports(target_name);");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_reports_created_at ON gf_reports(created_at);");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_reports_handled ON gf_reports(handled);");

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_npcs (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            server_name TEXT NOT NULL,
                            name TEXT NOT NULL,
                            world TEXT NOT NULL,
                            x REAL NOT NULL,
                            y REAL NOT NULL,
                            z REAL NOT NULL,
                            yaw REAL NOT NULL,
                            pitch REAL NOT NULL,
                            type TEXT NOT NULL,
                            target_server TEXT NULL,
                            skin_uuid TEXT NULL,
                            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )
                        """);

            } else {
                // External DB: keep MySQL/MariaDB-compatible DDL. Postgres is supported via JDBC,
                // but this schema is written for the typical MariaDB/MySQL setup.
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_roles (
                            id INT AUTO_INCREMENT PRIMARY KEY,
                            name VARCHAR(64) NOT NULL UNIQUE,
                            display_name VARCHAR(64) NOT NULL,
                            color_hex VARCHAR(16) NULL,
                            prefix VARCHAR(128) NULL,
                            suffix VARCHAR(128) NULL,
                            is_staff TINYINT(1) NOT NULL DEFAULT 0,
                            maintenance_bypass TINYINT(1) NOT NULL DEFAULT 0,
                            join_priority INT NOT NULL DEFAULT 0
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_user_roles (
                            uuid CHAR(36) NOT NULL PRIMARY KEY,
                            name VARCHAR(16) NOT NULL,
                            role_id INT NOT NULL,
                            expires_at TIMESTAMP NULL DEFAULT NULL,
                            FOREIGN KEY (role_id) REFERENCES gf_roles(id) ON DELETE CASCADE
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                        """);

                // role permissions with server scope
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_role_permissions (
                            role_id INT NOT NULL,
                            permission VARCHAR(128) NOT NULL,
                            server_scope VARCHAR(64) NOT NULL DEFAULT 'GLOBAL',
                            PRIMARY KEY (role_id, permission, server_scope),
                            FOREIGN KEY (role_id) REFERENCES gf_roles(id) ON DELETE CASCADE
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                        """);

                // Role inheritance (role -> parent role)
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_role_inherits (
                            role_id INT NOT NULL,
                            parent_role_id INT NOT NULL,
                            PRIMARY KEY (role_id, parent_role_id),
                            INDEX idx_role_inherits_role (role_id),
                            INDEX idx_role_inherits_parent (parent_role_id),
                            FOREIGN KEY (role_id) REFERENCES gf_roles(id) ON DELETE CASCADE,
                            FOREIGN KEY (parent_role_id) REFERENCES gf_roles(id) ON DELETE CASCADE
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_sessions (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            uuid CHAR(36) NOT NULL,
                            name VARCHAR(16) NOT NULL,
                            first_login TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            last_login TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            last_logout TIMESTAMP NULL DEFAULT NULL,
                            total_play_seconds BIGINT NOT NULL DEFAULT 0,
                            last_server VARCHAR(64) NULL,
                            INDEX idx_sessions_uuid (uuid),
                            INDEX idx_sessions_name (name)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_economy (
                            uuid CHAR(36) NOT NULL PRIMARY KEY,
                            name VARCHAR(16) NOT NULL,
                            balance BIGINT NOT NULL DEFAULT 0,
                            stardust BIGINT NOT NULL DEFAULT 0,
                            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_daily_rewards (
                            uuid CHAR(36) NOT NULL PRIMARY KEY,
                            name VARCHAR(16) NOT NULL,
                            last_claim_date DATE NOT NULL,
                            streak INT NOT NULL DEFAULT 1,
                            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_maintenance_config (
                            id INT PRIMARY KEY,
                            enabled TINYINT(1) NOT NULL DEFAULT 0,
                            end_at TIMESTAMP NULL DEFAULT NULL
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_maintenance_whitelist_players (
                            name VARCHAR(64) NOT NULL PRIMARY KEY
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_maintenance_whitelist_groups (
                            group_name VARCHAR(64) NOT NULL PRIMARY KEY
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_quests (
                            quest_key VARCHAR(64) NOT NULL PRIMARY KEY,
                            title VARCHAR(128) NOT NULL,
                            description TEXT NOT NULL,
                            type VARCHAR(32) NOT NULL,
                            goal BIGINT NOT NULL,
                            reward_galas BIGINT NOT NULL DEFAULT 0,
                            reward_stardust BIGINT NOT NULL DEFAULT 0,
                            active TINYINT(1) NOT NULL DEFAULT 1,
                            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_quest_progress (
                            uuid CHAR(36) NOT NULL,
                            quest_key VARCHAR(64) NOT NULL,
                            progress BIGINT NOT NULL DEFAULT 0,
                            completed TINYINT(1) NOT NULL DEFAULT 0,
                            completed_at TIMESTAMP NULL DEFAULT NULL,
                            reward_claimed TINYINT(1) NOT NULL DEFAULT 0,
                            last_update TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                            PRIMARY KEY (uuid, quest_key),
                            FOREIGN KEY (quest_key) REFERENCES gf_quests(quest_key) ON DELETE CASCADE
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_punishments (
                            id INT AUTO_INCREMENT PRIMARY KEY,
                            uuid CHAR(36) NULL,
                            name VARCHAR(16) NOT NULL,
                            ip VARCHAR(45) NULL,
                            type VARCHAR(16) NOT NULL,
                            reason VARCHAR(255) NOT NULL,
                            staff VARCHAR(32) NOT NULL,
                            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            expires_at TIMESTAMP NULL DEFAULT NULL,
                            active TINYINT(1) NOT NULL DEFAULT 1,
                            INDEX idx_punish_uuid_type_active (uuid, type, active),
                            INDEX idx_punish_ip_type_active (ip, type, active),
                            INDEX idx_punish_name (name)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_reports (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            reporter_name VARCHAR(16) NOT NULL,
                            target_name VARCHAR(16) NOT NULL,
                            server_name VARCHAR(64),
                            reason TEXT NOT NULL,
                            preset_key VARCHAR(64),
                            handled TINYINT(1) NOT NULL DEFAULT 0,
                            handled_by VARCHAR(32) NULL,
                            handled_at TIMESTAMP NULL DEFAULT NULL,
                            INDEX idx_reports_target (target_name),
                            INDEX idx_reports_created_at (created_at),
                            INDEX idx_reports_handled (handled)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                        """);

                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_npcs (
                            id INT AUTO_INCREMENT PRIMARY KEY,
                            server_name VARCHAR(64) NOT NULL,
                            name VARCHAR(64) NOT NULL,
                            world VARCHAR(64) NOT NULL,
                            x DOUBLE NOT NULL,
                            y DOUBLE NOT NULL,
                            z DOUBLE NOT NULL,
                            yaw FLOAT NOT NULL,
                            pitch FLOAT NOT NULL,
                            type VARCHAR(32) NOT NULL,
                            target_server VARCHAR(64) NULL,
                            skin_uuid CHAR(36) NULL,
                            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                        """);
            }

            // Post-creation: vendor-safe column backfills (no "IF NOT EXISTS" in ALTER across all vendors)
            ensureColumn(con, "gf_economy", "stardust", sqlite ? "INTEGER NOT NULL DEFAULT 0" : "BIGINT NOT NULL DEFAULT 0");
            ensureColumn(con, "gf_quest_progress", "reward_claimed", sqlite ? "INTEGER NOT NULL DEFAULT 0" : "TINYINT(1) NOT NULL DEFAULT 0");
            ensureColumn(con, "gf_reports", "handled", sqlite ? "INTEGER NOT NULL DEFAULT 0" : "TINYINT(1) NOT NULL DEFAULT 0");
            ensureColumn(con, "gf_reports", "handled_by", sqlite ? "TEXT NULL" : "VARCHAR(32) NULL");
            ensureColumn(con, "gf_reports", "handled_at", sqlite ? "TEXT NULL" : "TIMESTAMP NULL");

            logger.info("GalacticfyCore: DB-Migrationen erfolgreich.");
        } catch (SQLException e) {
            logger.error("Fehler bei DB-Migrationen", e);
        }
    }

    private void ensureColumn(Connection con, String table, String column, String columnDef) {
        try {
            if (columnExists(con, table, column)) return;
            try (Statement st = con.createStatement()) {
                st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + columnDef);
            }
            logger.info("DB-Migration: Added missing column {}.{}", table, column);
        } catch (Exception e) {
            // non-fatal; keep startup resilient
            logger.debug("DB-Migration: Could not add column {}.{} ({})", table, column, e.toString());
        }
    }

    private boolean columnExists(Connection con, String table, String column) throws SQLException {
        DatabaseMetaData meta = con.getMetaData();
        try (ResultSet rs = meta.getColumns(con.getCatalog(), null, table, column)) {
            if (rs.next()) return true;
        }
        // SQLite driver sometimes needs case-insensitive check
        try (ResultSet rs = meta.getColumns(con.getCatalog(), null, table.toUpperCase(), column)) {
            if (rs.next()) return true;
        }
        try (ResultSet rs = meta.getColumns(con.getCatalog(), null, table.toLowerCase(), column)) {
            return rs.next();
        }
    }
}
