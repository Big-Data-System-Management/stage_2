package bdsm.ingestion.storage;

import java.util.Set;

record FolderState(long modified, Set<Integer> bookIds) {

    static final long MISSING = -1L;
    static final long STALE = -2L;

    FolderState asStale() {
        return new FolderState(STALE, bookIds);
    }

    boolean isUpToDate(long currentModified) {
        return modified == currentModified;
    }
}
