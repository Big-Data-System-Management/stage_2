package bdsm.indexing.benchmark;

import bdsm.common.config.Config;
import bdsm.common.model.Book;
import bdsm.common.model.BookMetadata;
import bdsm.indexing.metadata.MetadataExtractor;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Threads(1)
@Fork(1)
public class MetadataExtractorBenchmark {

    private static final String BODY_START = "*** START OF";

    @Param({"11", "74750"})
    public int bookId;

    private Book book;

    @Setup
    public void readHeader() throws IOException {
        Path dataset = Config.getPath("BENCH_SAMPLE_DATASET", "sample_dataset");
        String text = Files.readString(dataset.resolve("pg" + bookId + ".txt"));
        book = new Book(bookId, text.substring(0, text.indexOf(BODY_START)), "");
    }

    @Benchmark
    public BookMetadata extract() {
        return MetadataExtractor.extract(book);
    }
}
