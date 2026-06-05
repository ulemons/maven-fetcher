package com.mavenfetcher;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Read-only DB access layer for the REST API endpoints.
 */
public class ApiRepository implements AutoCloseable {

    // ── Data types ────────────────────────────────────────────────────────────

    public record RunInfo(
            String since, String until,
            long newPackages, long changedPackages,
            long unchangedPackages, long totalProcessed) {}

    public record ChangeEntry(
            String purl, String groupId, String artifactId,
            String version, String publishedAt, String detectedAt,
            boolean isPrerelease, String changeType) {}

    public record ChangePage(
            String since, String until,
            List<ChangeEntry> changes,
            String nextCursor, boolean hasMore) {}

    /** Aggregate counts for a time window (new + changed packages only). */
    public record WindowStats(long newPackages, long changedPackages, long totalChanged) {}

    /** Keyset cursor: last-seen (last_updated_at, purl) pair. */
    public record Cursor(Timestamp ts, String purl) {

        String encode() {
            String raw = ts.getTime() + "|" + purl;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String encoded) {
            String raw = new String(Base64.getUrlDecoder().decode(encoded),
                    StandardCharsets.UTF_8);
            int sep = raw.indexOf('|');
            return new Cursor(
                    new Timestamp(Long.parseLong(raw.substring(0, sep))),
                    raw.substring(sep + 1));
        }
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    /** SQL fragment that excludes SNAPSHOT / alpha / beta / RC / milestone versions. */
    private static final String PRERELEASE_EXCLUSION = """
              AND (latest_version IS NULL OR NOT (
                    latest_version ILIKE '%-SNAPSHOT'
                 OR latest_version ILIKE '%-alpha%'
                 OR latest_version ILIKE '%-beta%'
                 OR latest_version ~* '-rc[0-9]*$'
                 OR latest_version ~* '-m[0-9]+$'
              ))
            """;

    // ── Construction ──────────────────────────────────────────────────────────

    private final Connection conn;

    public ApiRepository(String jdbcUrl, String user, String password) throws SQLException {
        conn = DriverManager.getConnection(jdbcUrl, user, password);
        conn.setReadOnly(true);
        conn.setAutoCommit(true);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Returns stats from the most recent run and the window it covers.
     * {@code since} = previous run's {@code run_at}; {@code until} = latest run's.
     */
    public RunInfo getLatestRunInfo() throws SQLException {
        final String sql = """
                SELECT run_at, new_packages, changed_packages, unchanged_packages, total_processed
                FROM   maven_fetcher_runs
                ORDER  BY run_at DESC
                LIMIT  2
                """;
        try (Statement stmt = conn.createStatement();
             ResultSet rs   = stmt.executeQuery(sql)) {

            if (!rs.next()) return null;

            Timestamp until          = rs.getTimestamp("run_at");
            long newPkgs             = rs.getLong("new_packages");
            long changedPkgs         = rs.getLong("changed_packages");
            long unchangedPkgs       = rs.getLong("unchanged_packages");
            long total               = rs.getLong("total_processed");
            Timestamp since          = rs.next() ? rs.getTimestamp("run_at") : new Timestamp(0);

            return new RunInfo(isoStr(since), isoStr(until),
                    newPkgs, changedPkgs, unchangedPkgs, total);
        }
    }

    /**
     * Returns a paginated page of packages that were new or changed in the window.
     *
     * @param since            window start (exclusive)
     * @param until            window end (inclusive)
     * @param cursor           keyset cursor from the previous page; null = first page
     * @param pageSize         rows per page (capped at 1 000)
     * @param includePrerelease if false, SNAPSHOT / alpha / beta / RC / milestone versions excluded
     */
    public ChangePage queryChanges(Timestamp since, Timestamp until,
                                    Cursor cursor, int pageSize,
                                    boolean includePrerelease) throws SQLException {
        int limit = Math.min(pageSize, 1_000);

        StringBuilder sql = new StringBuilder("""
                SELECT purl, namespace, name, latest_version,
                       latest_release_at, first_seen_at, last_updated_at
                FROM   maven_packages
                WHERE  last_updated_at >  ?
                  AND  last_updated_at <= ?
                """);

        if (!includePrerelease) {
            sql.append(PRERELEASE_EXCLUSION);
        }

        if (cursor != null) {
            sql.append("""
                      AND (last_updated_at > ?
                        OR (last_updated_at = ? AND purl > ?))
                    """);
        }

        sql.append("ORDER BY last_updated_at, purl\nLIMIT ?\n");

        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int p = 1;
            stmt.setTimestamp(p++, since);
            stmt.setTimestamp(p++, until);
            if (cursor != null) {
                stmt.setTimestamp(p++, cursor.ts());
                stmt.setTimestamp(p++, cursor.ts());
                stmt.setString  (p++, cursor.purl());
            }
            stmt.setInt(p, limit + 1);  // fetch one extra to detect hasMore

            List<ChangeEntry> entries = new ArrayList<>(limit);
            Timestamp lastTs   = null;
            String    lastPurl = null;
            boolean   hasMore  = false;

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    // The (limit+1)-th row signals hasMore — do not add it to results
                    if (entries.size() == limit) {
                        hasMore = true;
                        break;
                    }

                    String basePurl     = rs.getString("purl");
                    String version      = rs.getString("latest_version");
                    Timestamp firstSeen = rs.getTimestamp("first_seen_at");
                    lastTs   = rs.getTimestamp("last_updated_at");
                    lastPurl = basePurl;

                    entries.add(new ChangeEntry(
                            version != null ? basePurl + "@" + version : basePurl,
                            rs.getString("namespace"),
                            rs.getString("name"),
                            version,
                            isoStr(rs.getTimestamp("latest_release_at")),
                            isoStr(lastTs),
                            isPrerelease(version),
                            firstSeen.after(since) ? "new_artifact" : "new_version"));
                }

                String nextCursor = (hasMore && lastTs != null)
                        ? new Cursor(lastTs, lastPurl).encode()
                        : null;

                return new ChangePage(isoStr(since), isoStr(until), entries, nextCursor, hasMore);
            }
        }
    }

    /**
     * Computes aggregate stats for packages that were new or changed in the window.
     *
     * <p>A package is "new" when its {@code first_seen_at > since}; otherwise it is
     * "changed" (an existing artifact that received a new version). {@code unchangedPackages}
     * is intentionally omitted because {@code last_synced_at} only reflects the most recent
     * sync and cannot be reconstructed for arbitrary historical windows.
     *
     * @param since            window start (exclusive)
     * @param until            window end (inclusive)
     * @param includePrerelease if false, SNAPSHOT / alpha / beta / RC / milestone versions excluded
     */
    public WindowStats queryWindowStats(Timestamp since, Timestamp until,
                                        boolean includePrerelease) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT
                  COUNT(*) FILTER (WHERE first_seen_at >  ?) AS new_count,
                  COUNT(*) FILTER (WHERE first_seen_at <= ?) AS changed_count
                FROM maven_packages
                WHERE last_updated_at >  ?
                  AND last_updated_at <= ?
                """);

        if (!includePrerelease) {
            sql.append(PRERELEASE_EXCLUSION);
        }

        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            stmt.setTimestamp(1, since);  // FILTER: new
            stmt.setTimestamp(2, since);  // FILTER: changed
            stmt.setTimestamp(3, since);  // window start
            stmt.setTimestamp(4, until);  // window end

            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                long newPkgs     = rs.getLong("new_count");
                long changedPkgs = rs.getLong("changed_count");
                return new WindowStats(newPkgs, changedPkgs, newPkgs + changedPkgs);  // totalChanged = new + changed only
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String isoStr(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }

    private static boolean isPrerelease(String version) {
        if (version == null) return false;
        String v = version.toLowerCase();
        return v.endsWith("-snapshot")
                || v.contains("-alpha")
                || v.contains("-beta")
                || v.matches(".*-rc[0-9]*")
                || v.matches(".*-m[0-9]+");
    }

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
    }
}
