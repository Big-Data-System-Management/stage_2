package bdsm.ingestion.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class LocalSource implements BookSource {

    private final Path directory;

    public LocalSource(Path directory) {
        this.directory = directory;
    }

    @Override
    public Optional<String> fetch(int bookId) throws IOException {
        Path file = directory.resolve("pg" + bookId + ".txt");
        if (!Files.exists(file)) return Optional.empty();
        return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
    }
}
