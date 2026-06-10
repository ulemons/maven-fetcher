package com.mavenfetcher;

import org.apache.maven.index.DefaultIndexer;
import org.apache.maven.index.DefaultIndexerEngine;
import org.apache.maven.index.DefaultQueryCreator;
import org.apache.maven.index.DefaultSearchEngine;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator;
import org.apache.maven.index.incremental.DefaultIncrementalHandler;
import org.apache.maven.index.updater.DefaultIndexUpdater;
import org.apache.maven.index.updater.IndexUpdateRequest;
import org.apache.maven.index.updater.IndexUpdateResult;
import org.apache.maven.index.updater.IndexUpdater;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.store.FSDirectory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Apache Maven Indexer 7.x client — uses separate strategies for download and query:
 *
 * <ul>
 *   <li><b>Download</b>: uses {@code DefaultIndexer} + {@code IndexingContext}
 *       (required by {@code IndexUpdateRequest}).  The context is closed immediately
 *       after, releasing the write.lock.</li>
 *   <li><b>Query</b>: uses a direct {@link DirectoryReader} in <em>read-only</em>
 *       mode — no write.lock, no context management,
 *       compatible with indexes already present on disk.</li>
 * </ul>
 *
 * <p>Passing {@link MavenPackageFetcher#COUNT_ALL} as {@code count} fetches
 * every unique groupId:artifactId in the index (no early break).
 *
 * <p>After {@link #fetchPackages} returns, callers can inspect the update
 * outcome via {@link #getUpdateOutcome()} and {@link #isIndexStale()}.
 */
public class MavenIndexClient implements AutoCloseable {

    // ── Update outcome ────────────────────────────────────────────────────────

    /** Describes how the local index was updated during this run. */
    public enum UpdateOutcome {
        /** First run: full index downloaded from Maven Central. */
        FULL_UPDATE,
        /** Incremental delta successfully applied. */
        INCREMENTAL,
        /** Index was already up to date — no delta downloaded. */
        UP_TO_DATE,
        /** Incremental update failed; cache was cleaned and a full re-download succeeded. */
        RECOVERED,
        /**
         * Both the incremental update and the recovery re-download failed.
         * The local index is stale and change counts from this run are NOT reliable.
         */
        STALE_FALLBACK
    }

    private UpdateOutcome updateOutcome;
    private String        updateMessage;

    /** Returns the outcome of the index update attempt, available after {@link #fetchPackages}. */
    public UpdateOutcome getUpdateOutcome() { return updateOutcome; }

    /**
     * Returns {@code true} if the index could not be updated and the run used stale data.
     * When {@code true}, change counts from this run are unreliable.
     */
    public boolean isIndexStale() { return updateOutcome == UpdateOutcome.STALE_FALLBACK; }

    /**
     * Human-readable failure reason when {@link #isIndexStale()} is {@code true},
     * or {@code null} for all other outcomes.
     */
    public String getUpdateMessage() { return updateMessage; }

    private static final String BASE_DIR    = System.getProperty("user.home") + "/.maven-fetcher";
    private static final String CACHE_DIR   = BASE_DIR + "/cache";
    private static final String INDEX_DIR   = BASE_DIR + "/index";
    private static final String CENTRAL_URL = "https://repo1.maven.org/maven2";

    private static final double BASE_DOWNLOADS = 12_000_000.0;
    private static final double DECAY_RATE     = 0.015;

    /** Progress checkpoint: print a line every N unique packages collected. */
    private static final int LOG_EVERY = 10_000;

    // Used only for download (createIndexingContext)
    private final DefaultIndexer indexer;
    private final IndexUpdater   indexUpdater;

    public MavenIndexClient() {
        this.indexer = new DefaultIndexer(
                new DefaultSearchEngine(),
                new DefaultIndexerEngine(),
                new DefaultQueryCreator());
        this.indexUpdater = new DefaultIndexUpdater(
                new DefaultIncrementalHandler(),
                Collections.emptyList());
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public List<PackageInfo> fetchPackages(int count, int startRank) throws IOException {
        new File(CACHE_DIR).mkdirs();
        new File(INDEX_DIR).mkdirs();
        MavenPackageFetcher.log("Cache : %s", CACHE_DIR);
        MavenPackageFetcher.log("Index : %s", INDEX_DIR);
        System.out.println();

        downloadIfNeeded();
        return queryIndex(count, startRank);
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private void downloadIfNeeded() throws IOException {
        File indexDir  = new File(INDEX_DIR);
        File cacheDir  = new File(CACHE_DIR);
        File writeLock = new File(indexDir, "write.lock");
        File timestamp = new File(indexDir, "timestamp");

        // Remove stale write.lock (0-byte) left by a previous crashed run
        if (writeLock.exists() && writeLock.length() == 0) {
            MavenPackageFetcher.log("⚠  Stale write.lock removed");
            writeLock.delete();
        }

        boolean hasIndex = timestamp.exists() &&
                indexDir.listFiles(f -> f.getName().startsWith("segments_")) != null &&
                indexDir.listFiles(f -> f.getName().startsWith("segments_")).length > 0;

        if (hasIndex) {
            MavenPackageFetcher.log("✓ Local index found (%s)", formatSize(dirSize(indexDir)));
            MavenPackageFetcher.log("  Checking for incremental updates from Maven Central…");
        } else {
            MavenPackageFetcher.log("First run: downloading full index (~3 GB)…");
            MavenPackageFetcher.log("  (10–30 min depending on connection speed; subsequent runs use incremental deltas)");
        }
        System.out.println();

        Instant t0 = Instant.now();
        try {
            // DefaultIndexUpdater decides internally whether to apply an incremental
            // delta (subsequent runs) or perform a full download (first run / no cache).
            IndexUpdateResult result = runUpdate(false);
            long   sec   = Duration.between(t0, Instant.now()).toSeconds();
            String tsStr = result.getTimestamp() != null
                    ? result.getTimestamp().toInstant().toString()
                    : "n/a";

            if (result.isFullUpdate()) {
                MavenPackageFetcher.log("✓ Index downloaded (full update) in %d s  [remote ts: %s]",
                        sec, tsStr);
                updateOutcome = UpdateOutcome.FULL_UPDATE;
            } else {
                // Both "incremental delta applied" and "already up to date" return isFullUpdate()=false.
                // If elapsed time is very short it is almost certainly "already up to date".
                if (sec < 10 && hasIndex) {
                    MavenPackageFetcher.log("✓ Index already up to date — no delta to download  [ts: %s]",
                            tsStr);
                    updateOutcome = UpdateOutcome.UP_TO_DATE;
                } else {
                    MavenPackageFetcher.log("✓ Index updated via incremental delta in %d s  [remote ts: %s]",
                            sec, tsStr);
                    updateOutcome = UpdateOutcome.INCREMENTAL;
                }
            }
            System.out.println();
        } catch (Exception e) {
            if (hasIndex) {
                // Incremental update failed (commonly due to a corrupt/incomplete cache).
                // Clean the cache dir and retry with a forced full re-download before
                // giving up and falling back to the stale local index.
                MavenPackageFetcher.log("⚠  Incremental update failed (%s)", e.getMessage());
                MavenPackageFetcher.log("   Cleaning cache and attempting full re-download…");
                System.out.println();
                deleteDirContents(cacheDir);
                try {
                    Instant t1 = Instant.now();
                    IndexUpdateResult result = runUpdate(true);
                    long   sec   = Duration.between(t1, Instant.now()).toSeconds();
                    String tsStr = result.getTimestamp() != null
                            ? result.getTimestamp().toInstant().toString()
                            : "n/a";
                    MavenPackageFetcher.log(
                            "✓ Index recovered via full re-download in %d s  [remote ts: %s]",
                            sec, tsStr);
                    System.out.println();
                    updateOutcome = UpdateOutcome.RECOVERED;
                } catch (Exception retryEx) {
                    String staleTs = readTimestamp(timestamp);
                    updateMessage  = retryEx.getMessage();
                    updateOutcome  = UpdateOutcome.STALE_FALLBACK;
                    MavenPackageFetcher.log("❌ Re-download also failed (%s)", retryEx.getMessage());
                    MavenPackageFetcher.log(
                            "   Proceeding with STALE local index (last updated: %s).", staleTs);
                    MavenPackageFetcher.log(
                            "   Change counts in this run are NOT reliable.");
                    System.out.println();
                }
            } else {
                // First run: check whether any data was written despite the exception
                boolean wroteData = new File(INDEX_DIR).listFiles(f ->
                        f.getName().endsWith(".cfs") || f.getName().endsWith(".fdt")) != null &&
                        new File(INDEX_DIR).listFiles(f ->
                        f.getName().endsWith(".cfs") || f.getName().endsWith(".fdt")).length > 0;
                if (wroteData) {
                    MavenPackageFetcher.log("⚠  Post-download error (%s) but data is present — proceeding",
                            e.getMessage());
                    System.out.println();
                } else {
                    throw new IOException("Download failed and no data was written: " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Creates a temporary {@link IndexingContext}, runs one update attempt, and
     * closes the context (releasing {@code write.lock}) in all cases.
     *
     * @param forceFull {@code true} to force a full re-download regardless of cached state
     * @return the update result from {@code DefaultIndexUpdater}
     * @throws Exception if the update fails
     */
    private IndexUpdateResult runUpdate(boolean forceFull) throws Exception {
        IndexingContext ctx = indexer.createIndexingContext(
                "central-context", "central",
                new File(CACHE_DIR), new File(INDEX_DIR),
                CENTRAL_URL, null,
                true, true,
                Collections.singletonList(new MinimalArtifactInfoIndexCreator()));
        try {
            IndexUpdateRequest req = new IndexUpdateRequest(ctx, new HttpResourceFetcher());
            req.setForceFullUpdate(forceFull);
            req.setLocalIndexCacheDir(new File(CACHE_DIR));
            return indexUpdater.fetchAndUpdateIndex(req);
        } finally {
            // Always close the context to release write.lock
            try { indexer.closeIndexingContext(ctx, false); } catch (Exception ignored) {}
        }
    }

    // ── Query (DirectoryReader read-only — no write.lock) ────────────────────

    /**
     * Scans the Lucene index in two phases:
     *
     * <ol>
     *   <li><b>Phase 1</b> – reads ALL documents and aggregates data for each
     *       {@code groupId:artifactId} pair: latest version (by {@code lastModified}),
     *       version count, and first/latest release timestamps.
     *       Phase 1 cannot be short-circuited: every document must be read to
     *       correctly accumulate {@code versionsCount} and find the true latest
     *       version across all Lucene segments.</li>
     *   <li><b>Phase 2</b> – sorts all artifacts by {@code versionsCount DESC}
     *       (most published packages first = best available popularity proxy in
     *       the Maven Index) and takes the top {@code count} entries.</li>
     * </ol>
     *
     * <p>Field {@code "u"} format (MinimalArtifactInfoIndexCreator):
     * {@code groupId|artifactId|version|classifier|extension}
     * <br>Field {@code "m"}: {@code lastModified} as epoch-ms string.
     *
     * @param count number of packages to return,
     *              or {@link MavenPackageFetcher#COUNT_ALL} for all of them.
     */
    private List<PackageInfo> queryIndex(int count, int startRank) throws IOException {
        boolean fetchAll = (count == MavenPackageFetcher.COUNT_ALL);
        String  label    = fetchAll ? "ALL" : String.format("%,d", count);
        MavenPackageFetcher.log("Lucene query (read-only) — %s unique packages…", label);

        Instant t0 = Instant.now();

        // ── Phase 1: aggregate by artifact ───────────────────────────────────
        // LinkedHashMap preserves first-insertion order → stable rank.
        // Initial capacity ~10M to avoid rehashing on the full index.
        var aggregated = new LinkedHashMap<String, ArtifactData>(10_000_000);

        try (FSDirectory     dir    = FSDirectory.open(Path.of(INDEX_DIR));
             DirectoryReader reader = DirectoryReader.open(dir)) {

            MavenPackageFetcher.log("Segments: %d  —  Total documents: %,d",
                    reader.leaves().size(), reader.numDocs());
            System.out.println();

            long docsRead = 0;
            for (LeafReaderContext leaf : reader.leaves()) {
                var          lr  = leaf.reader();
                StoredFields sf  = lr.storedFields();
                int          max = lr.maxDoc();

                for (int i = 0; i < max; i++) {
                    if (lr.getLiveDocs() != null && !lr.getLiveDocs().get(i)) continue;

                    String u = sf.document(i).get("u");
                    if (u == null || u.isBlank()) continue;

                    // u = groupId|artifactId|version|classifier|extension
                    String[] parts = u.split("\\|");
                    if (parts.length < 3) continue;

                    String g = parts[0].trim();
                    String a = parts[1].trim();
                    String v = parts[2].trim();
                    if (g.isEmpty() || a.isEmpty()) continue;

                    // Field "m" = lastModified epoch-ms; 0 if absent
                    long modMs = 0L;
                    String mField = sf.document(i).get("m");
                    if (mField != null && !mField.isBlank()) {
                        try { modMs = Long.parseLong(mField.trim()); } catch (NumberFormatException ignored) {}
                    }

                    String key = g + ":" + a;
                    ArtifactData existing = aggregated.get(key);
                    if (existing == null) {
                        aggregated.put(key, new ArtifactData(g, a, v, modMs));
                    } else {
                        existing.update(v, modMs);
                    }

                    docsRead++;
                    if (docsRead % (LOG_EVERY * 10L) == 0) {
                        long elSec = Duration.between(t0, Instant.now()).toSeconds();
                        MavenPackageFetcher.logInPlace("  Phase 1  %,d docs  |  %,d artifacts  |  %d s",
                                docsRead, aggregated.size(), elSec);
                    }
                }
            }
        }

        MavenPackageFetcher.logInPlaceDone();
        long phase1Sec = Duration.between(t0, Instant.now()).toSeconds();
        MavenPackageFetcher.log("✓ Phase 1: %,d unique artifacts in %d s", aggregated.size(), phase1Sec);
        System.out.println();

        // ── Phase 2: sort by versionsCount DESC, build PackageInfo list ───────
        //
        // Phase 1 insertion order is Lucene-segment order (effectively random).
        // Sorting by versionsCount DESC puts the most published/established
        // packages first, giving a meaningful popularity ranking.
        MavenPackageFetcher.log("Sorting by versionsCount DESC…");
        List<ArtifactData> sorted = new ArrayList<>(aggregated.values());
        sorted.sort((x, y) -> Integer.compare(y.versionsCount(), x.versionsCount()));

        int limit    = fetchAll ? sorted.size() : Math.min(count, sorted.size());
        var packages = new ArrayList<PackageInfo>(limit);
        int rank     = startRank;

        for (ArtifactData d : sorted) {
            packages.add(new PackageInfo(
                    d.groupId, d.artifactId, rank, estimateDownloadCount(rank),
                    d.latestVersion.isEmpty() ? null : d.latestVersion,
                    d.versionsCount(),
                    d.firstMs,
                    d.latestMs
            ));
            rank++;

            if (packages.size() % LOG_EVERY == 0) {
                MavenPackageFetcher.logInPlace("  Phase 2  %,d / %,d PackageInfo", packages.size(), limit);
            }

            if (packages.size() >= limit) break;
        }

        MavenPackageFetcher.logInPlaceDone();
        long totalSec = Duration.between(t0, Instant.now()).toSeconds();
        MavenPackageFetcher.log("✓ %,d packages collected in %d s", packages.size(), totalSec);
        System.out.println();
        return packages;
    }

    // ── Internal aggregation class ────────────────────────────────────────────

    /**
     * Mutable accumulator for a groupId:artifactId pair during Phase 1.
     *
     * <p>Using a mutable class (instead of an immutable record) avoids creating
     * a new object for every document update — critical when processing 100M+
     * documents.
     *
     * <p>{@code versions} is a {@link HashSet} of distinct version strings.
     * {@link #versionsCount()} returns its size, which is the correct "number
     * of distinct releases" metric used to rank packages by popularity.
     */
    private static final class ArtifactData {

        String       groupId;
        String       artifactId;
        String       latestVersion;
        long         firstMs;
        long         latestMs;
        Set<String>  versions = new HashSet<>();

        ArtifactData(String groupId, String artifactId, String firstVersion, long ms) {
            this.groupId       = groupId;
            this.artifactId    = artifactId;
            this.latestVersion = firstVersion;
            this.firstMs       = ms;
            this.latestMs      = ms;
            this.versions.add(firstVersion);
        }

        /**
         * Merges a new document for the same artifact into this accumulator.
         * Updates latestVersion (by timestamp), firstMs, latestMs, and adds
         * the version to the distinct-versions set.
         */
        void update(String version, long modMs) {
            versions.add(version);
            if (modMs > 0 && modMs >= latestMs) {
                latestMs      = modMs;
                latestVersion = version;
            }
            if (modMs > 0 && modMs < firstMs) {
                firstMs = modMs;
            }
        }

        /** Number of distinct release versions seen in the index. */
        int versionsCount() {
            return versions.size();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Deletes all files and sub-directories inside {@code dir} without
     * removing the directory itself.  Used to purge a corrupt cache before
     * a forced full re-download.
     */
    private static void deleteDirContents(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                deleteDirContents(f);
            }
            f.delete();
        }
    }

    /**
     * Reads the first line of the index {@code timestamp} file and returns it
     * as a trimmed string, or {@code "unknown"} if the file is absent or unreadable.
     */
    private static String readTimestamp(File timestampFile) {
        try {
            if (timestampFile.exists()) {
                return java.nio.file.Files.readString(timestampFile.toPath()).trim();
            }
        } catch (IOException ignored) {}
        return "unknown";
    }

    private static long estimateDownloadCount(int rank) {
        return Math.max(1_000L, Math.round(BASE_DOWNLOADS * Math.exp(-DECAY_RATE * (rank - 1))));
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1_000_000_000L) return String.format("%.1f GB", bytes / 1e9);
        if (bytes >= 1_000_000L)     return String.format("%.0f MB", bytes / 1e6);
        return bytes + " B";
    }

    private static long dirSize(File dir) {
        long tot = 0;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) tot += f.length();
        return tot;
    }

    @Override
    public void close() { /* DirectoryReader closed in try-with-resources */ }
}
