package com.igrobar.mtrdynmap;

import java.util.List;

/**
 * Immutable copies of MTR data, taken on the MTR simulation thread and used on the plugin's threads.
 * Only {@link RailRef#rail()} points to a live MTR object: rails are immutable in MTR (an edit creates
 * a new Rail), so their shape can be sampled later.
 */
final class MtrData {
    private MtrData() {
    }

    /**
     * Everything that changes rarely in one MTR dimension.
     *
     * @param rawDimension MTR dimension id as MTR stores it, e.g. {@code minecraft/overworld}
     * @param dimension    the same id in Minecraft form, e.g. {@code minecraft:overworld}
     * @param railSignature changes whenever a rail is added, removed or rebuilt
     */
    record Network(String rawDimension, String dimension, List<Area> stations, List<Area> depots,
                   List<Route> routes, List<RailRef> rails, long railSignature) {
    }

    /** A station or a depot: an MTR area with a name and a colour. */
    record Area(long id, String hexId, String name, int color, String mode,
                long minX, long minZ, long maxX, long maxZ,
                long centerX, long centerY, long centerZ, List<Long> routeIds) {
    }

    /**
     * A route: one direction of a line.
     *
     * @param from     name of the first station, empty if unknown
     * @param to       name of the last station, empty if unknown
     * @param circular "clockwise", "anticlockwise" or empty for a route that is not circular
     */
    record Route(long id, String name, String number, int color, String mode, boolean hidden,
                 String from, String to, String circular) {
    }

    /**
     * A rail.
     *
     * @param rail     live MTR Rail object; MTR rails are immutable (an edit creates a new object), so its
     *                 kind and shape are read later on the plugin's own thread
     * @param routeIds routes whose depot path uses this rail (for colouring the line), may be empty
     */
    record RailRef(Object rail, long[] routeIds) {
    }

    /**
     * One MTR vehicle (train, boat, cable car or airplane).
     *
     * @param cars every car as {lead x, y, z, trail x, y, z}; the first car is the front of the vehicle
     */
    record Vehicle(String dimension, long id, String mode, boolean onRoute, boolean moving, boolean manual,
                   double kmh, long routeId, String routeName, String routeNumber, int routeColor,
                   String destination, long thisStationId, String thisStationName, long nextStationId, String nextStationName,
                   boolean terminating, List<double[]> cars) {
    }
}
