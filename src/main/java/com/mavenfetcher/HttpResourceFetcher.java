package com.mavenfetcher;

import org.apache.maven.index.updater.ResourceFetcher;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * {@link ResourceFetcher} backed by Java's built-in {@link HttpClient}.
 * Replaces the removed {@code WagonHelper.WagonFetcher} from maven-indexer ≤ 6.x.
 *
 * <p>Wraps the response stream in a {@link ProgressInputStream} that prints
 * download progress to stdout every {@value ProgressInputStream#INTERVAL_MS} ms,
 * so the user can see the ~3 GB index download is still progressing.
 */
public class HttpResourceFetcher implements ResourceFetcher {

    private String    baseUrl;
    private final HttpClient http;

    public HttpResourceFetcher() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ── ResourceFetcher contract ──────────────────────────────────────────────

    @Override
    public void connect(String id, String url) throws IOException {
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        System.out.println("  [index] connecting to " + this.baseUrl);
    }

    @Override
    public void disconnect() throws IOException { /* HttpClient manages its own pool */ }

    /**
     * Downloads {@code name} relative to the base URL.
     * Returns a {@link ProgressInputStream} so the caller sees live progress.
     */
    @Override
    public InputStream retrieve(String name) throws IOException, FileNotFoundException {
        String url = baseUrl + "/" + name;
        System.out.println("  [index] GET " + url);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "maven-fetcher/1.0")
                .timeout(Duration.ofMinutes(90))
                .GET()
                .build();

        try {
            HttpResponse<InputStream> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofInputStream());

            if (resp.statusCode() == 404) {
                throw new FileNotFoundException("404 Not Found: " + url);
            }
            if (resp.statusCode() != 200) {
                resp.body().close();
                throw new IOException("HTTP " + resp.statusCode() + ": " + url);
            }

            long contentLength = resp.headers()
                    .firstValueAsLong("Content-Length").orElse(-1L);

            return new ProgressInputStream(resp.body(), name, contentLength);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted: " + url, e);
        }
    }

    // ── Inner class: progress wrapper ────────────────────────────────────────

    /**
     * Wraps an {@link InputStream} and prints download progress every
     * {@value #INTERVAL_MS} ms.  Prints a final "done" line on close.
     */
    static final class ProgressInputStream extends InputStream {

        static final long INTERVAL_MS = 3_000;

        private final InputStream delegate;
        private final String      label;
        private final long        totalBytes;   // -1 if unknown

        private long      bytesRead  = 0;
        private long      lastPrintMs;
        private final Instant started;

        ProgressInputStream(InputStream delegate, String label, long totalBytes) {
            this.delegate   = delegate;
            this.label      = label;
            this.totalBytes = totalBytes;
            this.started    = Instant.now();
            this.lastPrintMs = System.currentTimeMillis();
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) tick(1);
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = delegate.read(buf, off, len);
            if (n > 0) tick(n);
            return n;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
            long elapsedSec = Duration.between(started, Instant.now()).toSeconds();
            if (bytesRead > 0) {
                // \r overwrites the last progress line; \n then advances to the next line
                System.out.printf("\r  ✓ %-40s  %.1f MB in %d s%n",
                        label, bytesRead / 1_000_000.0, elapsedSec);
                System.out.flush();
            }
        }

        private void tick(int n) {
            bytesRead += n;
            long now = System.currentTimeMillis();
            if (now - lastPrintMs >= INTERVAL_MS) {
                long elapsedSec = Math.max(1, Duration.between(started, Instant.now()).toSeconds());
                double mbps = (bytesRead / 1_000_000.0) / elapsedSec;
                if (totalBytes > 0) {
                    double pct = bytesRead * 100.0 / totalBytes;
                    long etaSec = (long) ((totalBytes - bytesRead) / 1_000_000.0 / mbps);
                    // \r returns to line start without advancing — overwrites the previous value
                    System.out.printf("\r  ↓ %-40s  %6.1f / %.0f MB  %4.1f%%  %.1f MB/s  ETA %ds   ",
                            label,
                            bytesRead  / 1_000_000.0,
                            totalBytes / 1_000_000.0,
                            pct,
                            mbps,
                            etaSec);
                } else {
                    System.out.printf("\r  ↓ %-40s  %6.1f MB  %.1f MB/s   ",
                            label,
                            bytesRead / 1_000_000.0,
                            mbps);
                }
                System.out.flush();   // required: stdout is line-buffered by default
                lastPrintMs = now;
            }
        }
    }
}
