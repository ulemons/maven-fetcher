package com.mavenfetcher;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.time.LocalDate;
import org.flywaydb.core.Flyway;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * <h1>Maven Package Fetcher</h1>
 *
 * <p>Entry-point application that retrieves <em>real</em> Maven artifacts and
 * writes them to a file in the SQL-values format expected by the caller:
 *
 * <pre>
 *   ('pkg:maven/org.springframework/spring-core', 'maven', 'org.springframework', 'spring-core', 1, 9856234, true),
 *   ('pkg:maven/org.apache.commons/commons-lang3', 'maven', 'org.apache.commons', 'commons-lang3', 2, 9723156, true),
 *   …
 * </pre>
 *
 * <h2>Modes</h2>
 * <ul>
 *   <li><strong>Index (default)</strong> – downloads and queries the real Nexus
 *       Lucene index published by Maven Central (~3 GB on first run, incremental
 *       afterwards).  Cached at {@code ~/.maven-fetcher/index}.</li>
 *   <li><strong>REST ({@code --use-rest})</strong> – queries the Maven Central
 *       Solr API.  Fast (seconds), no large download required, but limited to
 *       ~10 000 results and does not support {@code -n all}.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -jar target/maven-fetcher-1.0.0.jar [options]
 *
 *   -n | --count  &lt;N|all&gt;    Number of packages to fetch  (default: 100)
 *   -o | --output &lt;file&gt;     Output file path             (default: packages.sql)
 *        --start-rank &lt;N&gt;    First rank value             (default: 1)
 *        --use-rest           Use Maven Central REST API instead of the local index
 *        --skip-write-db      Skip the JDBC upsert (DB write is on by default;
 *                             writes only to maven_packages)
 *        --help               Print this help
 *
 *   Positional first argument is treated as --count if it is a plain integer or "all".
 *
 * Examples:
 *   java -jar maven-fetcher.jar 50
 *   java -jar maven-fetcher.jar -n 500 -o out.sql
 *   java -jar maven-fetcher.jar -n all --skip-write-db -o all_packages.sql
 *   java -jar maven-fetcher.jar -n 1000 --download-stats --concurrency 20
 *   java -jar maven-fetcher.jar --use-rest -n 100 -o rest_packages.sql
 * </pre>
 */
public class MavenPackageFetcher {

    // ── Defaults ─────────────────────────────────────────────────────────────
    private static final int    DEFAULT_COUNT      = 100;
    private static final String DEFAULT_OUTPUT     = "packages.sql";
    private static final int    DEFAULT_START_RANK = 1;

    /** Sentinel value for "-n all" — fetch every artifact in the index. */
    static final int COUNT_ALL = Integer.MAX_VALUE;

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {

        // ── Parse command-line arguments ──────────────────────────────────
        int     count         = DEFAULT_COUNT;
        String  output        = DEFAULT_OUTPUT;
        int     startRank     = DEFAULT_START_RANK;
        boolean useIndex      = true;   // Apache Maven Indexer is the default mode
        boolean writeDb       = true;   // write to DB by default; use --skip-write-db to disable
        boolean downloadStats = false;
        int     concurrency   = 1;
        String  dbUrl         = null;   // explicit JDBC URL; if null, falls back to env vars
        String  dbUser        = null;
        String  dbPassword    = null;
        String  reportFile    = null;   // path to write the detailed change report
        boolean serve         = false;  // start the API server instead of fetching
        int     apiPort       = 8080;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-n", "--count"      -> count       = parseCount(args[++i]);
                case "-o", "--output"     -> output      = args[++i];
                case "--start-rank"       -> startRank   = Integer.parseInt(args[++i]);
                case "--use-rest"         -> useIndex    = false;
                case "--use-index"        -> useIndex    = true;   // kept for backwards-compatibility
                case "--skip-write-db"    -> writeDb     = false;
                case "--download-stats"   -> downloadStats = true;
                case "--concurrency"      -> concurrency = parseConcurrency(args[++i]);
                case "--db-url"           -> dbUrl       = args[++i];
                case "--db-user"          -> dbUser      = args[++i];
                case "--db-password"      -> dbPassword  = args[++i];
                case "--report-file"      -> reportFile  = args[++i];
                case "--serve"            -> serve       = true;
                case "--port"             -> apiPort     = Integer.parseInt(args[++i]);
                case "--help", "-h"       -> { printHelp(); return; }
                default -> {
                    // Accept a bare integer or "all" as the count (positional first arg)
                    String a = args[i];
                    if (a.matches("\\d+") || a.equalsIgnoreCase("all")) {
                        count = parseCount(a);
                    } else {
                        System.err.println("Unknown option: " + a);
                        printHelp();
                        System.exit(1);
                    }
                }
            }
        }

        // ── API server mode ───────────────────────────────────────────────
        if (serve) {
            String sUrl  = dbUrl      != null ? dbUrl      : buildEnvUrl();
            String sUser = dbUser     != null ? dbUser     : System.getenv().getOrDefault("CROWD_PACKAGES_DB_USERNAME", "maven");
            String sPass = dbPassword != null ? dbPassword : System.getenv().getOrDefault("CROWD_PACKAGES_DB_PASSWORD", "maven");
            ApiServer.start(apiPort, sUrl, sUser, sPass);
            return;
        }

        printBanner();
        log("Mode           : %s", useIndex ? "Apache Maven Indexer (default)" : "REST API (--use-rest)");
        log("Count          : %s", count == COUNT_ALL ? "ALL" : String.format("%,d", count));
        log("Start rank     : %d", startRank);
        log("Output file    : %s", output);
        log("Write to DB    : %s", writeDb
                ? (dbUrl != null ? "yes (" + dbUrl + ")" : "yes (env vars)")
                : "no (--skip-write-db)");
        log("Download stats : %s", downloadStats
                ? String.format("yes (--download-stats, concurrency=%d)", concurrency)
                : "no (rank-based estimate)");
        System.out.println();

        // ── Fetch packages ────────────────────────────────────────────────
        List<PackageInfo> packages;
        long t0 = System.currentTimeMillis();

        if (useIndex) {
            if (count == COUNT_ALL) {
                log("⚠  -n all: ALL artifacts will be extracted from the index (~10 M).");
                log("   This operation may take 15–30 minutes.");
                System.out.println();
            }
            log("⚠  Maven Indexer mode: the full index (~3 GB) will be downloaded");
            log("   on the first run.  This can take 30–60 minutes depending on your");
            log("   connection.  Subsequent runs use incremental updates (~few MB).");
            log("   Index is cached at:  ~/.maven-fetcher/index");
            System.out.println();

            // When --download-stats is active we need a larger candidate pool so that
            // after re-ranking by directDependentCount the final top-N contains truly
            // popular packages and not just prolific publishers.
            //
            // Strategy: fetch OVERSAMPLE_FACTOR × count candidates from the index
            // (capped at MAX_CANDIDATES to keep the deps.dev enrichment feasible),
            // enrich them all with deps.dev, sort by directDependentCount DESC, then
            // keep only the requested count and re-assign ranks.
            final int OVERSAMPLE_FACTOR = 20;
            final int MAX_CANDIDATES    = 50_000;

            int candidateCount = count; // default: no oversampling
            if (downloadStats && count != COUNT_ALL) {
                candidateCount = Math.min(count * OVERSAMPLE_FACTOR, MAX_CANDIDATES);
                log("Oversampling: fetching %,d index candidates for final top-%,d"
                        + " (will re-rank by directDependentCount after deps.dev enrichment)",
                        candidateCount, count);
                System.out.println();
            }

            try (MavenIndexClient indexClient = new MavenIndexClient()) {
                packages = indexClient.fetchPackages(candidateCount, startRank);
            }
        } else {
            if (count == COUNT_ALL) {
                System.err.println("ERROR: -n all is only supported with --use-index.");
                System.err.println("       The REST API has a pagination limit (~10 000 results).");
                System.exit(1);
                return;
            }
            MavenCentralRestClient restClient = new MavenCentralRestClient();
            packages = restClient.fetchPackages(count, startRank);
        }

        long elapsedSec = (System.currentTimeMillis() - t0) / 1000;

        // ── Enrich with directDependentCount from deps.dev (optional) ─────
        if (downloadStats) {
            System.out.println();
            DepsDevClient depsDevClient = new DepsDevClient();
            packages = depsDevClient.enrichWithDirectDependents(packages, concurrency);
            System.out.println();

            // When oversampling was used, re-rank by directDependentCount DESC and trim.
            if (useIndex && count != COUNT_ALL && packages.size() > count) {
                log("Re-ranking %,d candidates by directDependentCount DESC → keeping top %,d …",
                        packages.size(), count);

                // Sort descending by directDependentCount (-1 = not enriched → goes last)
                List<PackageInfo> sorted = new java.util.ArrayList<>(packages);
                sorted.sort((a, b) -> Long.compare(b.getDependentPackagesCount(),
                                                    a.getDependentPackagesCount()));

                // Take top `count`, reassign sequential ranks from startRank
                List<PackageInfo> top = new java.util.ArrayList<>(count);
                int rank = startRank;
                for (PackageInfo p : sorted) {
                    top.add(p.withRank(rank++));
                    if (top.size() >= count) break;
                }

                packages = top;
                log("✓ Re-ranked: %,d packages kept (ranks %d – %d)",
                        packages.size(), startRank, startRank + packages.size() - 1);
                System.out.println();
            }
        }

        // ── Write SQL output (always) ─────────────────────────────────────
        log("Writing output to %s …", output);
        String formatted = OutputFormatter.format(packages);
        Files.writeString(Paths.get(output), formatted);

        log("✓  %,d packages written to %s  (total time: %d s)",
                packages.size(), output, elapsedSec);

        // Print first 5 lines as a preview
        System.out.println();
        System.out.println("── Preview (first 5 rows) ────────────────────────────────────────────");
        formatted.lines().limit(5).forEach(System.out::println);
        System.out.println("──────────────────────────────────────────────────────────────────────");

        // ── Write to DB ───────────────────────────────────────────────────
        if (writeDb) {
            System.out.println();
            String fwUrl  = dbUrl      != null ? dbUrl      : buildEnvUrl();
            String fwUser = dbUser     != null ? dbUser     : System.getenv().getOrDefault("CROWD_PACKAGES_DB_USERNAME", "maven");
            String fwPass = dbPassword != null ? dbPassword : System.getenv().getOrDefault("CROWD_PACKAGES_DB_PASSWORD", "maven");

            log("Applying DB migrations (Flyway) …");
            var migrationResult = Flyway.configure()
                    .dataSource(fwUrl, fwUser, fwPass)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load()
                    .migrate();
            log("✓  Flyway: %d migration(s) applied", migrationResult.migrationsExecuted);

            log("Connecting to packages DB …");
            try (DatabaseWriter db = dbUrl != null
                    ? DatabaseWriter.fromUrl(dbUrl,
                                            dbUser     != null ? dbUser     : "maven",
                                            dbPassword != null ? dbPassword : "maven")
                    : DatabaseWriter.fromEnv()) {

                // Use the DB server clock so runStart is guaranteed to precede
                // the NOW() values written by upsertMavenPackages.
                Timestamp runStart = db.fetchServerNow();

                log("Upserting %,d rows → maven_packages …", packages.size());
                long t1 = System.currentTimeMillis();
                db.upsertMavenPackages(packages);
                log("✓  maven_packages upsert complete in %d s",
                        (System.currentTimeMillis() - t1) / 1000);

                DatabaseWriter.RunStats stats = db.queryRunStats(runStart);
                db.insertRunRecord(stats);

                System.out.println();
                log("── Run summary ──────────────────────────────────────");
                log("   new packages       : %,d", stats.newPackages());
                log("   changed packages   : %,d", stats.changedPackages());
                log("   unchanged packages : %,d", stats.unchangedPackages());
                log("   total processed    : %,d", stats.totalProcessed());
                log("────────────────────────────────────────────────────");

                if (reportFile != null) {
                    writeChangeReport(reportFile, runStart, stats, db);
                    log("Change report written to: %s", reportFile);
                }

            } catch (IllegalStateException e) {
                System.err.println();
                System.err.println("ERROR: " + e.getMessage());
                System.err.println("       Provide --db-url / --db-user / --db-password,");
                System.err.println("       or set the env vars, or use --skip-write-db.");
                System.exit(1);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a human-readable change report to {@code path}.
     * New packages are listed first, then changed packages, each with their
     * current latest version.
     */
    private static void writeChangeReport(String path, Timestamp runStart,
                                           DatabaseWriter.RunStats stats,
                                           DatabaseWriter db) throws Exception {
        List<DatabaseWriter.ChangedPackage> changed = db.queryChangedPackages(runStart);

        Path dest = Paths.get(path);
        Files.createDirectories(dest.getParent() == null ? Paths.get(".") : dest.getParent());

        try (BufferedWriter w = Files.newBufferedWriter(dest,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            w.write("Maven Fetcher — Change Report");
            w.newLine();
            w.write("Run date    : " + LocalDate.now());
            w.newLine();
            w.write("Run start   : " + runStart);
            w.newLine();
            w.write(String.format("New         : %,d", stats.newPackages()));
            w.newLine();
            w.write(String.format("Changed     : %,d", stats.changedPackages()));
            w.newLine();
            w.write(String.format("Unchanged   : %,d", stats.unchangedPackages()));
            w.newLine();
            w.write(String.format("Total       : %,d", stats.totalProcessed()));
            w.newLine();
            w.newLine();

            List<DatabaseWriter.ChangedPackage> newPkgs     = new java.util.ArrayList<>();
            List<DatabaseWriter.ChangedPackage> changedPkgs = new java.util.ArrayList<>();
            for (DatabaseWriter.ChangedPackage p : changed) {
                if (p.isNew()) newPkgs.add(p); else changedPkgs.add(p);
            }

            if (!newPkgs.isEmpty()) {
                w.write("── NEW PACKAGES (" + newPkgs.size() + ") ──────────────────────────────────────────");
                w.newLine();
                w.write(String.format("%-70s  %s%n", "PURL", "LATEST VERSION"));
                for (DatabaseWriter.ChangedPackage p : newPkgs) {
                    w.write(String.format("%-70s  %s%n",
                            p.purl(), p.latestVersion() != null ? p.latestVersion() : "(unknown)"));
                }
                w.newLine();
            }

            if (!changedPkgs.isEmpty()) {
                w.write("── CHANGED PACKAGES (" + changedPkgs.size() + ") ──────────────────────────────────────");
                w.newLine();
                w.write(String.format("%-70s  %s%n", "PURL", "NEW VERSION"));
                for (DatabaseWriter.ChangedPackage p : changedPkgs) {
                    w.write(String.format("%-70s  %s%n",
                            p.purl(), p.latestVersion() != null ? p.latestVersion() : "(unknown)"));
                }
            }

            if (newPkgs.isEmpty() && changedPkgs.isEmpty()) {
                w.write("No changes detected in this run.");
                w.newLine();
            }
        }
    }

    /** Builds a JDBC URL from the standard CROWD_PACKAGES_DB_* env vars. */
    private static String buildEnvUrl() {
        String host = System.getenv().getOrDefault("CROWD_PACKAGES_DB_WRITE_HOST", "localhost");
        int    port = Integer.parseInt(System.getenv().getOrDefault("CROWD_PACKAGES_DB_PORT", "5432"));
        String db   = System.getenv().getOrDefault("CROWD_PACKAGES_DB_DATABASE", "maven");
        return String.format("jdbc:postgresql://%s:%d/%s", host, port, db);
    }

    /** Parses the --concurrency argument: a positive integer ≥ 1. */
    private static int parseConcurrency(String s) {
        int v = Integer.parseInt(s);
        if (v < 1) throw new IllegalArgumentException("--concurrency must be ≥ 1, got: " + v);
        return v;
    }

    /** Parses a count argument: a positive integer or the literal "all". */
    private static int parseCount(String s) {
        if (s.equalsIgnoreCase("all")) return COUNT_ALL;
        int v = Integer.parseInt(s);
        if (v <= 0) throw new IllegalArgumentException("Count must be > 0, got: " + v);
        return v;
    }

    /** Prints a timestamped log line to stdout (newline). */
    static void log(String fmt, Object... args) {
        String ts = LocalTime.now().format(TS_FMT);
        System.out.printf("[%s] " + fmt + "%n", prepend(ts, args));
    }

    /**
     * Overwrites the current terminal line with a timestamped progress message.
     * Uses {@code \r} so the next call replaces this line instead of appending.
     * Call {@link #logInPlaceDone()} when the progress sequence is finished to
     * move to the next line cleanly.
     *
     * <p>The line is padded to 80 characters so shorter messages fully erase
     * longer previous ones.
     */
    static void logInPlace(String fmt, Object... args) {
        String ts  = LocalTime.now().format(TS_FMT);
        String msg = String.format("[%s] " + fmt, prepend(ts, args));
        // \r returns to line start; pad to 80 chars to overwrite any leftover text
        System.out.printf("\r%-80s", msg);
        System.out.flush();
    }

    /**
     * Finalises an in-place progress sequence by printing a newline,
     * so subsequent {@link #log} calls start on a fresh line.
     */
    static void logInPlaceDone() {
        System.out.println();
    }

    /** Prepends the timestamp as the first vararg so printf works correctly. */
    private static Object[] prepend(Object first, Object[] rest) {
        Object[] out = new Object[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    private static void printBanner() {
        String ts = LocalTime.now().format(TS_FMT);
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║        Maven Package Fetcher  v1.0       ║");
        System.out.printf( "║  Started at %-29s║%n", ts);
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println();
    }

    private static void printHelp() {
        System.out.println("""
                Usage: java -jar maven-fetcher.jar [options]

                Options:
                  -n, --count <N|all>    Packages to fetch       (default: 100)
                                         Use "all" to fetch every artifact (index mode only)
                  -o, --output <file>    Output file             (default: packages.sql)
                      --start-rank <N>  First rank value        (default: 1)
                      --use-rest         Use Maven Central REST API instead of the local index.
                                         Fast (seconds), no large download, but limited to
                                         ~10 000 results and does not support -n all.
                      --use-index        (kept for backwards-compatibility; index is now default)
                      --download-stats   Replace the rank-based estimate with the real
                                         directDependentCount from deps.dev (how many Maven
                                         packages directly depend on this artifact).
                                         No API key needed.
                      --concurrency <N>  Parallel threads for --download-stats (default: 1).
                                         deps.dev sustains >450 req/s with no rate limiting;
                                         20 threads (~200 req/s) is a safe, fast choice.
                      --skip-write-db    Skip the DB upsert (SQL file is still written).
                                         By default the tool upserts into:
                                           maven_packages     – change-tracking table
                                                                (last_updated_at advances
                                                                 only when version/count changes)
                      --db-url <url>     JDBC URL for the target DB.
                                           e.g. jdbc:postgresql://localhost:5435/maven
                                           Overrides env vars when provided.
                      --db-user <user>   DB username (default: "maven" when --db-url is set)
                      --db-password <pw> DB password (default: "maven" when --db-url is set)
                                         Without --db-url, requires env vars:
                                           CROWD_PACKAGES_DB_WRITE_HOST
                                           CROWD_PACKAGES_DB_PORT        (default 5432)
                                           CROWD_PACKAGES_DB_DATABASE
                                           CROWD_PACKAGES_DB_USERNAME
                                           CROWD_PACKAGES_DB_PASSWORD
                      --serve            Start the REST API server (no fetch).
                                         Reads from the DB; requires --db-url or env vars.
                      --port <N>         API server port (default: 8080).
                  -h, --help             Show this help

                Examples:
                  java -jar maven-fetcher.jar 50
                  java -jar maven-fetcher.jar -n 500 -o out.sql
                  java -jar maven-fetcher.jar -n 200 --start-rank 21 -o slice.sql
                  java -jar maven-fetcher.jar -n 100 --download-stats --concurrency 20
                  java -jar maven-fetcher.jar -n all -o all_packages.sql --skip-write-db
                  java -jar maven-fetcher.jar --use-rest -n 100 -o rest_packages.sql
                """);
    }
}
