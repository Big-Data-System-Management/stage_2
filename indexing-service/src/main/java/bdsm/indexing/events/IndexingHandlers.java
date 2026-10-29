package bdsm.indexing.events;

import bdsm.common.datalake.TimeHierarchyDatalake;
import bdsm.common.events.BookDownloaded;
import bdsm.common.events.BookIndexed;
import bdsm.common.events.IndexRebuildRequested;
import bdsm.common.events.Publisher;
import bdsm.common.model.Book;
import bdsm.indexing.index.Datamart;
import bdsm.indexing.metadata.MetadataExtractor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

public class IndexingHandlers {

    private final TimeHierarchyDatalake datalake;
    private final Datamart datamart;

    public IndexingHandlers(TimeHierarchyDatalake datalake, Datamart datamart) {
        this.datalake = datalake;
        this.datamart = datamart;
    }

    public void onBookDownloaded(BookDownloaded event, Publisher publisher) throws Exception {
        int bookId = event.bookId();
        if (datamart.isPublished(bookId)) {
            System.out.printf("[Indexing] Book %d is already published%n", bookId);
        } else {
            index(datamart, bookId, event.path());
            System.out.printf("[Indexing] Book %d indexed%n", bookId);
        }
        publisher.publish(new BookIndexed(bookId));
    }

    public void onRebuildRequested(IndexRebuildRequested event, Publisher publisher) throws Exception {
        long start = System.nanoTime();
        datalake.scan();
        int[] processed = {0};
        datamart.rebuild(staging -> {
            for (int bookId : datalake.bookIds()) {
                index(staging, bookId, relativeDirectory(bookId));
                processed[0]++;
            }
        });
        int caughtUp = indexMissingBooks();
        System.out.printf(Locale.ROOT, "[Indexing] Index rebuilt from %d books in %.1f s%n",
                processed[0] + caughtUp, (System.nanoTime() - start) / 1e9);
    }

    private int indexMissingBooks() throws IOException {
        datalake.scan();
        int indexed = 0;
        for (int bookId : datalake.bookIds()) {
            if (datamart.isPublished(bookId)) continue;
            index(datamart, bookId, relativeDirectory(bookId));
            indexed++;
        }
        return indexed;
    }

    private void index(Datamart target, int bookId, String path) throws IOException {
        Book book = TimeHierarchyDatalake.readFrom(datalake.root().resolve(path), bookId);
        target.index(MetadataExtractor.extract(book), path, book.body());
    }

    private String relativeDirectory(int bookId) {
        Path directory = datalake.directory(bookId).orElseThrow();
        return datalake.root().relativize(directory).toString().replace('\\', '/');
    }
}
