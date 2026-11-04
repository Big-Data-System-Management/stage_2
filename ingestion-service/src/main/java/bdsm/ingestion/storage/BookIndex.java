package bdsm.ingestion.storage;

import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

final class BookIndex {

    private final ConcurrentNavigableMap<Integer, BookEntry> entries = new ConcurrentSkipListMap<>();
    private final Map<String, FolderState> folders = new HashMap<>();
    private int pendingChanges;

    Optional<BookEntry> completeEntry(int bookId) {
        return Optional.ofNullable(entries.get(bookId)).filter(BookEntry::isComplete);
    }

    List<Integer> completeIds() {
        return entries.values().stream().filter(BookEntry::isComplete).map(BookEntry::id).toList();
    }

    synchronized Optional<FolderState> folder(String name) {
        return Optional.ofNullable(folders.get(name));
    }

    synchronized Set<String> folderNames() {
        return new HashSet<>(folders.keySet());
    }

    synchronized int pendingChanges() {
        return pendingChanges;
    }

    synchronized void replaceFolder(String name, long modified, Map<Integer, BookFiles> found) {
        FolderState previous = folders.put(name, new FolderState(modified, new HashSet<>(found.keySet())));
        if (previous != null) removeBooksNoLongerIn(name, previous, found);
        found.forEach((id, files) -> putEntry(new BookEntry(id, name, files)));
    }

    synchronized void removeFolder(String name) {
        FolderState removed = folders.remove(name);
        if (removed == null) return;
        removed.bookIds().forEach(id -> removeEntryOf(name, id));
    }

    synchronized void discard(BookEntry entry) {
        if (entries.remove(entry.id(), entry)) pendingChanges++;
        folders.computeIfPresent(entry.folder(), (name, folder) -> folder.asStale());
    }

    synchronized void clear() {
        entries.clear();
        folders.clear();
    }

    synchronized void restore(IndexSnapshot snapshot) {
        clear();
        snapshot.folderModified().forEach((name, modified) -> folders.put(name, new FolderState(modified, new HashSet<>())));
        for (BookEntry entry : snapshot.entries()) {
            entries.put(entry.id(), entry);
            folders.get(entry.folder()).bookIds().add(entry.id());
        }
        pendingChanges = 0;
    }

    synchronized IndexSnapshot snapshot() {
        Map<String, Long> folderModified = new TreeMap<>();
        folders.forEach((name, folder) -> folderModified.put(name, folder.modified()));
        pendingChanges = 0;
        return new IndexSnapshot(folderModified, List.copyOf(entries.values()));
    }

    private void removeBooksNoLongerIn(String folder, FolderState previous, Map<Integer, BookFiles> found) {
        for (int id : previous.bookIds()) {
            if (!found.containsKey(id)) removeEntryOf(folder, id);
        }
    }

    private void removeEntryOf(String folder, int bookId) {
        BookEntry current = entries.get(bookId);
        if (current != null && current.folder().equals(folder) && entries.remove(bookId, current)) {
            pendingChanges++;
        }
    }

    private void putEntry(BookEntry fresh) {
        BookEntry current = entries.get(fresh.id());
        if (current != null && !shouldReplace(current, fresh)) return;
        entries.put(fresh.id(), fresh);
        if (!fresh.equals(current)) pendingChanges++;
    }

    private static boolean shouldReplace(BookEntry current, BookEntry fresh) {
        return current.folder().equals(fresh.folder()) || !current.isComplete();
    }
}
