package com.igrobar.mtrdynmap;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import com.igrobar.mtrdynmap.web.Json;
import com.igrobar.mtrdynmap.web.MapData;
import com.igrobar.mtrdynmap.web.WebMapServer;

/**
 * MTR → Dynmap bridge with a live web map of MTR vehicles.
 *
 * <p>The Bukkit scheduler is not used (some hybrid servers expose an incompatible one at runtime).
 * The plugin runs two threads of its own: one feeds the web map, the other synchronizes Dynmap markers.
 * MTR data is always read on MTR's simulation thread, see {@link MtrAccess}.</p>
 */
public final class MTRDynmapBridge extends JavaPlugin {
    /** Default port of the live web map. */
    static final int DEFAULT_WEB_PORT = 25970;
    private static final List<String> ALL_MODES = List.of("train", "boat", "cable_car", "airplane");
    private static final long NETWORK_TIMEOUT_MS = 5000L;
    private static final long VEHICLE_TIMEOUT_MS = 2000L;

    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;
    private volatile Lang lang = Lang.of("en");
    private BlacklistFiles blacklist;
    private WorldMappings worlds;
    private MtrAccess mtr;
    private ScheduledExecutorService webThread;
    private ScheduledExecutorService dynmapThread;

    // web map
    private WebMapServer webMap;
    private WebMapCollector webCollector;
    private long webTrainIntervalMs;
    private boolean showDepotVehicles;
    private Set<String> modes = Set.copyOf(ALL_MODES);

    // dynmap
    private boolean dynmapEnabled;
    private long dynmapIntervalMs;
    private long nextDynmapSync;
    private String stationIcon;
    private String depotIcon;
    private DynmapSync dynmap;

    // latest data for commands and exports
    private volatile List<MtrData.Network> latestNetworks = List.of();
    private volatile Map<Long, Integer> vehiclesPerRoute = Map.of();
    private volatile boolean mtrSeen;
    private String lastExport = "";

    @Override
    public void onEnable() {
        this.stopping = false;
        this.getLogger().info("MTR-Dynmap Bridge " + this.getDescription().getVersion() + " starting...");

        // write config.yml on first start, then add sections that older configs do not have yet
        this.saveDefaultConfig();
        ConfigUpgrade.apply(this);
        this.reloadConfig();
        this.lang = Lang.of(this.getConfig().getString("language", "en"));
        this.modes = this.readModes();

        File dataFolder = this.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs() && !dataFolder.isDirectory()) {
            throw new IllegalStateException("Cannot create plugin data folder: " + dataFolder);
        }
        for (String name : List.of("blacklist-stations.yml", "blacklist-routes.yml")) {
            if (!new File(dataFolder, name).exists()) {
                try {
                    this.saveResource(name, false);
                } catch (Throwable ignored) {
                    // BlacklistFiles writes a minimal template instead
                }
            }
        }
        this.blacklist = new BlacklistFiles(dataFolder.toPath());
        this.blacklist.reloadIfChanged();
        this.worlds = new WorldMappings(this);
        this.worlds.load();
        this.worlds.autoPopulateVanilla();
        this.mtr = new MtrAccess(this);

        this.registerCommands();

        this.dynmapEnabled = this.getConfig().getBoolean("dynmap.enabled", true);
        this.dynmapIntervalMs = Math.max(1L, this.getConfig().getLong("dynmap.sync-interval-minutes", 60L)) * 60_000L;
        this.stationIcon = this.getConfig().getString("dynmap.station-icon", "pin");
        this.depotIcon = this.getConfig().getString("dynmap.depot-icon", "building");
        this.dynmap = new DynmapSync(this, this.worlds);
        this.nextDynmapSync = 0L;

        this.dynmapThread = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "MTR-Dynmap-Bridge-Dynmap"));
        // Checks every 5 s: waits for MTR and Dynmap, then synchronizes every dynmap.sync-interval-minutes.
        this.dynmapThread.scheduleWithFixedDelay(this::dynmapTick, 5L, 5L, TimeUnit.SECONDS);

        this.startWebMap();

        this.getLogger().info("Dynmap markers: " + (this.dynmapEnabled
                ? "on (every " + this.dynmapIntervalMs / 60_000L + " min; Dynmap is optional)" : "off")
                + ". Waiting for MTR to start...");
    }

    @Override
    public void onDisable() {
        this.stopping = true;
        if (this.webThread != null) {
            this.webThread.shutdownNow();
            this.webThread = null;
        }
        if (this.dynmapThread != null) {
            this.dynmapThread.shutdownNow();
            this.dynmapThread = null;
        }
        if (this.webMap != null) {
            this.webMap.stop();
            this.webMap = null;
        }
        Reflect.clear();
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private void registerCommands() {
        if (this.getCommand("mtrmap") != null) {
            this.getCommand("mtrmap").setExecutor(new MapCommand(this));
        }
        if (this.getCommand("mtrstations") != null) {
            this.getCommand("mtrstations").setExecutor(new StationsCommand(this));
        }
        if (this.getCommand("mtrroutes") != null) {
            this.getCommand("mtrroutes").setExecutor(new RoutesCommand(this));
        }
    }

    private Set<String> readModes() {
        List<String> configured = this.getConfig().getStringList("web-map.transport-modes");
        if (configured == null || configured.isEmpty()) {
            return Set.copyOf(ALL_MODES);
        }
        Set<String> result = new LinkedHashSet<>();
        for (String mode : configured) {
            String normalized = mode.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            if (normalized.equals("cablecar")) {
                normalized = "cable_car";
            }
            if (normalized.equals("plane")) {
                normalized = "airplane";
            }
            result.add(normalized);
        }
        return Set.copyOf(result);
    }

    // ------------------------------------------------------------------ MTR state

    /** Logs once when MTR becomes available. */
    private boolean checkMtr() {
        boolean available = this.mtr.isAvailable();
        if (available && !this.mtrSeen) {
            this.mtrSeen = true;
            this.getLogger().info("MTR detected; reading stations, routes and vehicles from the MTR simulation.");
        }
        return available;
    }

    // ------------------------------------------------------------------ Dynmap

    private void dynmapTick() {
        if (this.stopping || !this.dynmapEnabled || System.currentTimeMillis() < this.nextDynmapSync) {
            return;
        }
        try {
            if (!this.checkMtr()) {
                return;
            }
            MtrAccess.Result<MtrData.Network> result = this.mtr.networks(NETWORK_TIMEOUT_MS);
            if (result == null) {
                return;
            }
            this.blacklist.reloadIfChanged();
            if (this.webMap == null) {
                this.latestNetworks = List.copyOf(result.values());
            }
            int count = this.dynmap.sync(result.values(), result.complete(), this.stationIcon, this.depotIcon);
            if (count >= 0) {
                this.nextDynmapSync = System.currentTimeMillis() + this.dynmapIntervalMs;
                this.getLogger().info("Dynmap: synchronized " + count + " station/depot marker(s); next sync in "
                        + this.dynmapIntervalMs / 60_000L + " min.");
            }
        } catch (Throwable throwable) {
            Throwable cause = Reflect.unwrap(throwable);
            this.warnOnce("dynmap-sync:" + cause.getClass().getName(), "Dynmap synchronization failed (will retry in 1 min): " + cause);
            this.nextDynmapSync = System.currentTimeMillis() + 60_000L;
        }
    }

    // ------------------------------------------------------------------ web map

    private void startWebMap() {
        if (!this.getConfig().getBoolean("web-map.enabled", true)) {
            this.getLogger().info("Web map is disabled (web-map.enabled: false).");
            return;
        }
        String bind = this.getConfig().getString("web-map.bind-address", "0.0.0.0");
        int port = this.getConfig().getInt("web-map.port", DEFAULT_WEB_PORT);
        int maxViewers = this.getConfig().getInt("web-map.max-viewers", 50);
        double trainSeconds = Math.max(0.25, this.getConfig().getDouble("web-map.train-update-interval-seconds", 1.0));
        long networkSeconds = Math.max(5L, this.getConfig().getLong("web-map.network-check-interval-seconds", 15L));
        this.webTrainIntervalMs = Math.round(trainSeconds * 1000.0);
        this.showDepotVehicles = this.getConfig().getBoolean("web-map.show-vehicles-in-depots", false);

        File webFolder = new File(this.getDataFolder(), "web");
        try {
            this.webMap = new WebMapServer(this.getLogger(), this.getClassLoader(), webFolder.toPath().toAbsolutePath().normalize(),
                    maxViewers, this.webConfigJson());
            this.webMap.start(bind, port);
        } catch (Throwable throwable) {
            if (this.webMap != null) {
                this.webMap.stop();
            }
            this.webMap = null;
            this.getLogger().warning("Web map could not start on " + bind + ":" + port + " — " + throwable
                    + ". Is the port already in use? Change web-map.port in config.yml.");
            return;
        }
        this.webCollector = new WebMapCollector(this);
        this.webThread = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "MTR-Dynmap-Bridge-Web"));
        this.webThread.scheduleWithFixedDelay(this::webNetworkTick, 3L, networkSeconds, TimeUnit.SECONDS);
        this.webThread.scheduleWithFixedDelay(this::webTrainTick, 4000L, this.webTrainIntervalMs, TimeUnit.MILLISECONDS);
        this.getLogger().info("Web map started on port " + port + ": " + this.webMapUrl()
                + " (vehicles every " + this.webTrainIntervalMs + " ms).");
    }

    private void webNetworkTick() {
        if (this.stopping || this.webMap == null) {
            return;
        }
        try {
            if (!this.checkMtr()) {
                return;
            }
            MtrAccess.Result<MtrData.Network> result = this.mtr.networks(NETWORK_TIMEOUT_MS);
            if (result == null) {
                return;
            }
            this.blacklist.reloadIfChanged();
            this.latestNetworks = List.copyOf(result.values());
            this.webMap.publishNetwork(this.webCollector.network(result.values(), this.modes));
            this.webMap.publishPlaces(this.webCollector.places(result.values(), this.modes));
            this.exportLists(result.values());
        } catch (Throwable throwable) {
            Throwable cause = Reflect.unwrap(throwable);
            this.warnOnce("web-network:" + cause.getClass().getName(), "Web map: reading the MTR network failed: " + cause);
        }
    }

    private void webTrainTick() {
        if (this.stopping || this.webMap == null) {
            return;
        }
        try {
            if (!this.mtrSeen && !this.checkMtr()) {
                return;
            }
            MtrAccess.Result<List<MtrData.Vehicle>> result = this.mtr.vehicles(this.showDepotVehicles, VEHICLE_TIMEOUT_MS);
            if (result == null) {
                return;
            }
            List<MtrData.Vehicle> vehicles = new ArrayList<>();
            Map<Long, Integer> perRoute = new HashMap<>();
            for (List<MtrData.Vehicle> list : result.values()) {
                vehicles.addAll(list);
                for (MtrData.Vehicle vehicle : list) {
                    if (vehicle.onRoute()) {
                        perRoute.merge(vehicle.routeId(), 1, Integer::sum);
                    }
                }
            }
            this.vehiclesPerRoute = Map.copyOf(perRoute);
            List<MapData.TrainInfo> trains = this.webCollector.trains(vehicles, this.modes);
            this.webMap.publishTrains(trains, System.currentTimeMillis(), this.webTrainIntervalMs);
        } catch (Throwable throwable) {
            Throwable cause = Reflect.unwrap(throwable);
            this.warnOnce("web-trains:" + cause.getClass().getName(), "Web map: reading MTR vehicles failed: " + cause);
        }
    }

    private String webConfigJson() {
        Json json = new Json(512);
        json.beginObject()
                .field("language", this.lang.code())
                .field("title", this.getConfig().getString("web-map.title", ""))
                .field("defaultDimension", this.getConfig().getString("web-map.default-dimension", "minecraft:overworld"))
                .field("stationLabels", this.getConfig().getBoolean("web-map.show-station-labels", true))
                .field("stationAreas", this.getConfig().getBoolean("web-map.show-station-areas", true))
                .field("depots", this.getConfig().getBoolean("web-map.show-depots", true))
                .field("lineColors", this.getConfig().getBoolean("web-map.color-lines", true))
                .field("vehicleOutline", this.getConfig().getBoolean("web-map.vehicle-outline.enabled", true))
                .field("vehicleOutlineColor", outlineColor(this.getConfig().getString("web-map.vehicle-outline.color", "#f4f6fa")))
                .field("vehicleOutlineOpacity", clamp(this.getConfig().getDouble("web-map.vehicle-outline.opacity", 0.7), 0.0, 1.0))
                .field("vehicleOutlineWidth", clamp(this.getConfig().getDouble("web-map.vehicle-outline.width", 1.5), 0.5, 5.0))
                .field("dynmapUrl", this.getConfig().getString("web-map.dynmap-url", ""))
                .field("mtrMapUrl", this.getConfig().getString("web-map.mtr-map-url", ""))
                .field("version", this.getDescription().getVersion());
        json.key("dimensions").beginObject();
        ConfigurationSection dimensions = this.getConfig().getConfigurationSection("web-map.dimensions");
        if (dimensions != null) {
            for (String key : dimensions.getKeys(false)) {
                json.field(key, dimensions.getString(key, key));
            }
        }
        json.endObject().endObject();
        return json.toString();
    }

    private static double clamp(double value, double min, double max) {
        return Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }

    /** "#RGB" / "#RRGGBB" (the "#" may be omitted); anything else falls back to the default almost-white. */
    private static String outlineColor(String value) {
        String color = value == null ? "" : value.trim();
        if (!color.startsWith("#")) {
            color = "#" + color;
        }
        return color.matches("#[0-9a-fA-F]{3}|#[0-9a-fA-F]{6}") ? color : "#f4f6fa";
    }

    /** Map link for the console: web-map.public-url or http://<server-ip>:<port>/. */
    String webMapUrl() {
        return this.webMapUrl(null);
    }

    /**
     * Map link for a command sender.
     * Order: web-map.public-url → the address the player used to join the server
     * (e.g. "play.example.com") → server-ip from server.properties → "<server-ip>".
     */
    String webMapUrl(org.bukkit.command.CommandSender sender) {
        String url = this.getConfig().getString("web-map.public-url", "");
        if (url != null && !url.isBlank()) {
            return url;
        }
        String host = sender == null ? null : joinHost(sender);
        if (host == null) {
            try {
                host = Bukkit.getIp();
            } catch (Throwable ignored) {
                host = null;
            }
        }
        if (host == null || host.isBlank() || host.equals("0.0.0.0")) {
            host = "<server-ip>";
        }
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]"; // IPv6 literal
        }
        return "http://" + host + ":" + this.getConfig().getInt("web-map.port", DEFAULT_WEB_PORT) + "/";
    }

    /** The host name a player typed to join (Paper's Player#getVirtualHost), or null if unknown. */
    private static String joinHost(org.bukkit.command.CommandSender sender) {
        try {
            Object address = Reflect.optionalCall(sender, "getVirtualHost");
            if (!(address instanceof java.net.InetSocketAddress socket)) {
                return null;
            }
            String host = socket.getHostString();
            if (host == null) {
                return null;
            }
            int marker = host.indexOf('\0'); // modded clients may append "\0FML…\0" to the host
            if (marker >= 0) {
                host = host.substring(0, marker);
            }
            while (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            host = host.trim();
            boolean useless = host.isEmpty() || (host.equalsIgnoreCase("localhost") && !isLocalSender(sender));
            return useless ? null : host;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isLocalSender(org.bukkit.command.CommandSender sender) {
        Object address = Reflect.optionalCall(sender, "getAddress");
        return address instanceof java.net.InetSocketAddress socket && socket.getAddress() != null
                && socket.getAddress().isLoopbackAddress();
    }

    boolean isWebMapRunning() {
        return this.webMap != null;
    }

    int webMapViewers() {
        return this.webMap == null ? 0 : this.webMap.viewers();
    }

    // ------------------------------------------------------------------ data for commands / exports

    /** Latest network snapshot (from the web map or the Dynmap thread); never waits for MTR. */
    List<MtrData.Network> networksForCommands() {
        return this.latestNetworks;
    }

    Map<Long, Integer> vehiclesPerRoute() {
        return this.vehiclesPerRoute;
    }

    /** Writes stations.txt and routes.txt to the plugin folder when the lists change. */
    private void exportLists(List<MtrData.Network> networks) {
        List<String> stations = new ArrayList<>();
        List<String> routes = new ArrayList<>();
        for (MtrData.Network network : networks) {
            for (MtrData.Area station : network.stations()) {
                stations.add(WebMapCollector.displayName(station.name()) + " (" + network.dimension() + ")");
            }
            for (MtrData.Route route : network.routes()) {
                String number = WebMapCollector.displayName(route.number());
                routes.add((number.isEmpty() ? "" : "[" + number + "] ") + WebMapCollector.displayName(route.name())
                        + " (" + route.mode() + ", " + network.dimension() + (route.hidden() ? ", hidden" : "") + ")");
            }
        }
        stations.sort(String.CASE_INSENSITIVE_ORDER);
        routes.sort(String.CASE_INSENSITIVE_ORDER);
        String signature = String.join("\n", stations) + "\n--\n" + String.join("\n", routes);
        if (signature.equals(this.lastExport)) {
            return;
        }
        try {
            Files.write(new File(this.getDataFolder(), "stations.txt").toPath(), stations, StandardCharsets.UTF_8);
            Files.write(new File(this.getDataFolder(), "routes.txt").toPath(), routes, StandardCharsets.UTF_8);
            this.lastExport = signature;
        } catch (Throwable throwable) {
            this.warnOnce("export", "Could not write stations.txt / routes.txt: " + throwable);
        }
    }

    // ------------------------------------------------------------------ shared helpers

    Lang lang() {
        return this.lang;
    }

    boolean isMtrAvailable() {
        return this.mtr != null && this.mtr.isAvailable();
    }

    boolean isStationBlacklisted(String... namesOrIds) {
        return this.blacklist != null && this.blacklist.isStationBlacklisted(namesOrIds);
    }

    boolean isRouteBlacklisted(String... namesOrNumbers) {
        return this.blacklist != null && this.blacklist.isRouteBlacklisted(namesOrNumbers);
    }

    void warnOnce(String key, String message) {
        if (this.warned.add(key)) {
            this.getLogger().warning(message);
        }
    }
}

/** /mtrmap — shows a clickable link to the live web map. */
final class MapCommand implements org.bukkit.command.CommandExecutor {
    private final MTRDynmapBridge plugin;

    MapCommand(MTRDynmapBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        Lang lang = this.plugin.lang();
        if (!this.plugin.isWebMapRunning()) {
            sender.sendMessage(lang.get("map-disabled"));
            return true;
        }
        String url = this.plugin.webMapUrl(sender);
        if (!(sender instanceof org.bukkit.entity.Player player) || !sendClickable(player, lang, url)) {
            sender.sendMessage(lang.get("map-link", url));
        }
        if (sender.hasPermission("mtrdynmap.admin")) {
            sender.sendMessage(lang.get("map-viewers", this.plugin.webMapViewers()));
        }
        return true;
    }

    /**
     * Sends the link as a clickable chat message through the vanilla /tellraw command.
     * This works on every server type (Paper, Spigot, hybrids) without extra APIs.
     */
    private static boolean sendClickable(org.bukkit.entity.Player player, Lang lang, String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://") || url.contains("<")) {
            return false; // not a real link (e.g. "<server-ip>"), plain text is clearer
        }
        try {
            Json json = new Json(256);
            json.beginArray();
            json.value("");
            json.beginObject().field("text", lang.get("map-link-prefix")).field("color", "gold").endObject();
            json.beginObject()
                    .field("text", url)
                    .field("color", "aqua")
                    .field("underlined", true);
            json.key("clickEvent").beginObject().field("action", "open_url").field("value", url).endObject();
            json.key("hoverEvent").beginObject().field("action", "show_text").field("contents", lang.get("map-link-hover")).endObject();
            json.endObject();
            json.endArray();
            // The player name, not the UUID: vanilla treats a UUID as an entity selector, which /tellraw rejects.
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:tellraw " + player.getName() + " " + json);
        } catch (Throwable ignored) {
            return false;
        }
    }
}

/** /mtrstations [search] — lists MTR stations. */
final class StationsCommand implements org.bukkit.command.CommandExecutor {
    private final MTRDynmapBridge plugin;

    StationsCommand(MTRDynmapBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        Lang lang = this.plugin.lang();
        if (!sender.hasPermission("mtrdynmap.admin")) {
            sender.sendMessage(lang.get("no-permission"));
            return true;
        }
        List<MtrData.Network> networks = this.plugin.networksForCommands();
        if (networks.isEmpty()) {
            sender.sendMessage(lang.get("mtr-not-ready"));
            return true;
        }
        String query = String.join(" ", args).trim().toLowerCase(Locale.ROOT);
        List<String> lines = new ArrayList<>();
        for (MtrData.Network network : networks) {
            for (MtrData.Area station : network.stations()) {
                String name = WebMapCollector.displayName(station.name());
                if (name.isEmpty()) {
                    name = lang.get("unnamed-station");
                }
                if (query.isEmpty() || name.toLowerCase(Locale.ROOT).contains(query)) {
                    lines.add("- " + name + " §7(" + network.dimension() + ", " + station.centerX() + " " + station.centerZ() + ")");
                }
            }
        }
        lines.sort(String.CASE_INSENSITIVE_ORDER);
        sender.sendMessage(lang.get("stations-header", lines.size()));
        if (lines.isEmpty()) {
            sender.sendMessage(lang.get("list-empty"));
        }
        for (String line : lines) {
            sender.sendMessage(line);
        }
        return true;
    }
}

/** /mtrroutes [search] — lists MTR routes and how many vehicles are on each. */
final class RoutesCommand implements org.bukkit.command.CommandExecutor {
    private final MTRDynmapBridge plugin;

    RoutesCommand(MTRDynmapBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        Lang lang = this.plugin.lang();
        if (!sender.hasPermission("mtrdynmap.admin")) {
            sender.sendMessage(lang.get("no-permission"));
            return true;
        }
        List<MtrData.Network> networks = this.plugin.networksForCommands();
        if (networks.isEmpty()) {
            sender.sendMessage(lang.get("mtr-not-ready"));
            return true;
        }
        String query = String.join(" ", args).trim().toLowerCase(Locale.ROOT);
        Map<Long, Integer> vehicles = this.plugin.vehiclesPerRoute();
        List<String> lines = new ArrayList<>();
        for (MtrData.Network network : networks) {
            for (MtrData.Route route : network.routes()) {
                String name = WebMapCollector.displayName(route.name());
                if (name.isEmpty()) {
                    name = lang.get("unnamed-route");
                }
                String number = WebMapCollector.displayName(route.number());
                String text = (number.isEmpty() ? "" : "[" + number + "] ") + name;
                if (!query.isEmpty() && !text.toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
                StringBuilder line = new StringBuilder("- ").append(text).append(" §7(").append(route.mode())
                        .append(", ").append(network.dimension()).append(", ")
                        .append(lang.get("route-vehicles", vehicles.getOrDefault(route.id(), 0)));
                if (route.hidden()) {
                    line.append(", ").append(lang.get("route-hidden"));
                }
                lines.add(line.append(")").toString());
            }
        }
        lines.sort(String.CASE_INSENSITIVE_ORDER);
        sender.sendMessage(lang.get("routes-header", lines.size()));
        if (lines.isEmpty()) {
            sender.sendMessage(lang.get("list-empty"));
        }
        for (String line : lines) {
            sender.sendMessage(line);
        }
        return true;
    }
}
