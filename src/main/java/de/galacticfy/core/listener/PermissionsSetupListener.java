package de.galacticfy.core.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.permission.PermissionFunction;
import com.velocitypowered.api.permission.PermissionProvider;
import com.velocitypowered.api.permission.PermissionSubject;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import de.galacticfy.core.permission.GalacticfyPermissionService;
import org.slf4j.Logger;

/**
 * Hängt dein eigenes Rank-/Permission-System in Velocity ein.
 *
 * Ergebnis:
 *  - Alle hasPermission()-Checks laufen über GalacticfyPermissionService.hasRankPermission(..., serverName)
 *  - server_scope aus gf_role_permissions wird berücksichtigt.
 */
public class PermissionsSetupListener {

    private final GalacticfyPermissionService permissionService;
    private final Logger logger;

    public PermissionsSetupListener(GalacticfyPermissionService permissionService, Logger logger) {
        this.permissionService = permissionService;
        this.logger = logger;
    }

    @Subscribe
    public void onPermissionsSetup(PermissionsSetupEvent event) {
        PermissionSubject subject = event.getSubject();
        final PermissionProvider baseProvider = event.getProvider(); // bisheriger Provider (Velocity/LuckPerms/…)

        PermissionProvider customProvider = new PermissionProvider() {
            @Override
            public PermissionFunction createFunction(PermissionSubject s) {

                // === Spieler → Dein Rank-System inkl. server_scope ===
                if (s instanceof Player player) {
                    return permission -> {
                        // aktueller Servername (oder PROXY)
                        String currentServerName = player.getCurrentServer()
                                .map(conn -> conn.getServerInfo().getName())
                                .orElse("PROXY");

                        boolean allowed = permissionService.hasRankPermission(
                                player.getUniqueId(),
                                permission,
                                currentServerName
                        );

                        if (allowed) {
                            return Tristate.TRUE;  // "*" aus DB = ALLES
                        }

                        // Fallback: ursprünglicher Provider (falls du ihn noch benutzen willst)
                        PermissionFunction original = baseProvider.createFunction(s);
                        return original.getPermissionValue(permission);
                    };
                }

                // === Konsole → alles erlaubt (PROXY-Kontext) ===
                if (s instanceof ConsoleCommandSource) {
                    return permission -> Tristate.TRUE;
                }

                // === Andere Subjects → Standard-Verhalten beibehalten ===
                PermissionFunction original = baseProvider.createFunction(s);
                return permission -> original.getPermissionValue(permission);
            }
        };

        event.setProvider(customProvider);

        logger.info(
                "Permissions-Provider für {} wurde auf Galacticfy gesetzt (Subject-Typ: {}).",
                subject, subject.getClass().getSimpleName()
        );
    }
}
