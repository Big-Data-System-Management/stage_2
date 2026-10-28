package bdsm.ingestion.events;

import bdsm.common.datalake.Datalake;
import bdsm.common.events.BookRequested;
import bdsm.common.events.EventHandler;
import bdsm.common.events.Publisher;
import bdsm.ingestion.source.BookSource;

public class IngestionHandler implements EventHandler<BookRequested> {

    private final Datalake datalake;
    private final BookSource source;

    public IngestionHandler(Datalake datalake, BookSource source) {
        this.datalake = datalake;
        this.source = source;
    }

    @Override
    public void handle(BookRequested event, Publisher publisher) {
        System.out.printf("[Ingestion] BookRequested %d received; handler not implemented yet%n", event.bookId());
    }
}
