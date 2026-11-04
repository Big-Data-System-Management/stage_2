package bdsm.ingestion.storage;

import java.util.List;
import java.util.Map;

record IndexSnapshot(Map<String, Long> folderModified, List<BookEntry> entries) {

    IndexSnapshot {
        for (BookEntry entry : entries) {
            if (!folderModified.containsKey(entry.folder())) {
                throw new IllegalArgumentException("Book " + entry.id() + " points to an unknown folder");
            }
        }
    }
}
