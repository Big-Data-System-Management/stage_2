package bdsm.common.benchmark;

import bdsm.common.config.Config;
import bdsm.common.text.Tokenizer;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Threads(1)
@Fork(1)
public class TokenizerBenchmark {

    @Param({"1", "11", "15"})
    public int bookId;

    private final Tokenizer tokenizer = new Tokenizer();
    private String text;

    @Setup
    public void readBook() throws IOException {
        Path dataset = Config.getPath("BENCH_SAMPLE_DATASET", "sample_dataset");
        text = Files.readString(dataset.resolve("pg" + bookId + ".txt"));
    }

    @Benchmark
    public List<String> tokenize() {
        return tokenizer.tokenize(text);
    }
}
