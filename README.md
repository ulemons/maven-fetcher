# Maven Package Fetcher

Java application that retrieves **real Maven packages** from Maven Central and
writes them in a SQL-values format suitable for database seeding.

## Output format

```
  ('pkg:maven/org.springframework/spring-core', 'maven', 'org.springframework', 'spring-core', 1, 9856234, true),
  ('pkg:maven/org.apache.commons/commons-lang3', 'maven', 'org.apache.commons', 'commons-lang3', 2, 9723156, true),
  …
```

| Column | Meaning |
|--------|---------|
| 1 | Package URL ([purl](https://github.com/package-url/purl-spec)) `pkg:maven/<groupId>/<artifactId>` |
| 2 | Ecosystem – always `'maven'` |
| 3 | Namespace (groupId) |
| 4 | Name (artifactId) |
| 5 | Rank (sequential integer) |
| 6 | Estimated download count |
| 7 | Visibility – always `true` |

> **Download counts** are estimated via an exponential decay formula
> (`~12 000 000 × e^{−0.015 × (rank−1)}`). The Maven Central public API does
> not expose per-artifact download statistics.

---

## Build

```bash
mvn clean package -q
```

Produces a fat jar at `target/maven-fetcher-1.0.0.jar`.

---

## Usage

```
java -jar target/maven-fetcher-1.0.0.jar [options]

  -n, --count <N|all>   Packages to fetch        (default: 100)
  -o, --output <file>   Output file              (default: packages.sql)
      --start-rank <N>  First rank value         (default: 1)
      --use-rest         Use Maven Central REST API instead of local index
      --use-index        (backwards-compat alias; index is now the default)
  -h, --help            Show help
```

### Fetch 100 packages (index mode – default)

```bash
java -jar target/maven-fetcher-1.0.0.jar
# → packages.sql  (100 rows, from the local Lucene index)
```

### Fetch using the REST API (fast, no local index required)

```bash
java -jar target/maven-fetcher-1.0.0.jar --use-rest -n 100
```

### Fetch a specific count and output file

```bash
java -jar target/maven-fetcher-1.0.0.jar -n 500 -o my_packages.sql
```

### Fetch a slice starting at a given rank

```bash
java -jar target/maven-fetcher-1.0.0.jar -n 25 --start-rank 21 -o slice.sql
```

### Also via `mvn exec:java`

```bash
mvn exec:java -Dexec.args="-n 50 -o output.sql"
```

---

## Modes

### 1. Apache Maven Indexer mode (default – recommended)

Uses the **Apache Maven Indexer** library to download and query the actual Nexus
Lucene index published by Maven Central at
`https://repo1.maven.org/maven2/.index/`.

```bash
java -jar target/maven-fetcher-1.0.0.jar -n 1000 -o indexed_packages.sql
```

- ⚠ First run downloads `nexus-maven-repository-index.gz` (~3 GB)
- ✅ Subsequent runs are incremental (a few MB each)
- ✅ Cached at `~/.maven-fetcher/index`
- ✅ All 10+ million artifacts available locally once indexed
- ✅ Supports `-n all`

### 2. REST API mode (`--use-rest`)

Queries the **Maven Central Solr search API** (`search.maven.org/solrsearch/select`),
sorted by `versioncount desc` to retrieve the most popular artifacts first.

```bash
java -jar target/maven-fetcher-1.0.0.jar --use-rest -n 100 -o rest_packages.sql
```

- ✅ Returns results in seconds
- ✅ No large download required
- ✅ Paginates automatically
- ⚠ Limited to ~10 000 results; does not support `-n all`

---

## Project structure

```
maven-fetcher/
├── pom.xml
├── README.md
└── src/main/java/com/mavenfetcher/
    ├── MavenPackageFetcher.java      ← main entry point
    ├── MavenCentralRestClient.java   ← REST API client (default mode)
    ├── MavenIndexClient.java         ← Apache Maven Indexer client (--use-index)
    ├── PackageInfo.java              ← value object
    └── OutputFormatter.java          ← SQL-values formatter
```

## Key dependencies

| Artifact | Purpose |
|----------|---------|
| `org.apache.maven.indexer:indexer-core:7.1.0` | Maven Indexer core + Lucene |
| `org.apache.maven.wagon:wagon-http:3.5.3` | HTTP transport for index download |
| `com.fasterxml.jackson.core:jackson-databind:2.16.1` | JSON parsing for REST API |
| `org.slf4j:slf4j-simple:1.7.36` | Logging |
