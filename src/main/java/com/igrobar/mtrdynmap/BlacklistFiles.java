package com.igrobar.mtrdynmap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Station and route blacklists (blacklist-stations.yml / blacklist-routes.yml).
 *
 * <ul>
 *   <li>Stations and depots listed by name are hidden on the web map and on Dynmap.</li>
 *   <li>Routes listed by name or number are hidden on the web map: their vehicles, line colours and legend entry.</li>
 * </ul>
 *
 * The files are checked once per update cycle (by modification time), so changes are picked up automatically.
 */
final class BlacklistFiles {
    private final Path folder;
    private long stationStamp = Long.MIN_VALUE;
    private long routeStamp = Long.MIN_VALUE;
    private volatile Set<String> stations = Set.of();
    private volatile Set<String> routes = Set.of();

    BlacklistFiles(Path folder) {
        this.folder = folder;
    }

    synchronized void reloadIfChanged() {
        try {
            Path stationFile = this.folder.resolve("blacklist-stations.yml");
            Path routeFile = this.folder.resolve("blacklist-routes.yml");
            if (!Files.exists(stationFile) || !Files.exists(routeFile)) {
                Files.createDirectories(this.folder);
                ensureTemplate(stationFile, "stations", "station or depot");
                ensureTemplate(routeFile, "routes", "route name or route number");
            }
            long stationTime = Files.getLastModifiedTime(stationFile).toMillis();
            if (stationTime != this.stationStamp) {
                this.stations = read(stationFile, "stations");
                this.stationStamp = stationTime;
            }
            long routeTime = Files.getLastModifiedTime(routeFile).toMillis();
            if (routeTime != this.routeStamp) {
                this.routes = read(routeFile, "routes");
                this.routeStamp = routeTime;
            }
        } catch (Throwable ignored) {
            // keep the previous lists if the files cannot be read
        }
    }

    /** A station/depot by its name (raw MTR name or the name as shown on the map) or by its Dynmap marker id. */
    boolean isStationBlacklisted(String... namesOrIds) {
        return containsAny(this.stations, namesOrIds);
    }

    /** A route by its name (raw or as shown) or by its number. */
    boolean isRouteBlacklisted(String... namesOrNumbers) {
        return containsAny(this.routes, namesOrNumbers);
    }

    private static void ensureTemplate(Path path, String section, String what) throws IOException {
        if (!Files.exists(path)) {
            String template = "# Add exact names (" + what + ") below, one per line. Changes are picked up automatically.\n"
                    + section + ":\n  # - Example name\n";
            Files.writeString(path, template, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
    }

    private static Set<String> read(Path path, String section) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        boolean inSection = false;
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!line.startsWith("-") && line.endsWith(":")) {
                inSection = line.substring(0, line.length() - 1).trim().equals(section);
                continue;
            }
            if (!inSection || !line.startsWith("-")) {
                continue;
            }
            String value = line.substring(1).trim();
            int comment = value.indexOf(" #");
            if (comment >= 0) {
                value = value.substring(0, comment).trim();
            }
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            if (value.isBlank() || value.startsWith("Example name")) {
                continue;
            }
            result.add(value.toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(result);
    }

    private static boolean containsAny(Set<String> set, String... values) {
        if (set.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (value != null && !value.isBlank() && set.contains(value.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
