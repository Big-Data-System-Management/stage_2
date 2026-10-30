package bdsm.indexing.benchmark;

import bdsm.common.config.Config;
import bdsm.common.model.Book;
import bdsm.common.text.Tokenizer;
import bdsm.indexing.index.MongoDatamart;
import bdsm.indexing.metadata.MetadataExtractor;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class BenchmarkDatabases {

    private static final String PREFIX = "bench_";
    private static final int BUILD_THREADS = 8;

    private BenchmarkDatabases() {
    }

    static String uri() {
        return Config.get("MONGO_URI", "mongodb://localhost:27017");
    }

    static String named(String name) {
        return PREFIX + name;
    }

    static String indexNamed(int size) {
        return named("index_" + size);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Usage: BenchmarkDatabases build <sizes...> | drop | version");
        switch (args[0]) {
            case "build" -> build(Arrays.stream(args).skip(1).mapToInt(Integer::parseInt).sorted().toArray());
            case "drop" -> drop();
            case "version" -> version();
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
        System.exit(0);
    }

    private static void build(int[] sizes) throws Exception {
        try (MongoClient client = MongoClients.create(uri())) {
            int largest = sizes[sizes.length - 1];
            String working = indexNamed(largest);
            if (isBuilt(client, working, largest)) {
                System.out.printf("[Benchmark] Base indexes already built up to %d books%n", largest);
                return;
            }
            client.getDatabase(working).drop();
            try (MongoDatamart datamart = new MongoDatamart(uri(), working, new Tokenizer())) {
                int done = 0;
                for (int size : sizes) {
                    long start = System.nanoTime();
                    indexBooks(datamart, BenchmarkBooks.base(size).subList(done, size));
                    done = size;
                    System.out.printf(Locale.ROOT, "[Benchmark] Base index with %d books built in %.1f s%n",
                            size, (System.nanoTime() - start) / 1e9);
                    if (size != largest) snapshot(client, working, indexNamed(size));
                }
            }
        }
    }

    private static boolean isBuilt(MongoClient client, String database, int books) {
        return client.getDatabase(database).getCollection(MongoDatamart.BOOKS_COLLECTION)
                .countDocuments(new Document("status", MongoDatamart.PUBLISHED)) == books;
    }

    private static void indexBooks(MongoDatamart datamart, List<Integer> ids) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(BUILD_THREADS);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int id : ids) tasks.add(pool.submit(() -> indexBook(datamart, id)));
            for (Future<?> task : tasks) task.get();
        } finally {
            pool.shutdown();
        }
    }

    private static void indexBook(MongoDatamart datamart, int id) {
        try {
            Book book = BenchmarkBooks.read(id);
            datamart.index(MetadataExtractor.extract(book), BenchmarkBooks.SPLIT_FOLDER, book.body());
        } catch (IOException e) {
            throw new IllegalStateException("Book " + id + " could not be read", e);
        }
    }

    private static void snapshot(MongoClient client, String source, String target) {
        client.getDatabase(target).drop();
        MongoDatabase database = client.getDatabase(source);
        for (String collection : List.of(MongoDatamart.INDEX_COLLECTION, MongoDatamart.BOOKS_COLLECTION)) {
            database.getCollection(collection)
                    .aggregate(List.of(new Document("$out", new Document("db", target).append("coll", collection))))
                    .toCollection();
        }
        new MongoDatamart(uri(), target, new Tokenizer()).close();
    }

    private static void version() {
        try (MongoClient client = MongoClients.create(uri())) {
            System.out.println(client.getDatabase("admin").runCommand(new Document("buildInfo", 1)).getString("version"));
        }
    }

    private static void drop() {
        try (MongoClient client = MongoClients.create(uri())) {
            for (String name : client.listDatabaseNames()) {
                if (name.startsWith(PREFIX)) client.getDatabase(name).drop();
            }
        }
        System.out.println("[Benchmark] Benchmark databases dropped");
    }
}
