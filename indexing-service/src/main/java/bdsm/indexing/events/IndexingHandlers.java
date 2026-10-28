package bdsm.indexing.events;

import bdsm.common.datalake.Datalake;
import bdsm.common.events.BookDownloaded;
import bdsm.common.events.IndexRebuildRequested;
import bdsm.common.events.Publisher;
import bdsm.indexing.index.InvertedIndex;

public class IndexingHandlers {

    private final Datalake datalake;
    private final InvertedIndex index;

    public IndexingHandlers(Datalake datalake, InvertedIndex index) {
        this.datalake = datalake;
        this.index = index;
    }

    public void onBookDownloaded(BookDownloaded event, Publisher publisher) {
        System.out.printf("[Indexing] BookDownloaded %d received; handler not implemented yet%n", event.bookId());
    }

    public void onRebuildRequested(IndexRebuildRequested event, Publisher publisher) {
        System.out.println("[Indexing] IndexRebuildRequested received; handler not implemented yet");
    }
}
