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
    private static final int HTTP_TIMEOUT_SECONDS = 30;

    private static final int MIN_BYTES_FOR_GZIP_CHECK = 2;
    private static final int GZIP_FOOTPRINT_BYTE_FIRST = 0x1F;
    private static final int GZIP_FOOTPRINT_BYTE_SECOND = 0x8B;
    private static final int UNSIGNED_BYTE_MASK = 0xFF;

    private static final int HTTP_OK = 200;
    private static final int HTTP_NOT_FOUND = 404;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Object rateLimitLock = new Object();

    @Override
    public Optional<String> fetch(int bookId) throws IOException, InterruptedException {
        waitForRateLimit();
        HttpRequest request = createBookRequest(bookId);
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return processHttpResponse(response, bookId);
    }

    private void waitForRateLimit() throws InterruptedException {
        synchronized (rateLimitLock) {
            Thread.sleep(ThreadLocalRandom.current().nextLong(MIN_DELAY_MS, MAX_DELAY_MS + 1));
        }
    }

    private HttpRequest createBookRequest(int bookId) {
        String url = String.format(URL_PATTERN, bookId, bookId);
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .GET()
                .build();
    }

    private Optional<String> processHttpResponse(HttpResponse<byte[]> response, int bookId) throws IOException {
        int statusCode = response.statusCode();
        if (statusCode == HTTP_NOT_FOUND) {
            return Optional.empty();
        }
        if (statusCode != HTTP_OK) {
            throw new IOException("Project Gutenberg answered HTTP " + statusCode + " for book " + bookId);
        }

        return Optional.of(decode(response.body()));
    }

    private static String decode(byte[] content) throws IOException {
        if (hasGzipHeader(content)) {
            content = decompressGzip(content);
        }
        return convertBytesToString(content);
    }

    private static boolean hasGzipHeader(byte[] content) {
        return isLongEnoughForHeader(content) && matchesGzipMagicNumbers(content);
    }

    private static boolean isLongEnoughForHeader(byte[] content) {
        return content.length >= MIN_BYTES_FOR_GZIP_CHECK;
    }

    private static boolean matchesGzipMagicNumbers(byte[] content) {
        int firstByteUnsigned = extractUnsignedByte(content[0]);
        int secondByteUnsigned = extractUnsignedByte(content[1]);

        return firstByteUnsigned == GZIP_FOOTPRINT_BYTE_FIRST
                && secondByteUnsigned == GZIP_FOOTPRINT_BYTE_SECOND;
    }

    private static int extractUnsignedByte(byte b) {
        return b & UNSIGNED_BYTE_MASK;
    }

    private static byte[] decompressGzip(byte[] compressedContent) throws IOException {
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(compressedContent))) {
            return gzipInputStream.readAllBytes();
        }
    }

    private static String convertBytesToString(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }
}