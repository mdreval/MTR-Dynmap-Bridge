package com.igrobar.mtrdynmap.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Built-in web server of the live map (JDK only, no external libraries).
 *
 * <ul>
 *   <li>{@code /} — the map website (files from the jar: {@code web/}; they can be replaced with your own in
 *       {@code plugins/MTR-Dynmap-Bridge/web/}).</li>
 *   <li>{@code /api/stream} — Server-Sent Events stream: events {@code network}, {@code stations}, {@code trains}.
 *       Every viewer only receives what changed.</li>
 *   <li>{@code /api/config.json}, {@code /api/network.json}, {@code /api/stations.json}, {@code /api/trains.json} — one-off requests.</li>
 * </ul>
 *
 * The server never reads MTR data itself: the plugin hands it ready snapshots
 * ({@link #publishNetwork}, {@link #publishPlaces}, {@link #publishTrains}); JSON building and
 * serving happen on separate threads, so the map does not slow down the Minecraft server or MTR.
 */
public final class WebMapServer {
    private static final long HEARTBEAT_NANOS = TimeUnit.SECONDS.toNanos(15);

    private final Logger logger;
    private final ClassLoader resources;
    private final Path overrideFolder;
    private final int maxClients;
    private final String configJson;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = this.lock.newCondition();
    private final AtomicInteger clients = new AtomicInteger();
    private final Map<String, byte[]> bundledFiles = new ConcurrentHashMap<>();

    // Latest data (guarded by lock). A version only grows when the JSON really changed.
    private String networkJson = "{\"tracks\":[],\"routes\":[]}";
    private String stationsJson = "{\"stations\":[],\"depots\":[]}";
    private String trainsJson = "{\"time\":0,\"interval\":1000,\"trains\":[]}";
    private long networkVersion = 1;
    private long stationsVersion = 1;
    private long trainsVersion = 1;

    private volatile boolean stopped;
    private HttpServer http;
    private ExecutorService requestExecutor;
    private ExecutorService jsonExecutor;

    public WebMapServer(Logger logger, ClassLoader resources, Path overrideFolder, int maxClients, String configJson) {
        this.logger = logger;
        this.resources = resources;
        this.overrideFolder = overrideFolder;
        this.maxClients = Math.max(1, maxClients);
        this.configJson = configJson;
    }

    public void start(String bindAddress, int port) throws IOException {
        InetSocketAddress address = bindAddress == null || bindAddress.isBlank() || bindAddress.equals("0.0.0.0")
                ? new InetSocketAddress(port)
                : new InetSocketAddress(bindAddress, port);
        this.http = HttpServer.create(address, 32);
        // Virtual threads (Java 21): an open SSE connection costs almost nothing.
        this.requestExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.jsonExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MTR-Dynmap-Bridge-WebMap-json");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        this.http.setExecutor(this.requestExecutor);
        this.http.createContext("/api/stream", this::handleStream);
        this.http.createContext("/api/", this::handleApi);
        this.http.createContext("/", this::handleStatic);
        this.http.start();
    }

    public void stop() {
        this.stopped = true;
        this.lock.lock();
        try {
            this.changed.signalAll();
        } finally {
            this.lock.unlock();
        }
        if (this.http != null) {
            this.http.stop(0);
            this.http = null;
        }
        if (this.jsonExecutor != null) {
            this.jsonExecutor.shutdownNow();
        }
        if (this.requestExecutor != null) {
            this.requestExecutor.shutdownNow();
        }
    }

    public int viewers() {
        return this.clients.get();
    }

    // ------------------------------------------------------------------ publishing data (called from the plugin's update thread)

    public void publishNetwork(MapData.Network network) {
        this.submit(() -> {
            String json = MapData.networkJson(network);
            this.lock.lock();
            try {
                if (!json.equals(this.networkJson)) {
                    this.networkJson = json;
                    this.networkVersion++;
                    this.changed.signalAll();
                }
            } finally {
                this.lock.unlock();
            }
        });
    }

    public void publishPlaces(MapData.Places places) {
        this.submit(() -> {
            String json = MapData.placesJson(places);
            this.lock.lock();
            try {
                if (!json.equals(this.stationsJson)) {
                    this.stationsJson = json;
                    this.stationsVersion++;
                    this.changed.signalAll();
                }
            } finally {
                this.lock.unlock();
            }
        });
    }

    public void publishTrains(List<MapData.TrainInfo> trains, long time, long intervalMs) {
        this.submit(() -> {
            String json = MapData.trainsJson(trains, time, intervalMs);
            this.lock.lock();
            try {
                // The timestamp always changes, so only the train list is compared.
                if (!sameTrains(json, this.trainsJson)) {
                    this.trainsJson = json;
                    this.trainsVersion++;
                    this.changed.signalAll();
                }
            } finally {
                this.lock.unlock();
            }
        });
    }

    private static boolean sameTrains(String a, String b) {
        int ia = a.indexOf("\"trains\":");
        int ib = b.indexOf("\"trains\":");
        return ia >= 0 && ib >= 0 && a.regionMatches(ia, b, ib, Math.max(a.length() - ia, b.length() - ib));
    }

    private void submit(Runnable task) {
        if (this.stopped || this.jsonExecutor == null) {
            return;
        }
        try {
            this.jsonExecutor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable throwable) {
                    this.logger.log(Level.WARNING, "Web map: could not prepare data: " + throwable);
                }
            });
        } catch (RuntimeException ignored) {
            // the executor is already stopped
        }
    }

    // ------------------------------------------------------------------ HTTP

    private void handleStream(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "text/plain", "Method not allowed");
            return;
        }
        if (this.clients.incrementAndGet() > this.maxClients) {
            this.clients.decrementAndGet();
            sendText(exchange, 503, "text/plain", "Too many viewers, try again later");
            return;
        }
        try {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/event-stream; charset=utf-8");
            headers.set("Cache-Control", "no-cache, no-transform");
            headers.set("X-Accel-Buffering", "no");
            headers.set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("retry: 3000\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            long sentNetwork = 0;
            long sentStations = 0;
            long sentTrains = 0;
            while (!this.stopped) {
                String network = null;
                String stations = null;
                String trains = null;
                this.lock.lock();
                try {
                    long wait = HEARTBEAT_NANOS;
                    while (!this.stopped && wait > 0 && sentNetwork == this.networkVersion
                            && sentStations == this.stationsVersion && sentTrains == this.trainsVersion) {
                        wait = this.changed.awaitNanos(wait);
                    }
                    if (this.stopped) {
                        break;
                    }
                    if (sentNetwork != this.networkVersion) {
                        network = this.networkJson;
                        sentNetwork = this.networkVersion;
                    }
                    if (sentStations != this.stationsVersion) {
                        stations = this.stationsJson;
                        sentStations = this.stationsVersion;
                    }
                    if (sentTrains != this.trainsVersion) {
                        trains = this.trainsJson;
                        sentTrains = this.trainsVersion;
                    }
                } finally {
                    this.lock.unlock();
                }
                StringBuilder chunk = new StringBuilder();
                appendEvent(chunk, "network", network);
                appendEvent(chunk, "stations", stations);
                appendEvent(chunk, "trains", trains);
                if (chunk.isEmpty()) {
                    chunk.append(": ping\n\n");
                }
                out.write(chunk.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException | InterruptedException ignored) {
            // the viewer closed the page or the server is stopping
        } finally {
            this.clients.decrementAndGet();
            exchange.close();
        }
    }

    private static void appendEvent(StringBuilder chunk, String name, String data) {
        if (data != null) {
            chunk.append("event: ").append(name).append('\n').append("data: ").append(data).append("\n\n");
        }
    }

    private void handleApi(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body;
        this.lock.lock();
        try {
            body = switch (path) {
                case "/api/config.json" -> this.configJson;
                case "/api/network.json" -> this.networkJson;
                case "/api/stations.json" -> this.stationsJson;
                case "/api/trains.json" -> this.trainsJson;
                default -> null;
            };
        } finally {
            this.lock.unlock();
        }
        if (body == null) {
            sendText(exchange, 404, "text/plain", "Not found");
            return;
        }
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        sendText(exchange, 200, "application/json", body);
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "text/plain", "Method not allowed");
                return;
            }
            String relative = safeRelativePath(exchange.getRequestURI());
            if (relative == null) {
                sendText(exchange, 404, "text/plain", "Not found");
                return;
            }
            byte[] data = this.readFile(relative);
            if (data == null) {
                sendText(exchange, 404, "text/plain", "Not found");
                return;
            }
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType(relative));
            // "no-cache" + ETag: the browser keeps its copy but asks every time whether it changed.
            // Unchanged files cost a tiny 304 answer; after a plugin update the new files load at once.
            String etag = etag(data);
            headers.set("Cache-Control", "no-cache");
            headers.set("ETag", etag);
            String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (ifNoneMatch != null && ifNoneMatch.contains(etag)) {
                exchange.sendResponseHeaders(304, -1);
                return;
            }
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
        } finally {
            exchange.close();
        }
    }

    private static String etag(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return "\"" + Long.toHexString(crc.getValue()) + "-" + Integer.toHexString(data.length) + "\"";
    }

    /** Returns a path like "assets/app.js", or null if the request points outside the website folder. */
    private static String safeRelativePath(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            return "index.html";
        }
        if (path.contains("..") || path.contains("\\") || path.contains("\0")) {
            return null;
        }
        String relative = path.startsWith("/") ? path.substring(1) : path;
        if (relative.endsWith("/")) {
            relative = relative + "index.html";
        }
        return relative.matches("[A-Za-z0-9_./-]+") ? relative : null;
    }

    private byte[] readFile(String relative) throws IOException {
        if (this.overrideFolder != null) {
            Path file = this.overrideFolder.resolve(relative).normalize();
            if (file.startsWith(this.overrideFolder) && Files.isRegularFile(file)) {
                return Files.readAllBytes(file);
            }
        }
        byte[] cached = this.bundledFiles.get(relative);
        if (cached != null) {
            return cached;
        }
        try (InputStream in = this.resources.getResourceAsStream("web/" + relative)) {
            if (in == null) {
                return null;
            }
            byte[] data = in.readAllBytes();
            this.bundledFiles.put(relative, data);
            return data;
        }
    }

    private static String contentType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    private static void sendText(HttpExchange exchange, int status, String type, String body) throws IOException {
        try {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            String contentType = type.contains("charset") ? type : type + "; charset=utf-8";
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
        } finally {
            exchange.close();
        }
    }
}
