package com.igrobar.mtrdynmap;

import java.util.Locale;
import java.util.Map;

/**
 * Plugin messages in English and Russian.
 * The language is chosen with {@code language} in config.yml ("en" or "ru"); it also sets
 * the language of the web map.
 */
final class Lang {
    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry("no-permission", "You do not have permission to use this command."),
            Map.entry("mtr-not-ready", "MTR data is not available yet. Try again in a few seconds."),
            Map.entry("stations-header", "=== MTR stations (%d) ==="),
            Map.entry("routes-header", "=== MTR routes (%d) ==="),
            Map.entry("list-empty", "Nothing found."),
            Map.entry("route-vehicles", "%d on the way"),
            Map.entry("route-hidden", "hidden"),
            Map.entry("map-disabled", "The live transit map is disabled."),
            Map.entry("map-link", "Transit map: %s"),
            Map.entry("map-link-prefix", "Transit map: "),
            Map.entry("map-link-hover", "Click to open the map in your browser"),
            Map.entry("map-viewers", "Viewers right now: %d"),
            Map.entry("unnamed-station", "Unnamed station"),
            Map.entry("unnamed-route", "Unnamed route"));

    private static final Map<String, String> RU = Map.ofEntries(
            Map.entry("no-permission", "У вас нет прав на выполнение этой команды."),
            Map.entry("mtr-not-ready", "Данные MTR ещё не готовы. Попробуйте через несколько секунд."),
            Map.entry("stations-header", "=== Станции MTR (%d) ==="),
            Map.entry("routes-header", "=== Маршруты MTR (%d) ==="),
            Map.entry("list-empty", "Ничего не найдено."),
            Map.entry("route-vehicles", "в пути: %d"),
            Map.entry("route-hidden", "скрыт"),
            Map.entry("map-disabled", "Веб-карта транспорта выключена."),
            Map.entry("map-link", "Карта транспорта: %s"),
            Map.entry("map-link-prefix", "Карта транспорта: "),
            Map.entry("map-link-hover", "Нажми, чтобы открыть карту в браузере"),
            Map.entry("map-viewers", "Сейчас карту смотрят: %d"),
            Map.entry("unnamed-station", "Станция без названия"),
            Map.entry("unnamed-route", "Маршрут без названия"));

    private final String code;
    private final Map<String, String> messages;

    private Lang(String code, Map<String, String> messages) {
        this.code = code;
        this.messages = messages;
    }

    /** "ru"/"russian" → Russian; everything else ("en", "uk", "gb", "us", empty) → English. */
    static Lang of(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("ru") || normalized.startsWith("ru_") || normalized.startsWith("ru-") || normalized.equals("russian")) {
            return new Lang("ru", RU);
        }
        return new Lang("en", EN);
    }

    /** Language code for the website: "en" or "ru". */
    String code() {
        return this.code;
    }

    String get(String key, Object... args) {
        String text = this.messages.getOrDefault(key, EN.getOrDefault(key, key));
        return args.length == 0 ? text : String.format(Locale.ROOT, text, args);
    }
}
