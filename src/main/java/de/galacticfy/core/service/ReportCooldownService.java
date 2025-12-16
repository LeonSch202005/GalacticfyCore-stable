package de.galacticfy.core.service;

import org.slf4j.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ReportCooldownService {

    private final Logger logger;
    private final long cooldownMs;

    // In-Memory cooldown – reicht erstmal (kein Config)
    private final Map<UUID, Long> lastReportAt = new ConcurrentHashMap<>();

    public ReportCooldownService(Logger logger) {
        this(logger, Duration.ofSeconds(30)); // Standard: 30s
    }

    public ReportCooldownService(Logger logger, Duration cooldown) {
        this.logger = logger;
        this.cooldownMs = Math.max(0L, cooldown.toMillis());
    }

    /**
     * Darf der Spieler reporten?
     */
    public boolean canReport(UUID uuid) {
        if (uuid == null) return true;
        if (cooldownMs <= 0L) return true;

        long now = System.currentTimeMillis();
        Long last = lastReportAt.get(uuid);
        if (last == null) return true;

        return (now - last) >= cooldownMs;
    }

    /**
     * Restzeit in Sekunden.
     */
    public long getRemainingSeconds(UUID uuid) {
        if (uuid == null) return 0L;
        if (cooldownMs <= 0L) return 0L;

        long now = System.currentTimeMillis();
        Long last = lastReportAt.get(uuid);
        if (last == null) return 0L;

        long diff = now - last;
        long remainingMs = cooldownMs - diff;
        if (remainingMs <= 0L) return 0L;

        long sec = remainingMs / 1000L;
        if (sec <= 0L) sec = 1L;
        return sec;
    }

    /**
     * Cooldown setzen (nach erfolgreichem Report).
     */
    public void markReported(UUID uuid) {
        if (uuid == null) return;
        lastReportAt.put(uuid, System.currentTimeMillis());
    }

    /**
     * Optional: wenn Spieler disconnectet, kann man aufräumen.
     */
    public void cleanup(UUID uuid) {
        if (uuid == null) return;
        lastReportAt.remove(uuid);
    }

    /**
     * Optional: global.
     */
    public void clearAll() {
        lastReportAt.clear();
        if (logger != null) logger.info("ReportCooldownService: Cooldown-Cache geleert.");
    }
}
