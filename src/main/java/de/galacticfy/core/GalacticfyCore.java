package de.galacticfy.core;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import de.galacticfy.core.command.*;
import de.galacticfy.core.config.ConfigManager;
import de.galacticfy.core.database.DatabaseManager;
import de.galacticfy.core.database.DatabaseMigrationService;
import de.galacticfy.core.listener.*;
import de.galacticfy.core.motd.GalacticfyMotdProvider;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import de.galacticfy.core.service.*;
import de.galacticfy.core.util.DiscordWebhookNotifier;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BiConsumer;

@Plugin(
        id = "galacticfycore",
        name = "GalacticfyCore",
        version = "0.4.5",
        url = "https://galacticfy.de"
)
public class GalacticfyCore {

    private final ProxyServer proxy;
    private final Logger logger;

    private ServerTeleportService teleportService;
    private MaintenanceService maintenanceService;
    private FreezeService freezeService;
    private DatabaseManager databaseManager;
    private ConfigManager config;

    private final java.nio.file.Path dataDirectory;
    private GalacticfyPermissionService permissionService;
    private DiscordWebhookNotifier discordNotifier;
    private SessionService sessionService;
    private ChatFilterService chatFilterService;
    private PunishmentService punishmentService;
    private ReportService reportService;
    private EconomyService economyService;
    private DailyRewardService dailyRewardService;
    private QuestService questService;
    private MessageService messageService;
    private AutoBroadcastService autoBroadcastService;

    // Identity Cache (Name<->UUID)
    private PlayerIdentityCacheService identityCacheService;

    // Report Cooldown
    private ReportCooldownService reportCooldownService;

    // Plugin-Message-Channel für Quest-GUI (Proxy -> Spigot UND CLAIM zurück)
    private static final ChannelIdentifier QUESTS_CHANNEL =
            MinecraftChannelIdentifier.create("galacticfy", "quests");
    // Plugin-Message-Channel für Quest-Stats (von Spigot)
    private static final ChannelIdentifier QUESTS_STATS_CHANNEL =
            MinecraftChannelIdentifier.create("galacticfy", "queststats");

    private QuestGuiMessenger questGuiMessenger;

    @Inject
    public GalacticfyCore(ProxyServer proxy, Logger logger, @DataDirectory java.nio.file.Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("GalacticfyCore wird initialisiert...");

        // ==============================
        // Config
        // ==============================
        this.config = new ConfigManager(logger, dataDirectory);
        this.config.loadOrCreate();

        // ==============================
        // DB
        // ==============================
        this.databaseManager = new DatabaseManager(logger, dataDirectory);
        this.databaseManager.init(config);
        new DatabaseMigrationService(databaseManager, logger).runMigrations();

        this.sessionService = new SessionService(databaseManager, logger);
        this.economyService = new EconomyService(databaseManager, logger);
        this.dailyRewardService = new DailyRewardService(databaseManager, economyService, logger);

        // ==============================
        // Services
        // ==============================
        this.teleportService = new ServerTeleportService(proxy, logger);
        this.maintenanceService = new MaintenanceService(logger, databaseManager);
        this.freezeService = new FreezeService(proxy);
        this.chatFilterService = new ChatFilterService();
        this.permissionService = new GalacticfyPermissionService(databaseManager, logger);
        this.punishmentService = new PunishmentService(databaseManager, logger);
        this.reportService = new ReportService(databaseManager, logger);
        this.messageService = new MessageService(proxy, logger);

        this.autoBroadcastService = new AutoBroadcastService(proxy, messageService, logger, this);
        autoBroadcastService.start();

        // ==============================
        // Identity Cache + Report Cooldown
        // ==============================
        this.identityCacheService = new PlayerIdentityCacheService(databaseManager, logger);
        this.reportCooldownService = new ReportCooldownService(logger);

        // ==============================
        // Quests + GUI (OHNE Community)
        // ==============================
        this.questService = new QuestService(logger, economyService, databaseManager);
        this.questGuiMessenger = new QuestGuiMessenger(proxy, QUESTS_CHANNEL, logger, questService);

        // Live-Updates aktivieren (Proxy → Spigot)
        this.questService.setUpdateHook(uuid -> questGuiMessenger.pushUpdate(uuid));

        // Quest-Playtime-Timer: jede Minute allen Online-Spielern 1 Minute gutschreiben
        proxy.getScheduler()
                .buildTask(this, () -> {
                    proxy.getAllPlayers().forEach(player -> {
                        UUID uuid = player.getUniqueId();
                        String name = player.getUsername();
                        BiConsumer<UUID, net.kyori.adventure.text.Component> sender =
                                (u, comp) -> proxy.getPlayer(u).ifPresent(p -> p.sendMessage(comp));

                        // 1 Minute Spielzeit hinzufügen → triggert Daily/Weekly/Monthly-Quests
                        questService.handlePlaytime(uuid, name, 1L, sender);
                    });
                })
                .repeat(Duration.ofMinutes(1))
                .schedule();

        String webhookUrl = "https://discord.com/api/webhooks/1443274192542765168/aHgrQP2ADryVWfhdoW5dcP7Vd8J_YU9aOkjEVkYNlVc-4wLEnAs-E5e-IfJg0fBwN8dJ";
        this.discordNotifier = new DiscordWebhookNotifier(logger, webhookUrl);

        // ==============================
        // Plugin-Message Channels
        // ==============================
        proxy.getChannelRegistrar().register(FreezeService.FREEZE_CHANNEL);
        proxy.getChannelRegistrar().register(QUESTS_CHANNEL);
        proxy.getChannelRegistrar().register(QUESTS_STATS_CHANNEL);

        CommandManager commandManager = proxy.getCommandManager();

        // ==============================
        // Quest-Command
        // ==============================
        CommandMeta questsMeta = commandManager.metaBuilder("quests").build();
        commandManager.register(questsMeta, new QuestCommand(questService, questGuiMessenger, proxy));

        // ==============================
        // Teleport- & Utility-Commands
        // ==============================
        CommandMeta freezeMeta = commandManager.metaBuilder("freeze").build();
        commandManager.register(freezeMeta, new FreezeCommand(proxy, freezeService));

        CommandMeta hubMeta = commandManager.metaBuilder("hub")
                .aliases("lobby")
                .build();
        commandManager.register(hubMeta, new HubCommand(teleportService, maintenanceService));

        CommandMeta cbMeta = commandManager.metaBuilder("citybuild")
                .aliases("cb")
                .build();
        commandManager.register(cbMeta, new CitybuildCommand(teleportService, maintenanceService));

        CommandMeta sbMeta = commandManager.metaBuilder("skyblock")
                .aliases("sb")
                .build();
        commandManager.register(sbMeta, new SkyblockCommand(teleportService, maintenanceService));

        CommandMeta eventMeta = commandManager.metaBuilder("event").build();
        commandManager.register(eventMeta, new EventCommand(teleportService, maintenanceService, permissionService));

        CommandMeta sendMeta = commandManager.metaBuilder("send").build();
        commandManager.register(sendMeta, new SendCommand(proxy, teleportService, permissionService));

        // ==============================
        // Maintenance
        // ==============================
        CommandMeta maintenanceMeta = commandManager.metaBuilder("maintenance").build();
        commandManager.register(
                maintenanceMeta,
                new MaintenanceCommand(maintenanceService, proxy, discordNotifier, false, permissionService)
        );

        CommandMeta wartungMeta = commandManager.metaBuilder("wartung").build();
        commandManager.register(
                wartungMeta,
                new MaintenanceCommand(maintenanceService, proxy, discordNotifier, true, permissionService)
        );

        // ==============================
        // Rank / Rollen-Verwaltung
        // ==============================
        CommandMeta rankMeta = commandManager.metaBuilder("rank").build();
        commandManager.register(rankMeta, new RankCommand(permissionService, proxy));

        CommandMeta proxyInfoMeta = commandManager.metaBuilder("proxyinfo")
                .aliases("tps")
                .build();
        commandManager.register(proxyInfoMeta, new ProxyInfoCommand(proxy));

        // Reports GUI/Staff Command (dein /reports)
        CommandMeta reportsMeta = commandManager.metaBuilder("reports").build();
        commandManager.register(reportsMeta, new ReportsCommand(proxy, permissionService, reportService, teleportService));

        // UPDATED: /seen kann jetzt offline
        CommandMeta seenMeta = commandManager.metaBuilder("seen").build();
        commandManager.register(seenMeta, new SeenCommand(proxy, permissionService, sessionService, identityCacheService));

        // ==============================
        // Punishment-Commands
        // ==============================
        CommandMeta banMeta = commandManager.metaBuilder("ban").build();
        commandManager.register(banMeta, new BanCommand(proxy, permissionService, punishmentService, discordNotifier, identityCacheService));

        CommandMeta banIpMeta = commandManager.metaBuilder("banip").build();
        commandManager.register(banIpMeta, new BanIpCommand(proxy, permissionService, punishmentService, discordNotifier, identityCacheService));

        CommandMeta unbanMeta = commandManager.metaBuilder("unban").build();
        commandManager.register(unbanMeta, new UnbanCommand(punishmentService, permissionService, proxy, identityCacheService));

        CommandMeta muteMeta = commandManager.metaBuilder("mute").build();
        commandManager.register(muteMeta, new MuteCommand(proxy, permissionService, punishmentService, discordNotifier, identityCacheService));

        CommandMeta unmuteMeta = commandManager.metaBuilder("unmute").build();
        commandManager.register(unmuteMeta, new UnmuteCommand(punishmentService, permissionService, proxy, identityCacheService));

        CommandMeta kickMeta = commandManager.metaBuilder("kick").build();
        commandManager.register(kickMeta, new KickCommand(proxy, permissionService, punishmentService, discordNotifier));

        CommandMeta historyMeta = commandManager.metaBuilder("history").build();
        commandManager.register(historyMeta, new HistoryCommand(proxy, punishmentService, permissionService, identityCacheService));

        CommandMeta checkMeta = commandManager.metaBuilder("check").build();
        commandManager.register(checkMeta, new CheckCommand(proxy, permissionService, punishmentService, identityCacheService));

        CommandMeta warningsMeta = commandManager.metaBuilder("warnings").build();
        commandManager.register(warningsMeta, new WarningsCommand(proxy, punishmentService, permissionService, identityCacheService));

        CommandMeta warnMeta = commandManager.metaBuilder("warn").build();
        commandManager.register(warnMeta, new WarnCommand(proxy, permissionService, punishmentService, discordNotifier, identityCacheService));

        // ==============================
        // Report Command (/report) – mit Cache + Cooldown
        // ==============================
        CommandMeta reportMeta = commandManager.metaBuilder("report").build();
        commandManager.register(reportMeta, new ReportCommand(proxy, permissionService, reportService, identityCacheService, reportCooldownService));

        CommandMeta unwarnMeta = commandManager.metaBuilder("unwarn").build();
        commandManager.register(unwarnMeta, new UnwarnCommand(proxy, punishmentService, permissionService, identityCacheService));

        // ==============================
        // Broadcast / Alert / Announce
        // ==============================
        CommandMeta alertMeta = commandManager.metaBuilder("alert").build();
        commandManager.register(alertMeta, new AlertCommand(messageService, permissionService));

        CommandMeta announceMeta = commandManager.metaBuilder("announce").build();
        commandManager.register(announceMeta, new AnnounceCommand(messageService, permissionService));

        CommandMeta broadcastMeta = commandManager.metaBuilder("broadcast")
                .aliases("bc")
                .build();
        commandManager.register(broadcastMeta, new BroadcastCommand(messageService, permissionService));

        CommandMeta autoBcMeta = commandManager.metaBuilder("autobc").build();
        commandManager.register(autoBcMeta, new AutoBroadcastCommand(autoBroadcastService));

        // ==============================
        // Global Player / Server-Commands
        // ==============================
        CommandMeta glistMeta = commandManager.metaBuilder("glist").build();
        commandManager.register(glistMeta, new GlistCommand(proxy));

        CommandMeta whereisMeta = commandManager.metaBuilder("whereis").build();
        commandManager.register(whereisMeta, new WhereisCommand(proxy));

        CommandMeta sendallMeta = commandManager.metaBuilder("sendall").build();
        commandManager.register(sendallMeta, new SendAllCommand(proxy));

        // Staffchat & Staff-Liste
        CommandMeta staffMeta = commandManager.metaBuilder("staffchat")
                .aliases("sc")
                .build();
        commandManager.register(staffMeta, new StaffChatCommand(proxy, permissionService));

        CommandMeta staffListMeta = commandManager.metaBuilder("staff").build();
        commandManager.register(staffListMeta, new StaffCommand(proxy, permissionService));

        // ==============================
        // Economy-Commands
        // ==============================
        CommandMeta moneyMeta = commandManager.metaBuilder("money").build();
        commandManager.register(moneyMeta, new MoneyCommand(proxy, economyService, permissionService));

        CommandMeta payMeta = commandManager.metaBuilder("pay").build();
        commandManager.register(payMeta, new PayCommand(proxy, economyService, permissionService));

        CommandMeta ecoMeta = commandManager.metaBuilder("eco").build();
        commandManager.register(ecoMeta, new EcoCommand(proxy, economyService, permissionService));

        CommandMeta baltopMeta = commandManager.metaBuilder("baltop").build();
        commandManager.register(baltopMeta, new BaltopCommand(economyService));

        // ==============================
        // Daily-Reward-Command
        // ==============================
        CommandMeta dailyMeta = commandManager.metaBuilder("daily")
                .aliases("dailyreward")
                .build();
        commandManager.register(dailyMeta, new DailyCommand(dailyRewardService, permissionService, proxy));

        // ==============================
        // Listener
        // ==============================
        proxy.getEventManager().register(this, new EconomyListener(economyService));
        proxy.getEventManager().register(this, new ConnectionProtectionListener(logger, proxy, maintenanceService));
        proxy.getEventManager().register(this, new GalacticfyMotdProvider(maintenanceService));
        proxy.getEventManager().register(this, new FreezeListener(freezeService));
        proxy.getEventManager().register(this, new MaintenanceListener(maintenanceService, logger, permissionService));
        proxy.getEventManager().register(this, new PermissionsSetupListener(permissionService, logger));
        proxy.getEventManager().register(this, new TablistPrefixListener(proxy, permissionService, logger));
        proxy.getEventManager().register(this, new PunishmentLoginListener(punishmentService, logger, proxy, permissionService));
        proxy.getEventManager().register(this, new ChatFilterListener(chatFilterService));
        proxy.getEventManager().register(this, new ReportJoinNotifyListener(reportService, permissionService));

        // UPDATED: SessionListener braucht identityCacheService
        proxy.getEventManager().register(this, new SessionListener(sessionService, questService, identityCacheService, logger));

        // Quest-Stat-Listener (für Fischen, Blöcke, etc.)
        proxy.getEventManager().register(this, new QuestEventListener(questService, QUESTS_STATS_CHANNEL, proxy, logger));

        logger.info("GalacticfyCore: Commands, Listener, Punishment-, Report-, Economy-, Daily- & Questsystem registriert (ohne Community-Quests).");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("GalacticfyCore fährt herunter, schließe Ressourcen...");

        if (autoBroadcastService != null) autoBroadcastService.shutdown();
        if (maintenanceService != null) maintenanceService.shutdown();
        if (discordNotifier != null) discordNotifier.shutdown();
        if (databaseManager != null) databaseManager.shutdown();
        if (punishmentService != null) punishmentService.shutdown();

        logger.info("GalacticfyCore: Shutdown abgeschlossen.");
    }
}
