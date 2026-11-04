package bdsm.ingestion.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface BookLocator {

    Optional<Path> find(int bookId) throws IOException;

    List<Integer> list() throws IOException;

    String relative(Path directory);
}
