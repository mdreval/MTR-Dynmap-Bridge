package com.igrobar.mtrdynmap;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Reads the MTR 4 simulation core (Transport Simulation Core) through reflection.
 *
 * <p>MTR runs one {@code Simulator} per dimension on its own thread. Reads are queued with
 * {@code Simulator.run(Runnable)}, the same way MTR's own web server does it, so they run between two
 * simulation ticks and only copy plain values into {@link MtrData}. Everything heavier happens on the
 * plugin's threads afterwards.</p>
 *
 * <p>Written against MTR 4.1.0-beta.2, with fallbacks for other 4.x builds.</p>
 */
final class MtrAccess {
    /** How many times in a row the previous result of a dimension may be reused when MTR does not answer in time. */
    private static final int MAX_STALE_REUSES = 3;
    private static final String[] MAIN_HOLDERS = {"org.mtr.MTR", "org.mtr.mod.Init", "org.mtr.mod.MTR", "org.mtr.core.Main"};

    /** Result of one request to all dimensions. {@code complete} is false if some dimension did not answer. */
    record Result<T>(List<T> values, boolean complete) {
    }

    private record Cached<T>(T value, int reuses) {
    }

    private final MTRDynmapBridge plugin;
    private final Map<String, Map<String, Cached<?>>> caches = new ConcurrentHashMap<>();
    private volatile Object main;

    MtrAccess(MTRDynmapBridge plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ MTR core lookup

    /** Whether the MTR core is loaded and running. */
    boolean isAvailable() {
        return this.main() != null;
    }

    /** The running {@code org.mtr.core.Main} (the object that owns the simulators), or null. */
    Object main() {
        Object current = this.main;
        if (current != null && simulatorsOf(current) != null) {
            return current;
        }
        current = findMain();
        this.main = current;
        return current;
    }

    private static Object findMain() {
        for (String className : MAIN_HOLDERS) {
            try {
                Class<?> type = Class.forName(className);
                // Conventional static fields first (main, instance...), then any static field.
                for (String fieldName : new String[]{"main", "instance", "MAIN", "INSTANCE"}) {
                    try {
                        Field field = type.getDeclaredField(fieldName);
                        field.setAccessible(true);
                        Object value = field.get(null);
                        if (simulatorsOf(value) != null) {
                            return value;
                        }
                    } catch (Throwable ignored) {
                        // try the next name
                    }
                }
                for (Field field : type.getDeclaredFields()) {
                    try {
                        if (!Modifier.isStatic(field.getModifiers())) {
                            continue;
                        }
                        field.setAccessible(true);
                        Object value = field.get(null);
                        if (simulatorsOf(value) != null) {
                            return value;
                        }
                    } catch (Throwable ignored) {
                        // try the next field
                    }
                }
            } catch (Throwable ignored) {
                // class not present in this MTR build
            }
        }
        return null;
    }

    /** The simulators of an MTR core Main, or null if the object is not one. */
    private static Iterable<?> simulatorsOf(Object main) {
        if (main == null) {
            return null;
        }
        try {
            Field field = main.getClass().getDeclaredField("simulators");
            field.setAccessible(true);
            Object value = field.get(main);
            return value instanceof Iterable<?> iterable ? iterable : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** MTR dimension id as stored by MTR, e.g. "minecraft/overworld". */
    static String rawDimension(Object simulator) {
        try {
            Object value = Reflect.fieldValue(simulator, "dimension");
            return value == null ? "minecraft/overworld" : value.toString();
        } catch (Throwable ignored) {
            return "minecraft/overworld";
        }
    }

    /** "minecraft/overworld" → "minecraft:overworld" (the form used by Minecraft and by the website). */
    static String dimensionId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "minecraft:overworld";
        }
        String value = raw.replace('\\', '/');
        if (value.contains(":")) {
            return value;
        }
        int slash = value.indexOf('/');
        return slash > 0 ? value.substring(0, slash) + ":" + value.substring(slash + 1) : "minecraft:" + value;
    }

    // ------------------------------------------------------------------ requests

    /** Stations, depots, routes and rails of every dimension. */
    Result<MtrData.Network> networks(long timeoutMs) throws Exception {
        return this.runOnSimulators("network", simulator -> readNetwork(simulator), timeoutMs);
    }

    /** All vehicles of every dimension. Vehicles waiting in depots are skipped unless {@code includeDepots}. */
    Result<List<MtrData.Vehicle>> vehicles(boolean includeDepots, long timeoutMs) throws Exception {
        return this.runOnSimulators("vehicles", simulator -> this.readVehicles(simulator, includeDepots), timeoutMs);
    }

    @FunctionalInterface
    private interface SimulatorTask<T> {
        T apply(Object simulator) throws Exception;
    }

    /**
     * Runs {@code task} on the simulation thread of every dimension and waits for the answers.
     * A dimension that does not answer within the timeout reuses its previous answer (a few times at most).
     * Returns null if MTR is not running.
     */
    @SuppressWarnings("unchecked")
    private <T> Result<T> runOnSimulators(String cacheKey, SimulatorTask<T> task, long timeoutMs) throws Exception {
        Object core = this.main();
        Iterable<?> simulators = simulatorsOf(core);
        if (simulators == null) {
            return null;
        }
        Map<String, CompletableFuture<T>> futures = new LinkedHashMap<>();
        for (Object simulator : snapshot(simulators)) {
            CompletableFuture<T> future = new CompletableFuture<>();
            Runnable job = () -> {
                if (future.isDone()) {
                    return; // the plugin already gave up waiting (timeout): do no work at all
                }
                // Never let anything escape into MTR's tick.
                try {
                    future.complete(task.apply(simulator));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            };
            Reflect.call(simulator, "run", job);
            futures.put(rawDimension(simulator), future);
        }

        Map<String, Cached<?>> cache = this.caches.computeIfAbsent(cacheKey, key -> new ConcurrentHashMap<>());
        cache.keySet().retainAll(futures.keySet());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(100L, timeoutMs));
        List<T> values = new ArrayList<>(futures.size());
        boolean complete = true;
        for (Map.Entry<String, CompletableFuture<T>> entry : futures.entrySet()) {
            CompletableFuture<T> future = entry.getValue();
            try {
                long left = Math.max(1L, deadline - System.nanoTime());
                T value = future.get(left, TimeUnit.NANOSECONDS);
                cache.put(entry.getKey(), new Cached<>(value, 0));
                values.add(value);
            } catch (TimeoutException timeout) {
                future.cancel(false);
                complete = false;
                Cached<T> previous = (Cached<T>) cache.get(entry.getKey());
                if (previous != null && previous.reuses() < MAX_STALE_REUSES) {
                    cache.put(entry.getKey(), new Cached<>(previous.value(), previous.reuses() + 1));
                    values.add(previous.value());
                }
                this.plugin.warnOnce("mtr-timeout:" + cacheKey + ":" + entry.getKey(),
                        "MTR dimension " + entry.getKey() + " did not answer within " + timeoutMs
                                + " ms (is the MTR simulation busy?). The previous data is kept for now.");
            } catch (ExecutionException failed) {
                complete = false;
                Throwable cause = Reflect.unwrap(failed.getCause() == null ? failed : failed.getCause());
                this.plugin.warnOnce("mtr-read:" + cacheKey + ":" + cause.getClass().getName(),
                        "Could not read MTR " + cacheKey + " data in " + entry.getKey() + ": " + cause);
            }
        }
        return new Result<>(values, complete);
    }

    // ------------------------------------------------------------------ reading (runs on the MTR simulation thread)

    private static MtrData.Network readNetwork(Object simulator) throws Exception {
        String raw = rawDimension(simulator);

        // Routes
        List<MtrData.Route> routes = new ArrayList<>();
        for (Object route : snapshot(Reflect.fieldValue(simulator, "routes"))) {
            List<Object> stops = routeStops(route);
            routes.add(new MtrData.Route(
                    longValue(Reflect.call(route, "getId")),
                    text(Reflect.optionalCall(route, "getName")),
                    text(Reflect.optionalCall(route, "getRouteNumber")),
                    intValue(Reflect.optionalCall(route, "getColor")),
                    mode(Reflect.optionalCall(route, "getTransportMode")),
                    Boolean.TRUE.equals(Reflect.optionalCall(route, "getHidden")),
                    stops.isEmpty() ? "" : stopName(stops.get(0)),
                    stops.isEmpty() ? "" : stopName(stops.get(stops.size() - 1)),
                    circular(Reflect.optionalCall(route, "getCircularState"))));
        }

        // Routes serving every station (through its platforms)
        Map<Long, Set<Long>> stationRoutes = new HashMap<>();
        for (Object platform : snapshot(Reflect.fieldValue(simulator, "platforms"))) {
            Object station = Reflect.fieldValue(platform, "area");
            if (station == null) {
                continue;
            }
            long stationId = longValue(Reflect.call(station, "getId"));
            for (Object route : snapshot(Reflect.fieldValue(platform, "routes"))) {
                stationRoutes.computeIfAbsent(stationId, key -> new LinkedHashSet<>()).add(longValue(Reflect.call(route, "getId")));
            }
        }

        List<MtrData.Area> stations = new ArrayList<>();
        for (Object station : snapshot(Reflect.fieldValue(simulator, "stations"))) {
            MtrData.Area area = readArea(station, stationRoutes);
            if (area != null) {
                stations.add(area);
            }
        }

        // Depots, and which rails each route uses (from the depot paths), to colour the lines
        Map<Object, Set<Long>> railRoutes = new IdentityHashMap<>();
        List<MtrData.Area> depots = new ArrayList<>();
        for (Object depot : snapshot(Reflect.fieldValue(simulator, "depots"))) {
            List<Long> depotRouteIds = new ArrayList<>();
            for (Object route : snapshot(Reflect.fieldValue(depot, "routes"))) {
                depotRouteIds.add(longValue(Reflect.call(route, "getId")));
            }
            MtrData.Area area = readArea(depot, Map.of(longValue(Reflect.call(depot, "getId")), new LinkedHashSet<>(depotRouteIds)));
            if (area != null) {
                depots.add(area);
            }
            if (!depotRouteIds.isEmpty()) {
                collectDepotPath(depot, depotRouteIds, railRoutes);
            }
        }

        // Rails: only references here (cheap); their kind and shape are read later off the MTR thread
        List<MtrData.RailRef> rails = new ArrayList<>();
        long signature = 0;
        long[] noRoutes = new long[0];
        for (Object rail : snapshot(Reflect.fieldValue(simulator, "rails"))) {
            Set<Long> ids = railRoutes.get(rail);
            long[] routeIds = ids == null ? noRoutes : ids.stream().mapToLong(Long::longValue).toArray();
            rails.add(new MtrData.RailRef(rail, routeIds));
            signature += mix(System.identityHashCode(rail));
        }
        signature = signature * 31 + rails.size();

        return new MtrData.Network(raw, dimensionId(raw), List.copyOf(stations), List.copyOf(depots),
                List.copyOf(routes), List.copyOf(rails), signature);
    }

    /** The stops of a route in order (RoutePlatformData objects). */
    private static List<Object> routeStops(Object route) {
        Object stops = Reflect.optionalCall(route, "getRoutePlatforms");
        if (stops == null) {
            try {
                stops = Reflect.fieldValue(route, "routePlatformData");
            } catch (Throwable ignored) {
                return List.of();
            }
        }
        return snapshot(stops);
    }

    /** Name of the station of one route stop, or empty if the platform is not inside a station. */
    private static String stopName(Object stop) {
        try {
            Object platform = Reflect.fieldValue(stop, "platform");
            if (platform == null) {
                platform = Reflect.optionalCall(stop, "getPlatform");
            }
            Object station = Reflect.fieldValue(platform, "area");
            return text(Reflect.optionalCall(station, "getName"));
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** MTR CircularState → "clockwise", "anticlockwise" or empty (not circular). */
    private static String circular(Object state) {
        String value = state == null ? "" : state.toString().toLowerCase(Locale.ROOT);
        return value.equals("clockwise") || value.equals("anticlockwise") ? value : "";
    }

    private static void collectDepotPath(Object depot, List<Long> depotRouteIds, Map<Object, Set<Long>> railRoutes) {
        Object path = Reflect.optionalCall(depot, "getPath");
        if (!(path instanceof Iterable<?> pathData)) {
            return;
        }
        boolean singleRoute = depotRouteIds.size() == 1;
        Map<Integer, Long> routeByStop = new HashMap<>();
        for (Object segment : snapshot(pathData)) {
            Object rail = Reflect.optionalCall(segment, "getRail");
            if (rail == null) {
                continue;
            }
            Set<Long> ids = railRoutes.computeIfAbsent(rail, key -> new LinkedHashSet<>());
            if (singleRoute) {
                ids.add(depotRouteIds.get(0));
                continue;
            }
            // Several routes in one depot: find the route of this part of the path by its stop index.
            Object stopIndex = Reflect.optionalCall(segment, "getStopIndex");
            Long routeId = null;
            if (stopIndex instanceof Number number) {
                routeId = routeByStop.computeIfAbsent(number.intValue(), index -> routeAtStop(depot, index));
            }
            if (routeId != null && routeId != 0L) {
                ids.add(routeId);
            } else {
                ids.addAll(depotRouteIds);
            }
        }
    }

    private static Long routeAtStop(Object depot, int stopIndex) {
        try {
            Object info = Reflect.call(depot, "getVehiclePlatformRouteInfo", stopIndex);
            Object route = Reflect.fieldValue(info, "thisRoute");
            return route == null ? 0L : longValue(Reflect.call(route, "getId"));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static MtrData.Area readArea(Object area, Map<Long, Set<Long>> routesById) throws Exception {
        long id = longValue(Reflect.call(area, "getId"));
        Object center = Reflect.optionalCall(area, "getCenter");
        if (center == null) {
            return null;
        }
        Set<Long> routeIds = routesById.get(id);
        return new MtrData.Area(
                id,
                text(Reflect.optionalCall(area, "getHexId")),
                text(Reflect.optionalCall(area, "getName")),
                intValue(Reflect.optionalCall(area, "getColor")),
                mode(Reflect.optionalCall(area, "getTransportMode")),
                longValue(Reflect.optionalCall(area, "getMinX")),
                longValue(Reflect.optionalCall(area, "getMinZ")),
                longValue(Reflect.optionalCall(area, "getMaxX")),
                longValue(Reflect.optionalCall(area, "getMaxZ")),
                longValue(Reflect.call(center, "getX")),
                longValue(Reflect.call(center, "getY")),
                longValue(Reflect.call(center, "getZ")),
                routeIds == null ? List.of() : List.copyOf(routeIds));
    }

    private List<MtrData.Vehicle> readVehicles(Object simulator, boolean includeDepots) throws Exception {
        String dimension = dimensionId(rawDimension(simulator));
        List<MtrData.Vehicle> result = new ArrayList<>();
        Throwable lastError = null;
        for (Object siding : snapshot(Reflect.fieldValue(simulator, "sidings"))) {
            for (Object vehicle : vehiclesOf(siding)) {
                try {
                    MtrData.Vehicle info = readVehicle(vehicle, dimension, includeDepots);
                    if (info != null) {
                        result.add(info);
                    }
                } catch (Throwable throwable) {
                    lastError = Reflect.unwrap(throwable);
                }
            }
        }
        if (lastError != null) {
            this.plugin.warnOnce("mtr-vehicle:" + lastError.getClass().getName(), "Could not read an MTR vehicle: " + lastError);
        }
        return result;
    }

    /** Vehicles of a siding: iterateVehicles(Consumer) in MTR 4.1, with fallbacks for other 4.x builds. */
    private static List<Object> vehiclesOf(Object siding) throws Exception {
        List<Object> vehicles = new ArrayList<>();
        Consumer<Object> collector = vehicles::add;
        if (Reflect.method(siding.getClass(), "iterateVehicles", new Object[]{collector}) != null) {
            Reflect.call(siding, "iterateVehicles", collector);
            return vehicles;
        }
        Object map = Reflect.fieldValue(siding, "vehicleIdMap");
        if (map instanceof Map<?, ?> byId) {
            vehicles.addAll(byId.values());
            return vehicles;
        }
        vehicles.addAll(snapshot(Reflect.fieldValue(siding, "vehicles")));
        return vehicles;
    }

    private static MtrData.Vehicle readVehicle(Object vehicle, String dimension, boolean includeDepots) throws Exception {
        boolean onRoute = !Boolean.FALSE.equals(Reflect.optionalCall(vehicle, "getIsOnRoute"));
        if (!onRoute && !includeDepots) {
            return null;
        }
        Object speedValue = Reflect.fieldValue(vehicle, "speed"); // metres per millisecond
        double speed = speedValue instanceof Number number ? Math.abs(number.doubleValue()) : 0.0;
        Object movingValue = Reflect.optionalCall(vehicle, "isMoving");
        boolean moving = movingValue instanceof Boolean flag ? flag : speed > 0.0;

        double[] head = point(Reflect.optionalCall(vehicle, "getHeadPositionAndTiltAngle"));
        List<double[]> cars = cars(vehicle, head);
        if (cars.isEmpty()) {
            if (head == null) {
                return null;
            }
            cars = List.of(new double[]{head[0], head[1], head[2], head[0], head[1], head[2]});
        }

        Object extra = Reflect.fieldValue(vehicle, "vehicleExtraData");
        return new MtrData.Vehicle(
                dimension,
                longValue(Reflect.call(vehicle, "getId")),
                mode(Reflect.optionalCall(vehicle, "getTransportMode")),
                onRoute,
                moving,
                Boolean.TRUE.equals(Reflect.optionalCall(extra, "getIsCurrentlyManual")),
                speed * 3600.0, // m/ms → km/h
                longValue(Reflect.optionalCall(extra, "getThisRouteId")),
                text(Reflect.optionalCall(extra, "getThisRouteName")),
                text(Reflect.optionalCall(extra, "getThisRouteNumber")),
                intValue(Reflect.optionalCall(extra, "getThisRouteColor")),
                text(Reflect.optionalCall(extra, "getThisRouteDestination")),
                longValue(Reflect.optionalCall(extra, "getThisStationId")),
                text(Reflect.optionalCall(extra, "getThisStationName")),
                longValue(Reflect.optionalCall(extra, "getNextStationId")),
                text(Reflect.optionalCall(extra, "getNextStationName")),
                Boolean.TRUE.equals(Reflect.optionalCall(extra, "getIsTerminating")),
                cars);
    }

    /** Cars as {lead xyz, trail xyz}, ordered from the front of the vehicle. */
    private static List<double[]> cars(Object vehicle, double[] head) {
        Object list = Reflect.optionalCall(vehicle, "getVehicleCarsAndPositions");
        if (!(list instanceof Iterable<?> pairs)) {
            return List.of();
        }
        List<double[]> cars = new ArrayList<>();
        for (Object pair : pairs) {
            Object bogies = Reflect.optionalCall(pair, "right");
            if (bogies == null) {
                bogies = Reflect.optionalCall(pair, "second");
            }
            List<Object> positions = snapshot(bogies);
            if (positions.isEmpty()) {
                continue;
            }
            double[] lead = point(Reflect.optionalCall(positions.get(0), "positionAndTiltAngle1"));
            double[] trail = point(Reflect.optionalCall(positions.get(positions.size() - 1), "positionAndTiltAngle2"));
            if (lead == null && trail == null) {
                continue;
            }
            if (lead == null) {
                lead = trail;
            }
            if (trail == null) {
                trail = lead;
            }
            cars.add(new double[]{lead[0], lead[1], lead[2], trail[0], trail[1], trail[2]});
        }
        if (head == null || cars.isEmpty()) {
            return cars;
        }
        // MTR falls back to a dummy point when a bogie is off its path: drop cars that are nowhere near the vehicle.
        double maxSpan = 64.0 + cars.size() * 48.0;
        cars.removeIf(car -> distance(head, car[0], car[2]) > maxSpan && distance(head, car[3], car[5]) > maxSpan);
        if (cars.isEmpty()) {
            return cars;
        }
        // Put the car nearest to the head of the vehicle first and orient every car front → back.
        double[] first = cars.get(0);
        double[] last = cars.get(cars.size() - 1);
        double firstDistance = Math.min(distance(head, first[0], first[2]), distance(head, first[3], first[5]));
        double lastDistance = Math.min(distance(head, last[0], last[2]), distance(head, last[3], last[5]));
        if (lastDistance < firstDistance) {
            Collections.reverse(cars);
        }
        double[] front = cars.get(0);
        if (distance(head, front[3], front[5]) < distance(head, front[0], front[2])) {
            for (double[] car : cars) {
                for (int i = 0; i < 3; i++) {
                    double swap = car[i];
                    car[i] = car[i + 3];
                    car[i + 3] = swap;
                }
            }
        }
        return cars;
    }

    // ------------------------------------------------------------------ helpers

    /** x, y, z of a PositionAndTiltAngle record (its Vector), or null. */
    private static double[] point(Object positionAndTilt) {
        if (positionAndTilt == null) {
            return null;
        }
        Object vector = Reflect.optionalCall(positionAndTilt, "position");
        if (vector == null) {
            return null;
        }
        Object x = Reflect.optionalCall(vector, "x");
        Object y = Reflect.optionalCall(vector, "y");
        Object z = Reflect.optionalCall(vector, "z");
        if (!(x instanceof Number nx) || !(y instanceof Number ny) || !(z instanceof Number nz)) {
            return null;
        }
        return new double[]{nx.doubleValue(), ny.doubleValue(), nz.doubleValue()};
    }

    private static double distance(double[] a, double x, double z) {
        return Math.hypot(a[0] - x, a[2] - z);
    }

    /** A copy of an MTR collection (MTR collections must not be iterated after the task returns). */
    static List<Object> snapshot(Object collection) {
        if (collection instanceof Collection<?> values) {
            return new ArrayList<>(values);
        }
        if (collection instanceof Iterable<?> iterable) {
            List<Object> list = new ArrayList<>();
            for (Object value : iterable) {
                list.add(value);
            }
            return list;
        }
        return List.of();
    }

    static String mode(Object transportMode) {
        return transportMode == null ? "train" : transportMode.toString().toLowerCase(Locale.ROOT);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static long mix(long value) {
        long h = value * 0x9E3779B97F4A7C15L;
        return h ^ (h >>> 29);
    }
}
