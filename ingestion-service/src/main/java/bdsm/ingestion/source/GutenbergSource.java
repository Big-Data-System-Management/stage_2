package bdsm.ingestion.source;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPInputStream;

public class GutenbergSource implements BookSource {

    private static final String URL_PATTERN = "https://www.gutenberg.org/cache/epub/%d/pg%d.txt";
    private static final long MIN_DELAY_MS = 768;
    private static final long MAX_DELAY_MS = 1024;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Object rateLimitLock = new Object();

    @Override
    public Optional<String> fetch(int bookId) throws IOException, InterruptedException {
        waitForRateLimit();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(URL_PATTERN, bookId, bookId)))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        if (status == 404) return Optional.empty();
        if (status != 200) throw new IOException("Project Gutenberg answered HTTP " + status + " for book " + bookId);
        return Optional.of(decode(response.body()));
    }

    static String decode(byte[] content) throws IOException {
        if (content.length >= 2 && (content[0] & 0xFF) == 0x1F && (content[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(content))) {
                content = gzip.readAllBytes();
            }
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    private void waitForRateLimit() throws InterruptedException {
        synchronized (rateLimitLock) {
            Thread.sleep(ThreadLocalRandom.current().nextLong(MIN_DELAY_MS, MAX_DELAY_MS + 1));
        }
    }
}
