package bdsm.ingestion.storage;

record BookFiles(boolean header, boolean body) {

    static final BookFiles HEADER_ONLY = new BookFiles(true, false);
    static final BookFiles BODY_ONLY = new BookFiles(false, true);

    boolean isComplete() {
        return header && body;
    }

    BookFiles union(BookFiles other) {
        return new BookFiles(header || other.header, body || other.body);
    }
}
