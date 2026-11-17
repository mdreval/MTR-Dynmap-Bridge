package com.igrobar.mtrdynmap;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MTR stations and depots as Dynmap markers in the "mtrbridge" marker set.
 * Marker ids are {@code station_<world>_<hexId>} / {@code depot_<world>_<hexId>}, so existing markers are
 * updated in place. Stale markers are only removed after a complete snapshot of every dimension.
 */
final class DynmapSync {
    static final String SET_ID = "mtrbridge";
    private static final String SET_LABEL = "MTR";

    private final MTRDynmapBridge plugin;
    private final WorldMappings worlds;
    private final Map<String, String> loggedWorlds = new HashMap<>();
    private Object markerApi;
    private Object markerSet;

    DynmapSync(MTRDynmapBridge plugin, WorldMappings worlds) {
        this.plugin = plugin;
        this.worlds = worlds;
    }

    /**
     * Synchronizes all markers. Returns the number of station/depot markers, or -1 if Dynmap is not ready.
     *
     * @param complete false if some MTR dimension did not answer: then stale markers are not removed
     */
    int sync(List<MtrData.Network> networks, boolean complete, String stationIcon, String depotIcon) throws Exception {
        Object dynmap = findDynmapPlugin();
        if (dynmap == null) {
            this.plugin.warnOnce("dynmap-missing", "Dynmap plugin not found yet; Dynmap markers will be added when it is available.");
            return -1;
        }
        Object api = invoke(dynmap, "getMarkerAPI");
        if (api == null) {
            this.plugin.warnOnce("dynmap-api", "Dynmap Marker API is not initialized yet; retrying.");
            return -1;
        }
        Object set = invoke(api, "getMarkerSet", String.class, SET_ID);
        if (set == null) {
            set = invoke(api, "createMarkerSet",
                    String.class, String.class, Set.class, boolean.class,
                    SET_ID, SET_LABEL, null, true);
            invoke(set, "setHideByDefault", boolean.class, false);
            invoke(set, "setLabelShow", Boolean.class, Boolean.TRUE);
        }
        this.markerApi = api;
        this.markerSet = set;

        List<String> dimensions = new ArrayList<>();
        for (MtrData.Network network : networks) {
            dimensions.add(network.rawDimension());
        }
        this.worlds.autoPopulate(dimensions);

        Set<String> seen = new HashSet<>();
        Object stationIconObject = this.icon(stationIcon);
        Object depotIconObject = this.icon(depotIcon);
        for (MtrData.Network network : networks) {
            String world = this.worlds.resolve(network.rawDimension());
            if (!world.equals(this.loggedWorlds.put(network.rawDimension(), world))) {
                this.plugin.getLogger().info("MTR dimension " + network.rawDimension() + " -> Dynmap world " + world);
            }
            this.syncAreas(network.stations(), "station", world, stationIconObject, seen);
            this.syncAreas(network.depots(), "depot", world, depotIconObject, seen);
        }

        if (complete) {
            this.removeStale(seen);
        }
        return seen.size();
    }

    private void syncAreas(List<MtrData.Area> areas, String type, String world, Object icon, Set<String> seen) throws Exception {
        for (MtrData.Area area : areas) {
            long x = area.centerX();
            long z = area.centerZ();
            long y = this.worlds.surfaceY(world, x, area.centerY(), z);

            String name = area.name();
            if (name == null || name.isBlank() || "null".equals(name)) {
                name = type.equals("station") ? "MTR Station" : "MTR Depot";
            }
            String id = type + "_" + world.replace(':', '_') + "_" + area.hexId();
            if (this.plugin.isStationBlacklisted(name, id)) {
                Object marker = invoke(this.markerSet, "findMarker", String.class, id);
                if (marker != null) {
                    invoke(marker, "deleteMarker");
                }
                continue;
            }
            seen.add(id);

            Object marker = invoke(this.markerSet, "findMarker", String.class, id);
            if (marker == null) {
                marker = invoke(this.markerSet, "createMarker",
                        String.class, String.class, String.class,
                        double.class, double.class, double.class,
                        Class.forName("org.dynmap.markers.MarkerIcon"), boolean.class,
                        id, name, world, (double) x, (double) y, (double) z, icon, true);
            } else {
                invoke(marker, "setLocation", String.class, double.class, double.class, double.class,
                        world, (double) x, (double) y, (double) z);
            }
            if (marker == null) {
                continue;
            }
            // keep the icon of persistent markers in sync with the config
            if (icon != null) {
                try {
                    invoke(marker, "setMarkerIcon", Class.forName("org.dynmap.markers.MarkerIcon"), icon);
                } catch (Throwable ignored) {
                    // older Dynmap APIs may not have setMarkerIcon; creation already used the right icon
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

    /** Uses Dynmap's built-in icon; falls back to "default" if a custom/older Dynmap does not have it. */
    private Object icon(String iconId) throws Exception {
        Object icon = invoke(this.markerApi, "getMarkerIcon", String.class, iconId == null || iconId.isBlank() ? "default" : iconId);
        if (icon == null) {
            icon = invoke(this.markerApi, "getMarkerIcon", String.class, "default");
        }
        return icon;
    }

    /** The mtrbridge marker set belongs to this plugin: every marker that is no longer in MTR is deleted. */
    private void removeStale(Set<String> seen) throws Exception {
        Object markers = invoke(this.markerSet, "getMarkers");
        int removed = 0;
        for (Object marker : new ArrayList<>((Collection<?>) markers)) {
            String id = String.valueOf(invoke(marker, "getMarkerID"));
            if (!seen.contains(id)) {
                invoke(marker, "deleteMarker");
                removed++;
            }
        }
        if (removed > 0) {
            this.plugin.getLogger().info("Removed " + removed + " stale station/depot marker(s) from Dynmap.");
        }
    }

    static Object findDynmapPlugin() throws Exception {
        Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
        Object manager = bukkit.getMethod("getPluginManager").invoke(null);
        Object dynmap = manager.getClass().getMethod("getPlugin", String.class).invoke(manager, "dynmap");
        if (dynmap == null) {
            return null;
        }
        try {
            Object enabled = dynmap.getClass().getMethod("isEnabled").invoke(dynmap);
            return Boolean.FALSE.equals(enabled) ? null : dynmap;
        } catch (Throwable ignored) {
            return dynmap; // cannot tell, assume it is enabled
        }
    }

    // ------------------------------------------------------------------ reflection

    private static Method accessibleMethod(Class<?> type, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        // Dynmap's MarkerImpl does not declare every API method itself; some come from the Marker interface.
        Set<Class<?>> visited = new HashSet<>();
        Method method = findMethodRecursive(type, name, parameterTypes, visited);
        if (method != null) {
            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
                // public interface methods work without it
            }
            return method;
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static Method findMethodRecursive(Class<?> type, String name, Class<?>[] parameterTypes, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) {
            return null;
        }
        try {
            return type.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            // look further
        }
        for (Class<?> iface : type.getInterfaces()) {
            Method method = findMethodRecursive(iface, name, parameterTypes, visited);
            if (method != null) {
                return method;
            }
        }
        return findMethodRecursive(type.getSuperclass(), name, parameterTypes, visited);
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? Object.class : args[i].getClass();
        }
        return accessibleMethod(target.getClass(), name, types).invoke(target, args);
    }

    private static Object invoke(Object target, String name, Class<?> p1, Object a1) throws Exception {
        return accessibleMethod(target.getClass(), name, p1).invoke(target, a1);
    }

    private static Object invoke(Object target, String name,
                                 Class<?> p1, Class<?> p2, Class<?> p3, Class<?> p4,
                                 Object a1, Object a2, Object a3, Object a4) throws Exception {
        return accessibleMethod(target.getClass(), name, p1, p2, p3, p4).invoke(target, a1, a2, a3, a4);
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
