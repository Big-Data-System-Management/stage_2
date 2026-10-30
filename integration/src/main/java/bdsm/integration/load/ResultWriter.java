package bdsm.integration.load;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ResultWriter {

    private static final List<String> COLUMNS = List.of(
            "run_id", "scope", "experiment", "benchmark", "metric", "unit", "mean", "stddev", "samples",
            "threads", "replicas", "users", "xmx", "books", "index_size", "batch_size", "params", "smoke",
            "p50", "p95", "p99", "errors", "valid");

    private final Path target;
    private final Map<String, String> labels = new LinkedHashMap<>();

    ResultWriter(Path target, String labels) {
        this.target = target;
        for (String label : labels.split(",")) {
            String[] parts = label.split("=", 2);
            this.labels.put(parts[0], parts[1].replace('|', ','));
        }
        this.labels.put("scope", "pipeline");
    }

    void write(Map<String, Object> values) throws IOException {
        List<String> row = new ArrayList<>();
        for (String column : COLUMNS) {
            Object value = values.containsKey(column) ? values.get(column) : labels.getOrDefault(column, "");
            row.add(value instanceof Double number ? String.format(Locale.ROOT, "%.6f", number) : String.valueOf(value));
        }
        if (Files.notExists(target)) Files.writeString(target, String.join(",", COLUMNS) + "\n");
        Files.writeString(target, String.join(",", row) + "\n", StandardOpenOption.APPEND);
    }

    static Statistics statistics(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(sorted).average().orElse(0);
        double squares = Arrays.stream(sorted).mapToDouble(value -> (value - mean) * (value - mean)).sum();
        double deviation = sorted.length > 1 ? Math.sqrt(squares / (sorted.length - 1)) : 0;
        return new Statistics(mean, deviation, percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99));
    }

    private static double percentile(long[] sorted, int percentile) {
        if (sorted.length == 0) return 0;
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.length);
        return sorted[Math.max(rank - 1, 0)];
    }

    record Statistics(double mean, double stddev, double p50, double p95, double p99) {
    }
}
