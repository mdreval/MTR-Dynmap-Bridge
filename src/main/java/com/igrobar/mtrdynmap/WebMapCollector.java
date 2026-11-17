package com.igrobar.mtrdynmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.igrobar.mtrdynmap.web.MapData;

/**
 * Turns MTR snapshots ({@link MtrData}) into website data ({@link MapData}) on the web map thread.
 * Rail shapes are sampled from MTR's own rail maths and simplified; MTR rails are immutable, so each
 * shape is cached until the rail is rebuilt or removed.
 */
final class WebMapCollector {
    /** Sampling step along a rail, in blocks. */
    private static final double SAMPLE_STEP = 2.0;
    private static final int MAX_SAMPLES = 800;
    /** Points closer than this to the simplified line are dropped, in blocks. */
    private static final double SIMPLIFY_TOLERANCE = 0.3;

    /** Kind ("train", "train-platform", "boat-siding"...) and simplified x/z points of a rail. */
    private record Shape(String type, double[] points) {
    }

    private static final Shape EMPTY = new Shape("train", new double[0]);

    private final MTRDynmapBridge plugin;
    private Map<Object, Shape> shapes = new IdentityHashMap<>();

    WebMapCollector(MTRDynmapBridge plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ names

    /**
     * MTR names may hold several languages separated by "|" (e.g. "Central|中環"), and text after "||"
     * is extra information. The map shows "Central / 中環".
     */
    static String displayName(String name) {
        if (name == null) {
            return "";
        }
        String value = name;
        int extra = value.indexOf("||");
        if (extra >= 0) {
            value = value.substring(0, extra);
        }
        StringBuilder result = new StringBuilder();
        for (String part : value.split("\\|")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                if (!result.isEmpty()) {
                    result.append(" / ");
                }
                result.append(trimmed);
            }
        }
        return result.toString();
    }

    static String color(int rgb) {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }

    private static String mode(String type) {
        int dash = type.indexOf('-');
        return dash < 0 ? type : type.substring(0, dash);
    }

    // ------------------------------------------------------------------ network (tracks and routes)

    MapData.Network network(List<MtrData.Network> networks, Set<String> modes) {
        List<MapData.TrackPiece> tracks = new ArrayList<>();
        List<MapData.RouteInfo> routes = new ArrayList<>();
        Map<Object, Shape> nextShapes = new IdentityHashMap<>();
        Throwable lastError = null;

        for (MtrData.Network network : networks) {
            Set<Long> visibleRoutes = new HashSet<>();
            for (MtrData.Route route : network.routes()) {
                if (route.hidden() || !modes.contains(route.mode()) || this.routeBlacklisted(route.name(), route.number())) {
                    continue;
                }
                visibleRoutes.add(route.id());
                String name = displayName(route.name());
                routes.add(new MapData.RouteInfo(network.dimension(), Long.toString(route.id()),
                        name.isEmpty() ? this.plugin.lang().get("unnamed-route") : name,
                        displayName(route.number()), color(route.color()), route.mode(),
                        displayName(route.from()), displayName(route.to()), route.circular()));
            }
            for (MtrData.RailRef rail : network.rails()) {
                Shape shape = this.shapes.get(rail.rail());
                if (shape == null) {
                    try {
                        shape = new Shape(railType(rail.rail()), sample(rail.rail()));
                    } catch (Throwable throwable) {
                        lastError = Reflect.unwrap(throwable);
                        shape = EMPTY;
                    }
                }
                nextShapes.put(rail.rail(), shape);
                double[] points = shape.points();
                if (points.length < 4 || !modes.contains(mode(shape.type()))) {
                    continue;
                }
                List<String> ids = new ArrayList<>(rail.routeIds().length);
                for (long id : rail.routeIds()) {
                    if (visibleRoutes.contains(id)) {
                        ids.add(Long.toString(id));
                    }
                }
                tracks.add(new MapData.TrackPiece(network.dimension(), shape.type(), ids.toArray(String[]::new), points));
            }
        }
        this.shapes = nextShapes;
        if (lastError != null) {
            this.plugin.warnOnce("web-rail:" + lastError.getClass().getName(), "Web map: could not read the shape of an MTR rail: " + lastError);
        }
        routes.sort(Comparator.comparing(MapData.RouteInfo::name, String.CASE_INSENSITIVE_ORDER));
        return new MapData.Network(List.copyOf(tracks), List.copyOf(routes));
    }

    /** "train", "train-platform", "boat-siding"... */
    private static String railType(Object rail) {
        String type = MtrAccess.mode(Reflect.optionalCall(rail, "getTransportMode"));
        if (Boolean.TRUE.equals(Reflect.optionalCall(rail, "isPlatform"))) {
            return type + "-platform";
        }
        if (Boolean.TRUE.equals(Reflect.optionalCall(rail, "isSiding"))) {
            return type + "-siding";
        }
        return type;
    }

    /** Points along the rail as x/z pairs, simplified. */
    private static double[] sample(Object rail) throws Exception {
        Object math = Reflect.fieldValue(rail, "railMath");
        Object lengthValue = math == null ? null : Reflect.call(math, "getLength");
        double length = lengthValue instanceof Number number ? number.doubleValue() : 0.0;
        if (!(length > 0.0) || Double.isInfinite(length)) {
            return endpoints(rail);
        }
        int steps = (int) Math.max(1, Math.min(MAX_SAMPLES, Math.ceil(length / SAMPLE_STEP)));
        double[] xz = new double[(steps + 1) * 2];
        for (int i = 0; i <= steps; i++) {
            Object vector = Reflect.call(math, "getPosition", length * i / steps, Boolean.FALSE);
            xz[i * 2] = ((Number) Reflect.call(vector, "x")).doubleValue();
            xz[i * 2 + 1] = ((Number) Reflect.call(vector, "z")).doubleValue();
        }
        return simplify(xz, SIMPLIFY_TOLERANCE);
    }

    /** Fallback: a straight line between the two rail ends. */
    private static double[] endpoints(Object rail) throws Exception {
        Object a = Reflect.fieldValue(rail, "position1");
        Object b = Reflect.fieldValue(rail, "position2");
        if (a == null || b == null) {
            return new double[0];
        }
        return new double[]{
            ((Number) Reflect.call(a, "getX")).doubleValue() + 0.5, ((Number) Reflect.call(a, "getZ")).doubleValue() + 0.5,
            ((Number) Reflect.call(b, "getX")).doubleValue() + 0.5, ((Number) Reflect.call(b, "getZ")).doubleValue() + 0.5};
    }

    /** Ramer–Douglas–Peucker simplification of an x/z polyline. */
    static double[] simplify(double[] xz, double tolerance) {
        int count = xz.length / 2;
        if (count <= 2) {
            return xz;
        }
        boolean[] keep = new boolean[count];
        keep[0] = true;
        keep[count - 1] = true;
        int[] stack = new int[count * 2];
        int top = 0;
        stack[top++] = 0;
        stack[top++] = count - 1;
        while (top > 0) {
            int last = stack[--top];
            int first = stack[--top];
            double maxDistance = 0.0;
            int index = -1;
            for (int i = first + 1; i < last; i++) {
                double distance = segmentDistance(xz, i, first, last);
                if (distance > maxDistance) {
                    maxDistance = distance;
                    index = i;
                }
            }
            if (index >= 0 && maxDistance > tolerance) {
                keep[index] = true;
                stack[top++] = first;
                stack[top++] = index;
                stack[top++] = index;
                stack[top++] = last;
            }
        }
        int kept = 0;
        for (boolean k : keep) {
            if (k) {
                kept++;
            }
        }
        double[] result = new double[kept * 2];
        int j = 0;
        for (int i = 0; i < count; i++) {
            if (keep[i]) {
                result[j++] = xz[i * 2];
                result[j++] = xz[i * 2 + 1];
            }
        }
        return result;
    }

    private static double segmentDistance(double[] xz, int point, int start, int end) {
        double px = xz[point * 2], pz = xz[point * 2 + 1];
        double ax = xz[start * 2], az = xz[start * 2 + 1];
        double bx = xz[end * 2], bz = xz[end * 2 + 1];
        double dx = bx - ax, dz = bz - az;
        double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared == 0.0) {
            return Math.hypot(px - ax, pz - az);
        }
        double t = Math.max(0.0, Math.min(1.0, ((px - ax) * dx + (pz - az) * dz) / lengthSquared));
        return Math.hypot(px - (ax + t * dx), pz - (az + t * dz));
    }

    // ------------------------------------------------------------------ stations and depots

    MapData.Places places(List<MtrData.Network> networks, Set<String> modes) {
        List<MapData.AreaInfo> stations = new ArrayList<>();
        List<MapData.AreaInfo> depots = new ArrayList<>();
        for (MtrData.Network network : networks) {
            Set<Long> visibleRoutes = new HashSet<>();
            for (MtrData.Route route : network.routes()) {
                if (!route.hidden() && modes.contains(route.mode()) && !this.routeBlacklisted(route.name(), route.number())) {
                    visibleRoutes.add(route.id());
                }
            }
            for (MtrData.Area station : network.stations()) {
                MapData.AreaInfo info = this.area(network, station, visibleRoutes, modes);
                if (info != null) {
                    stations.add(info);
                }
            }
            for (MtrData.Area depot : network.depots()) {
                MapData.AreaInfo info = this.area(network, depot, visibleRoutes, modes);
                if (info != null) {
                    depots.add(info);
                }
            }
        }
        return new MapData.Places(List.copyOf(stations), List.copyOf(depots));
    }

    private MapData.AreaInfo area(MtrData.Network network, MtrData.Area area, Set<Long> visibleRoutes, Set<String> modes) {
        if (!modes.contains(area.mode())) {
            return null;
        }
        String name = displayName(area.name());
        if (this.plugin.isStationBlacklisted(area.name(), name)) {
            return null;
        }
        List<String> routes = new ArrayList<>();
        for (long id : area.routeIds()) {
            if (visibleRoutes.contains(id)) {
                routes.add(Long.toString(id));
            }
        }
        double[] bounds = {area.minX(), area.minZ(), area.maxX() + 1, area.maxZ() + 1};
        return new MapData.AreaInfo(Long.toString(area.id()), name.isEmpty() ? this.plugin.lang().get("unnamed-station") : name,
                network.dimension(), area.centerX() + 0.5, area.centerY(), area.centerZ() + 0.5, bounds,
                area.color() == 0 ? null : color(area.color()), area.mode(), List.copyOf(routes));
    }

    // ------------------------------------------------------------------ vehicles

    List<MapData.TrainInfo> trains(List<MtrData.Vehicle> vehicles, Set<String> modes) {
        List<MapData.TrainInfo> result = new ArrayList<>(vehicles.size());
        Map<String, String> names = new HashMap<>();
        for (MtrData.Vehicle vehicle : vehicles) {
            if (!modes.contains(vehicle.mode()) || this.routeBlacklisted(vehicle.routeName(), vehicle.routeNumber())) {
                continue;
            }
            String routeName = names.computeIfAbsent(vehicle.routeName(), WebMapCollector::displayName);
            String number = displayName(vehicle.routeNumber());
            List<MapData.CarInfo> cars = new ArrayList<>(vehicle.cars().size());
            for (double[] car : vehicle.cars()) {
                cars.add(new MapData.CarInfo(vehicle.dimension(),
                        new double[]{car[0], car[1], car[2]}, new double[]{car[3], car[4], car[5]}));
            }
            // Short label on the map: route number if there is one, otherwise the route name.
            String label = !number.isEmpty() ? number : routeName;
            result.add(new MapData.TrainInfo(
                    Long.toString(vehicle.id()),
                    label,
                    Long.toString(vehicle.routeId()),
                    routeName,
                    number,
                    vehicle.routeId() == 0L ? null : color(vehicle.routeColor()), // no route yet (leaving the depot)
                    vehicle.mode(),
                    vehicle.kmh(),
                    vehicle.moving(),
                    vehicle.onRoute(),
                    vehicle.manual(),
                    Long.toString(vehicle.thisStationId()),
                    emptyToNull(displayName(vehicle.thisStationName())),
                    Long.toString(vehicle.nextStationId()),
                    emptyToNull(displayName(vehicle.nextStationName())),
                    emptyToNull(displayName(vehicle.destination())),
                    vehicle.terminating(),
                    List.copyOf(cars)));
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private boolean routeBlacklisted(String name, String number) {
        return this.plugin.isRouteBlacklisted(name, displayName(name), number, displayName(number));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
