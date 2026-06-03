package com.mavenfetcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fetches real {@code directDependentCount} values from the
 * <a href="https://deps.dev/">deps.dev</a> (Open Source Insights) API by Google
 * and stores them in the {@link PackageInfo#getDependentPackagesCount()} field.
 *
 * <h2>What is directDependentCount?</h2>
 * The number of Maven packages that explicitly declare this artifact as a
 * <em>direct</em> dependency in their {@code pom.xml}.  It is a factual
 * measure of ecosystem adoption — not an estimate of downloads.
 *
 * <h2>Why not actual download counts?</h2>
 * Maven Central decommissioned its public download-stats service
 * ({@code stats.maven.org}) in 2020, and {@code central.sonatype.com} has no
 * public stats endpoint.  {@code directDependentCount} is the best factual
 * proxy available from a free, unauthenticated API.
 *
 * <h2>Concurrency</h2>
 * The client runs requests in a fixed thread pool of size {@code concurrency}
 * (passed to {@link #enrichWithDirectDependents}).  No artificial delay is
 * added between requests: natural HTTP round-trip latency (~50–100 ms) combined
 * with thread count provides natural rate control.  Empirical testing shows
 * deps.dev sustains &gt;450 req/s with no rate limiting.
 *
 * <p>{@link HttpClient} and {@link ObjectMapper} are both thread-safe and
 * shared across all worker threads.
 */
public class DepsDevClient {

    private static final String BASE_URL =
            "https://api.deps.dev/v3alpha/systems/maven/packages";

    /** Log a progress line every N completions (reduces terminal noise). */
    private static final int LOG_EVERY = 500;

    private final HttpClient   http;
    private final ObjectMapper json;

    public DepsDevClient() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.json = new ObjectMapper();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Enriches each package with its real {@code directDependentCount} from
     * deps.dev, using a fixed thread pool of size {@code concurrency}.
     *
     * <p>Result order matches the input order exactly.
     *
     * <p>A package is left unchanged (original value kept) when:
     * <ul>
     *   <li>{@code latestVersion} is null/blank (no version to query)</li>
     *   <li>{@code latestVersion} is a pre-release — milestones/RCs have very
     *       few adopters yet and would produce misleadingly low counts</li>
     *   <li>the deps.dev call fails (404, network error, …)</li>
     * </ul>
     *
     * @param packages    original list — not modified
     * @param concurrency number of parallel HTTP threads (≥ 1)
     * @return new list with updated {@code dependentPackagesCount} values
     */
    public List<PackageInfo> enrichWithDirectDependents(
            List<PackageInfo> packages, int concurrency) throws InterruptedException {

        int total = packages.size();

        MavenPackageFetcher.log(
                "Fetching directDependentCount from deps.dev for %,d packages"
                + "  (concurrency=%d) …", total, concurrency);

        // Pre-allocated array preserves input order without sorting later
        PackageInfo[] result = new PackageInfo[total];

        AtomicInteger okCount      = new AtomicInteger();
        AtomicInteger skippedCount = new AtomicInteger();
        AtomicInteger errorCount   = new AtomicInteger();
        AtomicInteger doneCount    = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);

        for (int i = 0; i < total; i++) {
            final int        idx = i;
            final PackageInfo pkg = packages.get(i);

            executor.submit(() -> {
                result[idx] = enrich(pkg, okCount, skippedCount, errorCount);

                int done = doneCount.incrementAndGet();
                if (done % LOG_EVERY == 0 || done == total) {
                    MavenPackageFetcher.logInPlace(
                            "  deps.dev  %,d / %,d"
                            + "  (enriched=%,d  skip=%,d  err=%,d)",
                            done, total,
                            okCount.get(), skippedCount.get(), errorCount.get());
                }
            });
        }

        executor.shutdown();
        // Wait up to 24 h — the progress line above shows liveness
        executor.awaitTermination(24, TimeUnit.HOURS);

        MavenPackageFetcher.logInPlaceDone();
        MavenPackageFetcher.log(
                "  ✓ deps.dev: %,d enriched, %,d skipped (pre-release/no version),"
                + " %,d errors",
                okCount.get(), skippedCount.get(), errorCount.get());

        return Arrays.asList(result);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Enriches a single package, updating the appropriate counter.
     * Never throws — returns the original package on any error.
     */
    private PackageInfo enrich(PackageInfo pkg,
                                AtomicInteger ok,
                                AtomicInteger skipped,
                                AtomicInteger errors) {
        String version = pkg.getLatestVersion();

        if (version == null || version.isBlank() || isPreRelease(version)) {
            skipped.incrementAndGet();
            return pkg;
        }

        try {
            long count = fetchDirectDependentCount(
                    pkg.getGroupId(), pkg.getArtifactId(), version);
            ok.incrementAndGet();
            return pkg.withDependentPackagesCount(count);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            errors.incrementAndGet();
            return pkg;
        }
    }

    /**
     * Returns {@code true} if {@code version} looks like a pre-release.
     *
     * <p>Recognised patterns (case-insensitive):
     * {@code -M\d+} (milestone), {@code -RC\d+}, {@code -alpha}, {@code -beta},
     * {@code -SNAPSHOT}, {@code .CR\d+}, {@code .Alpha\d+}, {@code .Beta\d+}.
     */
    public static boolean isPreRelease(String version) {
        if (version == null) return false;
        String v = version.toUpperCase();
        return v.contains("-M")
            || v.contains("-RC")
            || v.contains("-ALPHA")
            || v.contains("-BETA")
            || v.contains("-SNAPSHOT")
            || v.contains(".CR")
            || v.contains(".ALPHA")
            || v.contains(".BETA");
    }

    /**
     * Calls the deps.dev dependents endpoint and returns {@code directDependentCount}.
     *
     * <pre>
     *   GET /v3alpha/systems/maven/packages/{g}%3A{a}/versions/{v}:dependents?pageSize=1
     * </pre>
     *
     * {@code pageSize=1} returns only the aggregate counts, not the full dependent list.
     *
     * @throws IOException          on non-200 status or JSON parse failure
     * @throws InterruptedException if the underlying HTTP send is interrupted
     */
    private long fetchDirectDependentCount(String groupId, String artifactId, String version)
            throws IOException, InterruptedException {

        String encodedPkg = URLEncoder.encode(
                groupId + ":" + artifactId, StandardCharsets.UTF_8);
        String encodedVer = URLEncoder.encode(version, StandardCharsets.UTF_8);

        String url = BASE_URL
                + "/" + encodedPkg
                + "/versions/" + encodedVer
                + ":dependents?pageSize=1";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "maven-fetcher/1.0")
                .header("Accept",     "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 404) {
            throw new IOException(
                    "not found in deps.dev: " + groupId + ":" + artifactId + "@" + version);
        }
        if (response.statusCode() != 200) {
            throw new IOException("deps.dev HTTP " + response.statusCode()
                    + " for " + groupId + ":" + artifactId);
        }

        /*
         * Response shape:
         * {
         *   "dependentCount":        4068,
         *   "directDependentCount":   680,   ← what we want
         *   "indirectDependentCount": 3388
         * }
         */
        JsonNode root = json.readTree(response.body());
        return root.path("directDependentCount").asLong(0L);
    }
}
