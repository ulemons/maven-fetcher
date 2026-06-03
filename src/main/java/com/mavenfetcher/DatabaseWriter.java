package com.mavenfetcher;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * Writes {@link PackageInfo} records into the {@code packages_universe} table
 * using JDBC batch upserts.
 *
 * <p>On conflict (purl), {@code rank_in_ecosystem} and {@code downloads_30d}
 * are always refreshed.  {@code dependent_packages_count} is updated only when
 * the incoming value is non-null (i.e. when {@code --download-stats} was used);
 * otherwise the existing DB value is preserved via {@code COALESCE}.
 * {@code is_critical} is intentionally left untouched — it is managed
 * exclusively by the {@code rank_packages_universe()} SQL function.
 *
 * <p>Connection parameters are read from the same environment variables used
 * by the TypeScript packages_worker:
 * <ul>
 *   <li>{@code CROWD_PACKAGES_DB_WRITE_HOST}</li>
 *   <li>{@code CROWD_PACKAGES_DB_PORT}         (default 5432)</li>
 *   <li>{@code CROWD_PACKAGES_DB_DATABASE}</li>
 *   <li>{@code CROWD_PACKAGES_DB_USERNAME}</li>
 *   <li>{@code CROWD_PACKAGES_DB_PASSWORD}</li>
 * </ul>
 */
public class DatabaseWriter implements AutoCloseable {

    /** Holds per-run change counts returned by {@link #queryRunStats}. */
    public record RunStats(long newPackages, long changedPackages, long unchangedPackages) {
        public long totalProcessed() { return newPackages + changedPackages + unchangedPackages; }
    }

    /** A package that was new or changed in this run. */
    public record ChangedPackage(String purl, String namespace, String name,
                                  String latestVersion, boolean isNew) {}


    /** Rows per JDBC batch + commit cycle. */
    private static final int BATCH_SIZE = 500;

    private static final String UPSERT_SQL = """
            INSERT INTO packages_universe
                (purl, ecosystem, namespace, name, rank_in_ecosystem, downloads_30d,
                 dependent_packages_count)
            VALUES (?, 'maven', ?, ?, ?, ?, ?)
            ON CONFLICT (purl) DO UPDATE SET
                rank_in_ecosystem        = EXCLUDED.rank_in_ecosystem,
                downloads_30d            = EXCLUDED.downloads_30d,
                -- Preserve an existing value when the new run did not fetch deps.dev data
                dependent_packages_count = COALESCE(
                    EXCLUDED.dependent_packages_count,
                    packages_universe.dependent_packages_count)
            """;

    private final Connection conn;

    // ── Construction ─────────────────────────────────────────────────────────

    /**
     * Opens a JDBC connection using the standard packages-db env vars.
     *
     * @throws IllegalStateException if a required env var is missing
     * @throws SQLException          if the connection cannot be established
     */
    public static DatabaseWriter fromEnv() throws SQLException {
        String host     = requireEnv("CROWD_PACKAGES_DB_WRITE_HOST");
        int    port     = Integer.parseInt(System.getenv().getOrDefault("CROWD_PACKAGES_DB_PORT", "5432"));
        String database = requireEnv("CROWD_PACKAGES_DB_DATABASE");
        String user     = requireEnv("CROWD_PACKAGES_DB_USERNAME");
        String password = requireEnv("CROWD_PACKAGES_DB_PASSWORD");
        return new DatabaseWriter(host, port, database, user, password);
    }

    /** Opens a connection using an explicit JDBC URL, bypassing env vars. */
    public static DatabaseWriter fromUrl(String jdbcUrl, String user, String password)
            throws SQLException {
        return new DatabaseWriter(jdbcUrl, user, password);
    }

    public DatabaseWriter(String host, int port, String database, String user, String password)
            throws SQLException {
        this(String.format("jdbc:postgresql://%s:%d/%s", host, port, database), user, password);
    }

    private DatabaseWriter(String jdbcUrl, String user, String password) throws SQLException {
        this.conn = DriverManager.getConnection(jdbcUrl, user, password);
        this.conn.setAutoCommit(false);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Upserts all packages in batches of {@value BATCH_SIZE}.
     * Commits after each batch and logs progress.
     *
     * @return total number of rows affected (inserted + updated)
     */
    public int upsert(List<PackageInfo> packages) throws SQLException {
        int total = 0;

        try (PreparedStatement stmt = conn.prepareStatement(UPSERT_SQL)) {

            for (int i = 0; i < packages.size(); i++) {
                PackageInfo pkg = packages.get(i);

                stmt.setString(1, pkg.getPurl());
                stmt.setString(2, pkg.getGroupId());
                stmt.setString(3, pkg.getArtifactId());
                stmt.setInt(4, pkg.getRank());
                // downloads_30d: no public Maven download API exists; always write NULL.
                stmt.setNull(5, java.sql.Types.BIGINT);
                // Write NULL when not fetched (-1); COALESCE in SQL keeps existing value.
                if (pkg.getDependentPackagesCount() >= 0) {
                    stmt.setLong(6, pkg.getDependentPackagesCount());
                } else {
                    stmt.setNull(6, java.sql.Types.BIGINT);
                }
                stmt.addBatch();

                boolean lastRow      = (i == packages.size() - 1);
                boolean batchFull    = ((i + 1) % BATCH_SIZE == 0);

                if (batchFull || lastRow) {
                    int[] counts = stmt.executeBatch();
                    conn.commit();

                    for (int c : counts)
                        total += (c == java.sql.Statement.SUCCESS_NO_INFO) ? 1 : Math.max(0, c);

                    if (lastRow) {
                        MavenPackageFetcher.logInPlaceDone();
                    } else {
                        MavenPackageFetcher.logInPlace("  DB packages_universe  %,d / %,d  (commit every %d)",
                                i + 1, packages.size(), BATCH_SIZE);
                    }
                }
            }
        }

        return total;
    }

    // ── packages — Tier-2 baseline ───────────────────────────────────────────

    /**
     * Upserts baseline rows into {@code packages} with the data the Maven Index
     * already provides: coordinates, registry URL, latest version, version count,
     * and first/latest release timestamps.
     *
     * <p>On conflict (purl):
     * <ul>
     *   <li>Structural fields ({@code versions_count}, {@code latest_release_at})
     *       are always refreshed.</li>
     *   <li>{@code latest_version} is preserved if a {@code pom_fetcher} run has
     *       already set it (i.e. the row was enriched); otherwise it is updated
     *       from the index.</li>
     *   <li>Enriched fields ({@code description}, {@code licenses}, …) are never
     *       touched here — those belong exclusively to the pom-fetcher.</li>
     * </ul>
     *
     * @return total number of rows affected (inserted + updated)
     */
    public int upsertPackagesBaseline(List<PackageInfo> packages) throws SQLException {
        final String sql = """
                INSERT INTO packages (
                    purl,
                    ecosystem,
                    namespace,
                    name,
                    registry_url,
                    latest_version,
                    versions_count,
                    first_release_at,
                    latest_release_at,
                    ingestion_source,
                    last_synced_at
                ) VALUES (?, 'maven', ?, ?, ?, ?, ?, ?, ?, 'maven_index', NOW())
                ON CONFLICT (purl) DO UPDATE SET
                    versions_count    = EXCLUDED.versions_count,
                    latest_release_at = EXCLUDED.latest_release_at,
                    -- Preserve the version already confirmed by pom_fetcher; otherwise update
                    latest_version    = CASE
                                          WHEN packages.ingestion_source LIKE 'pom_fetcher%'
                                          THEN packages.latest_version
                                          ELSE COALESCE(EXCLUDED.latest_version, packages.latest_version)
                                        END,
                    last_synced_at    = NOW()
                """;

        int total = 0;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < packages.size(); i++) {
                PackageInfo pkg = packages.get(i);

                stmt.setString(1, pkg.getPurl());
                stmt.setString(2, pkg.getGroupId());
                stmt.setString(3, pkg.getArtifactId());
                stmt.setString(4, pkg.getRegistryUrl());
                stmt.setString(5, pkg.getLatestVersion());          // null OK → NULL in DB
                stmt.setInt   (6, pkg.getVersionsCount());
                stmt.setTimestamp(7, toTimestamp(pkg.getFirstReleaseMs()));
                stmt.setTimestamp(8, toTimestamp(pkg.getLatestReleaseMs()));
                stmt.addBatch();

                boolean lastRow   = (i == packages.size() - 1);
                boolean batchFull = ((i + 1) % BATCH_SIZE == 0);

                if (batchFull || lastRow) {
                    int[] counts = stmt.executeBatch();
                    conn.commit();
                    for (int c : counts)
                        total += (c == java.sql.Statement.SUCCESS_NO_INFO) ? 1 : Math.max(0, c);

                    if (lastRow) {
                        MavenPackageFetcher.logInPlaceDone();
                    } else {
                        MavenPackageFetcher.logInPlace("  DB packages  %,d / %,d  (commit every %d)",
                                i + 1, packages.size(), BATCH_SIZE);
                    }
                }
            }
        }

        return total;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Converts epoch-ms to a {@link Timestamp}; returns {@code null} when {@code 0}. */
    private static Timestamp toTimestamp(long epochMs) {
        return epochMs == 0L ? null : new Timestamp(epochMs);
    }

    // ── maven_packages — change-tracking table ───────────────────────────────

    /**
     * Upserts rows into {@code maven_packages}, the change-tracking table that
     * records when each artifact was first seen and when it last changed.
     *
     * <p>Update rules on conflict (purl):
     * <ul>
     *   <li>{@code last_synced_at} is always set to {@code NOW()} — records that
     *       the fetcher processed this package in the current run.</li>
     *   <li>{@code last_updated_at} is set to {@code NOW()} <em>only</em> when
     *       {@code latest_version} or {@code versions_count} differs from the
     *       stored value — i.e. something actually changed on Maven Central.</li>
     *   <li>{@code first_seen_at} is never changed after the initial insert.</li>
     * </ul>
     *
     * @return total number of rows affected (inserted + updated)
     */
    public int upsertMavenPackages(List<PackageInfo> packages) throws SQLException {
        final String sql = """
                INSERT INTO maven_packages (
                    purl,
                    ecosystem,
                    namespace,
                    name,
                    registry_url,
                    latest_version,
                    versions_count,
                    first_release_at,
                    latest_release_at,
                    first_seen_at,
                    last_updated_at,
                    last_synced_at
                ) VALUES (?, 'maven', ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), NOW())
                ON CONFLICT (purl) DO UPDATE SET
                    latest_version    = COALESCE(EXCLUDED.latest_version,
                                                 maven_packages.latest_version),
                    versions_count    = EXCLUDED.versions_count,
                    latest_release_at = EXCLUDED.latest_release_at,
                    last_synced_at    = NOW(),
                    -- Advance last_updated_at only when something actually changed
                    last_updated_at   = CASE
                        WHEN maven_packages.latest_version  IS DISTINCT FROM EXCLUDED.latest_version
                          OR maven_packages.versions_count  IS DISTINCT FROM EXCLUDED.versions_count
                        THEN NOW()
                        ELSE maven_packages.last_updated_at
                    END
                """;

        int total = 0;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < packages.size(); i++) {
                PackageInfo pkg = packages.get(i);

                stmt.setString   (1, pkg.getPurl());
                stmt.setString   (2, pkg.getGroupId());
                stmt.setString   (3, pkg.getArtifactId());
                stmt.setString   (4, pkg.getRegistryUrl());
                stmt.setString   (5, pkg.getLatestVersion());           // null OK → NULL in DB
                stmt.setInt      (6, pkg.getVersionsCount());
                stmt.setTimestamp(7, toTimestamp(pkg.getFirstReleaseMs()));
                stmt.setTimestamp(8, toTimestamp(pkg.getLatestReleaseMs()));
                stmt.addBatch();

                boolean lastRow   = (i == packages.size() - 1);
                boolean batchFull = ((i + 1) % BATCH_SIZE == 0);

                if (batchFull || lastRow) {
                    int[] counts = stmt.executeBatch();
                    conn.commit();
                    for (int c : counts)
                        total += (c == java.sql.Statement.SUCCESS_NO_INFO) ? 1 : Math.max(0, c);

                    if (lastRow) {
                        MavenPackageFetcher.logInPlaceDone();
                    } else {
                        MavenPackageFetcher.logInPlace(
                                "  DB maven_packages  %,d / %,d  (commit every %d)",
                                i + 1, packages.size(), BATCH_SIZE);
                    }
                }
            }
        }

        return total;
    }

    // ── Server clock ─────────────────────────────────────────────────────────

    /**
     * Returns the current timestamp from the DB server.
     * Used as {@code runStart} so it is guaranteed to precede the {@code NOW()}
     * values written during the subsequent upsert.
     */
    public Timestamp fetchServerNow() throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT NOW()");
             ResultSet rs = stmt.executeQuery()) {
            rs.next();
            return rs.getTimestamp(1);
        }
    }

    // ── Run statistics ────────────────────────────────────────────────────────

    /**
     * Returns change counts for the current run by inspecting timestamps.
     *
     * <ul>
     *   <li><b>new</b>: first_seen_at &ge; runStart (inserted this run)</li>
     *   <li><b>changed</b>: existing rows where last_updated_at &ge; runStart
     *       (latest_version or versions_count advanced)</li>
     *   <li><b>unchanged</b>: processed this run but nothing changed</li>
     * </ul>
     *
     * @param runStart timestamp recorded just before {@code upsertMavenPackages} was called
     */
    public RunStats queryRunStats(Timestamp runStart) throws SQLException {
        final String sql = """
                SELECT
                    COUNT(*) FILTER (WHERE first_seen_at    >= ?)                    AS new_count,
                    COUNT(*) FILTER (WHERE first_seen_at     < ? AND last_updated_at >= ?) AS changed_count,
                    COUNT(*) FILTER (WHERE last_synced_at   >= ? AND last_updated_at  < ?) AS unchanged_count
                FROM maven_packages
                WHERE last_synced_at >= ?
                """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, runStart);
            stmt.setTimestamp(2, runStart);
            stmt.setTimestamp(3, runStart);
            stmt.setTimestamp(4, runStart);
            stmt.setTimestamp(5, runStart);
            stmt.setTimestamp(6, runStart);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return new RunStats(rs.getLong("new_count"),
                                    rs.getLong("changed_count"),
                                    rs.getLong("unchanged_count"));
            }
        }
    }

    /**
     * Returns the list of packages that were new or changed in this run,
     * ordered by: new packages first, then changed; within each group sorted
     * alphabetically by purl.
     *
     * @param runStart timestamp recorded just before {@code upsertMavenPackages} was called
     */
    public List<ChangedPackage> queryChangedPackages(Timestamp runStart) throws SQLException {
        final String sql = """
                SELECT purl, namespace, name, latest_version,
                       (first_seen_at >= ?) AS is_new
                FROM maven_packages
                WHERE last_updated_at >= ?
                ORDER BY is_new DESC, purl
                """;
        List<ChangedPackage> result = new java.util.ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, runStart);
            stmt.setTimestamp(2, runStart);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new ChangedPackage(
                            rs.getString("purl"),
                            rs.getString("namespace"),
                            rs.getString("name"),
                            rs.getString("latest_version"),
                            rs.getBoolean("is_new")));
                }
            }
        }
        return result;
    }

    /**
     * Appends a row to {@code maven_fetcher_runs} with the stats for this run.
     * Commits immediately so the record is visible even if the caller crashes.
     */
    public void insertRunRecord(RunStats stats) throws SQLException {
        final String sql = """
                INSERT INTO maven_fetcher_runs
                    (new_packages, changed_packages, unchanged_packages, total_processed)
                VALUES (?, ?, ?, ?)
                """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, stats.newPackages());
            stmt.setLong(2, stats.changedPackages());
            stmt.setLong(3, stats.unchangedPackages());
            stmt.setLong(4, stats.totalProcessed());
            stmt.executeUpdate();
            conn.commit();
        }
    }

    // ── AutoCloseable ─────────────────────────────────────────────────────────

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String requireEnv(String name) {
        String val = System.getenv(name);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException(
                    "Missing required environment variable: " + name);
        }
        return val;
    }
}
