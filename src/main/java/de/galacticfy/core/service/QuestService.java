package de.galacticfy.core.service;

import de.galacticfy.core.database.DatabaseManager;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Quest-System mit:
 * - DAILY / WEEKLY / MONTHLY / LIFETIME / EVENT
 * - per-Player Weeklies
 * - Persistenz in gf_quests + gf_quest_progress
 * - Anti-AFK für Playtime
 * - No-Death-Quest + Meta-Quest "erledige 3 Dailies"
 * - Rewards werden AUTOMATISCH beim Abschluss gutgeschrieben
 *
 * Zusätzlich:
 * - Täglicher Login mit 24h-Cooldown, in DB gespeichert (gf_quest_meta)
 * - Keine Chat/Actionbar-Nachrichten für Login-Quests/Streaks
 */
public class QuestService {

    // =====================================================
    // PUBLIC TYPES
    // =====================================================

    public enum QuestType {
        DAILY,
        WEEKLY,
        MONTHLY,
        LIFETIME,
        EVENT
    }

    public record QuestDefinition(
            String key,
            String title,
            String description,
            QuestType type,
            long goal,
            long rewardGalas,
            long rewardStardust,
            boolean active
    ) {
    }

    public record PlayerQuestView(
            QuestDefinition definition,
            long progress,
            boolean completed
    ) {
    }

    /**
     * Template für generierte Quests.
     */
    private record QuestTemplate(
            String keyBase,
            String titlePattern,
            String descriptionPattern,
            QuestType type,
            long minGoal,
            long maxGoal,
            long step,
            double galasPerUnit,
            long fixedStardust
    ) {
    }

    private static final class QuestProgress {
        long value;
        boolean completedForPeriod;
        boolean rewardClaimed;
        long lastPeriodId; // DAILY: epochDay, WEEKLY: epochDay/7, MONTHLY: year*12+month, LIFETIME: 1
    }

    /**
     * Interner Stat-Typ, auf den der QuestEventListener routet.
     */
    public enum StatType {
        // Blöcke (Mining)
        BREAK,
        STONE,
        ORE,          // Erze
        DIRT,
        GRAVEL,
        NETHERRACK,
        SAND_BREAK,
        SAND_GIVE,

        // Place
        PLACE,

        // Farming / Holz / Tiere / etc.
        CROPS,
        WOOD,
        FISH,
        MOB,
        ZOMBIE,
        CREEPER,
        TRADE,
        PLAYTIME,
        LOGIN,

        // CRAFTING – aufgesplittet
        CRAFT_TOOLS,   // Werkzeuge
        CRAFT_TORCHES, // Fackeln
        CRAFT_BREAD,   // Brote
        CRAFT_BLOCKS,  // Bau-Blöcke (z.B. Steinziegel)
        CRAFT_GEAR,    // Waffen/Rüstung
        CRAFT,         // aktuell: kein Quest-Use, nur Fallback

        // Schmelzen – getrennt
        SMELT,
        SMELT_ORES,    // Erze → Barren / Scrap
        SMELT_FOOD,    // Essen

        WALK,

        // Tiere
        BREED,
        TAME,
        SHEAR,
        MILK,
        EGG,

        // Sonstiges
        ENCHANT,
        DEATH,
        PVP_KILL,
        SLEEP,

        // Dimensionen
        NETHER_VISIT,
        END_VISIT,

        // Extra-Stat nur für Zuckerrohr-Quests
        SUGARCANE,

        // Event-Stats
        EVENT_SNOWBALL,
        EVENT_SNOWMAN,
        EVENT_PUMPKIN,
        EVENT_EASTER_EGG
    }

    // =====================================================
    // FIELDS
    // =====================================================

    private final Logger logger;
    private final EconomyService economy;
    private final DatabaseManager db; // optional, kann null sein

    private final Map<String, QuestDefinition> definitions = new LinkedHashMap<>();
    private final Map<UUID, Map<String, QuestProgress>> progressMap = new ConcurrentHashMap<>();

    private final List<QuestTemplate> templates = new ArrayList<>();
    private final List<QuestDefinition> lifetimeDefinitions = new ArrayList<>();

    // Hook, damit die GUI live updaten kann
    private java.util.function.Consumer<UUID> updateHook;

    // Rotation / Reroll
    private LocalDate lastGenerationDate;
    private long rerollOffset = 0L;

    // wie viele Weeklies pro Spieler
    private static final int WEEKLY_PER_PLAYER = 5;

    // Login mit 24h-Cooldown
    private final Map<UUID, Instant> lastLoginCounted = new ConcurrentHashMap<>();

    // No-Death / Playtime-AFK
    private final Map<UUID, LocalDate> lastDeathDate = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> lastActive = new ConcurrentHashMap<>();

    // =====================================================
    // CONSTRUCTOR
    // =====================================================

    public QuestService(Logger logger, EconomyService economy) {
        this(logger, economy, null);
    }

    public QuestService(Logger logger, EconomyService economy, DatabaseManager db) {
        this.logger = logger;
        this.economy = economy;
        this.db = db;

        registerTemplates();
        registerLifetimeDefinitions();
        ensureDefinitionsForToday();

        if (db != null) {
            try {
                ensureMetaTable();
                syncDefinitionsToDatabase();
            } catch (Exception e) {
                logger.error("QuestService: Fehler beim Sync/Setup nach gf_quests/gf_quest_meta", e);
            }
        }
    }

    public void setUpdateHook(java.util.function.Consumer<UUID> hook) {
        this.updateHook = hook;
    }

    // =====================================================
    // DB-META (Login-Cooldown)
    // =====================================================

    private void ensureMetaTable() throws SQLException {
        try (Connection con = db.getConnection();
             Statement st = con.createStatement()) {

            if (db.isSQLite()) {
                // SQLite does not support "ON UPDATE CURRENT_TIMESTAMP".
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_quest_meta (
                            uuid TEXT NOT NULL PRIMARY KEY,
                            last_login_at TEXT NULL,
                            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )
                        """);
            } else {
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS gf_quest_meta (
                            uuid CHAR(36) NOT NULL PRIMARY KEY,
                            last_login_at DATETIME NULL,
                            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                        )
                        """);
            }
        }
    }

    private Instant loadLastLoginFromDb(UUID uuid) {
        if (db == null || uuid == null) return null;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT last_login_at FROM gf_quest_meta WHERE uuid = ?"
             )) {
            ps.setString(1, uuid.toString());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    var inst = de.galacticfy.core.util.DbTimeUtil.readInstant(rs, "last_login_at");
                    if (inst != null) {
                        return inst;
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("QuestService: Fehler beim Laden von last_login_at für {}", uuid, e);
        }

        return null;
    }

    private void saveLastLoginToDb(UUID uuid, Instant instant) {
    if (db == null || uuid == null || instant == null) return;

    String sql;
    if (db.isSQLite()) {
        sql = "INSERT INTO gf_quest_meta (uuid, last_login_at) " +
                "VALUES (?, ?) " +
                "ON CONFLICT(uuid) DO UPDATE SET " +
                "last_login_at = excluded.last_login_at, " +
                "updated_at = CURRENT_TIMESTAMP";
    } else {
        sql = "INSERT INTO gf_quest_meta (uuid, last_login_at) " +
                "VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "last_login_at = VALUES(last_login_at), " +
                "updated_at = CURRENT_TIMESTAMP";
    }

    try (Connection con = db.getConnection();
         PreparedStatement ps = con.prepareStatement(sql)) {
        ps.setString(1, uuid.toString());
        ps.setTimestamp(2, Timestamp.from(instant));
        ps.executeUpdate();
    } catch (SQLException e) {
        logger.error("QuestService: Fehler beim Speichern von last_login_at für {}", uuid, e);
    }
}


    // =====================================================
    // TEMPLATES
    // =====================================================

    private void registerTemplates() {
        templates.clear();

        // ===== DAILY – Meta / No-Death =====
        templates.add(new QuestTemplate(
                "daily_complete_3_dailies",
                "Erledige %d Daily-Quests",
                "Schließe heute insgesamt %d Daily-Quests ab.",
                QuestType.DAILY,
                3, 3, 1,
                5.0,
                2
        ));

        templates.add(new QuestTemplate(
                "daily_nodeath_playtime",
                "Überlebe %d Minuten ohne Tod",
                "Spiele heute insgesamt %d Minuten, ohne zu sterben.",
                QuestType.DAILY,
                60, 60, 1,     // immer genau 60 Minuten
                0.8,           // Galas pro Minute
                2              // Stardust
        ));

        // ===== DAILY – RESSOURCEN / ABBau =====
        templates.add(new QuestTemplate(
                "daily_break_stone",
                "Baue %d Stein ab",
                "Baue insgesamt %d Stein auf dem Netzwerk ab.",
                QuestType.DAILY,
                40, 220, 20,
                0.4,
                0
        ));
        templates.add(new QuestTemplate(
                "daily_break_ores",
                "Abbauen: %d Erze",
                "Baue insgesamt %d Kohle-, Eisen-, Kupfer- oder Redstone-Erze ab.",
                QuestType.DAILY,
                20, 80, 10,
                0.7,
                0
        ));
        templates.add(new QuestTemplate(
                "daily_break_gravel",
                "Gravel-Schaufler: %d Kies",
                "Baue insgesamt %d Kiesblöcke ab.",
                QuestType.DAILY,
                32, 192, 16,
                0.3,
                0
        ));
        templates.add(new QuestTemplate(
                "daily_break_dirt",
                "Gärtnerarbeit: %d Erde",
                "Baue insgesamt %d Erde- oder Grasblöcke ab.",
                QuestType.DAILY,
                40, 240, 20,
                0.25,
                0
        ));
        templates.add(new QuestTemplate(
                "daily_break_sand",
                "Sammle %d Sand",
                "Baue bzw. sammle insgesamt %d Sand oder Red Sand.",
                QuestType.DAILY,
                32, 192, 16,
                0.3,
                0
        ));
        templates.add(new QuestTemplate(
                "daily_break_wood",
                "Baue %d Holzblöcke ab",
                "Fälle insgesamt %d Holzblöcke beliebiger Holzarten.",
                QuestType.DAILY,
                30, 150, 10,
                0.5,
                0
        ));
        templates.add(new QuestTemplate(
                "daily_break_crops",
                "Ernte %d Feldpflanzen",
                "Ernte in Summe %d Weizen, Karotten, Kartoffeln oder Rote Bete.",
                QuestType.DAILY,
                40, 200, 20,
                0.4,
                0
        ));
        templates.add(new QuestTemplate(
                "daily_break_sugarcane",
                "Ernte %d Zuckerrohr",
                "Ernte insgesamt %d Zuckerrohr auf dem Server.",
                QuestType.DAILY,
                30, 160, 10,
                0.4,
                0
        ));
        templates.add(new QuestTemplate(
                "daily_break_nether",
                "Nether-Schichtarbeiter: %d Netherrack",
                "Baue insgesamt %d Netherrack im Nether ab.",
                QuestType.DAILY,
                64, 256, 32,
                0.35,
                0
        ));

        // ===== DAILY – KAMPF =====
        templates.add(new QuestTemplate(
                "daily_kill_mobs",
                "Besiege %d Mobs",
                "Besiege insgesamt %d beliebige feindliche Mobs.",
                QuestType.DAILY,
                15, 70, 5,
                0.9,
                0
        ));
        templates.add(new QuestTemplate(
                "daily_kill_zombies",
                "Töte %d Zombies",
                "Töte insgesamt %d Zombies in der Overworld.",
                QuestType.DAILY,
                10, 40, 5,
                1.0,
                0
        ));
        templates.add(new QuestTemplate(
                "daily_kill_creepers",
                "Töte %d Creeper",
                "Töte insgesamt %d Creeper, ohne dass sie dich sprengen.",
                QuestType.DAILY,
                5, 20, 1,
                1.5,
                0
        ));

        // ===== DAILY – FISHING / WIRTSCHAFT / BEWEGUNG =====
        templates.add(new QuestTemplate(
                "daily_fish",
                "Fange %d Fische",
                "Angle insgesamt %d beliebige Fische.",
                QuestType.DAILY,
                3, 15, 1,
                1.2,
                0
        ));
        templates.add(new QuestTemplate(
                "daily_trades",
                "Führe %d Villager-Trades aus",
                "Handle insgesamt %d Mal mit Dorfbewohnern.",
                QuestType.DAILY,
                2, 10, 1,
                2.0,
                0
        ));
        templates.add(new QuestTemplate(
                "daily_walk",
                "Laufe %d Blöcke",
                "Bewege dich insgesamt %d Blöcke zu Fuß.",
                QuestType.DAILY,
                1200, 3500, 200,
                0.02,
                0
        ));

        // ===== DAILY – PLAYTIME / LOGIN =====
        templates.add(new QuestTemplate(
                "daily_play_minutes",
                "Spiele heute %d Minuten",
                "Spiele insgesamt %d Minuten auf dem Netzwerk.",
                QuestType.DAILY,
                20, 90, 10,
                0.6,
                1
        ));
        templates.add(new QuestTemplate(
                "daily_login",
                "Täglicher Login",
                "Logge dich heute mindestens einmal auf dem Netzwerk ein.",
                QuestType.DAILY,
                1, 1, 1,
                15.0,
                0
        ));

        // ===== DAILY – CRAFTING / SMELTING =====
        templates.add(new QuestTemplate(
                "daily_craft_torches",
                "Crafte %d Fackeln",
                "Stelle insgesamt %d Fackeln her.",
                QuestType.DAILY,
                16, 128, 16,
                0.25,
                0
        ));
        templates.add(new QuestTemplate(
                "daily_craft_bread",
                "Backe %d Brote",
                "Crafte insgesamt %d Brote aus Weizen.",
                QuestType.DAILY,
                6, 32, 2,
                0.5,
                0
        ));
        templates.add(new QuestTemplate(
                "daily_craft_tools",
                "Werkzeugmacher: %d Tools",
                "Stelle insgesamt %d Werkzeuge (Spitzhacken, Äxte, Schaufeln, Schwerter) her.",
                QuestType.DAILY,
                3, 12, 1,
                1.5,
                0
        ));
        templates.add(new QuestTemplate(
                "daily_smelt_ores",
                "Schmelze %d Erze",
                "Schmelze insgesamt %d Erz-Items in Öfen oder Schmelzöfen.",
                QuestType.DAILY,
                10, 60, 5,
                0.8,
                0
        ));
        templates.add(new QuestTemplate(
                "daily_smelt_food",
                "Koch des Tages: %d Essen",
                "Brate insgesamt %d Rohfleisch oder Fisch.",
                QuestType.DAILY,
                8, 40, 4,
                0.7,
                0
        ));

        // ===== WEEKLY =====
        templates.add(new QuestTemplate(
                "weekly_break_stone",
                "Wöchentlicher Miner: %d Stein",
                "Baue in dieser Woche insgesamt %d Stein ab.",
                QuestType.WEEKLY,
                800, 4000, 200,
                0.25,
                1
        ));
        templates.add(new QuestTemplate(
                "weekly_break_wood",
                "Wöchentlicher Holzfäller: %d Holz",
                "Baue in dieser Woche insgesamt %d Holzblöcke ab.",
                QuestType.WEEKLY,
                400, 2000, 100,
                0.25,
                1
        ));
        templates.add(new QuestTemplate(
                "weekly_kill_mobs",
                "Wöchentlicher Jäger: %d Mobs",
                "Besiege insgesamt %d feindliche Mobs in dieser Woche.",
                QuestType.WEEKLY,
                150, 700, 50,
                0.35,
                2
        ));
        templates.add(new QuestTemplate(
                "weekly_crops",
                "Wöchentlicher Farmer: %d Ernten",
                "Ernte insgesamt %d Feldpflanzen in dieser Woche.",
                QuestType.WEEKLY,
                250, 900, 50,
                0.25,
                1
        ));
        templates.add(new QuestTemplate(
                "weekly_trades",
                "Wöchentlicher Händler: %d Trades",
                "Führe in dieser Woche insgesamt %d Villager-Trades durch.",
                QuestType.WEEKLY,
                15, 60, 5,
                1.2,
                2
        ));
        templates.add(new QuestTemplate(
                "weekly_fish",
                "Wöchentlicher Fischer: %d Fänge",
                "Fange insgesamt %d Fische in dieser Woche.",
                QuestType.WEEKLY,
                20, 80, 5,
                0.6,
                1
        ));
        templates.add(new QuestTemplate(
                "weekly_walk",
                "Wöchentlicher Läufer: %d Blöcke",
                "Laufe insgesamt %d Blöcke in dieser Woche.",
                QuestType.WEEKLY,
                8000, 25000, 1000,
                0.01,
                1
        ));
        templates.add(new QuestTemplate(
                "weekly_play_minutes",
                "Wöchentlicher Grinder: %d Minuten",
                "Spiele in dieser Woche insgesamt %d Minuten auf dem Netzwerk.",
                QuestType.WEEKLY,
                240, 900, 60,
                0.25,
                2
        ));
        templates.add(new QuestTemplate(
                "weekly_login",
                "Wöchentlicher Stammspieler: %d Logins",
                "Logge dich in dieser Woche insgesamt %d Mal ein.",
                QuestType.WEEKLY,
                3, 7, 1,
                6.0,
                1
        ));
        templates.add(new QuestTemplate(
                "weekly_craft_blocks",
                "Baumeister: %d Blöcke craften",
                "Stelle in dieser Woche insgesamt %d Bau-Blöcke (z.B. Steinziegel) her.",
                QuestType.WEEKLY,
                64, 512, 32,
                0.4,
                1
        ));
        templates.add(new QuestTemplate(
                "weekly_smelt_ores",
                "Schmelzmeister: %d Erze",
                "Schmelze in dieser Woche insgesamt %d Erze zu Barren.",
                QuestType.WEEKLY,
                60, 300, 20,
                0.4,
                1
        ));

        // ===== MONTHLY =====
        templates.add(new QuestTemplate(
                "monthly_break_stone",
                "Monatsminer: %d Stein",
                "Baue in diesem Monat insgesamt %d Stein ab.",
                QuestType.MONTHLY,
                5000, 20000, 1000,
                0.20,
                3
        ));
        templates.add(new QuestTemplate(
                "monthly_kill_mobs",
                "Monatsjäger: %d Mobs",
                "Besiege in diesem Monat insgesamt %d Mobs.",
                QuestType.MONTHLY,
                800, 3000, 100,
                0.25,
                4
        ));
        templates.add(new QuestTemplate(
                "monthly_trades",
                "Monatshändler: %d Trades",
                "Führe in diesem Monat insgesamt %d Trades mit Villagern durch.",
                QuestType.MONTHLY,
                40, 180, 10,
                1.0,
                3
        ));
        templates.add(new QuestTemplate(
                "monthly_crops",
                "Monatsfarmer: %d Ernten",
                "Ernte in diesem Monat insgesamt %d Feldpflanzen.",
                QuestType.MONTHLY,
                1200, 4500, 200,
                0.18,
                3
        ));
        templates.add(new QuestTemplate(
                "monthly_walk",
                "Monatsläufer: %d Blöcke",
                "Laufe in diesem Monat insgesamt %d Blöcke.",
                QuestType.MONTHLY,
                40000, 160000, 5000,
                0.008,
                2
        ));
        templates.add(new QuestTemplate(
                "monthly_play_minutes",
                "Monatslegende: %d Minuten",
                "Spiele in diesem Monat insgesamt %d Minuten auf dem Netzwerk.",
                QuestType.MONTHLY,
                1200, 4800, 120,
                0.15,
                4
        ));
        templates.add(new QuestTemplate(
                "monthly_login",
                "Monatsstammspieler: %d Logins",
                "Logge dich in diesem Monat insgesamt %d Mal ein.",
                QuestType.MONTHLY,
                10, 25, 1,
                5.0,
                3
        ));
        templates.add(new QuestTemplate(
                "monthly_craft_gear",
                "Ausrüstungsbauer: %d Rüstungsteile",
                "Stelle in diesem Monat insgesamt %d Rüstungsteile oder Waffen her.",
                QuestType.MONTHLY,
                20, 120, 10,
                0.7,
                3
        ));
        templates.add(new QuestTemplate(
                "monthly_smelt_materials",
                "Großschmelzer: %d Items",
                "Schmelze in diesem Monat insgesamt %d Items.",
                QuestType.MONTHLY,
                200, 1200, 100,
                0.3,
                3
        ));

        // ===== EVENT =====
        templates.add(new QuestTemplate(
                "event_xmas_snowballs",
                "Weihnachtsevent: Sammle %d Schneebälle",
                "Sammle während des Weihnachtsevents insgesamt %d Schneebälle.",
                QuestType.EVENT,
                40, 200, 20,
                0.25,
                1
        ));
        templates.add(new QuestTemplate(
                "event_xmas_snowmen",
                "Weihnachtsevent: Baue %d Schneegolems",
                "Baue während des Weihnachtsevents insgesamt %d Schneegolems.",
                QuestType.EVENT,
                2, 6, 1,
                2.0,
                1
        ));
        templates.add(new QuestTemplate(
                "event_halloween_pumpkins",
                "Halloween: Sammle %d Kürbisse",
                "Sammle während des Halloween-Events insgesamt %d Kürbisse.",
                QuestType.EVENT,
                10, 60, 5,
                0.4,
                1
        ));
        templates.add(new QuestTemplate(
                "event_easter_eggs",
                "Osterevent: Sammle %d Eier",
                "Sammle während des Osterevents insgesamt %d Eier.",
                QuestType.EVENT,
                10, 80, 10,
                0.3,
                1
        ));

        logger.info("QuestService: {} Quest-Templates registriert (rotierend).", templates.size());
    }

    private void registerLifetimeDefinitions() {
        lifetimeDefinitions.clear();

        lifetimeDefinitions.add(new QuestDefinition(
                "lifetime_mine_100k_stone",
                "Lebensziel: 100.000 Stein",
                "Baue insgesamt 100.000 Steinblöcke im gesamten Netzwerk ab.",
                QuestType.LIFETIME,
                100_000,
                0,
                10,
                true
        ));
        lifetimeDefinitions.add(new QuestDefinition(
                "lifetime_kill_50k_mobs",
                "Lebensziel: 50.000 Mobs",
                "Besiege insgesamt 50.000 feindliche Mobs.",
                QuestType.LIFETIME,
                50_000,
                0,
                15,
                true
        ));
        lifetimeDefinitions.add(new QuestDefinition(
                "lifetime_trades_500",
                "Lebensziel: 500 Trades",
                "Führe insgesamt 500 Villager-Trades durch.",
                QuestType.LIFETIME,
                500,
                0,
                10,
                true
        ));
        lifetimeDefinitions.add(new QuestDefinition(
                "lifetime_walk_2m_blocks",
                "Lebensziel: 2.000.000 Blöcke",
                "Laufe insgesamt 2.000.000 Blöcke.",
                QuestType.LIFETIME,
                2_000_000,
                0,
                20,
                true
        ));

        // Login-Streak-Lifetime-Quests (aktuell: einfache Login-Zähler)
        lifetimeDefinitions.add(new QuestDefinition(
                "lifetime_login_streak_7",
                "Stammspieler: 7-Tage-Streak",
                "Erreiche einen Login-Streak von 7 Tagen in Folge.",
                QuestType.LIFETIME,
                7,
                0,
                5,
                true
        ));
        lifetimeDefinitions.add(new QuestDefinition(
                "lifetime_login_streak_14",
                "Stammspieler: 14-Tage-Streak",
                "Erreiche einen Login-Streak von 14 Tagen in Folge.",
                QuestType.LIFETIME,
                14,
                0,
                10,
                true
        ));
        lifetimeDefinitions.add(new QuestDefinition(
                "lifetime_login_streak_30",
                "Stammspieler: 30-Tage-Streak",
                "Erreiche einen Login-Streak von 30 Tagen in Folge.",
                QuestType.LIFETIME,
                30,
                0,
                25,
                true
        ));

        logger.info("QuestService: {} Lifetime-Quests registriert.", lifetimeDefinitions.size());
    }

    // =====================================================
    // ROTATION & REROLL
    // =====================================================

    private void ensureDefinitionsForToday() {
        LocalDate today = LocalDate.now();
        if (lastGenerationDate == null || !lastGenerationDate.equals(today)) {
            generateDefinitionsFor(today);
        }
    }

    public void reloadDefinitions() {
        logger.info("QuestService: Quest-Definitionen werden neu generiert (Admin-Reload)...");
        rerollOffset = 0L;
        generateDefinitionsFor(LocalDate.now());

        if (db != null) {
            try {
                syncDefinitionsToDatabase();
            } catch (Exception e) {
                logger.error("QuestService: Fehler beim Sync nach gf_quests (reload)", e);
            }
        }
        logger.info("QuestService: Reload abgeschlossen (Offset zurückgesetzt).");
    }

    public void forceRandomRoll() {
        logger.info("QuestService: Force-Roll wird ausgeführt ...");
        rerollOffset++;
        generateDefinitionsFor(LocalDate.now());

        if (db != null) {
            try {
                syncDefinitionsToDatabase();
            } catch (Exception e) {
                logger.error("QuestService: Fehler beim Sync nach gf_quests (reroll)", e);
            }
        }

        logger.info("QuestService: Force-Roll abgeschlossen — neue Quests wurden generiert (Offset={}).", rerollOffset);
    }

    private void generateDefinitionsFor(LocalDate date) {
        definitions.clear();

        // Lifetime immer rein
        for (QuestDefinition def : lifetimeDefinitions) {
            definitions.put(def.key().toLowerCase(Locale.ROOT), def);
        }

        long dailySeed = date.toEpochDay() + rerollOffset * 31_415L;
        long weekId = date.toEpochDay() / 7;
        long weeklySeed = weekId * 13_337L + rerollOffset * 17_171L;
        long monthId = date.getYear() * 12L + date.getMonthValue();
        long monthlySeed = monthId * 77_777L + rerollOffset * 9_191L;
        long eventSeedBase = date.toEpochDay() * 99_999L + rerollOffset * 42_042L;

        // DAILY
        Random dailyRandom = new Random(dailySeed);
        generateFromTemplates(QuestType.DAILY, 8, dailyRandom);

        // WEEKLY
        Random weeklyRandom = new Random(weeklySeed);
        generateFromTemplates(QuestType.WEEKLY, 5, weeklyRandom);

        // MONTHLY
        Random monthlyRandom = new Random(monthlySeed);
        generateFromTemplates(QuestType.MONTHLY, 4, monthlyRandom);

        // EVENT – pro aktivem Event immer 3 passende Quests
        if (isChristmas(date)) {
            Random eventRandom = new Random(eventSeedBase ^ 0xC01DL);
            generateFromTemplates(
                    QuestType.EVENT,
                    3,
                    eventRandom,
                    t -> t.keyBase().startsWith("event_xmas_")
            );
        }

        if (isHalloween(date)) {
            // FIX: 0xHAL0 war ungültig, jetzt ein normaler Hex-Wert
            Random eventRandom = new Random(eventSeedBase ^ 0xABCD1234L);
            generateFromTemplates(
                    QuestType.EVENT,
                    3,
                    eventRandom,
                    t -> t.keyBase().startsWith("event_halloween_")
            );
        }

        if (isEaster(date)) {
            Random eventRandom = new Random(eventSeedBase ^ 0xE45EL);
            generateFromTemplates(
                    QuestType.EVENT,
                    3,
                    eventRandom,
                    t -> t.keyBase().startsWith("event_easter_")
            );
        }


        lastGenerationDate = date;
        logger.info("QuestService: {} Quest-Definitionen für {} generiert (inkl. Lifetime, rerollOffset={}).",
                definitions.size(), date, rerollOffset);
    }

    private void generateFromTemplates(QuestType type, int amount, Random random) {
        generateFromTemplates(type, amount, random, t -> true);
    }

    private void generateFromTemplates(QuestType type,
                                       int amount,
                                       Random random,
                                       java.util.function.Predicate<QuestTemplate> filter) {
        List<QuestTemplate> pool = templates.stream()
                .filter(t -> t.type == type)
                .filter(filter)
                .toList();

        if (pool.isEmpty()) {
            logger.warn("QuestService: Keine Templates für Typ {} mit Filter vorhanden.", type);
            return;
        }

        List<QuestTemplate> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, random);

        int count = Math.min(amount, shuffled.size());
        for (int i = 0; i < count; i++) {
            QuestTemplate t = shuffled.get(i);
            QuestDefinition def = createDefinitionFromTemplate(t, random);
            definitions.put(def.key().toLowerCase(Locale.ROOT), def);
        }
    }

    private QuestDefinition createDefinitionFromTemplate(QuestTemplate t, Random random) {
        long goal;
        if (t.minGoal() == t.maxGoal()) {
            goal = t.minGoal();
        } else {
            long range = t.maxGoal() - t.minGoal();
            long steps = Math.max(1, range / Math.max(1, t.step()));
            long chosenStep = random.nextInt((int) steps + 1);
            goal = t.minGoal() + chosenStep * t.step();
        }
        if (goal <= 0) goal = t.minGoal();

        String title = String.format(t.titlePattern(), goal);
        String description = String.format(t.descriptionPattern(), goal);

        long galas = Math.round(goal * t.galasPerUnit());
        switch (t.type()) {
            case DAILY -> galas = Math.min(galas, 300);
            case WEEKLY -> galas = Math.min(galas, 1000);
            case MONTHLY -> galas = Math.min(galas, 6000);
            case EVENT -> galas = Math.min(galas, 500);
            case LIFETIME -> {
            }
        }
        if (galas < 0) galas = 0;

        long stardust = t.fixedStardust();
        String key = (t.keyBase() + "_" + goal + "_" + t.type().name())
                .toLowerCase(Locale.ROOT);

        return new QuestDefinition(
                key,
                title,
                description,
                t.type(),
                goal,
                galas,
                stardust,
                true
        );
    }

    private boolean isChristmas(LocalDate d) {
        return d.getMonth() == Month.DECEMBER;
    }

    private boolean isHalloween(LocalDate d) {
        return d.getMonth() == Month.OCTOBER;
    }

    private boolean isEaster(LocalDate d) {
        return d.getMonth() == Month.APRIL;
    }

    // =====================================================
    // DB-SYNC (Definitionen)
    // =====================================================

    private void syncDefinitionsToDatabase() throws SQLException {
        if (db == null) return;

        try (Connection con = db.getConnection()) {
            
String upsert;
if (db.isSQLite()) {
    upsert = """
            INSERT INTO gf_quests
                (quest_key, title, description, type, goal, reward_galas, reward_stardust, active)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(quest_key) DO UPDATE SET
                title = excluded.title,
                description = excluded.description,
                type = excluded.type,
                goal = excluded.goal,
                reward_galas = excluded.reward_galas,
                reward_stardust = excluded.reward_stardust,
                active = excluded.active,
                updated_at = CURRENT_TIMESTAMP
            """;
} else {
    upsert = """
            INSERT INTO gf_quests
                (quest_key, title, description, type, goal, reward_galas, reward_stardust, active)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                title = VALUES(title),
                description = VALUES(description),
                type = VALUES(type),
                goal = VALUES(goal),
                reward_galas = VALUES(reward_galas),
                reward_stardust = VALUES(reward_stardust),
                active = VALUES(active),
                updated_at = CURRENT_TIMESTAMP
            """;
}

            try (PreparedStatement ps = con.prepareStatement(upsert)) {
                for (QuestDefinition def : definitions.values()) {
                    ps.setString(1, def.key());
                    ps.setString(2, def.title());
                    ps.setString(3, def.description());
                    ps.setString(4, def.type().name());
                    ps.setLong(5, def.goal());
                    ps.setLong(6, def.rewardGalas());
                    ps.setLong(7, def.rewardStardust());
                    ps.setBoolean(8, def.active());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    // =====================================================
    // DB-SYNC (Progress)
    // =====================================================

    private Map<String, QuestProgress> loadProgressFor(UUID uuid) {
        Map<String, QuestProgress> map = new ConcurrentHashMap<>();
        if (db == null) return map;

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT quest_key, progress, completed, reward_claimed FROM gf_quest_progress WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString("quest_key");
                    long progress = rs.getLong("progress");
                    boolean completed = rs.getBoolean("completed");
                    boolean rewardClaimed = rs.getBoolean("reward_claimed");

                    QuestProgress qp = new QuestProgress();
                    qp.value = progress;
                    qp.completedForPeriod = completed;
                    qp.rewardClaimed = rewardClaimed;
                    qp.lastPeriodId = computeCurrentPeriodId(getDefinition(key));
                    map.put(key, qp);
                }
            }
        } catch (SQLException e) {
            logger.error("QuestService: Fehler beim Laden von gf_quest_progress für {}", uuid, e);
        }

        return map;
    }

    private void saveProgress(UUID uuid, QuestDefinition def, QuestProgress qp) {
    if (db == null || uuid == null || def == null || qp == null) return;

    String sql;
    if (db.isSQLite()) {
        sql = """
                INSERT INTO gf_quest_progress
                    (uuid, quest_key, progress, completed, completed_at, reward_claimed, last_update)
                VALUES
                    (?, ?, ?, ?, CASE WHEN ? = 1 THEN CURRENT_TIMESTAMP ELSE NULL END, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(uuid, quest_key) DO UPDATE SET
                    progress = excluded.progress,
                    completed = excluded.completed,
                    completed_at = CASE
                        WHEN excluded.completed = 1 AND gf_quest_progress.completed_at IS NULL THEN CURRENT_TIMESTAMP
                        ELSE gf_quest_progress.completed_at
                    END,
                    reward_claimed = excluded.reward_claimed,
                    last_update = CURRENT_TIMESTAMP
                """;
    } else {
        sql = """
                INSERT INTO gf_quest_progress
                    (uuid, quest_key, progress, completed, completed_at, reward_claimed, last_update)
                VALUES
                    (?, ?, ?, ?, CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END, ?, CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE
                    progress = VALUES(progress),
                    completed = VALUES(completed),
                    completed_at = CASE
                        WHEN VALUES(completed)=1 AND completed_at IS NULL THEN CURRENT_TIMESTAMP
                        ELSE completed_at
                    END,
                    reward_claimed = VALUES(reward_claimed),
                    last_update = CURRENT_TIMESTAMP
                """;
    }

    try (Connection con = db.getConnection();
         PreparedStatement ps = con.prepareStatement(sql)) {
        ps.setString(1, uuid.toString());
        ps.setString(2, def.key());
        ps.setLong(3, qp.value);
        ps.setBoolean(4, qp.completedForPeriod);
        // completed_at trigger flag
        ps.setBoolean(5, qp.completedForPeriod);
        ps.setBoolean(6, qp.rewardClaimed);
        ps.executeUpdate();
    } catch (SQLException e) {
        logger.error("QuestService: Fehler beim Speichern von gf_quest_progress ({} / {})", uuid, def.key(), e);
    }
}


    // =====================================================
    // PUBLIC API
    // =====================================================

    public QuestDefinition getDefinition(String key) {
        if (key == null) return null;
        ensureDefinitionsForToday();
        return definitions.get(key.toLowerCase(Locale.ROOT));
    }

    public List<String> getActiveQuestKeys() {
        ensureDefinitionsForToday();
        return definitions.values().stream()
                .filter(QuestDefinition::active)
                .map(QuestDefinition::key)
                .sorted(String::compareToIgnoreCase)
                .toList();
    }

    private Set<String> getWeeklyKeysForPlayer(UUID uuid) {
        ensureDefinitionsForToday();

        List<QuestDefinition> allWeeklies = definitions.values().stream()
                .filter(d -> d.active() && d.type() == QuestType.WEEKLY)
                .sorted(Comparator.comparing(QuestDefinition::key))
                .toList();

        if (allWeeklies.isEmpty()) {
            return Collections.emptySet();
        }

        long today = LocalDate.now().toEpochDay();
        long weekId = today / 7L;
        long seed = uuid.getMostSignificantBits()
                ^ uuid.getLeastSignificantBits()
                ^ (weekId * 31_415_927L);

        Random rnd = new Random(seed);
        List<QuestDefinition> pool = new ArrayList<>(allWeeklies);
        Set<String> result = new HashSet<>();

        int target = Math.min(WEEKLY_PER_PLAYER, pool.size());
        for (int i = 0; i < target; i++) {
            int idx = rnd.nextInt(pool.size());
            QuestDefinition pick = pool.remove(idx);
            result.add(pick.key());
        }

        return result;
    }

    public List<PlayerQuestView> getQuestsFor(UUID uuid) {
        if (uuid == null) return List.of();
        ensureDefinitionsForToday();

        Map<String, QuestProgress> map = progressMap
                .computeIfAbsent(uuid, this::loadProgressFor);

        List<PlayerQuestView> result = new ArrayList<>();
        Set<String> weeklyForPlayer = getWeeklyKeysForPlayer(uuid);

        for (QuestDefinition def : definitions.values()) {
            if (!def.active()) continue;

            if (def.type() == QuestType.WEEKLY && !weeklyForPlayer.contains(def.key())) {
                continue;
            }

            QuestProgress qp = (map != null) ? map.get(def.key()) : null;

            // Periodenwechsel
            if (qp != null && def.type() != QuestType.LIFETIME) {
                long currentPeriod = computeCurrentPeriodId(def);
                if (qp.lastPeriodId != currentPeriod) {
                    qp.lastPeriodId = currentPeriod;
                    qp.value = 0;
                    qp.completedForPeriod = false;
                    saveProgress(uuid, def, qp);
                }
            }

            long value = (qp != null) ? qp.value : 0L;
            boolean completed = (qp != null) && qp.completedForPeriod;

            result.add(new PlayerQuestView(def, value, completed));
        }

        return result;
    }

    /**
     * Admin: Setzt den Quest-Fortschritt ALLER Spieler zurück.
     * Löscht alle Einträge aus gf_quest_progress und leert die Caches.
     */
    public void resetAllProgress() {
        logger.warn("QuestService: ADMIN-Reset für ALLE Quest-Fortschritte wird ausgeführt ...");

        // betroffene Spieler merken, damit wir das GUI refreshen können
        Set<UUID> affected = new HashSet<>(progressMap.keySet());

        progressMap.clear();
        lastLoginCounted.clear();
        lastDeathDate.clear();
        lastActive.clear();

        if (db != null) {
            try (Connection con = db.getConnection();
                 Statement st = con.createStatement()) {
                st.executeUpdate("DELETE FROM gf_quest_progress");
                st.executeUpdate("DELETE FROM gf_quest_meta");
            } catch (SQLException e) {
                logger.error("QuestService: Fehler beim globalen Reset von gf_quest_progress / gf_quest_meta", e);
            }
        }

        if (updateHook != null) {
            for (UUID uuid : affected) {
                updateHook.accept(uuid);
            }
        }

        logger.info("QuestService: ADMIN-Reset ALLER Quest-Fortschritte abgeschlossen.");
    }

    /**
     * Admin: Setzt den Quest-Fortschritt eines einzelnen Spielers zurück.
     */
    public void resetProgress(UUID uuid) {
        if (uuid == null) return;

        // Cache leeren
        progressMap.remove(uuid);
        lastLoginCounted.remove(uuid);
        lastDeathDate.remove(uuid);
        lastActive.remove(uuid);

        // Datenbank löschen
        if (db != null) {
            try (Connection con = db.getConnection();
                 PreparedStatement ps1 = con.prepareStatement(
                         "DELETE FROM gf_quest_progress WHERE uuid = ?"
                 );
                 PreparedStatement ps2 = con.prepareStatement(
                         "DELETE FROM gf_quest_meta WHERE uuid = ?"
                 )) {
                ps1.setString(1, uuid.toString());
                ps1.executeUpdate();

                ps2.setString(1, uuid.toString());
                ps2.executeUpdate();
            } catch (SQLException e) {
                logger.error("[Quests] Konnte Progress/Meta für {} nicht löschen", uuid, e);
            }
        }

        // GUI updaten
        if (updateHook != null) {
            updateHook.accept(uuid);
        }
    }

    /**
     * ALT: wurde vom Velocity-Listener aufgerufen, wenn aus dem Spigot-GUI ein
     * "CLAIM|<questKey>" kommt.
     *
     * NEU: Rewards sind jetzt automatisch – diese Methode informiert nur noch.
     */
    public boolean claimReward(UUID uuid,
                               String questKey,
                               BiConsumer<UUID, Component> sender) {
        if (uuid == null) return false;

        if (sender != null) {
            sender.accept(uuid, Component.text(
                    "§d[Quests] §7Belohnungen werden jetzt automatisch beim Abschluss gutgeschrieben."
            ));
        }

        return false;
    }

    // =====================================================
    // STAT-HANDLER
    // =====================================================

    // Blöcke / Ressourcen
    public void handleBlocksBroken(UUID uuid, String name, long amount,
                                   BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.BREAK);
    }

    public void handleStoneBroken(UUID uuid, String name, long amount,
                                  BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.STONE);
    }

    public void handleOresBroken(UUID uuid, String name, long amount,
                                 BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.ORE);
    }

    public void handleDirtBroken(UUID uuid, String name, long amount,
                                 BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.DIRT);
    }

    public void handleGravelBroken(UUID uuid, String name, long amount,
                                   BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.GRAVEL);
    }
    // Weihnachts-Event: Schneebälle sammeln
    public void handleXmasSnowballs(UUID uuid, String name, long amount,
                                    BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.EVENT_SNOWBALL);
    }


    public void handleNetherrackBroken(UUID uuid, String name, long amount,
                                       BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.NETHERRACK);
    }

    public void handleSandBroken(UUID uuid, String name, long amount,
                                 BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.SAND_BREAK);
    }

    public void handleSandDelivered(UUID uuid, String name, long amount,
                                    BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.SAND_GIVE);
    }

    public void handleBlocksPlaced(UUID uuid, String name, long amount,
                                   BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.PLACE);
    }

    public void handleCropsHarvested(UUID uuid, String name, long amount,
                                     BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.CROPS);
    }

    public void handleWoodChopped(UUID uuid, String name, long amount,
                                  BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.WOOD);
    }

    public void handleSugarCaneHarvested(UUID uuid, String name, long amount,
                                         BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.SUGARCANE);
    }

    // Fishing / Handel / Bewegung / Spielzeit / Login
    public void handleFishCaught(UUID uuid, String name, long amount,
                                 BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.FISH);
    }

    public void handleTradesMade(UUID uuid, String name, long amount,
                                 BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.TRADE);
    }

    public void handleBlocksWalked(UUID uuid, String name, long blocks,
                                   BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, blocks, sender, StatType.WALK);
    }

    public void handlePlaytime(UUID uuid, String name, long minutes,
                               BiConsumer<UUID, Component> sender) {
        if (uuid == null || minutes <= 0) return;

        // Anti-AFK: nur zählen, wenn in den letzten 3 Minuten etwas passiert ist
        Instant now = Instant.now();
        Instant last = lastActive.get(uuid);

        if (last == null || Duration.between(last, now).toMinutes() >= 3) {
            // Spieler ist offenbar AFK -> keine Playtime-Quests fortschreiben
            return;
        }

        applyStatToMatchingQuests(uuid, name, minutes, sender, StatType.PLAYTIME);
    }

    public void handleLogin(UUID uuid, String name,
                            BiConsumer<UUID, Component> sender) {
        if (uuid == null) return;

        Instant now = Instant.now();

        Instant last = lastLoginCounted.get(uuid);
        if (last == null && db != null) {
            last = loadLastLoginFromDb(uuid);
            if (last != null) {
                lastLoginCounted.put(uuid, last);
            }
        }

        if (last != null) {
            Duration diff = Duration.between(last, now);
            // weniger als 24h seit letztem gezählten Login -> NICHT zählen
            if (!diff.isNegative() && diff.compareTo(Duration.ofHours(24)) < 0) {
                return;
            }
        }

        lastLoginCounted.put(uuid, now);
        saveLastLoginToDb(uuid, now);

        applyStatToMatchingQuests(uuid, name, 1, sender, StatType.LOGIN);
    }

    // Craft / Smelt
    public void handleToolsCrafted(UUID uuid, String name, long amount,
                                   BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.CRAFT_TOOLS);
    }

    public void handleTorchesCrafted(UUID uuid, String name, long amount,
                                     BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.CRAFT_TORCHES);
    }

    public void handleBreadCrafted(UUID uuid, String name, long amount,
                                   BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.CRAFT_BREAD);
    }

    public void handleBlocksCrafted(UUID uuid, String name, long amount,
                                    BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.CRAFT_BLOCKS);
    }

    public void handleGearCrafted(UUID uuid, String name, long amount,
                                  BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.CRAFT_GEAR);
    }

    public void handleOresSmelted(UUID uuid, String name, long amount,
                                  BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.SMELT_ORES);
    }

    public void handleFoodSmelted(UUID uuid, String name, long amount,
                                  BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.SMELT_FOOD);
    }

    // Kampf / Tiere / PvP / Tod / Schlaf / Dimensionen
    public void handleMobsKilled(UUID uuid, String name, long amount,
                                 BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.MOB);
    }

    public void handleZombiesKilled(UUID uuid, String name, long amount,
                                    BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.ZOMBIE);
    }

    public void handleCreepersKilled(UUID uuid, String name, long amount,
                                     BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.CREEPER);
    }

    public void handleAnimalsBred(UUID uuid, String name, long amount,
                                  BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.BREED);
    }

    public void handleAnimalsTamed(UUID uuid, String name, long amount,
                                   BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.TAME);
    }

    public void handleSheepSheared(UUID uuid, String name, long amount,
                                   BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.SHEAR);
    }

    public void handleMilkCollected(UUID uuid, String name, long amount,
                                    BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.MILK);
    }

    public void handleEggsThrown(UUID uuid, String name, long amount,
                                 BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.EGG);
    }

    public void handleItemsEnchanted(UUID uuid, String name, long amount,
                                     BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.ENCHANT);
    }

    public void handleDeaths(UUID uuid, String name, long amount,
                             BiConsumer<UUID, Component> sender) {
        if (uuid == null || amount <= 0) return;

        // 1) Zeit des letzten Todes merken (für No-Death-Quests)
        lastDeathDate.put(uuid, LocalDate.now());

        // 2) Normale Death-Quests (falls später nötig)
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.DEATH);

        // 3) No-Death-Quests zurücksetzen
        Map<String, QuestProgress> playerMap = progressMap.get(uuid);
        if (playerMap == null) {
            return;
        }

        boolean changed = false;

        for (QuestDefinition def : definitions.values()) {
            if (!def.active()) continue;

            String key = def.key().toLowerCase(Locale.ROOT);

            // unsere No-Death-Quests erkennen wir am Key
            if (!key.contains("nodeath")) continue;

            QuestProgress qp = playerMap.get(def.key());
            if (qp == null) continue;

            // Fortschritt und Status resetten
            qp.value = 0;
            qp.completedForPeriod = false;
            changed = true;

            // in DB speichern
            saveProgress(uuid, def, qp);
        }

        if (changed && updateHook != null) {
            updateHook.accept(uuid);
        }
    }

    public void handlePvpKills(UUID uuid, String name, long amount,
                               BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.PVP_KILL);
    }

    public void handleSleeps(UUID uuid, String name, long amount,
                             BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.SLEEP);
    }

    public void handleNetherVisits(UUID uuid, String name, long amount,
                                   BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.NETHER_VISIT);
    }

    public void handleEndVisits(UUID uuid, String name, long amount,
                                BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.END_VISIT);
    }

    public void handleEventSnowball(UUID uuid, String name, long amount,
                                    BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.EVENT_SNOWBALL);
    }
    public void handleEventSnowman(UUID uuid, String name, long amount,
                                   BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.EVENT_SNOWMAN);
    }
    public void handleEventPumpkin(UUID uuid, String name, long amount,
                                   BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.EVENT_PUMPKIN);
    }
    public void handleEventEasterEgg(UUID uuid, String name, long amount,
                                     BiConsumer<UUID, Component> sender) {
        applyStatToMatchingQuests(uuid, name, amount, sender, StatType.EVENT_EASTER_EGG);
    }



    // =====================================================
    // Stat-Router intern
    // =====================================================

    // QuestService
    private boolean shouldNotifyProgress(StatType statType) {
        // Für diese Stats KEINE Fortschrittsmeldung:
        // - WALK (Laufen)
        // - PLAYTIME (Spielzeit)
        // - LOGIN (alle Login-Quests / Streaks)
        return switch (statType) {
            case WALK, PLAYTIME, LOGIN -> false;
            default -> true; // alle anderen Quests bekommen eine Anzeige (über sender = Actionbar)
        };
    }

    private void applyStatToMatchingQuests(UUID uuid,
                                           String name,
                                           long amount,
                                           BiConsumer<UUID, Component> sender,
                                           StatType statType) {
        if (uuid == null || amount <= 0) return;
        ensureDefinitionsForToday();

        // Anti-AFK: jede Aktion außer PLAYTIME gilt als Aktivität
        if (statType != StatType.PLAYTIME) {
            lastActive.put(uuid, Instant.now());
        }

        long effective = amount; // keine Boni mehr, 1:1 zählen

        for (QuestDefinition def : definitions.values()) {
            if (!def.active()) continue;
            if (!matchesStat(def, statType)) continue;

            increment(uuid, name, def.key(), def.type(), effective, statType, sender);
        }
    }

    private boolean matchesStat(QuestDefinition def, StatType statType) {
        String k = def.key().toLowerCase(Locale.ROOT);
        boolean isLifetime = (def.type() == QuestType.LIFETIME);

        return switch (statType) {
            // Mining: genauere Zuordnung

            // Stein-Quests (inkl. Lifetime)
            case STONE -> (
                    k.contains("break_stone")
                            || k.contains("mine_stone")
                            || (isLifetime && k.contains("mine") && k.contains("stone"))
            );

            // Nur Erz-Quests
            case ORE -> k.contains("break_ores");

            // Nur Dirt-Quests
            case DIRT -> k.contains("break_dirt");

            // Nur Gravel-Quests
            case GRAVEL -> k.contains("break_gravel");

            // Nur Netherrack-Quests
            case NETHERRACK -> k.contains("break_nether");

            // Generelles Block-Abbauen (falls du mal generische Quests nutzt)
            case BREAK -> (k.contains("break_") || k.contains("mine_")) && !k.contains("sand");

            case SAND_BREAK -> (k.contains("sand") && k.contains("break"));
            case SAND_GIVE  -> k.contains("sand_give");
            case PLACE      -> k.contains("place_");

            // Feldpflanzen-Quests (ohne Zuckerrohr)
            case CROPS -> (
                    (k.contains("crops")
                            || k.contains("farmer")
                            || k.contains("feld"))
                            && !k.contains("sugarcane")
            );

            // Nur Zuckerrohr-Quests
            case SUGARCANE -> k.contains("sugarcane");

            case WOOD       -> k.contains("break_wood");
            case FISH       -> k.contains("fish");

            // Mobs (inkl. Lifetime)
            case MOB -> (
                    k.contains("kill_mobs")
                            || (isLifetime && k.contains("kill") && k.contains("mobs"))
            );

            case ZOMBIE     -> k.contains("kill_zombies");
            case CREEPER    -> k.contains("kill_creepers");
            case TRADE      -> k.contains("trades");
            case PLAYTIME   -> (k.contains("play_minutes") || k.contains("nodeath_playtime"));
            case LOGIN      -> k.contains("login");

            // Crafting – sehr spezifisch
            case CRAFT_TOOLS   -> k.contains("craft_tools");
            case CRAFT_TORCHES -> k.contains("craft_torches");
            case CRAFT_BREAD   -> k.contains("craft_bread");
            case CRAFT_BLOCKS  -> k.contains("craft_blocks");
            case CRAFT_GEAR    -> k.contains("craft_gear");
            case CRAFT         -> false; // aktuell keine generische Craft-Quest

            case SMELT -> k.contains("smelt_");
            // Erze schmelzen
            case SMELT_ORES -> (
                    k.contains("smelt_ores")
                            || k.contains("smelt_materials") // Monatsquest: alle geschmolzenen Items
            );
            // Essen braten
            case SMELT_FOOD -> (
                    k.contains("smelt_food")
                            || k.contains("smelt_materials") // Monatsquest ebenfalls
            );
            case WALK       -> k.contains("walk_");

            // Events
            case EVENT_SNOWBALL -> k.contains("event_xmas_snowballs");
            case EVENT_SNOWMAN  -> k.contains("event_xmas_snowmen");
            case EVENT_PUMPKIN  -> k.contains("event_halloween_pumpkins");
            case EVENT_EASTER_EGG -> k.contains("event_easter_eggs");


            // aktuell keine Quests für:
            case BREED, TAME, SHEAR, MILK, EGG -> false;

            case ENCHANT, DEATH, PVP_KILL, SLEEP, NETHER_VISIT, END_VISIT -> false;
        };
    }

    // =====================================================
    // INCREMENT-LOGIK (Player)
    // =====================================================

    private long computeCurrentPeriodId(QuestDefinition def) {
        if (def == null) return 1L;
        LocalDate today = LocalDate.now();
        long todayEpoch = today.toEpochDay();

        return switch (def.type()) {
            case DAILY, EVENT -> todayEpoch;
            case WEEKLY -> todayEpoch / 7L;
            case MONTHLY -> (long) today.getYear() * 12L + today.getMonthValue();
            case LIFETIME -> 1L;
        };
    }

    private void increment(UUID uuid,
                           String name,
                           String questKey,
                           QuestType typeHint,
                           long delta,
                           StatType statType,
                           BiConsumer<UUID, Component> sender) {

        if (uuid == null || delta <= 0) return;
        ensureDefinitionsForToday();

        QuestDefinition def = getDefinition(questKey);
        if (def == null) return;
        if (!def.active()) return;

        // No-Death-Quest: nur zählen, wenn heute noch kein Tod registriert
        String defKeyLower = def.key().toLowerCase(Locale.ROOT);
        if (defKeyLower.contains("nodeath")) {
            LocalDate today = LocalDate.now();
            LocalDate lastDeath = lastDeathDate.get(uuid);
            if (lastDeath != null && lastDeath.equals(today)) {
                return;
            }
        }

        QuestType type = def.type();
        long periodId = computeCurrentPeriodId(def);

        Map<String, QuestProgress> playerMap = progressMap
                .computeIfAbsent(uuid, this::loadProgressFor);

        QuestProgress qp = playerMap.computeIfAbsent(def.key(), k -> new QuestProgress());

        // Periodenwechsel (Daily/Weekly/Monthly/Event)
        if (qp.lastPeriodId != periodId && type != QuestType.LIFETIME) {
            qp.value = 0;
            qp.completedForPeriod = false;
        }
        qp.lastPeriodId = periodId;

        long before = qp.value;
        long goal = def.goal();

        // Wenn schon fertig → nur clampen, fertig
        if (qp.completedForPeriod) {
            qp.value = Math.min(qp.value + delta, goal);
            saveProgress(uuid, def, qp);
            return;
        }

        qp.value = Math.min(qp.value + delta, goal);

        long progress = qp.value;

        // Actionbar-Progress (nicht für LOGIN/PLAYTIME/WALK)
        if (sender != null && shouldNotifyProgress(statType)) {
            String msg = "§d[Quests] §7" + def.title()
                    + " §8» §e" + progress + "§7/§e" + goal;
            sender.accept(uuid, Component.text(msg));
        }

        boolean justCompleted = (before < goal && qp.value >= goal);

        if (justCompleted) {
            qp.completedForPeriod = true;

            // === HIER: Belohnung direkt auszahlen ===
            long galas = def.rewardGalas();
            long dust = def.rewardStardust();

            if (galas > 0) {
                economy.deposit(uuid, galas);
            }
            if (dust > 0) {
                economy.addStardust(uuid, dust);
            }

            // Abschluss-Nachricht NICHT für Login-Quests/Streaks
            boolean isLoginQuest = defKeyLower.contains("login");

            if (sender != null && !isLoginQuest) {
                String reward = "";
                if (galas > 0) reward += "§e" + galas + "⛃ ";
                if (dust > 0) reward += "§d" + dust + "✧";
                sender.accept(uuid, Component.text(
                        "§d[Quests] §7Quest §d" + def.title()
                                + " §7abgeschlossen! "
                                + (reward.isEmpty() ? "§aBelohnung erhalten." : "§aBelohnung: " + reward)
                ));
            }

            logger.info("QuestService: Quest '{}' von {} ({}) abgeschlossen, Reward ausgezahlt.",
                    def.key(), name, uuid);

            // Meta-Quest: "Erledige 3 Daily-Quests"
            if (def.type() == QuestType.DAILY && !isLoginQuest) {
                definitions.values().stream()
                        .filter(q -> q.active()
                                && q.type() == QuestType.DAILY
                                && q.key().toLowerCase(Locale.ROOT).startsWith("daily_complete_3_dailies"))
                        .filter(q -> !q.key().equals(def.key()))
                        .findFirst()
                        .ifPresent(meta ->
                                increment(uuid, name, meta.key(), meta.type(), 1, StatType.LOGIN, sender)
                        );
            }
        }

        // GUI-Update (Velocity-GUI)
        if (updateHook != null) {
            updateHook.accept(uuid);
        }

        // DB speichern
        saveProgress(uuid, def, qp);
    }

}
