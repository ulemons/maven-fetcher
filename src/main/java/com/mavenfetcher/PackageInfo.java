package com.mavenfetcher;

/**
 * Immutable value-object representing one Maven artifact.
 *
 * <p>Fields derived from the Maven Index (only available when using
 * {@code --use-index}):
 * <ul>
 *   <li>{@code latestVersion}   – version whose {@code lastModified} is highest</li>
 *   <li>{@code versionsCount}   – number of distinct versions in the index</li>
 *   <li>{@code firstReleaseMs}  – epoch-ms of the oldest indexed version</li>
 *   <li>{@code latestReleaseMs} – epoch-ms of the newest indexed version</li>
 * </ul>
 *
 * <p>Fields populated by {@code --download-stats} (via deps.dev):
 * <ul>
 *   <li>{@code dependentPackagesCount} – number of Maven packages that directly
 *       declare this artifact as a dependency ({@code directDependentCount} from
 *       deps.dev).  {@code -1} means "not fetched".</li>
 * </ul>
 */
public final class PackageInfo {

    private final String groupId;
    private final String artifactId;
    private final int    rank;
    private final long   downloadCount;

    // ── Index-only fields (null / 0 when built from REST API) ────────────────
    private final String latestVersion;   // null if unknown
    private final int    versionsCount;   // 0 if unknown
    private final long   firstReleaseMs;  // 0 if unknown
    private final long   latestReleaseMs; // 0 if unknown

    // ── deps.dev field (populated only with --download-stats) ────────────────
    /** {@code directDependentCount} from deps.dev; -1 means not fetched. */
    private final long dependentPackagesCount;

    // ── Constructors ──────────────────────────────────────────────────────────

    /** Full constructor (Maven Index path). */
    public PackageInfo(String groupId, String artifactId, int rank, long downloadCount,
                       String latestVersion, int versionsCount,
                       long firstReleaseMs, long latestReleaseMs) {
        this.groupId                = groupId;
        this.artifactId             = artifactId;
        this.rank                   = rank;
        this.downloadCount          = downloadCount;
        this.latestVersion          = latestVersion;
        this.versionsCount          = versionsCount;
        this.firstReleaseMs         = firstReleaseMs;
        this.latestReleaseMs        = latestReleaseMs;
        this.dependentPackagesCount = -1L;
    }

    /** Convenience constructor for the REST API path (no version metadata). */
    public PackageInfo(String groupId, String artifactId, int rank, long downloadCount) {
        this(groupId, artifactId, rank, downloadCount, null, 0, 0L, 0L);
    }

    /** Private full constructor used by the {@code with*} copy methods. */
    private PackageInfo(String groupId, String artifactId, int rank, long downloadCount,
                        String latestVersion, int versionsCount,
                        long firstReleaseMs, long latestReleaseMs,
                        long dependentPackagesCount) {
        this.groupId                = groupId;
        this.artifactId             = artifactId;
        this.rank                   = rank;
        this.downloadCount          = downloadCount;
        this.latestVersion          = latestVersion;
        this.versionsCount          = versionsCount;
        this.firstReleaseMs         = firstReleaseMs;
        this.latestReleaseMs        = latestReleaseMs;
        this.dependentPackagesCount = dependentPackagesCount;
    }

    // ── Identity ──────────────────────────────────────────────────────────────

    /**
     * Returns the Package URL (pURL) in the form
     * {@code pkg:maven/<groupId>/<artifactId>}.
     */
    public String getPurl() {
        return "pkg:maven/" + groupId + "/" + artifactId;
    }

    /**
     * Returns the Sonatype Central URL for this artifact, e.g.
     * {@code https://central.sonatype.com/artifact/org.springframework/spring-core}.
     */
    public String getRegistryUrl() {
        return "https://central.sonatype.com/artifact/" + groupId + "/" + artifactId;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getGroupId()                { return groupId; }
    public String getArtifactId()             { return artifactId; }
    public int    getRank()                   { return rank; }
    public long   getDownloadCount()          { return downloadCount; }
    public String getLatestVersion()          { return latestVersion; }
    public int    getVersionsCount()          { return versionsCount; }
    public long   getFirstReleaseMs()         { return firstReleaseMs; }
    public long   getLatestReleaseMs()        { return latestReleaseMs; }

    /**
     * Returns the number of Maven packages that directly depend on this artifact
     * ({@code directDependentCount} from deps.dev), or {@code -1} if not fetched.
     */
    public long getDependentPackagesCount()   { return dependentPackagesCount; }

    // ── Copy-with methods ─────────────────────────────────────────────────────

    /** Returns a copy with {@code dependentPackagesCount} set to {@code value}. */
    public PackageInfo withDependentPackagesCount(long value) {
        return new PackageInfo(groupId, artifactId, rank, downloadCount,
                latestVersion, versionsCount, firstReleaseMs, latestReleaseMs,
                value);
    }

    /** Returns a copy with {@code rank} replaced (used after deps.dev re-sort). */
    public PackageInfo withRank(int newRank) {
        return new PackageInfo(groupId, artifactId, newRank, downloadCount,
                latestVersion, versionsCount, firstReleaseMs, latestReleaseMs,
                dependentPackagesCount);
    }

    @Override
    public String toString() {
        String dep = dependentPackagesCount >= 0
                ? "  dependentPkgs=" + dependentPackagesCount : "";
        return getPurl() + "  rank=" + rank + "  downloads=" + downloadCount
                + dep + (latestVersion != null ? "  latest=" + latestVersion : "");
    }
}
