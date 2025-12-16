package de.galacticfy.core.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal YAML config loader for Velocity.
 * - Writes default config.yml from resources if missing
 * - Exposes simple getters with defaults
 */
public final class ConfigManager {

    private final Logger logger;
    private final Path dataDirectory;
    private final Path configFile;
    private Map<String, Object> root = new LinkedHashMap<>();

    public ConfigManager(Logger logger, Path dataDirectory) {
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.configFile = dataDirectory.resolve("config.yml");
    }

    public void loadOrCreate() {
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            logger.error("Konnte Plugin-DataFolder nicht erstellen: {}", dataDirectory, e);
        }

        if (Files.notExists(configFile)) {
            try (InputStream in = ConfigManager.class.getClassLoader().getResourceAsStream("config.yml")) {
                if (in == null) {
                    logger.warn("Default config.yml nicht im Jar gefunden. Erstelle leere Config.");
                    root = new LinkedHashMap<>();
                    return;
                }
                Files.copy(in, configFile);
                logger.info("config.yml erstellt: {}", configFile);
            } catch (IOException e) {
                logger.error("Konnte default config.yml nicht schreiben: {}", configFile, e);
            }
        }

        try {
            String yamlText = Files.readString(configFile, StandardCharsets.UTF_8);
            Object loaded = new Yaml().load(yamlText);
            if (loaded instanceof Map<?, ?> map) {
                //noinspection unchecked
                root = (Map<String, Object>) map;
            } else {
                root = new LinkedHashMap<>();
            }
        } catch (Exception e) {
            logger.error("Konnte config.yml nicht laden: {}", configFile, e);
            root = new LinkedHashMap<>();
        }
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public String getString(String path, String def) {
        Object v = get(path);
        if (v == null) return def;
        return String.valueOf(v);
    }

    public int getInt(String path, int def) {
        Object v = get(path);
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? def : Integer.parseInt(String.valueOf(v));
        } catch (Exception ignored) {
            return def;
        }
    }

    public boolean getBoolean(String path, boolean def) {
        Object v = get(path);
        if (v instanceof Boolean b) return b;
        if (v == null) return def;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    public Object get(String path) {
        String[] parts = path.split("\\.");
        Object cur = root;
        for (String p : parts) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(p);
        }
        return cur;
    }
}
