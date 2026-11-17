package com.igrobar.mtrdynmap;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * saveDefaultConfig() never touches an existing config.yml, so new settings would be missed after an update.
 * Sections that are missing are copied (with their comments) from the bundled config.yml and appended
 * to the end of the file. Nothing else in the file is changed.
 */
final class ConfigUpgrade {
    /** A section of the bundled config (header "# ==== ... ====") and the key that shows it is already present. */
    private record Section(String header, String key) {
    }

    private static final List<Section> SECTIONS = List.of(
            new Section("# ==== Language ====", "language:"),
            new Section("# ==== Dynmap markers ====", "dynmap:"),
            new Section("# ==== Live web map ====", "web-map:"),
            new Section("# ==== World mappings ====", "worlds:"));

    private ConfigUpgrade() {
    }

    static void apply(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.isFile()) {
            return;
        }
        try {
            List<String> current = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            List<String> bundled;
            try (InputStream in = plugin.getResource("config.yml")) {
                if (in == null) {
                    return;
                }
                bundled = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            }
            StringBuilder additions = new StringBuilder();
            for (Section section : SECTIONS) {
                if (hasTopLevelKey(current, section.key())) {
                    continue;
                }
                List<String> lines = extract(bundled, section.header());
                if (lines.isEmpty()) {
                    continue;
                }
                additions.append(System.lineSeparator());
                for (String line : lines) {
                    additions.append(line).append(System.lineSeparator());
                }
                plugin.getLogger().info("Added the new '" + section.key().replace(":", "") + "' settings to config.yml.");
            }
            if (!additions.isEmpty()) {
                String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                String prefix = text.isEmpty() || text.endsWith("\n") ? "" : System.lineSeparator();
                Files.writeString(file.toPath(), prefix + additions, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not add new settings to config.yml (defaults will be used): " + throwable);
        }
    }

    private static boolean hasTopLevelKey(List<String> lines, String key) {
        for (String line : lines) {
            if (line.startsWith(key)) {
                return true;
            }
        }
        return false;
    }

    /** Lines from the section header up to the first blank line or the next "# ====" header. */
    private static List<String> extract(List<String> lines, String header) {
        List<String> result = new ArrayList<>();
        boolean inside = false;
        for (String line : lines) {
            if (inside && (line.isBlank() || line.startsWith("# ===="))) {
                break;
            }
            if (!inside && line.trim().equals(header)) {
                inside = true;
            }
            if (inside) {
                result.add(line);
            }
        }
        return result;
    }
}
