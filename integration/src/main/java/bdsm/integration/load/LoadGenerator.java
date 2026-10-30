package bdsm.integration.load;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class LoadGenerator {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final double NANOS_PER_MS = 1_000_000.0;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final List<String> targets;
    private final List<String> queries;
    private final int users;
    private final long warmupNanos;
    private final long durationNanos;
    private final long seed;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicInteger nextTarget = new AtomicInteger();
    private final AtomicLong errors = new AtomicLong();

    private LoadGenerator(Options options) throws IOException {
        targets = List.of(options.get("targets").split(","));
        queries = Files.readAllLines(Path.of(options.get("queries"))).stream().filter(line -> !line.isBlank()).toList();
        users = options.getInt("users");
        warmupNanos = options.getInt("warmup-s", 10) * NANOS_PER_SECOND;
        durationNanos = options.getInt("duration-s", 60) * NANOS_PER_SECOND;
        seed = options.getInt("seed", 1);
    }

    public static void main(String[] args) throws Exception {
        Options options = new Options(args);
        LoadGenerator generator = new LoadGenerator(options);
        ResultWriter writer = new ResultWriter(Path.of(options.get("out")), options.get("labels", ""));
        generator.run(writer);
        System.exit(0);
    }

    private void run(ResultWriter writer) throws Exception {
        List<List<Long>> latencies = new ArrayList<>();
        CountDownLatch finished = new CountDownLatch(users);
        long start = System.nanoTime();
        for (int user = 0; user < users; user++) {
            List<Long> ofUser = new ArrayList<>();
            latencies.add(ofUser);
            Random random = new Random(seed + user);
            Thread thread = new Thread(() -> {
                simulateUser(random, start, ofUser);
                finished.countDown();
            });
            thread.setDaemon(true);
            thread.start();
        }
        finished.await();
        long[] nanos = latencies.stream().flatMap(List::stream).mapToLong(Long::longValue).toArray();
        double seconds = (double) durationNanos / NANOS_PER_SECOND;
        report(writer, Arrays.stream(nanos).toArray(), seconds);
    }

    private void simulateUser(Random random, long start, List<Long> latencies) {
        long measureFrom = start + warmupNanos;
        long stopAt = measureFrom + durationNanos;
        while (System.nanoTime() < stopAt) {
            String target = targets.get(Math.floorMod(nextTarget.getAndIncrement(), targets.size()));
            String query = queries.get(random.nextInt(queries.size()));
            long sent = System.nanoTime();
            boolean ok = send(target + "/search?" + query);
            long received = System.nanoTime();
            if (received < measureFrom || received > stopAt) continue;
            if (ok) latencies.add(received - sent);
            else errors.incrementAndGet();
        }
    }

    private boolean send(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void report(ResultWriter writer, long[] nanos, double seconds) throws IOException {
        ResultWriter.Statistics latency = ResultWriter.statistics(nanos);
        double throughput = nanos.length / seconds;
        writer.write(Map.of(
                "benchmark", "search.latency", "metric", "avgt", "unit", "ms/op",
                "mean", latency.mean() / NANOS_PER_MS, "stddev", latency.stddev() / NANOS_PER_MS, "samples", nanos.length,
                "p50", latency.p50() / NANOS_PER_MS, "p95", latency.p95() / NANOS_PER_MS, "p99", latency.p99() / NANOS_PER_MS,
                "errors", errors.get()));
        writer.write(Map.of(
                "benchmark", "search.throughput", "metric", "thrpt", "unit", "ops/s",
                "mean", throughput, "stddev", 0.0, "samples", nanos.length, "errors", errors.get()));
        System.out.printf(Locale.ROOT, "[load] %d users: %.1f requests/s, mean %.2f ms, p95 %.2f ms, %d errors%n",
                users, throughput, latency.mean() / NANOS_PER_MS, latency.p95() / NANOS_PER_MS, errors.get());
    }
}
