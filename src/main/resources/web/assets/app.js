/*
 * MTR-Dynmap-Bridge live web map.
 * Data arrives as a Server-Sent Events stream (/api/stream):
 *   network  — rail shapes and routes (sent only when something changes),
 *   stations — stations and depots,
 *   trains   — vehicle positions (every 1–2 s, animated in between).
 * The language comes from config.yml (/api/config.json) and can be overridden with ?lang=en / ?lang=ru.
 */
"use strict";

(() => {
  // ------------------------------------------------------------------ translations

  const I18N = {
    en: {
      defaultTitle: "MTR Transit Map",
      connecting: "Connecting…",
      online: "Online",
      reconnecting: "No connection — reconnecting…",
      closed: "No connection (server is off or too many viewers) — retrying in 10 s",
      togglePanel: "Collapse / expand",
      dimension: "Dimension",
      layers: "Layers",
      layerTracks: "Rails",
      layerLineColors: "Line colours",
      layerStations: "Stations",
      layerStationLabels: "Station names",
      layerAreas: "Station areas",
      layerDepots: "Depots",
      layerTrains: "Vehicles",
      layerTrainLabels: "Vehicle labels",
      modes: "Transport types",
      modeToggle_train: "🚆 Trains",
      modeToggle_boat: "⛴️ Boats",
      modeToggle_cable_car: "🚡 Cable cars",
      modeToggle_airplane: "✈️ Airplanes",
      lines: "Lines",
      lineHint: "Click to highlight this line, click again to show all",
      trains: "Vehicles",
      search: "Search a line, station or destination",
      openDynmap: "Open Dynmap",
      openMtrMap: "MTR system map",
      following: "Following:",
      stop: "Stop",
      kind_train: "Rails",
      "kind_train-platform": "Platforms",
      "kind_train-siding": "Sidings (depots)",
      kind_boat: "Boat routes",
      "kind_boat-platform": "Boat piers",
      "kind_boat-siding": "Boat sidings",
      kind_cable_car: "Cable car",
      "kind_cable_car-platform": "Cable car stations",
      "kind_cable_car-siding": "Cable car sidings",
      kind_airplane: "Flight paths",
      "kind_airplane-platform": "Airport gates",
      "kind_airplane-siding": "Airplane stands",
      mode_train: "Train",
      mode_boat: "Boat",
      mode_cable_car: "Cable car",
      mode_airplane: "Airplane",
      station: "Station",
      depot: "Depot",
      linesHere: "Lines:",
      atStation: "At the station:",
      comingHere: "Heading here:",
      depotLines: "Lines from this depot:",
      distance: "≈ {0} m away",
      followHint: "Click to follow this vehicle",
      none: "none",
      stoppedAt: "At {0}",
      stopped: "Stopped",
      notInService: "Not in service (to/from the depot)",
      inDepot: "In the depot",
      returning: "Returning to the depot",
      manual: "manual driving",
      terminating: "terminates here",
      speed: "{0} km/h",
      nextStop: "next: {0}",
      nothingFound: "Nothing found",
      noTrains: "No vehicles in this dimension",
      otherDimensions: "+{0} in other dimensions",
      vehiclesOnLine: "{0} on the way",
      "minecraft:overworld": "Overworld",
      "minecraft:the_nether": "Nether",
      "minecraft:the_end": "The End",
    },
    ru: {
      defaultTitle: "Карта транспорта MTR",
      connecting: "Подключение…",
      online: "В сети",
      reconnecting: "Нет связи — переподключение…",
      closed: "Нет связи (сервер выключен или слишком много зрителей) — повтор через 10 с",
      togglePanel: "Свернуть / развернуть",
      dimension: "Измерение",
      layers: "Слои",
      layerTracks: "Пути",
      layerLineColors: "Цвета линий",
      layerStations: "Станции",
      layerStationLabels: "Подписи станций",
      layerAreas: "Зоны станций",
      layerDepots: "Депо",
      layerTrains: "Транспорт",
      layerTrainLabels: "Подписи транспорта",
      modes: "Виды транспорта",
      modeToggle_train: "🚆 Поезда",
      modeToggle_boat: "⛴️ Корабли",
      modeToggle_cable_car: "🚡 Канатки",
      modeToggle_airplane: "✈️ Самолёты",
      lines: "Линии",
      lineHint: "Нажми, чтобы выделить линию; нажми ещё раз, чтобы показать все",
      trains: "Транспорт",
      search: "Поиск линии, станции или направления",
      openDynmap: "Открыть Dynmap",
      openMtrMap: "Схема MTR",
      following: "Слежу за:",
      stop: "Остановить",
      kind_train: "Пути",
      "kind_train-platform": "Платформы",
      "kind_train-siding": "Пути в депо",
      kind_boat: "Водные маршруты",
      "kind_boat-platform": "Причалы",
      "kind_boat-siding": "Стоянки лодок",
      kind_cable_car: "Канатная дорога",
      "kind_cable_car-platform": "Станции канатной дороги",
      "kind_cable_car-siding": "Стоянки канатной дороги",
      kind_airplane: "Воздушные маршруты",
      "kind_airplane-platform": "Выходы на посадку",
      "kind_airplane-siding": "Стоянки самолётов",
      mode_train: "Поезд",
      mode_boat: "Лодка",
      mode_cable_car: "Канатная дорога",
      mode_airplane: "Самолёт",
      station: "Станция",
      depot: "Депо",
      linesHere: "Линии:",
      atStation: "На станции:",
      comingHere: "Едут сюда:",
      depotLines: "Линии из этого депо:",
      distance: "≈ {0} м",
      followHint: "Нажми, чтобы следить",
      none: "нет",
      stoppedAt: "Стоит: {0}",
      stopped: "Стоит",
      notInService: "Вне маршрута (в депо / из депо)",
      inDepot: "В депо",
      returning: "Возвращается в депо",
      manual: "ручное управление",
      terminating: "конечная",
      speed: "{0} км/ч",
      nextStop: "далее: {0}",
      nothingFound: "Ничего не найдено",
      noTrains: "В этом измерении транспорта нет",
      otherDimensions: "Ещё {0} в других измерениях",
      vehiclesOnLine: "в пути: {0}",
      "minecraft:overworld": "Обычный мир",
      "minecraft:the_nether": "Незер",
      "minecraft:the_end": "Энд",
    },
  };
  let strings = I18N.en;

  /** Translated text; {0}, {1}… are replaced with the arguments. */
  function t(key, ...args) {
    const text = strings[key] ?? I18N.en[key] ?? key;
    return text.replace(/\{(\d+)\}/g, (_, i) => String(args[Number(i)] ?? ""));
  }

  function applyLanguage(code) {
    strings = I18N[code] || I18N.en;
    document.documentElement.lang = I18N[code] ? code : "en";
    document.querySelectorAll("[data-i18n]").forEach((el) => (el.textContent = t(el.dataset.i18n)));
    document.querySelectorAll("[data-i18n-placeholder]").forEach((el) => (el.placeholder = t(el.dataset.i18nPlaceholder)));
    document.querySelectorAll("[data-i18n-title]").forEach((el) => {
      el.title = t(el.dataset.i18nTitle);
      el.setAttribute("aria-label", el.title);
    });
  }

  // ------------------------------------------------------------------ display settings

  /** How each kind of rail is drawn (kind = transport mode + "-platform" / "-siding"). */
  const TRACK_STYLES = {
    train: { color: "#aab4c3", weight: 3 },
    "train-platform": { color: "#ffffff", weight: 6 },
    "train-siding": { color: "#7d8796", weight: 3, dash: "3 5" },
    boat: { color: "#4fc3f7", weight: 2, dash: "6 6" },
    "boat-platform": { color: "#b3e5fc", weight: 5 },
    "boat-siding": { color: "#4fc3f7", weight: 2, dash: "2 6" },
    cable_car: { color: "#c792ea", weight: 2, dash: "1 5" },
    "cable_car-platform": { color: "#e1bee7", weight: 5 },
    "cable_car-siding": { color: "#c792ea", weight: 2, dash: "1 7" },
    airplane: { color: "#f2c14e", weight: 2, dash: "8 8" },
    "airplane-platform": { color: "#ffe082", weight: 5 },
    "airplane-siding": { color: "#f2c14e", weight: 2, dash: "2 8" },
  };
  const DEFAULT_TRACK = TRACK_STYLES.train;
  const DEFAULT_VEHICLE_COLOR = "#9aa5b8"; // vehicles that are not on a line right now (to/from the depot)
  const VEHICLE_WEIGHT = 7;
  const TELEPORT_DISTANCE = 160; // if a vehicle "jumps" farther than this, move it instantly instead of animating
  const AREA_MARGIN = 12; // a vehicle stopped this close to a station area counts as standing at the station

  const state = {
    config: { dimensions: {} },
    network: { tracks: [], routes: [] },
    routes: new Map(), // route id → route
    stations: [],
    depots: [],
    trains: new Map(),
    networkDims: new Set(),
    stationDims: new Set(),
    interval: 1000,
    dim: null,
    follow: null,
    openPopup: null,
    selectedRoute: null,
    fittedOnce: false,
    animating: false,
  };

  // ------------------------------------------------------------------ transport types

  const MODE_ORDER = ["train", "boat", "cable_car", "airplane"];
  const MODES_STORAGE_KEY = "mtr-dynmap-bridge.hidden-modes";
  /** Transport types the visitor switched off (remembered in this browser). */
  const hiddenModes = new Set(loadHiddenModes());

  function loadHiddenModes() {
    try {
      const saved = JSON.parse(localStorage.getItem(MODES_STORAGE_KEY) || "[]");
      return Array.isArray(saved) ? saved : [];
    } catch {
      return [];
    }
  }

  function saveHiddenModes() {
    try {
      localStorage.setItem(MODES_STORAGE_KEY, JSON.stringify([...hiddenModes]));
    } catch {
      // storage unavailable (private mode etc.) — the choice simply is not remembered
    }
  }

  /** "train-platform" → "train". */
  const modeOfType = (type) => String(type || "train").split("-")[0];
  const modeVisible = (mode) => !hiddenModes.has(mode || "train");

  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  const ll = (p) => [p[2], p[0]];
  const dimLabel = (d) => state.config.dimensions?.[d] || (strings[d] ?? I18N.en[d]) || prettyDimension(d);
  const collator = () => new Intl.Collator(document.documentElement.lang, { numeric: true, sensitivity: "base" });

  /** "minecraft:old" → "Old", "twilightforest:twilight_forest" → "Twilight Forest". */
  function prettyDimension(id) {
    const path = String(id ?? "").split(":").pop();
    return path.split(/[_\-/]+/).filter(Boolean).map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ") || String(id);
  }

  // ------------------------------------------------------------------ map

  // X to the right (east), Z down (south) — like Minecraft maps. 1 block = 1 pixel at zoom 0.
  const crs = L.Util.extend({}, L.CRS.Simple, { transformation: new L.Transformation(1, 0, 1, 0) });
  const map = L.map("map", {
    crs,
    minZoom: -6,
    maxZoom: 4,
    zoomSnap: 0.25,
    zoomDelta: 0.5,
    wheelPxPerZoomLevel: 90,
    zoomControl: false,
    preferCanvas: true,
  });
  L.control.zoom({ position: "topright" }).addTo(map);
  map.attributionControl.setPrefix('<a href="https://leafletjs.com" target="_blank" rel="noopener">Leaflet</a>');
  map.setView([0, 0], -1);

  // Areas, rails and vehicles are drawn on canvases (fast for thousands of shapes). Depots and stations use SVG
  // above them: every SVG circle receives its own clicks, while empty space lets clicks through to the
  // vehicles canvas below (see app.css). A canvas on top would swallow all clicks for the layers under it.
  for (const [name, z] of [["areas", 405], ["tracks", 410], ["trains", 420], ["depots", 430], ["stations", 440]]) {
    map.createPane(name).style.zIndex = z;
  }
  const renderers = {
    areas: L.canvas({ pane: "areas", padding: 0.5 }),
    tracks: L.canvas({ pane: "tracks", padding: 0.5 }),
    trains: L.canvas({ pane: "trains", padding: 0.5 }),
    depots: L.svg({ pane: "depots", padding: 0.5 }),
    stations: L.svg({ pane: "stations", padding: 0.5 }),
  };
  const layers = {
    areas: L.layerGroup().addTo(map),
    tracks: L.layerGroup().addTo(map),
    depots: L.layerGroup().addTo(map),
    stations: L.layerGroup().addTo(map),
    trains: L.layerGroup().addTo(map),
  };

  // ------------------------------------------------------------------ rails

  /** Rail points: flat [x1, z1, x2, z2, ...] → Leaflet [[z, x], ...]. */
  function trackLatLngs(p) {
    const out = new Array(p.length / 2);
    for (let i = 0, j = 0; i + 1 < p.length; i += 2, j++) out[j] = [p[i + 1], p[i]];
    return out;
  }

  function trackColor(track, style) {
    if (!$("layer-line-colors").checked || !track.r) return style.color;
    if (state.selectedRoute && track.r.includes(state.selectedRoute)) return state.routes.get(state.selectedRoute)?.c || style.color;
    for (const id of track.r) {
      const route = state.routes.get(id);
      if (route) return route.c;
    }
    return style.color;
  }

  function renderTracks() {
    layers.tracks.clearLayers();
    // All pieces of one look become one multi-polyline, so thousands of rails draw quickly.
    const groups = new Map();
    const kinds = new Set();
    for (const track of state.network.tracks) {
      if (track.d !== state.dim || !modeVisible(modeOfType(track.t))) continue;
      const style = TRACK_STYLES[track.t] || DEFAULT_TRACK;
      kinds.add(track.t);
      const color = trackColor(track, style);
      const faded = state.selectedRoute && !(track.r && track.r.includes(state.selectedRoute));
      const key = `${track.t}|${color}|${faded ? 1 : 0}`;
      if (!groups.has(key)) groups.set(key, { style, color, faded, lines: [] });
      groups.get(key).lines.push(trackLatLngs(track.p));
    }
    // Plain rails first, coloured lines and platforms on top.
    const plain = (g) => (g.color === g.style.color ? 1 : 0);
    const sorted = [...groups.values()].sort((a, b) => (b.faded - a.faded) || (plain(b) - plain(a)) || (a.style.weight - b.style.weight));
    for (const g of sorted) {
      const coloured = g.color !== g.style.color;
      L.polyline(g.lines, {
        renderer: renderers.tracks,
        color: g.color,
        weight: coloured && !g.style.dash ? Math.max(g.style.weight, 4) : g.style.weight,
        opacity: g.faded ? 0.18 : 0.95,
        dashArray: g.style.dash || null,
        lineCap: "round",
        lineJoin: "round",
        interactive: false,
      }).addTo(layers.tracks);
    }
    renderLegend(kinds);
  }

  function renderLegend(kinds) {
    const order = Object.keys(TRACK_STYLES);
    const items = [...kinds]
      .sort((a, b) => order.indexOf(a) - order.indexOf(b))
      .map((kind) => {
        const style = TRACK_STYLES[kind] || DEFAULT_TRACK;
        const dashed = style.dash ? ` class="dashed" style="--c:${style.color}"` : ` style="background:${style.color}"`;
        return `<span><i${dashed}></i>${esc(t(`kind_${kind}`))}</span>`;
      });
    $("legend").innerHTML = items.length > 1 ? items.join("") : "";
  }

  // ------------------------------------------------------------------ lines (routes)

  function routesOfDimension() {
    return state.network.routes.filter((r) => r.d === state.dim && modeVisible(r.m));
  }

  function routeTitle(route) {
    return route.num && route.num !== route.n ? `${route.num} · ${route.n}` : route.n;
  }

  function renderLines() {
    const routes = routesOfDimension();
    const counts = new Map();
    for (const train of state.trains.values()) {
      if (train.info.or) counts.set(train.info.r, (counts.get(train.info.r) || 0) + 1);
    }
    $("lines-box").hidden = routes.length === 0;
    $("line-count").textContent = routes.length;
    $("line-list").innerHTML = routes
      .map((route) => {
        const active = state.selectedRoute === route.id ? " active" : "";
        const count = counts.get(route.id) || 0;
        return `<li data-route="${esc(route.id)}" class="${active}" title="${esc(t("lineHint"))}">
          <i class="line-swatch" style="background:${esc(route.c)}"></i>
          <span class="line-name">${esc(routeTitle(route))}</span>
          <span class="muted">${count ? esc(t("vehiclesOnLine", count)) : ""}</span></li>`;
      })
      .join("");
  }

  $("line-list").addEventListener("click", (event) => {
    const item = event.target.closest("li[data-route]");
    if (!item) return;
    selectRoute(state.selectedRoute === item.dataset.route ? null : item.dataset.route);
  });

  function selectRoute(id) {
    state.selectedRoute = id;
    renderTracks();
    renderLines();
    redrawAllTrains();
    renderTrainList();
    if (id) fitRoute(id);
  }

  function fitRoute(id) {
    const points = [];
    for (const track of state.network.tracks) {
      if (track.d === state.dim && track.r && track.r.includes(id)) {
        points.push([track.p[1], track.p[0]], [track.p[track.p.length - 1], track.p[track.p.length - 2]]);
      }
    }
    if (points.length) map.fitBounds(L.latLngBounds(points), { paddingTopLeft: [panelRightEdge() + 60, 60], paddingBottomRight: [60, 60], maxZoom: 1 });
  }

  // ------------------------------------------------------------------ stations and depots

  function areaBounds(a) {
    return [[a[1], a[0]], [a[3], a[2]]];
  }

  function renderAreas() {
    layers.areas.clearLayers();
    if (!$("layer-areas").checked) return;
    for (const station of state.stations) {
      if (station.d !== state.dim || !station.a || !modeVisible(station.m)) continue;
      const color = station.c || "#8b95a7";
      L.rectangle(areaBounds(station.a), {
        renderer: renderers.areas,
        color,
        weight: 1,
        opacity: 0.55,
        fillColor: color,
        fillOpacity: 0.1,
        interactive: false,
      }).addTo(layers.areas);
    }
    if (!$("layer-depots").checked) return;
    for (const depot of state.depots) {
      if (depot.d !== state.dim || !depot.a || !modeVisible(depot.m)) continue;
      L.rectangle(areaBounds(depot.a), {
        renderer: renderers.areas,
        color: depot.c || "#7d8796",
        weight: 1,
        opacity: 0.45,
        dashArray: "4 4",
        fillOpacity: 0.05,
        interactive: false,
      }).addTo(layers.areas);
    }
  }

  function bindPlacePopup(marker, place, kind) {
    // Registered before bindPopup, so it runs first: keep the popup clear of the side panel.
    marker.on("click", () => {
      const popup = marker.getPopup();
      if (popup) popup.options.autoPanPaddingTopLeft = L.point(panelRightEdge() + 12, 12);
    });
    marker.bindPopup(() => (kind === "station" ? stationPopup(place) : depotPopup(place)), {
      maxWidth: 420,
      minWidth: 260,
      className: "station-popup",
      autoPanPaddingTopLeft: L.point(panelRightEdge() + 12, 12),
    });
    marker.on("popupopen", () => (state.openPopup = { place, marker, kind }));
    marker.on("popupclose", () => {
      if (state.openPopup?.marker === marker) state.openPopup = null;
    });
  }

  function renderStations() {
    layers.stations.clearLayers();
    const labels = $("layer-station-labels").checked;
    for (const station of state.stations) {
      if (station.d !== state.dim || !modeVisible(station.m)) continue;
      const marker = L.circleMarker(ll(station.p), {
        renderer: renderers.stations,
        radius: 6,
        color: station.c || "#11151c",
        weight: 3,
        fillColor: "#ffffff",
        fillOpacity: 1,
      });
      marker.bindTooltip(esc(station.name), { permanent: labels, direction: "right", offset: [7, 0], className: "station-label" });
      bindPlacePopup(marker, station, "station");
      marker.addTo(layers.stations);
    }
  }

  function renderDepots() {
    layers.depots.clearLayers();
    if (!$("layer-depots").checked) return;
    for (const depot of state.depots) {
      if (depot.d !== state.dim || !modeVisible(depot.m)) continue;
      const marker = L.circleMarker(ll(depot.p), {
        renderer: renderers.depots,
        radius: 5,
        color: "#11151c",
        weight: 2,
        fillColor: depot.c || "#7d8796",
        fillOpacity: 1,
      });
      marker.bindTooltip(`${esc(t("depot"))}: ${esc(depot.name)}`, { direction: "right", offset: [7, 0], className: "station-label depot-label" });
      bindPlacePopup(marker, depot, "depot");
      marker.addTo(layers.depots);
    }
  }

  /** Right edge of the side panel in pixels (0 when it is collapsed), so popups are not hidden under it. */
  function panelRightEdge() {
    const panel = $("panel");
    return panel.classList.contains("collapsed") ? 0 : Math.round(panel.getBoundingClientRect().right);
  }

  function routeChips(ids) {
    const routes = (ids || []).map((id) => state.routes.get(id)).filter(Boolean);
    if (!routes.length) return `<div class="muted popup-none">${esc(t("none"))}</div>`;
    const unique = new Map();
    for (const route of routes) unique.set(`${route.n}|${route.c}`, route);
    return `<div class="chips">${[...unique.values()]
      .map((route) => `<span class="chip" data-route="${esc(route.id)}" title="${esc(t("lineHint"))}"><i style="background:${esc(route.c)}"></i>${esc(routeTitle(route))}</span>`)
      .join("")}</div>`;
  }

  function insideArea(point, area, margin) {
    return point[0] >= area[0] - margin && point[0] <= area[2] + margin && point[2] >= area[1] - margin && point[2] <= area[3] + margin;
  }

  /** Popup content: lines, vehicles standing at the station and vehicles heading to it (refreshed live while open). */
  function stationPopup(station) {
    const here = [];
    const coming = [];
    for (const train of state.trains.values()) {
      const info = train.info;
      if (!modeVisible(info.m)) continue;
      const head = leadPoint(train.to);
      const sameDim = head && head.d === station.d;
      if (standingAt(train) === station) {
        here.push({ train, sort: trainTitle(info), detail: info.term ? t("terminating") : info.next ? t("nextStop", info.next) : t("stopped") });
      } else if (heading(train)?.id === station.id) {
        const meters = sameDim ? Math.round(distance(head.p, station.p)) : null;
        const parts = [];
        if (meters !== null) parts.push(t("distance", meters));
        else if (head) parts.push(dimLabel(head.d));
        parts.push(info.mv ? t("speed", info.kmh) : t("stopped"));
        coming.push({ train, sort: meters ?? Number.MAX_SAFE_INTEGER, detail: parts.join(" · ") });
      }
    }
    const order = collator();
    here.sort((a, b) => order.compare(a.sort, b.sort));
    coming.sort((a, b) => a.sort - b.sort);
    return `<h3>${esc(station.name)}</h3>
      <div class="muted">X ${Math.round(station.p[0])}, Z ${Math.round(station.p[2])}</div>
      <div class="popup-section">${esc(t("linesHere"))}</div>${routeChips(station.r)}
      <div class="popup-section">${esc(t("atStation"))}</div>${trainItems(here)}
      <div class="popup-section">${esc(t("comingHere"))}</div>${trainItems(coming)}`;
  }

  function depotPopup(depot) {
    return `<h3><span class="muted">${esc(t("depot"))}:</span> ${esc(depot.name)}</h3>
      <div class="muted">X ${Math.round(depot.p[0])}, Z ${Math.round(depot.p[2])}</div>
      <div class="popup-section">${esc(t("depotLines"))}</div>${routeChips(depot.r)}`;
  }

  function trainItems(items) {
    if (!items.length) return `<div class="muted popup-none">${esc(t("none"))}</div>`;
    return `<ul class="popup-trains">${items
      .map(({ train, detail }) => `<li data-follow="${esc(train.id)}" title="${esc(t("followHint"))}">
          <span class="train-dot" style="background:${esc(vehicleColor(train.info))}"></span>
          <span><b>${esc(trainTitle(train.info))}</b>${train.info.dest ? ` → ${esc(train.info.dest)}` : ""}<br><span class="muted">${esc(detail)}</span></span></li>`)
      .join("")}</ul>`;
  }

  // Clicks inside popups: a vehicle → follow it, a line chip → highlight the line.
  map.on("popupopen", (e) => {
    const el = e.popup.getElement();
    if (!el || el.dataset.bound) return;
    el.dataset.bound = "1";
    el.addEventListener("click", (event) => {
      const item = event.target.closest("[data-follow]");
      if (item) {
        map.closePopup();
        followTrain(item.dataset.follow);
        return;
      }
      const chip = event.target.closest("[data-route]");
      if (chip) {
        map.closePopup();
        selectRoute(chip.dataset.route);
      }
    });
  });

  function refreshOpenPopup() {
    const open = state.openPopup;
    if (open && open.kind === "station") open.marker.setPopupContent(stationPopup(open.place));
  }

  // ------------------------------------------------------------------ vehicles

  /** Vehicle outline from config.yml (web-map.vehicle-outline). */
  function outline() {
    const c = state.config;
    return {
      color: c.vehicleOutlineColor || "#f4f6fa",
      opacity: c.vehicleOutline === false ? 0 : (typeof c.vehicleOutlineOpacity === "number" ? c.vehicleOutlineOpacity : 0.7),
      width: typeof c.vehicleOutlineWidth === "number" ? c.vehicleOutlineWidth : 1.5,
    };
  }

  function vehicleColor(info) {
    return info.c || DEFAULT_VEHICLE_COLOR;
  }

  const stationById = (id) => state.stations.find((s) => s.id === id) || null;

  /** The station a vehicle is standing at (stopped inside or right next to its area), or null. */
  function standingAt(train) {
    const info = train.info;
    if (info.mv || !info.or) return null;
    const station = stationById(info.sid);
    const head = leadPoint(train.to);
    return station && head && head.d === station.d && insideArea(head.p, station.a, AREA_MARGIN) ? station : null;
  }

  /**
   * The station a moving vehicle is going to: {id, name}, or null.
   * MTR reports the last/current stop ("sid") and the stop after it ("nsid"); while the vehicle is still
   * getting closer to the current stop it is heading there, once it moves away it is heading to the next one.
   */
  function heading(train) {
    const info = train.info;
    if (!info.mv || !info.or || !info.station) return null;
    const current = stationById(info.sid);
    const head = leadPoint(train.to);
    if (current && head && train.prevHead && head.d === current.d) {
      const approaching = distance(head.p, current.p) < distance(train.prevHead, current.p) - 0.05;
      if (approaching) return { id: current.id, name: info.station };
    } else if (!info.next) {
      return { id: info.sid, name: info.station };
    }
    return info.next ? { id: info.nsid, name: info.next } : { id: info.sid, name: info.station };
  }

  /** Short label on the map: the line number ("B"), else the line name, else the vehicle type. */
  function trainLabel(info) {
    return info.num || info.rn || t(`mode_${info.m}`);
  }

  /** Title in lists: "B · Blue Line", or the vehicle type if it has no line yet. */
  function trainTitle(info) {
    if (info.num && info.rn && info.num !== info.rn) return `${info.num} · ${info.rn}`;
    return info.rn || info.num || t(`mode_${info.m}`);
  }

  function trainStateText(train) {
    const info = train.info;
    let text;
    let target = info.station;
    if (!info.or) text = t("inDepot");
    else if (!info.c) text = t("notInService");
    else if (!info.station) text = info.mv ? t("returning") : t("stopped");
    else if (!info.mv) text = standingAt(train) ? t("stoppedAt", info.station) : t("stopped");
    else {
      target = heading(train)?.name || info.station;
      text = `${info.kmh > 0 ? `${t("speed", info.kmh)} ` : ""}→ ${target}`;
    }
    if (info.dest && info.or && info.c && info.dest !== target) text += ` · ${info.dest}`;
    if (info.man) text += ` · ${t("manual")}`;
    const head = leadPoint(train.to);
    if (head && head.d !== state.dim) text += ` · ${dimLabel(head.d)}`;
    return text;
  }

  function lerpPoint(a, b, k) {
    return [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k];
  }

  function distance(a, b) {
    return Math.hypot(a[0] - b[0], a[2] - b[2]);
  }

  /** Whether cars can be animated smoothly from position a to position b. */
  function compatible(a, b) {
    if (!a || a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) {
      const x = a[i], y = b[i];
      if (x.ld !== y.ld || x.td !== y.td || !x.l !== !y.l || !x.t !== !y.t) return false;
      if (x.l && distance(x.l, y.l) > TELEPORT_DISTANCE) return false;
      if (x.t && distance(x.t, y.t) > TELEPORT_DISTANCE) return false;
    }
    return true;
  }

  function carsAt(train, now) {
    const k = Math.max(0, Math.min(1, (now - train.start) / state.interval));
    if (k >= 1 || train.from === train.to) return train.to;
    return train.to.map((car, i) => {
      const from = train.from[i];
      return {
        ld: car.ld,
        td: car.td,
        l: car.l && lerpPoint(from.l, car.l, k),
        t: car.t && lerpPoint(from.t, car.t, k),
      };
    });
  }

  function leadPoint(cars) {
    const first = cars[0];
    if (!first) return null;
    return first.l ? { d: first.ld, p: first.l } : first.t ? { d: first.td, p: first.t } : null;
  }

  function createTrainLayers(train) {
    // outline under the vehicle so it stands out from a rail in the same line colour (web-map.vehicle-outline)
    train.casing = L.polyline([], {
      renderer: renderers.trains,
      color: outline().color,
      weight: VEHICLE_WEIGHT + outline().width * 2,
      opacity: outline().opacity,
      lineCap: "round",
      interactive: false,
    });
    train.casing.addTo(layers.trains);
    train.line = L.polyline([], {
      renderer: renderers.trains,
      weight: VEHICLE_WEIGHT,
      opacity: 1,
      lineCap: "round",
      interactive: true,
    }).on("click", () => followTrain(train.id));
    train.head = L.circleMarker([0, 0], {
      renderer: renderers.trains,
      radius: 4,
      color: "#11151c",
      weight: 2,
      fillColor: "#ffffff",
      fillOpacity: 1,
    }).on("click", () => followTrain(train.id));
    train.line.addTo(layers.trains);
  }

  function styleTrain(train) {
    const faded = state.selectedRoute && train.info.r !== state.selectedRoute;
    train.line.setStyle({ color: vehicleColor(train.info), opacity: faded ? 0.3 : 1 });
    train.casing.setStyle({ opacity: faded ? outline().opacity * 0.2 : outline().opacity });
    train.head.setStyle({ opacity: faded ? 0.3 : 1, fillOpacity: faded ? 0.3 : 1 });
    const labels = $("layer-train-labels").checked && !faded;
    const name = trainLabel(train.info);
    if (train.labelShown !== labels) {
      // Permanent label or hover label: recreate the tooltip when the setting changes.
      train.head.unbindTooltip();
      train.head.bindTooltip(esc(name), { permanent: labels, direction: "right", offset: [8, 0], className: "train-label" });
      train.labelShown = labels;
    } else if (train.labelName !== name) {
      train.head.setTooltipContent(esc(name));
    }
    train.labelName = name;
  }

  function drawTrain(train, now) {
    if (!modeVisible(train.info.m)) {
      // switched off by the visitor: keep the vehicle, just draw nothing
      train.line.setLatLngs([]);
      train.casing.setLatLngs([]);
      if (layers.trains.hasLayer(train.head)) layers.trains.removeLayer(train.head);
      return;
    }
    const cars = carsAt(train, now);
    const segments = [];
    for (const car of cars) {
      const lead = car.l && car.ld === state.dim ? car.l : null;
      const trail = car.t && car.td === state.dim ? car.t : null;
      if (lead && trail) segments.push([ll(lead), ll(trail)]);
      else if (lead || trail) segments.push([ll(lead || trail), ll(lead || trail)]);
    }
    train.line.setLatLngs(segments);
    train.casing.setLatLngs(segments);
    const head = leadPoint(cars);
    if (head && head.d === state.dim) {
      train.head.setLatLng(ll(head.p));
      if (!layers.trains.hasLayer(train.head)) train.head.addTo(layers.trains);
    } else if (layers.trains.hasLayer(train.head)) {
      layers.trains.removeLayer(train.head);
    }
  }

  function animate() {
    const now = performance.now();
    let moving = false;
    for (const train of state.trains.values()) {
      if (train.settled) continue;
      drawTrain(train, now);
      if (train.from === train.to || now - train.start >= state.interval) {
        train.settled = true; // the last frame was drawn exactly at the target position
      } else {
        moving = true;
      }
    }
    if (moving) {
      requestAnimationFrame(animate);
    } else {
      state.animating = false;
    }
  }

  function startAnimation() {
    if (!state.animating) {
      state.animating = true;
      requestAnimationFrame(animate);
    }
  }

  function onTrains(data) {
    state.interval = Math.max(250, data.interval || 1000);
    const now = performance.now();
    const seen = new Set();
    for (const info of data.trains) {
      seen.add(info.id);
      let train = state.trains.get(info.id);
      if (!train) {
        train = { id: info.id, info, from: info.cars, to: info.cars, start: now };
        createTrainLayers(train);
        state.trains.set(info.id, train);
      } else {
        const current = carsAt(train, now);
        train.prevHead = leadPoint(train.to)?.p || null; // previous reported position: tells where it is heading
        train.from = compatible(current, info.cars) ? current : info.cars;
        train.to = info.cars;
        train.start = now;
        train.info = info;
      }
      train.settled = false;
      styleTrain(train);
      drawTrain(train, now);
    }
    for (const [id, train] of state.trains) {
      if (!seen.has(id)) {
        layers.trains.removeLayer(train.casing);
        layers.trains.removeLayer(train.line);
        layers.trains.removeLayer(train.head);
        state.trains.delete(id);
        if (state.follow === id) stopFollow();
      }
    }
    startAnimation();
    renderModeToggles();
    renderTrainList();
    renderLines();
    refreshOpenPopup();
    updateDimensions();
    if (state.follow) {
      const train = state.trains.get(state.follow);
      const head = train && leadPoint(train.to);
      if (head && head.d === state.dim) {
        map.panTo(ll(head.p), { animate: true, duration: state.interval / 1000, easeLinearity: 1, noMoveStart: true });
      }
      if (train) updateFollowBadge(train);
    }
  }

  function redrawAllTrains() {
    const now = performance.now();
    for (const train of state.trains.values()) {
      styleTrain(train);
      drawTrain(train, now);
    }
  }

  // ------------------------------------------------------------------ vehicle list

  /** Dimension a vehicle is in (its front), or null if unknown. */
  function trainDimension(train) {
    const head = leadPoint(train.to);
    return head ? head.d : null;
  }

  function renderTrainList() {
    const query = $("train-search").value.trim().toLowerCase();
    // selected dimension (and highlighted line) only; vehicles elsewhere are just counted
    const all = [...state.trains.values()];
    const order = collator();
    const trains = all
      .filter((train) => trainDimension(train) === state.dim && modeVisible(train.info.m))
      .filter((train) => !state.selectedRoute || train.info.r === state.selectedRoute)
      .sort((a, b) => order.compare(trainTitle(a.info), trainTitle(b.info)) || order.compare(a.info.station || "", b.info.station || ""));
    const elsewhere = all.filter((train) => trainDimension(train) !== state.dim && modeVisible(train.info.m)).length;
    $("train-count").textContent = trains.length;
    const items = [];
    for (const train of trains) {
      const info = train.info;
      const haystack = `${info.rn || ""} ${info.num || ""} ${info.station || ""} ${info.next || ""} ${info.dest || ""} ${t(`mode_${info.m}`)}`.toLowerCase();
      if (query && !haystack.includes(query)) continue;
      items.push(`<li data-id="${esc(train.id)}" class="${state.follow === train.id ? "active" : ""}">
        <span class="train-dot" style="background:${esc(vehicleColor(info))}"></span>
        <span class="train-info"><div class="train-name">${esc(trainTitle(info))}</div><div class="train-state">${esc(trainStateText(train))}</div></span>
      </li>`);
    }
    if (!items.length) {
      items.push(`<li class="empty">${esc(trains.length ? t("nothingFound") : t("noTrains"))}</li>`);
    }
    if (elsewhere > 0) {
      items.push(`<li class="empty other-dims">${esc(t("otherDimensions", elsewhere))}</li>`);
    }
    $("train-list").innerHTML = items.join("");
  }

  $("train-list").addEventListener("click", (event) => {
    const item = event.target.closest("li[data-id]");
    if (item) followTrain(item.dataset.id);
  });
  $("train-search").addEventListener("input", renderTrainList);

  function updateFollowBadge(train) {
    $("follow-name").textContent = trainTitle(train.info);
    $("follow-dot").style.background = vehicleColor(train.info);
    $("follow-state").textContent = trainStateText(train);
  }

  function followTrain(id) {
    const train = state.trains.get(id);
    if (!train) return;
    const head = leadPoint(train.to);
    if (!head) return;
    state.follow = id;
    if (head.d !== state.dim) selectDimension(head.d);
    map.setView(ll(head.p), Math.max(map.getZoom(), 1));
    updateFollowBadge(train);
    $("follow").hidden = false;
    renderTrainList();
  }

  function stopFollow() {
    state.follow = null;
    $("follow").hidden = true;
    renderTrainList();
  }

  $("follow-stop").addEventListener("click", stopFollow);
  map.on("dragstart", () => state.follow && stopFollow());

  // ------------------------------------------------------------------ dimensions and view

  function knownDimensions() {
    const dims = new Set(Object.keys(state.config.dimensions || {}));
    for (const d of state.networkDims) dims.add(d);
    for (const d of state.stationDims) dims.add(d);
    for (const tr of state.trains.values()) for (const c of tr.to) { if (c.ld) dims.add(c.ld); if (c.td) dims.add(c.td); }
    if (state.dim) dims.add(state.dim);
    return [...dims];
  }

  function updateDimensions() {
    const select = $("dimension");
    const dims = knownDimensions();
    const current = [...select.options].map((o) => o.value).join("|");
    if (current === dims.join("|")) return;
    select.innerHTML = dims.map((d) => `<option value="${esc(d)}">${esc(dimLabel(d))}</option>`).join("");
    select.value = state.dim;
  }

  function renderPlaces() {
    renderAreas();
    renderDepots();
    renderStations();
  }

  function selectDimension(dim, center) {
    if (!dim || dim === state.dim) {
      if (center) map.setView(ll(center), Math.max(map.getZoom(), 0));
      return;
    }
    state.dim = dim;
    state.selectedRoute = null;
    updateDimensions();
    $("dimension").value = dim;
    renderTracks();
    renderLines();
    renderPlaces();
    redrawAllTrains();
    renderTrainList();
    if (center) map.setView(ll(center), Math.max(map.getZoom(), 0));
    else fitDimension();
    saveView();
  }

  $("dimension").addEventListener("change", (e) => {
    if (state.follow) stopFollow();
    selectDimension(e.target.value);
  });

  function fitDimension() {
    const points = [];
    for (const track of state.network.tracks) {
      if (track.d === state.dim) points.push([track.p[1], track.p[0]], [track.p[track.p.length - 1], track.p[track.p.length - 2]]);
    }
    for (const s of state.stations) if (s.d === state.dim) points.push(ll(s.p));
    if (points.length) {
      map.fitBounds(L.latLngBounds(points), { paddingTopLeft: [panelRightEdge() + 40, 40], paddingBottomRight: [40, 40], maxZoom: 1 });
      return true;
    }
    return false;
  }

  function saveView() {
    if (!state.dim) return;
    const c = map.getCenter();
    const hash = `#${encodeURIComponent(state.dim)}/${Math.round(c.lng)}/${Math.round(c.lat)}/${map.getZoom()}`;
    history.replaceState(null, "", hash);
  }

  function loadView() {
    const parts = location.hash.replace(/^#/, "").split("/");
    if (parts.length !== 4) return false;
    const [dim, x, z, zoom] = [decodeURIComponent(parts[0]), Number(parts[1]), Number(parts[2]), Number(parts[3])];
    if (!dim || [x, z, zoom].some(Number.isNaN)) return false;
    state.dim = dim;
    map.setView([z, x], zoom);
    return true;
  }

  map.on("moveend", saveView);
  map.on("mousemove", (e) => {
    $("coords").textContent = `X: ${Math.round(e.latlng.lng)}  Z: ${Math.round(e.latlng.lat)}`;
  });

  // ------------------------------------------------------------------ layers

  function bindLayerToggle(id, layer) {
    $(id).addEventListener("change", (e) => (e.target.checked ? layer.addTo(map) : map.removeLayer(layer)));
  }
  bindLayerToggle("layer-tracks", layers.tracks);
  bindLayerToggle("layer-stations", layers.stations);
  bindLayerToggle("layer-trains", layers.trains);
  $("layer-line-colors").addEventListener("change", renderTracks);
  $("layer-station-labels").addEventListener("change", renderStations);
  $("layer-areas").addEventListener("change", renderAreas);
  $("layer-depots").addEventListener("change", () => {
    renderAreas();
    renderDepots();
  });
  $("layer-train-labels").addEventListener("change", redrawAllTrains);

  $("panel-toggle").addEventListener("click", () => $("panel").classList.toggle("collapsed"));
  if (window.innerWidth < 600) $("panel").classList.add("collapsed");

  // ------------------------------------------------------------------ transport type switches

  /** Transport types that exist in the data (switches are shown only for them). */
  function presentModes() {
    const modes = new Set();
    for (const track of state.network.tracks) modes.add(modeOfType(track.t));
    for (const route of state.network.routes) modes.add(route.m || "train");
    for (const station of state.stations) modes.add(station.m || "train");
    for (const train of state.trains.values()) modes.add(train.info.m || "train");
    return MODE_ORDER.filter((m) => modes.has(m)).concat([...modes].filter((m) => !MODE_ORDER.includes(m)));
  }

  function renderModeToggles() {
    const modes = presentModes();
    const key = modes.join("|");
    if ($("modes").dataset.key === key) return;
    $("modes").dataset.key = key;
    // One transport type only: nothing to choose, so the block stays hidden.
    $("modes-box").hidden = modes.length < 2;
    $("modes").innerHTML = modes
      .map((m) => `<label><input type="checkbox" data-mode="${esc(m)}"${modeVisible(m) ? " checked" : ""}> <span>${esc(t(`modeToggle_${m}`) === `modeToggle_${m}` ? prettyDimension(m) : t(`modeToggle_${m}`))}</span></label>`)
      .join("");
  }

  $("modes").addEventListener("change", (event) => {
    const mode = event.target.dataset?.mode;
    if (!mode) return;
    if (event.target.checked) hiddenModes.delete(mode);
    else hiddenModes.add(mode);
    saveHiddenModes();
    if (state.selectedRoute && !modeVisible(state.routes.get(state.selectedRoute)?.m)) state.selectedRoute = null;
    if (state.follow && !modeVisible(state.trains.get(state.follow)?.info.m)) stopFollow();
    renderTracks();
    renderLines();
    renderPlaces();
    redrawAllTrains();
    renderTrainList();
    refreshOpenPopup();
  });

  // ------------------------------------------------------------------ data from the server

  function setStatus(kind, text) {
    $("status").className = `status status-${kind}`;
    $("status-text").textContent = text;
  }

  function onNetwork(data) {
    state.network = { tracks: data.tracks || [], routes: data.routes || [] };
    state.routes = new Map(state.network.routes.map((r) => [r.id, r]));
    if (state.selectedRoute && !state.routes.has(state.selectedRoute)) state.selectedRoute = null;
    state.networkDims = new Set(state.network.tracks.map((tr) => tr.d));
    renderModeToggles();
    updateDimensions();
    renderTracks();
    renderLines();
    renderStations(); // station markers use route colours in their popups
    if (!state.fittedOnce) {
      state.fittedOnce = fitDimension();
    }
  }

  function onStations(data) {
    state.stations = data.stations || [];
    state.depots = data.depots || [];
    state.stationDims = new Set([...state.stations, ...state.depots].map((s) => s.d));
    renderModeToggles();
    updateDimensions();
    renderPlaces();
    if (!state.fittedOnce) {
      state.fittedOnce = fitDimension();
    }
  }

  let source = null;
  function connect() {
    if (source) source.close();
    source = new EventSource("api/stream");
    source.addEventListener("network", (e) => onNetwork(JSON.parse(e.data)));
    source.addEventListener("stations", (e) => onStations(JSON.parse(e.data)));
    source.addEventListener("trains", (e) => onTrains(JSON.parse(e.data)));
    source.onopen = () => setStatus("ok", t("online"));
    source.onerror = () => {
      if (source.readyState === EventSource.CLOSED) {
        setStatus("bad", t("closed"));
        setTimeout(connect, 10000);
      } else {
        setStatus("bad", t("reconnecting"));
      }
    };
  }

  function applyConfig(config) {
    state.config = Object.assign({ dimensions: {} }, config);
    const override = new URLSearchParams(location.search).get("lang");
    applyLanguage(override || state.config.language || "en");
    $("modes").dataset.key = ""; // labels in the chosen language
    const title = state.config.title || t("defaultTitle");
    $("title").textContent = title;
    document.title = title;
    $("layer-station-labels").checked = state.config.stationLabels !== false;
    $("layer-areas").checked = state.config.stationAreas !== false;
    $("layer-depots").checked = state.config.depots !== false;
    $("layer-line-colors").checked = state.config.lineColors !== false;
    if (state.config.dynmapUrl) {
      $("dynmap-link").href = state.config.dynmapUrl;
      $("dynmap-link").hidden = false;
    }
    if (state.config.mtrMapUrl) {
      $("mtr-link").href = state.config.mtrMapUrl;
      $("mtr-link").hidden = false;
    }
    if (state.config.version) $("version").textContent = `v${state.config.version}`;
    if (loadView()) {
      state.fittedOnce = true;
    } else {
      state.dim = state.config.defaultDimension || "minecraft:overworld";
    }
    updateDimensions();
  }

  fetch("api/config.json")
    .then((r) => (r.ok ? r.json() : {}))
    .catch(() => ({}))
    .then((config) => {
      applyConfig(config);
      connect();
    });
})();
