package bdsm.ingestion.events;

import bdsm.common.datalake.Datalake;
import bdsm.common.events.BookDownloaded;
import bdsm.common.events.BookRequested;
import bdsm.common.events.EventHandler;
import bdsm.common.events.Publisher;
import bdsm.common.model.Book;
import bdsm.ingestion.source.BookSource;
import bdsm.ingestion.source.GutenbergSplitter;
import bdsm.ingestion.storage.BookLocator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class IngestionHandler implements EventHandler<BookRequested> {

    private static final int LOCK_STRIPES = 32;

    private final Datalake datalake;
    private final BookLocator locator;
    private final BookSource source;
    private final Object[] locks = new Object[LOCK_STRIPES];

    public IngestionHandler(Datalake datalake, BookLocator locator, BookSource source) {
        this.datalake = datalake;
        this.locator = locator;
        this.source = source;
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new Object();
    }

    @Override
    public void handle(BookRequested event, Publisher publisher) throws Exception {
        int bookId = event.bookId();
        Optional<String> path = getOrStoreBook(bookId);
        if (path.isEmpty()) return;
        notifyBookDownloaded(publisher, bookId, path.get());
    }

    private Optional<String> getOrStoreBook(int bookId) throws Exception {
        synchronized (locks[Math.floorMod(bookId, LOCK_STRIPES)]) {
            return ensureStored(bookId);
        }
    }

    private Optional<String> ensureStored(int bookId) throws Exception {
        Optional<Path> stored = locator.find(bookId);
        if (stored.isPresent()) {
            System.out.printf("[Ingestion] Book %d is already stored, skipping the download%n", bookId);
            return stored.map(locator::relative);
        }
        return downloadBookAndEnsureStored(bookId);
    }

    private Optional<String> downloadBookAndEnsureStored(int bookId) throws IOException, InterruptedException {
        Optional<String> text = source.fetch(bookId);
        if (text.isEmpty()) {
            System.err.printf("[Ingestion] Book %d does not exist, it will not be retried%n", bookId);
            return Optional.empty();
        }
        Optional<Book> book = GutenbergSplitter.split(bookId, text.get());
        if (book.isEmpty()) {
            System.err.printf("[Ingestion] Book %d has no Project Gutenberg markers, it will not be retried%n", bookId);
            return Optional.empty();
        }
        Optional<Path> stored = locator.find(bookId);
        if (stored.isPresent()) {
            System.out.printf("[Ingestion] Book %d was stored by another instance meanwhile%n", bookId);
            return stored.map(locator::relative);
        }
        return Optional.of(locator.relative(datalake.store(book.get())));
    }

    private void notifyBookDownloaded(Publisher publisher, int bookId, String path) throws Exception {
        publisher.publish(new BookDownloaded(bookId, path));
        System.out.printf("[Ingestion] Book %d is available at %s%n", bookId, path);
    }
}
