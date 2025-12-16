package de.galacticfy.core.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import de.galacticfy.core.service.EconomyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

public class EconomyListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(EconomyListener.class);

    private final EconomyService economy;

    public EconomyListener(EconomyService economy) {
        this.economy = economy;
    }

    @Subscribe
    public void onJoin(PlayerChooseInitialServerEvent e) {
        var p = e.getPlayer();

        // Spieleraccount automatisch anlegen (falls nicht vorhanden)
        try {
            economy.ensureAccount(p.getUniqueId(), p.getUsername());
        } catch (SQLException ex) {
            // Do not fail the login flow if the economy backend is temporarily unavailable.
            LOGGER.error("Failed to ensure economy account for {} ({})", p.getUsername(), p.getUniqueId(), ex);
        }
    }
}
