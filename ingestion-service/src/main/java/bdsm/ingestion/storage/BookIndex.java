package bdsm.ingestion.storage;

import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

final class BookIndex {

    private final ConcurrentNavigableMap<Integer, BookEntry> entries = new ConcurrentSkipListMap<>();
    private final Map<String, FolderState> folders = new HashMap<>();

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
        entries.remove(entry.id(), entry);
        folders.computeIfPresent(entry.folder(), (name, folder) -> folder.asStale());
    }

    synchronized void clear() {
        entries.clear();
        folders.clear();
    }

    private void removeBooksNoLongerIn(String folder, FolderState previous, Map<Integer, BookFiles> found) {
        for (int id : previous.bookIds()) {
            if (!found.containsKey(id)) removeEntryOf(folder, id);
        }
    }

    private void removeEntryOf(String folder, int bookId) {
        BookEntry current = entries.get(bookId);
        if (current != null && current.folder().equals(folder)) entries.remove(bookId, current);
    }

    private void putEntry(BookEntry fresh) {
        BookEntry current = entries.get(fresh.id());
        if (current == null || shouldReplace(current, fresh)) entries.put(fresh.id(), fresh);
    }

    private static boolean shouldReplace(BookEntry current, BookEntry fresh) {
        return current.folder().equals(fresh.folder()) || !current.isComplete();
    }
}
