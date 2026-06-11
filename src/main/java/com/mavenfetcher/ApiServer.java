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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
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
        MavenPackageFetcher.log("  GET /api/changes[?since=yyyy-mm-dd&until=yyyy-mm-dd&cursor=&pageSize=100&includePrerelease=false]");
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

        Map<String, String> params = parseQuery(exchange.getRequestURI());
        boolean includePrerelease = "true".equalsIgnoreCase(params.get("includePrerelease"));

        LocalDate sinceDate = Instant.parse(info.since()).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate untilDate = Instant.parse(info.until()).atZone(ZoneOffset.UTC).toLocalDate();

        // Recompute new/changed over the calendar window (same logic as /api/changes)
        // so both endpoints return consistent counts for the same since/until.
        Timestamp since = Timestamp.from(sinceDate.atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp until = Timestamp.from(untilDate.atTime(LocalTime.MAX).atZone(ZoneOffset.UTC).toInstant());
        ApiRepository.WindowStats windowStats = repo.queryWindowStats(since, until, includePrerelease);

        ObjectNode root = JSON.createObjectNode();
        root.put("runAt", untilDate.toString());

        ObjectNode window = root.putObject("window");
        window.put("since", sinceDate.toString());
        window.put("until", untilDate.toString());

        ObjectNode stats = root.putObject("stats");
        // new/changed: recomputed from last_updated_at over the calendar window (matches /api/changes)
        stats.put("newPackages",       windowStats.newPackages());
        stats.put("changedPackages",   windowStats.changedPackages());
        // unchanged/totalProcessed: still per-run counters from maven_fetcher_runs
        stats.put("unchangedPackages", info.unchangedPackages());
        stats.put("totalProcessed",    info.totalProcessed());

        sendJson(exchange, 200, root);
    }

    private static void handleChanges(HttpExchange exchange, ApiRepository repo)
            throws Exception {

        Map<String, String> params = parseQuery(exchange.getRequestURI());

        // Resolve window: default to last run's window, date-granularity only
        ApiRepository.RunInfo info = repo.getLatestRunInfo();
        if (info == null) {
            sendJson(exchange, 404, JSON.createObjectNode().put("error", "No runs recorded yet"));
            return;
        }

        LocalDate sinceDate = params.containsKey("since")
                ? parseDate(params.get("since"))
                : Instant.parse(info.since()).atZone(ZoneOffset.UTC).toLocalDate();

        LocalDate untilDate;
        if (params.containsKey("until")) {
            untilDate = parseDate(params.get("until"));
        } else if (params.containsKey("since")) {
            // since-only: scope the window to just that day
            untilDate = sinceDate;
        } else {
            untilDate = Instant.parse(info.until()).atZone(ZoneOffset.UTC).toLocalDate();
        }

        // since = start of day (exclusive >); until = last instant of day (inclusive <=)
        Timestamp since = Timestamp.from(sinceDate.atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp until = Timestamp.from(untilDate.atTime(LocalTime.MAX).atZone(ZoneOffset.UTC).toInstant());

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
        window.put("since", sinceDate.toString());
        window.put("until", untilDate.toString());

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

    /** Accepts {@code yyyy-MM-dd} or a full ISO-8601 instant; always returns a date. */
    private static LocalDate parseDate(String value) {
        if (value.length() == 10) {
            return LocalDate.parse(value);
        }
        return Instant.parse(value).atZone(ZoneOffset.UTC).toLocalDate();
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
