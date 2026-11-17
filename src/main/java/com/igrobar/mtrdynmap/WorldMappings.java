package com.igrobar.mtrdynmap;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;

/**
 * MTR dimension → Bukkit/Dynmap world name (the {@code worlds:} section of config.yml).
 * No world name is hard-coded: vanilla dimensions are matched by their Bukkit environment, custom ones by
 * world name. Detected mappings are written back line by line, so the comments in config.yml are kept.
 */
final class WorldMappings {
    private final MTRDynmapBridge plugin;
    private final Map<String, String> overrides = new LinkedHashMap<>();
    private final Set<String> warned = new HashSet<>();

    WorldMappings(MTRDynmapBridge plugin) {
        this.plugin = plugin;
    }

    private File file() {
        return new File(this.plugin.getDataFolder(), "config.yml");
    }

    // ------------------------------------------------------------------ loading

    synchronized void load() {
        this.overrides.clear();
        try {
            File file = this.file();
            if (!file.isFile()) {
                return;
            }
            this.overrides.putAll(read(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)));
            if (!this.overrides.isEmpty()) {
                this.plugin.getLogger().info("Loaded " + this.overrides.size() + " world mapping(s) from config.yml.");
            }
        } catch (Throwable throwable) {
            this.plugin.getLogger().warning("Could not read world mappings from config.yml: " + throwable);
        }
    }

    /** Writes the vanilla dimensions right away, even before MTR has started. */
    synchronized void autoPopulateVanilla() {
        try {
            List<?> worlds = Bukkit.getWorlds();
            LinkedHashMap<String, String> mappings = new LinkedHashMap<>(this.overrides);
            boolean changed = false;
            changed |= this.addEnvironmentMapping(mappings, "minecraft/overworld", "NORMAL", worlds);
            changed |= this.addEnvironmentMapping(mappings, "minecraft/the_nether", "NETHER", worlds);
            changed |= this.addEnvironmentMapping(mappings, "minecraft/the_end", "THE_END", worlds);
            if (changed) {
                this.writeAll(mappings);
                this.overrides.clear();
                this.overrides.putAll(mappings);
                this.plugin.getLogger().info("Detected " + mappings.size() + " world mapping(s) and wrote them to config.yml.");
            }
        } catch (Throwable throwable) {
            this.plugin.getLogger().warning("Could not detect Bukkit worlds: " + throwable);
        }
    }

    /** Adds mappings for MTR dimensions that are not in config.yml yet, and repairs mappings to worlds that disappeared. */
    synchronized void autoPopulate(Iterable<String> rawDimensions) {
        try {
            LinkedHashMap<String, String> mappings = new LinkedHashMap<>(this.overrides);
            boolean changed = false;
            List<?> worlds = Bukkit.getWorlds();
            for (String dimension : rawDimensions) {
                if (dimension == null || dimension.isBlank()) {
                    continue;
                }
                String existing = mappings.get(dimension);
                if (existing != null && findWorldByName(worlds, existing) != null) {
                    continue;
                }
                String detected = this.resolveIgnoringOverride(dimension);
                if (detected != null && !detected.isBlank() && !detected.equals(dimension) && !detected.equals(existing)) {
                    mappings.put(dimension, detected);
                    changed = true;
                    this.plugin.getLogger().info((existing == null ? "Added" : "Repaired") + " world mapping "
                            + dimension + " -> " + detected + " in config.yml.");
                }
            }
            if (changed) {
                this.writeAll(mappings);
                this.overrides.clear();
                this.overrides.putAll(mappings);
            }
        } catch (Throwable throwable) {
            this.plugin.getLogger().warning("Could not update world mappings: " + throwable);
        }
    }

    // ------------------------------------------------------------------ resolving

    /** Bukkit/Dynmap world for an MTR dimension such as "minecraft/overworld". */
    synchronized String resolve(String dimension) {
        List<?> worlds;
        try {
            worlds = Bukkit.getWorlds();
        } catch (Throwable throwable) {
            return dimension;
        }

        // 1) Explicit mapping from config.yml
        String override = this.overrides.get(dimension);
        if (override == null) {
            override = this.overrides.get(normalize(dimension));
        }
        if (override != null && !override.isBlank()) {
            Object configured = findWorldByName(worlds, override.trim());
            if (configured != null) {
                return worldName(configured);
            }
            this.warnOnce("override:" + dimension, "configured world '" + override + "' for dimension '" + dimension
                    + "' is not loaded; falling back to automatic detection.");
        }

        // 2) Exact world name
        String normalized = normalize(dimension);
        for (Object world : worlds) {
            String name = worldName(world);
            if (name != null && (name.equals(dimension) || name.equals(normalized))) {
                return name;
            }
        }

        // 3) Vanilla dimensions by Bukkit environment (never by an assumed name such as "world")
        String lower = normalized.toLowerCase(Locale.ROOT);
        String environment = null;
        if (lower.equals("overworld") || lower.endsWith("/overworld")) {
            environment = "NORMAL";
        } else if (lower.equals("the_nether") || lower.endsWith("/the_nether")) {
            environment = "NETHER";
        } else if (lower.equals("the_end") || lower.endsWith("/the_end")) {
            environment = "THE_END";
        }
        if (environment != null) {
            List<String> candidates = worldsForEnvironment(worlds, environment);
            if (!candidates.isEmpty()) {
                if (candidates.size() > 1) {
                    this.warnOnce("multi:" + dimension, "multiple loaded " + environment + " worlds found for MTR dimension '"
                            + dimension + "': " + candidates + ". Set the one to use under worlds: in config.yml.");
                }
                return candidates.get(0);
            }
        }

        // 4) Custom dimensions: last path element = world name
        String path = normalized;
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < path.length()) {
            path = path.substring(slash + 1);
        }
        for (Object world : worlds) {
            String name = worldName(world);
            if (name != null && name.equalsIgnoreCase(path)) {
                return name;
            }
        }

        this.warnOnce("unresolved:" + dimension, "could not match MTR dimension '" + dimension
                + "' to a loaded world; using '" + dimension + "'. Set it under worlds: in config.yml.");
        return dimension;
    }

    private String resolveIgnoringOverride(String dimension) {
        String previous = this.overrides.remove(dimension);
        String key = normalize(dimension);
        String previousNormalized = this.overrides.remove(key);
        try {
            return this.resolve(dimension);
        } finally {
            if (previous != null) {
                this.overrides.put(dimension, previous);
            }
            if (previousNormalized != null) {
                this.overrides.put(key, previousNormalized);
            }
        }
    }

    private boolean addEnvironmentMapping(Map<String, String> mappings, String dimension, String environment, List<?> worlds) {
        String existing = mappings.get(dimension);
        if (existing != null && !existing.isBlank() && findWorldByName(worlds, existing) != null) {
            return false;
        }
        List<String> candidates = worldsForEnvironment(worlds, environment);
        if (candidates.isEmpty()) {
            return false;
        }
        if (candidates.size() > 1) {
            this.warnOnce("startup-multi:" + dimension, "multiple loaded " + environment + " worlds found for '" + dimension
                    + "': " + candidates + ". '" + candidates.get(0) + "' was written to config.yml; change it there if needed.");
        }
        String selected = candidates.get(0);
        if (selected.equals(existing)) {
            return false;
        }
        mappings.put(dimension, selected);
        return true;
    }

    /** Surface height + 1 at x/z, used when MTR stores a station/depot centre with Y = 0. */
    long surfaceY(String worldName, long x, long y, long z) {
        if (y != 0) {
            return y;
        }
        try {
            Object world = Bukkit.getWorld(worldName);
            if (world != null) {
                Method method = Class.forName("org.bukkit.World").getMethod("getHighestBlockYAt", int.class, int.class);
                return ((Number) method.invoke(world, (int) x, (int) z)).longValue() + 1L;
            }
        } catch (Throwable throwable) {
            this.warnOnce("surface:" + worldName, "could not read the surface height in " + worldName + ": " + throwable);
        }
        return y;
    }

    // ------------------------------------------------------------------ config.yml (line based, keeps comments)

    /** Reads "worlds:" entries. */
    static LinkedHashMap<String, String> read(List<String> lines) {
        LinkedHashMap<String, String> mappings = new LinkedHashMap<>();
        int start = worldsLine(lines);
        if (start < 0) {
            return mappings;
        }
        int end = blockEnd(lines, start);
        for (int i = start + 1; i < end; i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(": ");
            if (colon < 0 && line.endsWith(":")) {
                continue;
            }
            if (colon < 0) {
                colon = line.indexOf(':');
            }
            if (colon <= 0) {
                continue;
            }
            String key = stripQuotes(line.substring(0, colon).trim());
            String value = stripQuotes(stripComment(line.substring(colon + 1).trim()));
            if (!key.isEmpty() && !value.isEmpty()) {
                mappings.put(key, value);
            }
        }
        return mappings;
    }

    /** Replaces the entries of the "worlds:" section; comments inside and outside the section stay. */
    private void writeAll(LinkedHashMap<String, String> mappings) throws Exception {
        File file = this.file();
        List<String> lines = file.isFile()
                ? new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))
                : new ArrayList<>();
        int start = worldsLine(lines);
        if (start < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.add("worlds:");
            start = lines.size() - 1;
        }
        lines.set(start, "worlds:"); // "worlds: {}" → "worlds:"
        int end = blockEnd(lines, start);
        // Keep indented comment lines of the section, replace only the entries.
        List<String> kept = new ArrayList<>();
        for (int i = start + 1; i < end; i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("#")) {
                kept.add(lines.get(i));
            }
        }
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            kept.add("  " + quoteIfNeeded(entry.getKey()) + ": " + quoteIfNeeded(entry.getValue()));
        }
        lines.subList(start + 1, end).clear();
        lines.addAll(start + 1, kept);
        Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
    }

    private static int worldsLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("worlds:")) {
                return i;
            }
        }
        return -1;
    }

    /** Index after the last indented line that belongs to the section starting at {@code start}. */
    private static int blockEnd(List<String> lines, int start) {
        int last = start;
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith(" ") || line.startsWith("\t")) {
                last = i;
            } else {
                break;
            }
        }
        return last + 1;
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> worldsForEnvironment(List<?> worlds, String environment) {
        List<String> candidates = new ArrayList<>();
        for (Object world : worlds) {
            if (environment.equals(worldEnvironment(world))) {
                String name = worldName(world);
                if (name != null && !name.isBlank()) {
                    candidates.add(name);
                }
            }
        }
        return candidates;
    }

    private static Object findWorldByName(List<?> worlds, String name) {
        for (Object world : worlds) {
            String worldName = worldName(world);
            if (worldName != null && worldName.equals(name)) {
                return world;
            }
        }
        return null;
    }

    private static String worldName(Object world) {
        try {
            Object value = world.getClass().getMethod("getName").invoke(world);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String worldEnvironment(Object world) {
        try {
            Object value = world.getClass().getMethod("getEnvironment").invoke(world);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String normalize(String dimension) {
        if (dimension == null) {
            return "";
        }
        return dimension.replace('\\', '/').replace(':', '/').replaceAll("^/+|/+$", "");
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String stripComment(String value) {
        if (value.startsWith("\"") || value.startsWith("'")) {
            return value;
        }
        int comment = value.indexOf(" #");
        return comment >= 0 ? value.substring(0, comment).trim() : value;
    }

    private static String quoteIfNeeded(String value) {
        if (value.matches("[A-Za-z0-9_./-]+")) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void warnOnce(String key, String message) {
        if (this.warned.add(key)) {
            this.plugin.getLogger().warning(message);
        }
    }
}
