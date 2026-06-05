package com.mavenfetcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight HTTP API server.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/runs/latest} — stats from the most recent run</li>
 *   <li>{@code GET /api/changes}     — paginated list of changed packages</li>
 * </ul>
 */
public class ApiServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Starts the HTTP server and blocks the calling thread until the JVM exits.
     */
    public static void start(int port, String jdbcUrl, String user, String password)
            throws Exception {

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/api/runs/latest", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { send405(exchange); return; }
            try (ApiRepository repo = new ApiRepository(jdbcUrl, user, password)) {
                handleRunsLatest(exchange, repo);
            } catch (Exception e) {
                send500(exchange, e);
            }
        });

        server.createContext("/api/changes", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { send405(exchange); return; }
            try (ApiRepository repo = new ApiRepository(jdbcUrl, user, password)) {
                handleChanges(exchange, repo);
            } catch (Exception e) {
                send500(exchange, e);
            }
        });

        server.start();
        MavenPackageFetcher.log("API server listening on http://0.0.0.0:%d", port);
        MavenPackageFetcher.log("  GET /api/runs/latest");
        MavenPackageFetcher.log("  GET /api/changes[?since=&until=&cursor=&pageSize=100&includePrerelease=false]");
        Thread.currentThread().join();
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private static void handleRunsLatest(HttpExchange exchange, ApiRepository repo)
            throws Exception {

        ApiRepository.RunInfo info = repo.getLatestRunInfo();
        if (info == null) {
            sendJson(exchange, 404, JSON.createObjectNode().put("error", "No runs recorded yet"));
            return;
        }

        ObjectNode root = JSON.createObjectNode();
        root.put("runAt", info.until());

        ObjectNode window = root.putObject("window");
        window.put("since", info.since());
        window.put("until", info.until());

        ObjectNode stats = root.putObject("stats");
        stats.put("newPackages",       info.newPackages());
        stats.put("changedPackages",   info.changedPackages());
        stats.put("unchangedPackages", info.unchangedPackages());
        stats.put("totalProcessed",    info.totalProcessed());

        sendJson(exchange, 200, root);
    }

    private static void handleChanges(HttpExchange exchange, ApiRepository repo)
            throws Exception {

        Map<String, String> params = parseQuery(exchange.getRequestURI());

        // Resolve window: default to last run's window
        ApiRepository.RunInfo info = repo.getLatestRunInfo();
        if (info == null) {
            sendJson(exchange, 404, JSON.createObjectNode().put("error", "No runs recorded yet"));
            return;
        }

        Timestamp since = params.containsKey("since")
                ? Timestamp.from(Instant.parse(params.get("since")))
                : Timestamp.from(Instant.parse(info.since()));

        Timestamp until = params.containsKey("until")
                ? Timestamp.from(Instant.parse(params.get("until")))
                : Timestamp.from(Instant.parse(info.until()));

        ApiRepository.Cursor cursor = params.containsKey("cursor")
                ? ApiRepository.Cursor.decode(params.get("cursor"))
                : null;

        int pageSize = params.containsKey("pageSize")
                ? Math.min(Integer.parseInt(params.get("pageSize")), 1_000)
                : 100;

        boolean includePrerelease = "true".equalsIgnoreCase(params.get("includePrerelease"));

        ApiRepository.ChangePage page = repo.queryChanges(
                since, until, cursor, pageSize, includePrerelease);

        ApiRepository.WindowStats windowStats = repo.queryWindowStats(
                since, until, includePrerelease);

        // Build response
        ObjectNode root = JSON.createObjectNode();

        ObjectNode window = root.putObject("window");
        window.put("since", page.since());
        window.put("until", page.until());

        ObjectNode stats = root.putObject("stats");
        stats.put("newPackages",     windowStats.newPackages());
        stats.put("changedPackages", windowStats.changedPackages());
        stats.put("totalChanged",    windowStats.totalChanged());

        ArrayNode changes = root.putArray("changes");
        for (ApiRepository.ChangeEntry e : page.changes()) {
            ObjectNode entry = changes.addObject();
            entry.put("purl",        e.purl());
            entry.put("groupId",     e.groupId());
            entry.put("artifactId",  e.artifactId());
            entry.put("version",     e.version());
            entry.put("publishedAt", e.publishedAt());   // when artifact was published to Maven Central
            entry.put("detectedAt",  e.detectedAt());    // when our fetcher first saw this change
            entry.put("isPrerelease", e.isPrerelease());
            entry.put("changeType",  e.changeType());
        }

        if (page.nextCursor() != null) {
            root.put("nextCursor", page.nextCursor());
        } else {
            root.putNull("nextCursor");
        }
        root.put("hasMore", page.hasMore());

        sendJson(exchange, 200, root);
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private static void sendJson(HttpExchange exchange, int status, ObjectNode body)
            throws IOException {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void send405(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(405, -1);
        exchange.close();
    }

    private static void send500(HttpExchange exchange, Exception e) throws IOException {
        byte[] msg = ("Internal error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(500, msg.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(msg); }
        e.printStackTrace();
    }

    /** Parses {@code key=value&key2=value2} from the request URI query string. */
    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> params = new HashMap<>();
        String query = uri.getQuery();
        if (query == null || query.isBlank()) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) params.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return params;
    }
}
