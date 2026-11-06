package bdsm.indexing.benchmark;

import bdsm.common.datalake.TimeHierarchyDatalake;
import bdsm.common.events.BookDownloaded;
import bdsm.common.model.Book;
import bdsm.common.text.Tokenizer;
import bdsm.indexing.events.IndexingHandlers;
import bdsm.indexing.index.MongoDatamart;
import bdsm.indexing.metadata.MetadataExtractor;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.size;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Threads(1)
@Fork(1)
public class IndexingBatchBenchmark {

    private static final String TERM = "term";
    private static final String POSTINGS = "postings";

    @Param({"1", "2", "4", "8"})
    public int threads;

    @Param({"1000"})
    public int indexSize;

    private final Tokenizer tokenizer = new Tokenizer();
    private List<Book> batch;
    private List<Integer> batchIds;
    private Set<String> batchTerms;
    private long expectedPostings;
    private long baseTerms;
    private long baseBooks;
    private MongoClient client;
    private MongoCollection<Document> index;
    private MongoCollection<Document> books;
    private MongoDatamart datamart;
    private IndexingHandlers handlers;
    private ExecutorService pool;

    @Setup(Level.Trial)
    public void prepare() throws IOException {
        batchIds = BenchmarkBooks.batch();
        batch = new ArrayList<>();
        batchTerms = new HashSet<>();
        for (int id : batchIds) {
            Book book = BenchmarkBooks.read(id);
            batch.add(book);
            Set<String> terms = new HashSet<>(tokenizer.tokenize(book.body()));
            expectedPostings += terms.size();
            batchTerms.addAll(terms);
        }
        String databaseName = BenchmarkDatabases.indexNamed(indexSize);
        client = MongoClients.create(BenchmarkDatabases.uri());
        MongoDatabase database = client.getDatabase(databaseName);
        index = database.getCollection(MongoDatamart.INDEX_COLLECTION);
        books = database.getCollection(MongoDatamart.BOOKS_COLLECTION);
        baseTerms = index.countDocuments();
        baseBooks = books.countDocuments();
        if (baseBooks != indexSize) throw new IllegalStateException(databaseName + " has " + baseBooks + " books");
        if (books.countDocuments(in("_id", batchIds)) != 0) throw new IllegalStateException("The batch is inside the base index");
        datamart = new MongoDatamart(BenchmarkDatabases.uri(), databaseName, tokenizer);
        handlers = new IndexingHandlers(new TimeHierarchyDatalake(BenchmarkBooks.cacheDirectory()), datamart);
        pool = Executors.newFixedThreadPool(threads);
    }

    @Benchmark
    @OperationsPerInvocation(BenchmarkBooks.BATCH_SIZE)
    public void updateDatamart() throws Exception {
        runBatch(book -> datamart.index(MetadataExtractor.extract(book), BenchmarkBooks.SPLIT_FOLDER, book.body()));
    }

    @Benchmark
    @OperationsPerInvocation(BenchmarkBooks.BATCH_SIZE)
    public void handleBookDownloaded() throws Exception {
        runBatch(book -> handlers.onBookDownloaded(new BookDownloaded(book.id(), BenchmarkBooks.SPLIT_FOLDER), event -> { }));
    }

    @TearDown(Level.Iteration)
    public void removeBatch() {
        verifyBatchIsIndexedOnce();
        index.updateMany(in(TERM, batchTerms), Updates.pullAll(POSTINGS, batchIds));
        index.deleteMany(and(in(TERM, batchTerms), size(POSTINGS, 0)));
        books.deleteMany(in("_id", batchIds));
        if (index.countDocuments() != baseTerms || books.countDocuments() != baseBooks) {
            throw new IllegalStateException("The base index was not restored after the iteration");
        }
    }

    @TearDown(Level.Trial)
    public void close() {
        pool.shutdown();
        datamart.close();
        client.close();
    }

    private void runBatch(BookTask task) throws Exception {
        List<Callable<Void>> calls = new ArrayList<>();
        for (Book book : batch) {
            calls.add(() -> {
                task.run(book);
                return null;
            });
        }
        for (Future<Void> call : pool.invokeAll(calls)) call.get();
    }

    private void verifyBatchIsIndexedOnce() {
        long published = books.countDocuments(and(in("_id", batchIds), eq("status", MongoDatamart.PUBLISHED)));
        Document total = index.aggregate(List.of(
                new Document("$match", new Document(TERM, new Document("$in", new ArrayList<>(batchTerms)))),
                new Document("$project", new Document("found", new Document("$size",
                        new Document("$filter", new Document("input", "$" + POSTINGS)
                                .append("cond", new Document("$in", List.of("$$this", batchIds))))))),
                new Document("$group", new Document("_id", null).append("sum", new Document("$sum", "$found"))))).first();
        long postings = total == null ? 0 : ((Number) total.get("sum")).longValue();
        if (published != batchIds.size() || postings != expectedPostings) {
            throw new IllegalStateException("Wrong batch result: " + published + " books and " + postings
                    + " postings, expected " + batchIds.size() + " and " + expectedPostings);
        }
    }

    private interface BookTask {
        void run(Book book) throws Exception;
    }
}
