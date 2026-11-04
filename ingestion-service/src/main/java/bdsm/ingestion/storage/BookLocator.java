package bdsm.ingestion.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class BookLocator {

    private static final int SAVE_THRESHOLD = 100;
    private static final long STALE_TIME = -2L;

    public record Entry(int id, String folder, boolean header, boolean body) {
        boolean complete() {
            return header && body;
        }
    }

    public record Folder(long modified, Set<Integer> ids) {
    }

    private final Path root;
    private final BookDirectoryScanner scanner;
    private final BookIndexStore store;

    private final ConcurrentNavigableMap<Integer, Entry> entries = new ConcurrentSkipListMap<>();
    private final Map<String, Folder> folders = new HashMap<>();
    private int unsavedChanges;

    public BookLocator(Path root) throws IOException {
        this.root = root;
        this.scanner = new BookDirectoryScanner(root);
        this.store = new BookIndexStore(root);

        if (store.load(folders, entries)) {
            refresh();
            save();
        } else {
            reconcile();
        }
    }

    public Optional<Path> find(int bookId) throws IOException {
        Optional<Path> known = verified(bookId);
        if (known.isPresent()) return known;
        refreshRecent();
        return verified(bookId);
    }

    public List<Integer> list() throws IOException {
        refresh();
        return entries.values().stream().filter(Entry::complete).map(Entry::id).toList();
    }

    public String relative(Path directory) {
        return scanner.relative(directory);
    }

    public synchronized void refresh() throws IOException {
        Set<String> present = new HashSet<>();
        List<String> recent = scanner.getRecentFolders().stream().map(scanner::relative).toList();

        for (Path directory : scanner.getAllSubdirectories()) {
            String name = scanner.relative(directory);
            present.add(name);
            long modified = scanner.getModificationTime(directory);
            Folder known = folders.get(name);

            if (known == null || known.modified() != modified || recent.contains(name)) {
                scan(directory, modified);
            }
        }

        for (String name : new ArrayList<>(folders.keySet())) {
            if (!present.contains(name)) forget(name);
        }
        saveIfNeeded();
    }

    public synchronized void reconcile() throws IOException {
        entries.clear();
        folders.clear();
        for (Path directory : scanner.getAllSubdirectories()) {
            scan(directory, scanner.getModificationTime(directory));
        }
        save();
    }

    public synchronized void save() throws IOException {
        store.save(folders, entries);
        unsavedChanges = 0;
    }

    private Optional<Path> verified(int bookId) {
        Entry entry = entries.get(bookId);
        if (entry == null || !entry.complete()) return Optional.empty();
        Path directory = root.resolve(entry.folder());
        if (scanner.isBookCompleteOnDisk(directory, bookId)) return Optional.of(directory);
        entries.remove(bookId, entry);
        invalidate(entry.folder());
        return Optional.empty();
    }

    private synchronized void invalidate(String name) {
        folders.computeIfPresent(name, (key, folder) -> new Folder(STALE_TIME, folder.ids()));
    }

    private synchronized void refreshRecent() throws IOException {
        for (Path directory : scanner.getRecentFolders()) {
            scan(directory, scanner.getModificationTime(directory));
        }
        saveIfNeeded();
    }

    private void scan(Path directory, long modified) throws IOException {
        String name = scanner.relative(directory);
        Map<Integer, boolean[]> found = scanner.scanDirectoryForBooks(directory);

        Folder previous = folders.put(name, new Folder(modified, new HashSet<>(found.keySet())));
        if (previous != null) {
            for (int id : previous.ids()) {
                if (!found.containsKey(id)) removeFrom(id, name);
            }
        }

        for (Map.Entry<Integer, boolean[]> item : found.entrySet()) {
            Entry before = entries.get(item.getKey());
            Entry fresh = new Entry(item.getKey(), name, item.getValue()[0], item.getValue()[1]);
            entries.merge(fresh.id(), fresh, (current, candidate) ->
                    current.folder().equals(candidate.folder()) || !current.complete() ? candidate : current);
            if (!Objects.equals(before, entries.get(item.getKey()))) unsavedChanges++;
        }
    }

    private void forget(String name) {
        Folder gone = folders.remove(name);
        if (gone == null) return;
        for (int id : gone.ids()) removeFrom(id, name);
    }

    private void removeFrom(int id, String folder) {
        Entry current = entries.get(id);
        if (current != null && current.folder().equals(folder) && entries.remove(id, current)) {
            unsavedChanges++;
        }
    }

    private void saveIfNeeded() throws IOException {
        if (unsavedChanges >= SAVE_THRESHOLD) save();
    }
}