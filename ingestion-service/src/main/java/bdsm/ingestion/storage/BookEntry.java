package bdsm.ingestion.storage;

record BookEntry(int id, String folder, BookFiles files) {

    boolean isComplete() {
        return files.isComplete();
    }
}
