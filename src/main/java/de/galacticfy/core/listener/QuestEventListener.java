package de.galacticfy.core.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import de.galacticfy.core.service.QuestService;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Empfängt Stat-Updates vom Spigot-Plugin (galacticfy:queststats)
 * und leitet sie an den QuestService weiter.
 *
 * Payload-Format (UTF-8-String, eine Zeile):
 *   TYPE|UUID|NAME|AMOUNT
 */
public class QuestEventListener {

    private final QuestService questService;
    private final ChannelIdentifier statsChannel;
    private final ProxyServer proxy;
    private final Logger logger;

    public QuestEventListener(QuestService questService,
                              ChannelIdentifier statsChannel,
                              ProxyServer proxy,
                              Logger logger) {
        this.questService = questService;
        this.statsChannel = statsChannel;
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(statsChannel)) {
            return;
        }

        byte[] data = event.getData();
        if (data == null || data.length == 0) {
            return;
        }

        String msg = new String(data, StandardCharsets.UTF_8);
        String[] parts = msg.split("\\|");
        if (parts.length < 4) {
            logger.warn("[Quests] Ungültige Quest-Stat-Message: '{}'", msg);
            return;
        }

        String type = parts[0];
        String uuidStr = parts[1];
        String name = parts[2];
        String amountStr = parts[3];

        UUID uuid;
        long amount;

        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException ex) {
            logger.warn("[Quests] Ungültige UUID in Stat-Message: '{}'", msg, ex);
            return;
        }

        try {
            amount = Long.parseLong(amountStr);
        } catch (NumberFormatException ex) {
            logger.warn("[Quests] Ungültige Anzahl in Stat-Message: '{}'", msg, ex);
            return;
        }

        // jetzt an den QuestService routen
        switch (type) {
            case "BREAK" -> questService.handleBlocksBroken(uuid, name, amount, this::sendBar);
            case "STONE" -> questService.handleStoneBroken(uuid, name, amount, this::sendBar);
            case "ORE" -> questService.handleOresBroken(uuid, name, amount, this::sendBar);
            case "DIRT" -> questService.handleDirtBroken(uuid, name, amount, this::sendBar);
            case "GRAVEL" -> questService.handleGravelBroken(uuid, name, amount, this::sendBar);
            case "NETHERRACK" -> questService.handleNetherrackBroken(uuid, name, amount, this::sendBar);
            case "SAND_BREAK" -> questService.handleSandBroken(uuid, name, amount, this::sendBar);
            case "SAND_GIVE" -> questService.handleSandDelivered(uuid, name, amount, this::sendBar);
            case "PLACE" -> questService.handleBlocksPlaced(uuid, name, amount, this::sendBar);

            case "CROPS" -> questService.handleCropsHarvested(uuid, name, amount, this::sendBar);
            case "WOOD" -> questService.handleWoodChopped(uuid, name, amount, this::sendBar);
            case "SUGARCANE" -> questService.handleSugarCaneHarvested(uuid, name, amount, this::sendBar);

            case "FISH" -> questService.handleFishCaught(uuid, name, amount, this::sendBar);
            case "TRADE" -> questService.handleTradesMade(uuid, name, amount, this::sendBar);
            case "WALK" -> questService.handleBlocksWalked(uuid, name, amount, this::sendBar);
            case "PLAYTIME" -> questService.handlePlaytime(uuid, name, amount, this::sendBar);
            case "LOGIN" -> questService.handleLogin(uuid, name, this::sendBar);

            case "CRAFT_TOOLS" -> questService.handleToolsCrafted(uuid, name, amount, this::sendBar);
            case "CRAFT_TORCHES" -> questService.handleTorchesCrafted(uuid, name, amount, this::sendBar);
            case "CRAFT_BREAD" -> questService.handleBreadCrafted(uuid, name, amount, this::sendBar);
            case "CRAFT_BLOCKS" -> questService.handleBlocksCrafted(uuid, name, amount, this::sendBar);
            case "CRAFT_GEAR" -> questService.handleGearCrafted(uuid, name, amount, this::sendBar);

            case "SMELT_ORES" -> questService.handleOresSmelted(uuid, name, amount, this::sendBar);
            case "SMELT_FOOD" -> questService.handleFoodSmelted(uuid, name, amount, this::sendBar);

            case "MOB" -> questService.handleMobsKilled(uuid, name, amount, this::sendBar);
            case "ZOMBIE" -> questService.handleZombiesKilled(uuid, name, amount, this::sendBar);
            case "CREEPER" -> questService.handleCreepersKilled(uuid, name, amount, this::sendBar);
            case "BREED" -> questService.handleAnimalsBred(uuid, name, amount, this::sendBar);
            case "TAME" -> questService.handleAnimalsTamed(uuid, name, amount, this::sendBar);
            case "SHEAR" -> questService.handleSheepSheared(uuid, name, amount, this::sendBar);
            case "MILK" -> questService.handleMilkCollected(uuid, name, amount, this::sendBar);
            case "EGG" -> questService.handleEggsThrown(uuid, name, amount, this::sendBar);
            case "ENCHANT" -> questService.handleItemsEnchanted(uuid, name, amount, this::sendBar);
            case "DEATH" -> questService.handleDeaths(uuid, name, amount, this::sendBar);
            case "PVP_KILL" -> questService.handlePvpKills(uuid, name, amount, this::sendBar);
            case "SLEEP" -> questService.handleSleeps(uuid, name, amount, this::sendBar);
            case "NETHER_VISIT" -> questService.handleNetherVisits(uuid, name, amount, this::sendBar);
            case "END_VISIT" -> questService.handleEndVisits(uuid, name, amount, this::sendBar);

            // Event-Stats
            case "EVENT_SNOWBALL"   -> questService.handleEventSnowball(uuid, name, amount, this::sendBar);
            case "EVENT_SNOWMAN"    -> questService.handleEventSnowman(uuid, name, amount, this::sendBar);
            case "EVENT_PUMPKIN"    -> questService.handleEventPumpkin(uuid, name, amount, this::sendBar);
            case "EVENT_EASTER_EGG" -> questService.handleEventEasterEgg(uuid, name, amount, this::sendBar);


            default -> logger.debug("[Quests] Unbekannter Stat-Type '{}'", type);
        }
    }

    /**
     * Schickt eine Actionbar-Nachricht an den Spieler (wird als BiConsumer übergeben).
     */
    private void sendBar(UUID uuid, Component message) {
        proxy.getPlayer(uuid).ifPresent(player -> player.sendActionBar(message));
    }
}
