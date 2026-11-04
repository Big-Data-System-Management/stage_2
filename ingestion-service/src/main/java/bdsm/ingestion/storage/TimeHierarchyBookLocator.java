package bdsm.ingestion.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class TimeHierarchyBookLocator implements BookLocator, AutoCloseable {

    private static final int SAVE_THRESHOLD = 100;

    private final TimeHierarchyScanner scanner;
    private final BookIndex index;
    private final IndexRepository repository;
    private final TimeHierarchyIndexSynchronizer synchronizer;

    public TimeHierarchyBookLocator(Path root) throws IOException {
        this(new TimeHierarchyScanner(root), new BookIndex(), new TsvIndexRepository(root));
    }

    TimeHierarchyBookLocator(TimeHierarchyScanner scanner, BookIndex index, IndexRepository repository) throws IOException {
        this.scanner = scanner;
        this.index = index;
        this.repository = repository;
        this.synchronizer = new TimeHierarchyIndexSynchronizer(scanner, index);
        startFromSavedIndex();
    }

    @Override
    public Optional<Path> find(int bookId) throws IOException {
        Optional<Path> indexed = findIndexed(bookId);
        if (indexed.isPresent()) return indexed;
        synchronizer.refreshRecent();
        saveIfNeeded();
        return findIndexed(bookId);
    }

    @Override
    public List<Integer> list() throws IOException {
        synchronizer.refreshAll();
        saveIfNeeded();
        return index.completeIds();
    }

    @Override
    public String relative(Path directory) {
        return scanner.relative(directory);
    }

    public void reconcile() throws IOException {
        synchronizer.rebuild();
        save();
    }

    public void save() throws IOException {
        repository.save(index.snapshot());
    }

    @Override
    public void close() throws IOException {
        save();
    }

    private void startFromSavedIndex() throws IOException {
        Optional<IndexSnapshot> saved = repository.load();
        if (saved.isEmpty()) {
            reconcile();
            return;
        }
        index.restore(saved.get());
        synchronizer.refreshAll();
        save();
    }

    private Optional<Path> findIndexed(int bookId) {
        Optional<BookEntry> entry = index.completeEntry(bookId);
        if (entry.isEmpty()) return Optional.empty();
        String folder = entry.get().folder();
        if (scanner.hasCompleteBook(folder, bookId)) return Optional.of(scanner.resolve(folder));
        index.discard(entry.get());
        return Optional.empty();
    }

    private void saveIfNeeded() throws IOException {
        if (index.pendingChanges() >= SAVE_THRESHOLD) save();
    }
}
