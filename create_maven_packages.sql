-- DDL for the maven_packages tracking table.
--
-- Purpose: record every Maven artifact seen by the maven-fetcher script
-- and expose when each package was first discovered and last changed.
--
-- Columns
-- -------
--   purl              Primary key; matches packages.purl and packages_universe.purl.
--   ecosystem         Always 'maven'.
--   namespace         Maven groupId.
--   name              Maven artifactId.
--   registry_url      Sonatype Central URL for this artifact.
--   latest_version    Most recent version string seen in the Maven Index.
--   versions_count    Number of distinct versions indexed on Maven Central.
--   first_release_at  Timestamp of the oldest indexed version.
--   latest_release_at Timestamp of the newest indexed version.
--   first_seen_at     When the fetcher first inserted this package (never changed).
--   last_updated_at   Set to NOW() only when latest_version or versions_count changes.
--   last_synced_at    Set to NOW() on every fetcher run that processes this package.

CREATE TABLE IF NOT EXISTS maven_packages (
    purl                VARCHAR         NOT NULL,
    ecosystem           VARCHAR         NOT NULL DEFAULT 'maven',
    namespace           VARCHAR         NOT NULL,
    name                VARCHAR         NOT NULL,
    registry_url        VARCHAR,
    latest_version      VARCHAR,
    versions_count      INTEGER,
    first_release_at    TIMESTAMPTZ,
    latest_release_at   TIMESTAMPTZ,
    first_seen_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    last_updated_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    last_synced_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT maven_packages_pkey PRIMARY KEY (purl)
);

-- Index to quickly find all packages updated in a given time window
-- (e.g. "what changed today?").
CREATE INDEX IF NOT EXISTS idx_maven_packages_last_updated
    ON maven_packages (last_updated_at DESC);

-- Index to quickly find all packages whose latest release is recent
-- (complements last_updated_at for release-date–based queries).
CREATE INDEX IF NOT EXISTS idx_maven_packages_latest_release
    ON maven_packages (latest_release_at DESC);

-- Run history: one row per maven-fetcher execution.
-- Lets you query how many packages changed day by day.
CREATE TABLE IF NOT EXISTS maven_fetcher_runs (
    id                 SERIAL      PRIMARY KEY,
    run_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    new_packages       INTEGER     NOT NULL,
    changed_packages   INTEGER     NOT NULL,
    unchanged_packages INTEGER     NOT NULL,
    total_processed    INTEGER     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_fetcher_runs_run_at
    ON maven_fetcher_runs (run_at DESC);
