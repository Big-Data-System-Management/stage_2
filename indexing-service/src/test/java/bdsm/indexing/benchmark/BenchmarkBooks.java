package bdsm.indexing.benchmark;

import bdsm.common.config.Config;
import bdsm.common.datalake.TimeHierarchyDatalake;
import bdsm.common.model.Book;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class BenchmarkBooks {

    static final int BATCH_SIZE = 20;
    static final String SPLIT_FOLDER = "split";

    private static final String ROLE_BATCH = "batch";
    private static final String ROLE_BASE = "base";

    private BenchmarkBooks() {
    }

    static Path cacheDirectory() {
        return Config.getPath("BENCH_CACHE_DIR", "benchmark/cache");
    }

    static List<Integer> batch() throws IOException {
        return idsWithRole(ROLE_BATCH);
    }

    static List<Integer> base(int size) throws IOException {
        List<Integer> base = idsWithRole(ROLE_BASE);
        if (size > base.size()) throw new IllegalArgumentException("Only " + base.size() + " base books are listed, asked for " + size);
        return base.subList(0, size);
    }

    static Book read(int bookId) throws IOException {
        return TimeHierarchyDatalake.readFrom(cacheDirectory().resolve(SPLIT_FOLDER), bookId);
    }

    private static List<Integer> idsWithRole(String role) throws IOException {
        Path listing = Config.getPath("BENCH_BOOKS_CSV", "benchmark/books.csv");
        List<Integer> ids = new ArrayList<>();
        for (String line : Files.readAllLines(listing)) {
            String[] fields = line.split(",");
            if (fields[1].equals(role)) ids.add(Integer.parseInt(fields[0]));
        }
        return ids;
    }
}
