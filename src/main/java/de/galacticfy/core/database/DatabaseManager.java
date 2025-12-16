package de.galacticfy.core.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.galacticfy.core.config.ConfigManager;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Central JDBC manager with multi-vendor support:
 * - External DB: MariaDB/MySQL/Postgres
 * - Fallback: SQLite file
 * - Optional: SQLite in-memory
 */
public final class DatabaseManager {

    public enum Mode {
        AUTO,
        EXTERNAL,
        SQLITE,
        MEMORY
    }

    public enum Vendor {
        MARIADB,
        MYSQL,
        POSTGRES
    }

    private final Logger logger;
    private final Path dataDir;
    private HikariDataSource dataSource;
    private Mode activeMode;
    private Vendor activeVendor;

    public DatabaseManager(Logger logger, Path dataDir) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
    }

    public synchronized void init(ConfigManager cfg) {
        shutdown();

        Mode requested = parseMode(cfg.getString("database.mode", "AUTO"));
        Vendor vendor = parseVendor(cfg.getString("database.external.vendor", "MARIADB"));

        // 1) Try external if requested (AUTO/EXTERNAL)
        if (requested == Mode.AUTO || requested == Mode.EXTERNAL) {
            boolean ok = tryInitExternal(cfg, vendor);
            if (ok) {
                this.activeMode = Mode.EXTERNAL;
                this.activeVendor = vendor;
                return;
            }
            if (requested == Mode.EXTERNAL) {
                // Explicit external-only: do not fall back silently.
                throw new IllegalStateException("External database was requested but connection failed. Check config.yml (database.external.*)");
            }
            logger.warn("GalacticfyCore: External DB failed – falling back to SQLite (mode=AUTO).");
        }

        // 2) SQLite file
        if (requested == Mode.SQLITE || requested == Mode.AUTO) {
            if (tryInitSqliteFile(cfg)) {
                this.activeMode = Mode.SQLITE;
                this.activeVendor = null;
                return;
            }
            if (requested == Mode.SQLITE) {
                throw new IllegalStateException("SQLite was requested but could not be initialized. Check file path and permissions.");
            }
        }

        // 3) SQLite in-memory
        if (requested == Mode.MEMORY) {
            if (tryInitSqliteMemory()) {
                this.activeMode = Mode.MEMORY;
                this.activeVendor = null;
                return;
            }
            throw new IllegalStateException("MEMORY DB was requested but could not be initialized.");
        }

        // Absolute last resort (should not happen in normal configs)
        logger.error("GalacticfyCore: No database backend could be initialized.");
        throw new IllegalStateException("No database backend could be initialized (external + sqlite failed).");
    }

    private boolean tryInitExternal(ConfigManager cfg, Vendor vendor) {
        String host = cfg.getString("database.external.host", "localhost");
        int port = cfg.getInt("database.external.port", vendor == Vendor.POSTGRES ? 5432 : 3306);
        String database = cfg.getString("database.external.database", "galacticfycore");
        String user = cfg.getString("database.external.username", "galacticfy");
        String pass = cfg.getString("database.external.password", "");
        String params = cfg.getString("database.external.params", "");

        if (pass == null) pass = "";
        if (params == null) params = "";

        String jdbcUrl;
        String driver;

        switch (vendor) {
            case POSTGRES -> {
                jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database;
                driver = "org.postgresql.Driver";
                if (!params.isBlank()) {
                    jdbcUrl = jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + params;
                }
            }
            case MYSQL -> {
                jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database;
                driver = "com.mysql.cj.jdbc.Driver";
                if (!params.isBlank()) {
                    jdbcUrl = jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + params;
                }
            }
            case MARIADB -> {
                jdbcUrl = "jdbc:mariadb://" + host + ":" + port + "/" + database;
                driver = "org.mariadb.jdbc.Driver";
                if (!params.isBlank()) {
                    jdbcUrl = jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + params;
                }
            }
            default -> throw new IllegalStateException("Unsupported vendor: " + vendor);
        }

        try {
            HikariConfig hc = new HikariConfig();
            hc.setPoolName("GalacticfyCorePool");
            hc.setJdbcUrl(jdbcUrl);
            hc.setDriverClassName(driver);
            hc.setUsername(user);
            hc.setPassword(pass);

            // Reasonable defaults for a proxy plugin
            hc.setMaximumPoolSize(cfg.getInt("database.pool.maxSize", 10));
            hc.setMinimumIdle(cfg.getInt("database.pool.minIdle", 1));
            hc.setConnectionTimeout(Duration.ofSeconds(cfg.getInt("database.pool.connectionTimeoutSeconds", 10)).toMillis());
            hc.setIdleTimeout(Duration.ofMinutes(cfg.getInt("database.pool.idleTimeoutMinutes", 10)).toMillis());
            hc.setMaxLifetime(Duration.ofMinutes(cfg.getInt("database.pool.maxLifetimeMinutes", 30)).toMillis());

            this.dataSource = new HikariDataSource(hc);
            // Validate connection
            try (Connection c = this.dataSource.getConnection()) {
                // no-op
            }

            logger.info("GalacticfyCore: Database connected (EXTERNAL: {}).", vendor);
            return true;
        } catch (Exception e) {
            logger.warn("GalacticfyCore: External DB init failed ({}). {}", vendor, e.getMessage());
            shutdown();
            return false;
        }
    }

    private boolean tryInitSqliteFile(ConfigManager cfg) {
        try {
            String file = cfg.getString("database.sqlite.file", "galacticfycore.db");
            if (file == null || file.isBlank()) file = "galacticfycore.db";

            Path dbFile = Path.of(file);
            if (!dbFile.isAbsolute()) {
                dbFile = dataDir.resolve(file);
            }

            // Ensure folder exists
            if (dbFile.getParent() != null) {
                java.nio.file.Files.createDirectories(dbFile.getParent());
            }

            String jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();

            HikariConfig hc = new HikariConfig();
            hc.setPoolName("GalacticfyCoreSQLite");
            hc.setJdbcUrl(jdbcUrl);
            hc.setDriverClassName("org.sqlite.JDBC");

            // SQLite should be single-connection in most cases
            hc.setMaximumPoolSize(Math.max(1, cfg.getInt("database.sqlite.poolSize", 1)));
            hc.setConnectionTimeout(Duration.ofSeconds(cfg.getInt("database.pool.connectionTimeoutSeconds", 10)).toMillis());

            this.dataSource = new HikariDataSource(hc);
            try (Connection c = this.dataSource.getConnection()) {
                // Enable WAL for better concurrency (best-effort)
                try {
                    c.createStatement().execute("PRAGMA journal_mode=WAL;");
                } catch (SQLException ignored) {
                }
            }

            logger.info("GalacticfyCore: Using SQLite file database: {}", dbFile.toAbsolutePath());
            return true;
        } catch (Exception e) {
            logger.warn("GalacticfyCore: SQLite file init failed. {}", e.getMessage());
            shutdown();
            return false;
        }
    }

    private boolean tryInitSqliteMemory() {
        try {
            HikariConfig hc = new HikariConfig();
            hc.setPoolName("GalacticfyCoreMemorySQLite");
            hc.setJdbcUrl("jdbc:sqlite::memory:");
            hc.setDriverClassName("org.sqlite.JDBC");
            hc.setMaximumPoolSize(1);
            this.dataSource = new HikariDataSource(hc);
            try (Connection c = this.dataSource.getConnection()) {
                // no-op
            }
            logger.info("GalacticfyCore: Using in-memory SQLite (no persistence).");
            return true;
        } catch (Exception e) {
            logger.warn("GalacticfyCore: In-memory SQLite init failed. {}", e.getMessage());
            shutdown();
            return false;
        }
    }

    public boolean isAvailable() {
        return dataSource != null;
    }

    public Mode getActiveMode() {
        return activeMode;
    }

    public boolean isSQLite() {
        return activeMode == Mode.SQLITE || activeMode == Mode.MEMORY;
    }

    public boolean isExternalDb() {
        return activeMode == Mode.EXTERNAL;
    }

    public Vendor getActiveVendor() {
        return activeVendor;
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new IllegalStateException("Database has not been initialized.");
        }
        return dataSource.getConnection();
    }

    public synchronized void shutdown() {
        if (dataSource != null) {
            try {
                dataSource.close();
            } catch (Exception ignored) {
            }
            dataSource = null;
        }
    }

    private static Mode parseMode(String s) {
        if (s == null) return Mode.AUTO;
        try {
            return Mode.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return Mode.AUTO;
        }
    }

    private static Vendor parseVendor(String s) {
        if (s == null) return Vendor.MARIADB;
        try {
            return Vendor.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return Vendor.MARIADB;
        }
    }
}
