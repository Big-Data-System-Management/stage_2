package bdsm.indexing.index;

import bdsm.common.model.BookMetadata;

public interface Datamart {
    boolean isPublished(int bookId);

    void index(BookMetadata metadata, String path, String body);

    void rebuild(Loader loader) throws Exception;

    IndexStats stats();

    interface Loader {
        void load(Datamart staging) throws Exception;
    }
}
