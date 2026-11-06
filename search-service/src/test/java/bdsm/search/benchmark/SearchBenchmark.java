package bdsm.search.benchmark;

import bdsm.common.config.Config;
import bdsm.common.text.Tokenizer;
import bdsm.search.query.SearchQuery;
import bdsm.search.query.SearchResult;
import bdsm.search.query.SearchService;
import bdsm.search.repository.MongoBookRepository;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
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

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Threads(1)
@Fork(1)
public class SearchBenchmark {

    private static final String RARE_TERM = "lighthouse";
    private static final String COMMON_TERM = "time";

    @Param({"100", "500", "1000", "2000"})
    public int indexSize;

    @Param({"rare", "common", "common_language", "common_author", "common_year", "common_combined", "common_page"})
    public String search;

    private MongoClient client;
    private SearchService service;
    private SearchQuery query;

    @Setup(Level.Trial)
    public void connect() {
        client = MongoClients.create(Config.get("MONGO_URI", "mongodb://localhost:27017"));
        service = new SearchService(new MongoBookRepository(client.getDatabase("bench_index_" + indexSize)), new Tokenizer());
        if (service.search(SearchQuery.from(COMMON_TERM, null, null, null, null, null)).total() == 0) {
            throw new IllegalStateException("No books match \"" + COMMON_TERM + "\" in bench_index_" + indexSize);
        }
        query = switch (search) {
            case "rare" -> SearchQuery.from(RARE_TERM, null, null, null, null, null);
            case "common" -> SearchQuery.from(COMMON_TERM, null, null, null, null, null);
            case "common_language" -> SearchQuery.from(COMMON_TERM, null, "en", null, null, null);
            case "common_author" -> SearchQuery.from(COMMON_TERM, "dickens", null, null, null, null);
            case "common_year" -> SearchQuery.from(COMMON_TERM, null, null, "1920", null, null);
            case "common_combined" -> SearchQuery.from(COMMON_TERM, "dickens", "en", "1920", null, null);
            case "common_page" -> SearchQuery.from(COMMON_TERM, null, null, null, "40", "20");
            default -> throw new IllegalArgumentException("Unknown search: " + search);
        };
    }

    @Benchmark
    public SearchResult search() {
        return service.search(query);
    }

    @TearDown(Level.Trial)
    public void disconnect() {
        client.close();
    }
}
