package com.igrobar.mtrdynmap.web;

import java.util.List;

/**
 * Immutable snapshots of the MTR data for the website, turned into JSON on the web map's own thread.
 * MTR ids are 64-bit numbers, so they are sent as strings (JavaScript numbers cannot hold them exactly).
 */
public final class MapData {
    private MapData() {
    }

    /**
     * A drawn rail.
     *
     * @param type   transport mode and kind, e.g. "train", "train-platform", "boat-siding"
     * @param routes ids of the routes that use this rail (for line colours), may be empty
     * @param points x/z pairs: x1, z1, x2, z2, ...
     */
    public record TrackPiece(String dimension, String type, String[] routes, double[] points) {
    }

    /** A route (line) for colours, the legend and station windows. */
    public record RouteInfo(String dimension, String id, String name, String number, String color, String mode) {
    }

    /**
     * A station or a depot.
     *
     * @param area  min x, min z, max x, max z
     * @param routes ids of the routes that stop here (stations) or start here (depots)
     */
    public record AreaInfo(String id, String name, String dimension, double x, double y, double z,
                           double[] area, String color, String mode, List<String> routes) {
    }

    /** A car: front (lead) and back (trail) point. */
    public record CarInfo(String dimension, double[] lead, double[] trail) {
    }

    public record TrainInfo(String id, String name, String routeId, String routeName, String number, String color,
                            String mode, double kmh, boolean moving, boolean onRoute, boolean manual,
                            String stationId, String station, String nextId, String next, String destination, boolean terminating,
                            List<CarInfo> cars) {
    }

    public record Network(List<TrackPiece> tracks, List<RouteInfo> routes) {
    }

    public record Places(List<AreaInfo> stations, List<AreaInfo> depots) {
    }

    static String networkJson(Network network) {
        Json json = new Json(64 + network.tracks().size() * 90 + network.routes().size() * 100);
        json.beginObject();
        json.key("tracks").beginArray();
        for (TrackPiece track : network.tracks()) {
            json.beginObject().field("d", track.dimension()).field("t", track.type());
            if (track.routes().length > 0) {
                json.key("r").beginArray();
                for (String route : track.routes()) {
                    json.value(route);
                }
                json.endArray();
            }
            json.key("p").beginArray();
            for (double value : track.points()) {
                json.value(value);
            }
            json.endArray().endObject();
        }
        json.endArray();
        json.key("routes").beginArray();
        for (RouteInfo route : network.routes()) {
            json.beginObject()
                    .field("d", route.dimension())
                    .field("id", route.id())
                    .field("n", route.name())
                    .field("num", route.number())
                    .field("c", route.color())
                    .field("m", route.mode())
                    .endObject();
        }
        json.endArray();
        json.endObject();
        return json.toString();
    }

    static String placesJson(Places places) {
        Json json = new Json(64 + (places.stations().size() + places.depots().size()) * 160);
        json.beginObject();
        json.key("stations");
        areas(json, places.stations());
        json.key("depots");
        areas(json, places.depots());
        json.endObject();
        return json.toString();
    }

    private static void areas(Json json, List<AreaInfo> areas) {
        json.beginArray();
        for (AreaInfo area : areas) {
            json.beginObject()
                    .field("id", area.id())
                    .field("name", area.name())
                    .field("d", area.dimension())
                    .field("c", area.color())
                    .field("m", area.mode());
            json.key("p").point(area.x(), area.y(), area.z());
            json.key("a").beginArray();
            for (double value : area.area()) {
                json.value(value);
            }
            json.endArray();
            json.key("r").beginArray();
            for (String route : area.routes()) {
                json.value(route);
            }
            json.endArray();
            json.endObject();
        }
        json.endArray();
    }

    static String trainsJson(List<TrainInfo> trains, long time, long intervalMs) {
        Json json = new Json(64 + trains.size() * 420);
        json.beginObject().field("time", time).field("interval", intervalMs);
        json.key("trains").beginArray();
        for (TrainInfo train : trains) {
            json.beginObject()
                    .field("id", train.id())
                    .field("name", train.name())
                    .field("r", train.routeId())
                    .field("rn", train.routeName())
                    .field("num", train.number())
                    .field("c", train.color())
                    .field("m", train.mode())
                    .field("kmh", Math.round(train.kmh()))
                    .field("mv", train.moving())
                    .field("or", train.onRoute())
                    .field("man", train.manual())
                    .field("sid", train.stationId())
                    .field("station", train.station())
                    .field("nsid", train.nextId())
                    .field("next", train.next())
                    .field("dest", train.destination())
                    .field("term", train.terminating());
            json.key("cars").beginArray();
            for (CarInfo car : train.cars()) {
                json.beginObject();
                if (car.lead() != null) {
                    json.field("ld", car.dimension());
                    json.key("l").point(car.lead()[0], car.lead()[1], car.lead()[2]);
                }
                if (car.trail() != null) {
                    json.field("td", car.dimension());
                    json.key("t").point(car.trail()[0], car.trail()[1], car.trail()[2]);
                }
                json.endObject();
            }
            json.endArray().endObject();
        }
        json.endArray().endObject();
        return json.toString();
    }
}
