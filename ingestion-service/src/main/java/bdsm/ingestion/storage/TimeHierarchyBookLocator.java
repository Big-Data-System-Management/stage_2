package bdsm.ingestion.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class TimeHierarchyBookLocator implements BookLocator {

    private final TimeHierarchyScanner scanner;
    private final BookIndex index;
    private final TimeHierarchyIndexSynchronizer synchronizer;

    public TimeHierarchyBookLocator(Path root) throws IOException {
        this.scanner = new TimeHierarchyScanner(root);
        this.index = new BookIndex();
        this.synchronizer = new TimeHierarchyIndexSynchronizer(scanner, index);
        synchronizer.rebuild();
    }

    @Override
    public Optional<Path> find(int bookId) throws IOException {
        Optional<Path> indexed = findIndexed(bookId);
        if (indexed.isPresent()) return indexed;
        synchronizer.refreshRecent();
        return findIndexed(bookId);
    }

    @Override
    public List<Integer> list() throws IOException {
        synchronizer.refreshAll();
        return index.completeIds();
    }

    @Override
    public String relative(Path directory) {
        return scanner.relative(directory);
    }

    private Optional<Path> findIndexed(int bookId) {
        Optional<BookEntry> entry = index.completeEntry(bookId);
        if (entry.isEmpty()) return Optional.empty();
        String folder = entry.get().folder();
        if (scanner.hasCompleteBook(folder, bookId)) return Optional.of(scanner.resolve(folder));
        index.discard(entry.get());
        return Optional.empty();
    }
}
