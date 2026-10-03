package com.igrobar.mtrdynmap;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

/**
 * MTR -> Dynmap bridge.
 *
 * This source is a cleaned/reconstructed source representation of the supplied
 * bridge JAR. MTR and Dynmap are accessed reflectively so the plugin does not
 * need their classes at compile time.
 */
public final class MTRDynmapBridge extends JavaPlugin {
    private String getPluginVersion() {
        return getDescription().getVersion();
    }
    private static final long INITIAL_DELAY_SECONDS = 5L;
    private static final long SYNC_DELAY_SECONDS = 3600L;

    private ScheduledExecutorService executor;
    private volatile boolean mtrInitialized;
    private ScheduledFuture<?> syncFuture;
    private Object markerApi;
    private Object markerSet;
    private final Set<String> worldResolutionWarnings = new HashSet<>();
    private int fallbackLogCount;
    private volatile boolean stopping;
    private final Map<String, String> worldOverrides = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        getLogger().info("MTR-Dynmap Bridge " + getPluginVersion() + " starting (executor + world-height Y mode)...");
        getLogger().info("MTR-Dynmap: sync scheduler = startup checks every 5s; after MTR is ready, every 60m");
        stopping = false;
        mtrInitialized = false;
        ensureConfigFile();
        loadWorldOverrides();
        autoPopulateVanillaWorldMappings();

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MTR-Dynmap-Bridge");
            t.setDaemon(true);
            return t;
        });

        syncFuture = executor.scheduleWithFixedDelay(this::runSyncTick,
                INITIAL_DELAY_SECONDS, 5L, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        stopping = true;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
            syncFuture = null;
        }
    }

    private void runSyncTick() {
        if (stopping) return;
        getLogger().info("MTR-Dynmap: synchronization tick started");
        try {
            // Do not call Bukkit scheduler APIs here. Some hybrid server builds expose
            // an incompatible Bukkit API at runtime (for example Server#getScheduler
            // may be absent even though the plugin was compiled against Paper).
            // The bridge historically performed the MTR/Dynmap reflection directly,
            // and keeping that path avoids linkage errors on those hybrids.
            safeRefresh();
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "MTR-Dynmap: scheduler tick failed: " + t, t);
        }

        // During startup MTR may initialize after Bukkit plugins. Keep checking
        // every 5 seconds until the first successful MTR synchronization. Once
        // MTR is available, switch this same scheduler to the hourly interval.
        if (!mtrInitialized && isMtrCoreAvailable()) {
            mtrInitialized = true;
            if (executor != null && !executor.isShutdown()) {
                ScheduledFuture<?> oldFuture = syncFuture;
                syncFuture = executor.scheduleWithFixedDelay(this::runSyncTick,
                        SYNC_DELAY_SECONDS, SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
                if (oldFuture != null) oldFuture.cancel(false);
            }
            getLogger().info("MTR-Dynmap: MTR detected; switching synchronization interval to every 60m");
        }
    }

    private boolean isMtrCoreAvailable() {
        return getMTRMain() != null;
    }

    private Method findCompatibleMethod(Class<?> type, String name, int parameterCount) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == parameterCount) return m;
            }
            for (Class<?> iface : c.getInterfaces()) {
                Method m = findCompatibleMethod(iface, name, parameterCount);
                if (m != null) return m;
            }
        }
        return null;
    }

    private void safeRefresh() {
        try {
            refresh();
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "MTR-Dynmap: refresh failed: " + t, t);
        }
    }

    private void refresh() throws Exception {
        if (stopping) return;

        Object dynmap = findDynmapPlugin();
        if (dynmap == null) {
            getLogger().warning("MTR-Dynmap: Dynmap plugin not found yet.");
            return;
        }

        Object api = invoke(dynmap, "getMarkerAPI");
        if (api == null) {
            getLogger().warning("MTR-Dynmap: Dynmap Marker API is not initialized yet.");
            return;
        }

        Object set = invoke(api, "getMarkerSet", String.class, "mtrbridge");
        if (set == null) {
            set = invoke(api, "createMarkerSet",
                    String.class, String.class, Set.class, boolean.class,
                    "mtrbridge", "MTR", null, true);
            invoke(set, "setHideByDefault", boolean.class, false);
            invoke(set, "setLabelShow", Boolean.class, Boolean.TRUE);
        }
        markerApi = api;
        markerSet = set;

        Object mtrMain = getMTRMain();
        if (mtrMain == null) {
            getLogger().warning("MTR-Dynmap: MTR core Main is not available yet.");
            return;
        }

        Field simulatorsField = mtrMain.getClass().getDeclaredField("simulators");
        simulatorsField.setAccessible(true);
        Object simulators = simulatorsField.get(mtrMain);

        // Populate missing world mappings automatically from the dimensions
        // that MTR actually exposes. Existing mappings are preserved.
        autoPopulateWorldMappings((Iterable<?>) simulators);

        Set<String> seen = new HashSet<>();
        fallbackLogCount = 0;

        for (Object simulator : (Iterable<?>) simulators) {
            String dimension = String.valueOf(simulator.getClass().getField("dimension").get(simulator));
            String world = resolveDynmapWorld(dimension);
            getLogger().info("MTR-Dynmap: MTR dimension " + dimension + " -> Dynmap world " + world);
            syncCollection(simulator, "stations", "station", world, seen);
            syncCollection(simulator, "depots", "depot", world, seen);
        }

        removeStale(seen);
        getLogger().info("MTR-Dynmap: synchronized " + seen.size() + " station/depot markers.");
        getLogger().info("MTR-Dynmap: synchronization tick finished");
    }

    private void syncCollection(Object simulator, String fieldName, String type,
                                String world, Set<String> seen) throws Exception {
        Field field = simulator.getClass().getField(fieldName);
        field.setAccessible(true);
        Object collection = field.get(simulator);

        for (Object entry : (Iterable<?>) collection) {
            Object center = accessibleMethod(entry.getClass(), "getCenter").invoke(entry);
            if (center == null) continue;

            long x = ((Number) invoke(center, "getX")).longValue();
            long y = ((Number) invoke(center, "getY")).longValue();
            long z = ((Number) invoke(center, "getZ")).longValue();
            y = resolveY(x, y, z, world);

            String hexId = String.valueOf(invoke(entry, "getHexId"));
            String name = String.valueOf(invoke(entry, "getName"));
            if (name == null || name.isBlank() || "null".equals(name)) {
                name = type.equals("station") ? "MTR Station" : "MTR Depot";
            }

            String id = type + "_" + world.replace(':', '_') + "_" + hexId;
            seen.add(id);

            // Use Dynmap's explicit transport-style icons instead of the default
            // marker (the default icon is a house). Stations use the standard
            // pin icon; depots use the standard building icon. If a custom/older
            // Dynmap build does not provide one of them, fall back to default.
            String iconId = type.equals("station") ? "pin" : "building";
            Object icon = invoke(markerApi, "getMarkerIcon", String.class, iconId);
            if (icon == null) {
                icon = invoke(markerApi, "getMarkerIcon", String.class, "default");
            }

            Object marker = invoke(markerSet, "findMarker", String.class, id);
            if (marker == null) {
                marker = invoke(markerSet, "createMarker",
                        String.class, String.class, String.class,
                        double.class, double.class, double.class,
                        Class.forName("org.dynmap.markers.MarkerIcon"), boolean.class,
                        id, name, world, (double) x, (double) y, (double) z, icon, true);
            } else {
                invoke(marker, "setLocation", String.class, double.class, double.class, double.class,
                        world, (double) x, (double) y, (double) z);
            }

            // Also repair the icon on existing persistent markers, so upgrading
            // from an older bridge version immediately restores the intended icons.
            if (icon != null) {
                try {
                    invoke(marker, "setMarkerIcon", Class.forName("org.dynmap.markers.MarkerIcon"), icon);
                } catch (Throwable ignored) {
                    // Older Dynmap APIs may not expose setMarkerIcon; creation still
                    // uses the correct icon above.
                }
            }

            String prefix = type.equals("station") ? "Station: " : "Depot: ";
            invoke(marker, "setLabel", String.class, prefix + name);
            invoke(marker, "setDescription", String.class,
                    "<b>" + escape(name) + "</b><br>Type: " + escape(type)
                            + "<br>World: " + escape(world)
                            + "<br>X: " + x + " Y: " + y + " Z: " + z);
        }
    }

    /**
     * Resolves an MTR dimension to the actual Bukkit/Dynmap world name.
     *
     * No server/world name is embedded in the plugin. Vanilla dimensions are
     * matched by their Bukkit environment, while custom dimensions first try
     * their actual world name/path. This keeps the bridge portable between
     * servers whose overworlds are named differently (for example Virus,
     * world, Survival, etc.).
     */
    private String resolveDynmapWorld(String dimension) {
        List<?> worlds;
        try {
            worlds = Bukkit.getWorlds();
        } catch (Throwable t) {
            return dimension;
        }

        // 1) Explicit server-local override from config.yml.
        String override = worldOverrides.get(dimension);
        if (override == null) override = worldOverrides.get(normalizeDimensionKey(dimension));
        if (override != null && !override.isBlank()) {
            Object configuredWorld = findWorldByName(worlds, override.trim());
            if (configuredWorld != null) {
                return getWorldName(configuredWorld);
            }
            warnOnce("override:" + dimension,
                    "configured world '" + override + "' for dimension '" + dimension
                            + "' is not loaded; falling back to automatic detection.");
        }

        // 2) Exact Bukkit world-name match. Useful for custom MTR dimensions.
        for (Object world : worlds) {
            String name = getWorldName(world);
            if (name != null && (name.equals(dimension) || name.equals(normalizeDimensionName(dimension)))) {
                return name;
            }
        }

        String normalized = normalizeDimensionName(dimension);
        String lower = normalized.toLowerCase(Locale.ROOT);

        // 3) Vanilla dimensions: use Bukkit Environment, never assume a name
        // such as "world", "Virus", etc.
        String environment = null;
        if (lower.equals("overworld") || lower.endsWith("/overworld")) {
            environment = "NORMAL";
        } else if (lower.equals("the_nether") || lower.endsWith("/the_nether")) {
            environment = "NETHER";
        } else if (lower.equals("the_end") || lower.endsWith("/the_end")) {
            environment = "THE_END";
        }

        if (environment != null) {
            List<String> candidates = new ArrayList<>();
            for (Object world : worlds) {
                if (environment.equals(getWorldEnvironment(world))) {
                    String name = getWorldName(world);
                    if (name != null && !name.isBlank()) candidates.add(name);
                }
            }
            if (!candidates.isEmpty()) {
                if (candidates.size() > 1) {
                    warnOnce("multi:" + dimension,
                            "multiple loaded " + environment + " worlds found for MTR dimension '"
                                    + dimension + "': " + candidates
                                    + ". Add an override under worlds: in plugins/MTR-Dynmap-Bridge/config.yml.");
                }
                return candidates.get(0);
            }
        }

        // 4) Custom dimensions: compare the final path component to a Bukkit world name.
        String path = normalized;
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < path.length()) path = path.substring(slash + 1);
        int colon = path.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < path.length()) path = path.substring(colon + 1);

        for (Object world : worlds) {
            String name = getWorldName(world);
            if (name != null && name.equalsIgnoreCase(path)) return name;
        }

        warnOnce("unresolved:" + dimension,
                "could not automatically resolve MTR dimension '" + dimension
                        + "' to a loaded Bukkit world; using '" + dimension + "' as fallback.");
        return dimension;
    }

    private String normalizeDimensionKey(String dimension) {
        if (dimension == null) return "";
        return dimension.replace('\\', '/').replace(':', '/').replaceAll("^/+|/+$", "");
    }

    private Object findWorldByName(List<?> worlds, String name) {
        for (Object world : worlds) {
            String worldName = getWorldName(world);
            if (worldName != null && worldName.equals(name)) return world;
        }
        return null;
    }

    private void warnOnce(String key, String message) {
        if (worldResolutionWarnings.add(key)) {
            getLogger().warning("MTR-Dynmap: " + message);
        }
    }

    /**
     * Populate the config immediately from the worlds Bukkit already knows.
     * This makes the first generated config useful even when MTR itself has
     * not finished initializing yet. Custom MTR dimensions are added later
     * when the MTR simulators become available.
     */
    private void autoPopulateVanillaWorldMappings() {
        try {
            List<?> worlds = Bukkit.getWorlds();
            LinkedHashMap<String, String> mappings = new LinkedHashMap<>(worldOverrides);
            boolean changed = false;

            changed |= addEnvironmentMapping(mappings, "minecraft/overworld", "NORMAL", worlds);
            changed |= addEnvironmentMapping(mappings, "minecraft/the_nether", "NETHER", worlds);
            changed |= addEnvironmentMapping(mappings, "minecraft/the_end", "THE_END", worlds);

            if (changed) {
                rewriteWorldMappingsPreservingComments(mappings);
                worldOverrides.clear();
                worldOverrides.putAll(mappings);
                getLogger().info("MTR-Dynmap: automatically detected " + mappings.size()
                        + " world mapping(s) and wrote them to config.yml");
            }
        } catch (Throwable t) {
            getLogger().warning("MTR-Dynmap: could not auto-detect Bukkit worlds: " + t);
        }
    }

    private boolean addEnvironmentMapping(Map<String, String> mappings, String dimension,
                                           String environment, List<?> worlds) {
        String existing = mappings.get(dimension);
        if (existing != null && !existing.isBlank() && findWorldByName(worlds, existing) != null) return false;

        List<String> candidates = new ArrayList<>();
        for (Object world : worlds) {
            if (environment.equals(getWorldEnvironment(world))) {
                String name = getWorldName(world);
                if (name != null && !name.isBlank()) candidates.add(name);
            }
        }
        if (candidates.isEmpty()) return false;
        if (candidates.size() > 1) {
            warnOnce("startup-multi:" + dimension,
                    "multiple loaded " + environment + " worlds found for '" + dimension
                            + "': " + candidates + ". The first one ('" + candidates.get(0)
                            + "') was written automatically; change config.yml if another world should be used.");
        }
        String selected = candidates.get(0);
        if (selected.equals(existing)) return false;
        mappings.put(dimension, selected);
        return true;
    }

    private void rewriteWorldMappingsPreservingComments(LinkedHashMap<String, String> mappings) throws Exception {
        File file = new File(getDataFolder(), "config.yml");
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        int worldsIndex = -1;
        int end = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals("worlds:")) {
                worldsIndex = i;
                end = i + 1;
                while (end < lines.size()) {
                    String raw = lines.get(end);
                    if (raw.startsWith("  ") || raw.startsWith("\t") || raw.trim().isEmpty() || raw.trim().startsWith("#")) end++;
                    else break;
                }
                break;
            }
        }
        if (worldsIndex < 0) {
            lines.add("worlds:");
            worldsIndex = lines.size() - 1;
            end = lines.size();
        }
        lines.subList(worldsIndex + 1, end).clear();
        List<String> replacement = new ArrayList<>();
        for (Map.Entry<String, String> e : mappings.entrySet()) {
            replacement.add("  " + yamlQuoteIfNeeded(e.getKey()) + ": " + yamlQuoteIfNeeded(e.getValue()));
        }
        lines.addAll(worldsIndex + 1, replacement);
        Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
    }

    private void autoPopulateWorldMappings(Iterable<?> simulators) {
        try {
            File file = new File(getDataFolder(), "config.yml");
            if (!file.isFile()) return;

            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            LinkedHashMap<String, String> mappings = readWorldMappings(lines);
            LinkedHashMap<String, String> additions = new LinkedHashMap<>();

            for (Object simulator : simulators) {
                String dimension = String.valueOf(simulator.getClass().getField("dimension").get(simulator));
                if (dimension == null || dimension.isBlank()) continue;

                String key = dimension;
                if (mappings.containsKey(key)) {
                    // If an existing mapping points to a world that disappeared,
                    // repair it automatically using the current server worlds.
                    if (findWorldByName(Bukkit.getWorlds(), mappings.get(key)) == null) {
                        String detected = resolveDynmapWorldIgnoringOverride(dimension);
                        if (detected != null && !detected.equals(dimension)) {
                            mappings.put(key, detected);
                            getLogger().info("MTR-Dynmap: repaired world mapping " + dimension + " -> " + detected);
                        }
                    }
                    continue;
                }

                String detected = resolveDynmapWorldIgnoringOverride(dimension);
                if (detected != null && !detected.isBlank() && !detected.equals(dimension)) {
                    additions.put(key, detected);
                    mappings.put(key, detected);
                }
            }

            if (!additions.isEmpty()) {
                writeWorldMappings(lines, additions);
                getLogger().info("MTR-Dynmap: automatically added " + additions.size()
                        + " world mapping(s) to config.yml");
                worldOverrides.clear();
                worldOverrides.putAll(mappings);
            } else {
                // Keep the runtime view synchronized with the file if a repair occurred.
                writeAllMappingsIfChanged(lines, mappings);
                worldOverrides.clear();
                worldOverrides.putAll(mappings);
            }
        } catch (Throwable t) {
            getLogger().warning("MTR-Dynmap: could not auto-populate world mappings: " + t);
        }
    }

    private String resolveDynmapWorldIgnoringOverride(String dimension) {
        String previous = worldOverrides.remove(dimension);
        String normalizedKey = normalizeDimensionKey(dimension);
        String previousNormalized = worldOverrides.remove(normalizedKey);
        try {
            return resolveDynmapWorld(dimension);
        } finally {
            if (previous != null) worldOverrides.put(dimension, previous);
            if (previousNormalized != null) worldOverrides.put(normalizedKey, previousNormalized);
        }
    }

    private LinkedHashMap<String, String> readWorldMappings(List<String> lines) {
        LinkedHashMap<String, String> mappings = new LinkedHashMap<>();
        boolean inWorlds = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.equals("worlds:")) { inWorlds = true; continue; }
            if (!inWorlds) continue;
            if (!raw.startsWith("  ") && !raw.startsWith("\t")) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = stripYamlQuotes(line.substring(0, colon).trim());
            String value = stripYamlQuotes(line.substring(colon + 1).trim());
            if (!key.isEmpty() && !value.isEmpty()) mappings.put(key, value);
        }
        return mappings;
    }

    private void writeWorldMappings(List<String> lines, LinkedHashMap<String, String> additions) throws Exception {
        int worldsIndex = -1;
        int insertAt = -1;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.equals("worlds:")) {
                worldsIndex = i;
                insertAt = i + 1;
                while (insertAt < lines.size()) {
                    String raw = lines.get(insertAt);
                    if (raw.startsWith("  ") || raw.startsWith("\t") || raw.trim().isEmpty() || raw.trim().startsWith("#")) insertAt++;
                    else break;
                }
                break;
            }
        }
        if (worldsIndex < 0) {
            lines.add("worlds:");
            insertAt = lines.size();
        }
        List<String> additionsLines = new ArrayList<>();
        for (Map.Entry<String, String> e : additions.entrySet()) {
            additionsLines.add("  " + yamlQuoteIfNeeded(e.getKey()) + ": " + yamlQuoteIfNeeded(e.getValue()));
        }
        lines.addAll(insertAt, additionsLines);
        Files.write(new File(getDataFolder(), "config.yml").toPath(), lines, StandardCharsets.UTF_8);
    }

    private void writeAllMappingsIfChanged(List<String> lines, LinkedHashMap<String, String> mappings) throws Exception {
        // Only rewrite when a previously configured world disappeared and was repaired.
        LinkedHashMap<String, String> current = readWorldMappings(lines);
        if (current.equals(mappings)) return;
        int worldsIndex = -1;
        int end = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals("worlds:")) {
                worldsIndex = i; end = i + 1;
                while (end < lines.size()) {
                    String raw = lines.get(end);
                    if (raw.startsWith("  ") || raw.startsWith("\t") || raw.trim().isEmpty() || raw.trim().startsWith("#")) end++;
                    else break;
                }
                break;
            }
        }
        if (worldsIndex < 0) { lines.add("worlds:"); worldsIndex = lines.size()-1; end=lines.size(); }
        List<String> replacement = new ArrayList<>();
        for (Map.Entry<String, String> e : mappings.entrySet()) {
            replacement.add("  " + yamlQuoteIfNeeded(e.getKey()) + ": " + yamlQuoteIfNeeded(e.getValue()));
        }
        lines.subList(worldsIndex + 1, end).clear();
        lines.addAll(worldsIndex + 1, replacement);
        Files.write(new File(getDataFolder(), "config.yml").toPath(), lines, StandardCharsets.UTF_8);
    }

    private String yamlQuoteIfNeeded(String value) {
        if (value.matches("[A-Za-z0-9_./-]+")) return value;
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private void ensureConfigFile() {
        try {
            File dir = getDataFolder();
            if (dir == null) return;
            if (!dir.exists() && !dir.mkdirs()) return;
            File file = new File(dir, "config.yml");
            if (!file.exists()) {
                String content = "# MTR-Dynmap Bridge " + getPluginVersion() + "\\n"
                        + "# World mappings are detected automatically from the MTR dimensions on the server.\\n"
                        + "# You normally do NOT need to edit this file.\\n"
                        + "# On first synchronization, missing mappings are written here automatically.\\n"
                        + "# If you have multiple worlds and want to choose a specific one, edit the mapping.\\n"
                        + "# Example:\\n"
                        + "# worlds:\\n"
                        + "#   minecraft/overworld: Survival\\n"
                        + "#   minecraft/the_nether: Survival_nether\\n"
                        + "#   minecraft/the_end: Survival_the_end\\n"
                        + "# Existing entries are preserved unless the configured world no longer exists.\\n"
                        + "worlds:\\n";
                Files.writeString(file.toPath(), content.replace("\\n", System.lineSeparator()), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            getLogger().warning("MTR-Dynmap: could not create config.yml: " + t);
        }
    }

    private void loadWorldOverrides() {
        worldOverrides.clear();
        try {
            File file = new File(getDataFolder(), "config.yml");
            if (!file.isFile()) return;
            boolean inWorlds = false;
            for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.equals("worlds:")) { inWorlds = true; continue; }
                if (!inWorlds) continue;
                if (!raw.startsWith("  ") && !raw.startsWith("\t")) break;
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = stripYamlQuotes(line.substring(0, colon).trim());
                String value = stripYamlQuotes(line.substring(colon + 1).trim());
                if (!key.isEmpty() && !value.isEmpty()) worldOverrides.put(key, value);
            }
            if (!worldOverrides.isEmpty()) {
                getLogger().info("MTR-Dynmap: loaded " + worldOverrides.size() + " world mapping override(s) from config.yml");
            }
        } catch (Throwable t) {
            getLogger().warning("MTR-Dynmap: could not read config.yml: " + t);
        }
    }

    private String stripYamlQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0), last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private String normalizeDimensionName(String dimension) {
        if (dimension == null) return "";
        return dimension.replace('\\', '/').replace(':', '/').replaceAll("^/+|/+$", "");
    }

    private String getWorldName(Object world) {
        try {
            Object value = world.getClass().getMethod("getName").invoke(world);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String getWorldEnvironment(Object world) {
        try {
            Object environment = world.getClass().getMethod("getEnvironment").invoke(world);
            return environment == null ? null : String.valueOf(environment);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private long resolveY(long x, long y, long z, String worldName) {
        if (y != 0) return y;
        try {
            Object world = Bukkit.getWorld(worldName);
            if (world != null) {
                Method m = Class.forName("org.bukkit.World")
                        .getMethod("getHighestBlockYAt", int.class, int.class);
                long surface = ((Number) m.invoke(world, (int) x, (int) z)).longValue() + 1L;
                logFallback("world surface", worldName, x, y, z, surface);
                return surface;
            }
        } catch (Throwable t) {
            if (fallbackLogCount < 10) {
                getLogger().warning("MTR-Dynmap: Y recovery failed " + worldName + " "
                        + x + "," + y + "," + z + ": " + t);
                fallbackLogCount++;
            }
        }
        return y;
    }

    private void logFallback(String mode, String world, long x, long oldY, long z, long newY) {
        if (fallbackLogCount < 10) {
            getLogger().info("MTR-Dynmap: Y recovery (" + mode + "): " + world + " "
                    + x + "," + oldY + "," + z + " -> " + newY);
            fallbackLogCount++;
        }
    }

    private void removeStale(Set<String> seen) throws Exception {
        // The mtrbridge marker set is owned by this plugin. Delete every marker
        // that is no longer present in the current MTR station/depot snapshot.
        // This also cleans up markers created by older bridge builds whose IDs
        // used the station_<world>_<hex> / depot_<world>_<hex> format.
        Object markers = invoke(markerSet, "getMarkers");
        int removed = 0;
        for (Object marker : new ArrayList<>((Collection<?>) markers)) {
            String id = String.valueOf(invoke(marker, "getMarkerID"));
            if (!seen.contains(id)) {
                invoke(marker, "deleteMarker");
                removed++;
            }
        }
        if (removed > 0) {
            getLogger().info("MTR-Dynmap: removed " + removed + " stale station/depot marker(s).");
        }
    }

    private Object findDynmapPlugin() throws Exception {
        Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
        Object manager = bukkit.getMethod("getPluginManager").invoke(null);
        return manager.getClass().getMethod("getPlugin", String.class).invoke(manager, "dynmap");
    }

    private Object getMTRMain() {
        String[] candidates = {
                "org.mtr.MTR",
                "org.mtr.mod.Init",
                "org.mtr.mod.MTR",
                "org.mtr.core.Main"
        };

        for (String className : candidates) {
            try {
                Class<?> type = Class.forName(className);

                // First try conventional static fields (main, instance, etc.).
                for (String fieldName : new String[]{"main", "instance", "MAIN", "INSTANCE"}) {
                    try {
                        Field field = type.getDeclaredField(fieldName);
                        field.setAccessible(true);
                        Object value = field.get(null);
                        Object main = findMainLikeObject(value);
                        if (main != null) return main;
                    } catch (Throwable ignored) {
                    }
                }

                // Some MTR hosts keep the core Main in another static field.
                for (Field field : type.getDeclaredFields()) {
                    try {
                        if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                        field.setAccessible(true);
                        Object value = field.get(null);
                        Object main = findMainLikeObject(value);
                        if (main != null) return main;
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Object findMainLikeObject(Object value) {
        if (value == null) return null;
        try {
            Field simulators = value.getClass().getDeclaredField("simulators");
            simulators.setAccessible(true);
            Object sims = simulators.get(value);
            if (sims instanceof Iterable<?>) return value;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Method accessibleMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        // Dynmap's concrete MarkerImpl does not necessarily declare every API
        // method itself; methods such as setLocation/setMarkerIcon can be exposed
        // through the Marker interface. Search superclasses and interfaces.
        Set<Class<?>> visited = new HashSet<>();
        Method method = findMethodRecursive(type, name, parameterTypes, visited);
        if (method != null) {
            try { method.setAccessible(true); } catch (Throwable ignored) {}
            return method;
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static Method findMethodRecursive(Class<?> type, String name, Class<?>[] parameterTypes,
                                              Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) return null;
        try {
            return type.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
        }
        for (Class<?> iface : type.getInterfaces()) {
            Method method = findMethodRecursive(iface, name, parameterTypes, visited);
            if (method != null) return method;
        }
        return findMethodRecursive(type.getSuperclass(), name, parameterTypes, visited);
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) types[i] = args[i] == null ? Object.class : args[i].getClass();
        return accessibleMethod(target.getClass(), name, types).invoke(target, args);
    }

    private static Object invoke(Object target, String name, Class<?> p1, Object a1) throws Exception {
        return accessibleMethod(target.getClass(), name, p1).invoke(target, a1);
    }

    private static Object invoke(Object target, String name,
                                  Class<?> p1, Class<?> p2, Class<?> p3, Class<?> p4,
                                  Object a1, Object a2, Object a3, Object a4) throws Exception {
        return accessibleMethod(target.getClass(), name, p1, p2, p3, p4)
                .invoke(target, a1, a2, a3, a4);
    }

    private static Object invoke(Object target, String name,
                                  Class<?> p1, Class<?> p2, Class<?> p3, Class<?> p4,
                                  Class<?> p5, Class<?> p6, Class<?> p7, Class<?> p8,
                                  Object a1, Object a2, Object a3, Object a4,
                                  Object a5, Object a6, Object a7, Object a8) throws Exception {
        return accessibleMethod(target.getClass(), name, p1, p2, p3, p4, p5, p6, p7, p8)
                .invoke(target, a1, a2, a3, a4, a5, a6, a7, a8);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
