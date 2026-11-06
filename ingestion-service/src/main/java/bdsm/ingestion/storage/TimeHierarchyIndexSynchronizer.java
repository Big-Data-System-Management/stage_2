package bdsm.ingestion.storage;

import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

final class TimeHierarchyIndexSynchronizer {

    private final TimeHierarchyScanner scanner;
    private final BookIndex index;

    TimeHierarchyIndexSynchronizer(TimeHierarchyScanner scanner, BookIndex index) {
        this.scanner = scanner;
        this.index = index;
    }

    synchronized void rebuild() throws IOException {
        index.clear();
        for (String folder : scanner.allFolders()) rescan(folder);
    }

    synchronized void refreshAll() throws IOException {
        Set<String> recent = new HashSet<>(scanner.recentFolders());
        Set<String> present = new HashSet<>();
        for (String folder : scanner.allFolders()) {
            present.add(folder);
            if (recent.contains(folder) || isOutdated(folder)) rescan(folder);
        }
        for (String known : index.folderNames()) {
            if (!present.contains(known)) index.removeFolder(known);
        }
    }

    synchronized void refreshRecent() throws IOException {
        for (String folder : scanner.recentFolders()) rescan(folder);
    }

    private boolean isOutdated(String folder) throws IOException {
        Optional<FolderState> known = index.folder(folder);
        return known.isEmpty() || !known.get().isUpToDate(scanner.modifiedOf(folder));
    }

    private void rescan(String folder) throws IOException {
        long modified = scanner.modifiedOf(folder);
        index.replaceFolder(folder, modified, scanner.booksIn(folder));
    }
}
