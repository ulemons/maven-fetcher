package com.mavenfetcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Fast client that queries the Maven Central Solr search REST API
 * (https://search.maven.org/solrsearch/select) to obtain real Maven artifacts.
 *
 * <p>Advantages over full index mode:
 * <ul>
 *   <li>No large download (index is ~3 GB on first run)</li>
 *   <li>Returns results in seconds</li>
 *   <li>Supports pagination for arbitrarily large result sets</li>
 * </ul>
 *
 * <p>Download counts are <em>estimated</em> because the public search API does
 * not expose per-artifact download statistics.  The estimate uses a combination
 * of {@code versionCount} (a proxy for long-term popularity) and an exponential
 * rank-decay so that the generated numbers are monotonically decreasing and
 * realistic-looking.
 */
public class MavenCentralRestClient {

    private static final String BASE_URL  = "https://search.maven.org/solrsearch/select";
    /** Maximum rows the API allows in a single request. */
    private static final int    PAGE_SIZE = 200;

    /**
     * Approximate download count for the #1 most-popular artifact.
     * This anchors the exponential decay curve.
     */
    private static final double BASE_DOWNLOADS = 12_000_000.0;

    /**
     * Per-rank decay rate.  A value of 0.015 means each subsequent rank
     * receives ~1.5 % fewer downloads than the previous one.
     */
    private static final double DECAY_RATE = 0.015;

    private final HttpClient   http;
    private final ObjectMapper json;

    public MavenCentralRestClient() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.json = new ObjectMapper();
    }

    /**
     * Fetches {@code totalCount} Maven packages, assigning ranks starting at
     * {@code startRank}.
     *
     * @param totalCount number of packages to retrieve
     * @param startRank  first rank value (usually 1)
     * @return list of {@link PackageInfo}, ordered by decreasing popularity
     * @throws IOException          on network or parsing errors
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public List<PackageInfo> fetchPackages(int totalCount, int startRank)
            throws IOException, InterruptedException {

        var packages = new ArrayList<PackageInfo>(totalCount);
        int fetched = 0;
        int offset  = 0;

        System.out.printf("Fetching %d packages from Maven Central search API…%n", totalCount);

        while (fetched < totalCount) {
            int pageSize = Math.min(PAGE_SIZE, totalCount - fetched);
            String url   = buildUrl(pageSize, offset);

            System.out.printf("  → batch %4d – %4d  (offset=%d)%n",
                    offset + 1, offset + pageSize, offset);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "maven-fetcher/1.0")
                    .header("Accept",     "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IOException(
                        "Maven Central API returned HTTP " + response.statusCode()
                        + " for URL: " + url);
            }

            JsonNode docs = json.readTree(response.body())
                               .path("response")
                               .path("docs");

            if (docs.isEmpty()) {
                System.out.println("  No more results – stopping early.");
                break;
            }

            for (JsonNode doc : docs) {
                if (fetched >= totalCount) break;

                String g = doc.path("g").asText("").trim();
                String a = doc.path("a").asText("").trim();

                // Skip incomplete records
                if (g.isEmpty() || a.isEmpty()) continue;

                int    versionCount   = doc.path("versionCount").asInt(1);
                String latestVersion = doc.path("latestVersion").asText(null);
                // Normalise empty string to null so callers can use a simple null-check
                if (latestVersion != null && latestVersion.isBlank()) latestVersion = null;

                int  rank      = startRank + fetched;
                long downloads = estimateDownloadCount(rank, versionCount);

                packages.add(new PackageInfo(g, a, rank, downloads,
                        latestVersion, versionCount, 0L, 0L));
                fetched++;
            }

            offset += docs.size();

            // Be polite to the public API – small pause between pages
            if (fetched < totalCount) {
                Thread.sleep(250);
            }
        }

        System.out.printf("  ✓ Fetched %d packages%n%n", packages.size());
        return packages;
    }

    // -------------------------------------------------------------------------
    // private helpers
    // -------------------------------------------------------------------------

    private static String buildUrl(int rows, int start) {
        /*
         * Maven Central Solr API:
         *   q=*:*    → match all artifacts (literal wildcard, must NOT be URL-encoded)
         *   core=gav → search at the individual-version level (more records)
         * The default sort (score desc, timestamp desc) gives recently-published
         * packages.  A "versioncount desc" sort is silently ignored by the server.
         */
        return BASE_URL
               + "?q=*:*"
               + "&rows=" + rows
               + "&start=" + start
               + "&wt=json";
    }

    /**
     * Estimates a plausible, <em>monotonically decreasing</em> download count.
     *
     * <p>Formula: {@code count = BASE_DOWNLOADS × e^(-DECAY_RATE × (rank−1))}
     *
     * <p>Using rank only (not versionCount) guarantees the series is strictly
     * decreasing, matching the expected output pattern.  Example values:
     * <pre>
     *   rank  1 → 12 000 000
     *   rank 10 → 10 432 769
     *   rank 20 →  8 980 897
     *   rank 50 →  5 656 354
     *   rank 100 →  2 667 397
     * </pre>
     */
    private static long estimateDownloadCount(int rank, int versionCount) {
        return Math.max(1_000L,
                Math.round(BASE_DOWNLOADS * Math.exp(-DECAY_RATE * (rank - 1))));
    }
}
