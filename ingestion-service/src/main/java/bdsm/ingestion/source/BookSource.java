package bdsm.ingestion.source;

import java.io.IOException;
import java.util.Optional;

public interface BookSource {
    Optional<String> fetch(int bookId) throws IOException, InterruptedException;
}
