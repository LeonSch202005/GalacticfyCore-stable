package de.galacticfy.core.service;

import java.util.List;
import java.util.Locale;

public class ChatFilterService {

    // sehr simpel – später DB / Config
    private final List<String> blockedWords = List.of(
            "hurensohn", "nigger", "fuck", "scheisse"
    );

    public boolean isBlocked(String message) {
        String lower = message.toLowerCase(Locale.ROOT);

        for (String bad : blockedWords) {
            if (lower.contains(bad)) {
                return true;
            }
        }

        // Werbung (primitive Variante)
        if (lower.contains("play.") || lower.contains(".net") || lower.contains(".de")) {
            // hier kannst du whitelisten: dein eigener Serverdomain etc.
            if (!lower.contains("galacticfy")) {
                return true;
            }
        }

        return false;
    }
}
