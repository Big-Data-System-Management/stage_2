package bdsm.indexing.benchmark;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.mongodb.client.model.Filters.eq;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Threads(1)
@Fork(1)
public class AddToSetBenchmark {

    private static final String DATABASE = "addtoset";
    private static final String COLLECTION = "inverted_index";
    private static final String TERM = "term";
    private static final String POSTINGS = "postings";
    private static final String PROBE = "probe";

    @Param({"100", "1000", "10000", "100000"})
    public int postings;

    @Param({"new", "repeated"})
    public String bookId;

    private final UpdateOptions upsert = new UpdateOptions().upsert(true);
    private MongoClient client;
    private MongoCollection<Document> index;
    private int value;

    @Setup(Level.Trial)
    public void createTerm() {
        client = MongoClients.create(BenchmarkDatabases.uri());
        index = client.getDatabase(BenchmarkDatabases.named(DATABASE)).getCollection(COLLECTION);
        index.drop();
        index.createIndex(Indexes.ascending(TERM), new IndexOptions().unique(true));
        List<Integer> ids = IntStream.range(0, postings).boxed().toList();
        index.insertOne(new Document(TERM, PROBE).append(POSTINGS, ids));
        value = bookId.equals("new") ? postings : postings - 1;
    }

    @Benchmark
    public UpdateResult addToSet() {
        return index.updateOne(eq(TERM, PROBE), Updates.addToSet(POSTINGS, value), upsert);
    }

    @TearDown(Level.Invocation)
    public void removeNewValue() {
        if (bookId.equals("new")) index.updateOne(eq(TERM, PROBE), Updates.popLast(POSTINGS));
    }

    @TearDown(Level.Trial)
    public void dropTerm() {
        index.drop();
        client.close();
    }
}
