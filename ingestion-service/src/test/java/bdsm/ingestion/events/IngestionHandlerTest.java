package bdsm.ingestion.events;

import bdsm.common.datalake.TimeHierarchyDatalake;
import bdsm.common.events.BookDownloaded;
import bdsm.common.events.BookRequested;
import bdsm.common.events.Publisher;
import bdsm.common.model.Book;
import bdsm.ingestion.source.BookSource;
import bdsm.ingestion.storage.TimeHierarchyBookLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionHandlerTest {

    private static final String TEXT = "Title: Pi\n\n*** START OF THE PROJECT GUTENBERG EBOOK PI ***\n\n3.14159\n\n*** END OF THE PROJECT GUTENBERG EBOOK PI ***\nlicense";

    @TempDir
    Path root;

    private static class CountingSource implements BookSource {
        final AtomicInteger calls = new AtomicInteger();
        private final BookSource delegate;

        CountingSource(BookSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<String> fetch(int bookId) throws IOException, InterruptedException {
            calls.incrementAndGet();
            return delegate.fetch(bookId);
        }
    }

    private static class CapturingPublisher implements Publisher {
        final List<Object> events = new ArrayList<>();

        @Override
        public synchronized void publish(Object event) {
            events.add(event);
        }
    }

    private IngestionHandler handlerWith(BookSource source) throws IOException {
        return new IngestionHandler(new TimeHierarchyDatalake(root), new TimeHierarchyBookLocator(root), source);
    }

    @Test
    void storesANewBookAndPublishesItsRelativePath() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();

        handlerWith(id -> Optional.of(TEXT)).handle(new BookRequested(5), publisher);

        assertEquals(1, publisher.events.size());
        BookDownloaded event = (BookDownloaded) publisher.events.get(0);
        assertEquals(5, event.bookId());
        assertTrue(event.path().matches("\\d{8}/\\d{2}"), event.path());
        assertEquals(new Book(5, "Title: Pi", "3.14159"), TimeHierarchyDatalake.readFrom(root.resolve(event.path()), 5));
    }

    @Test
    void doesNotDownloadAnExistingBookButAnnouncesItAgain() throws Exception {
        CountingSource source = new CountingSource(id -> Optional.of(TEXT));
        IngestionHandler handler = handlerWith(source);
        CapturingPublisher publisher = new CapturingPublisher();

        handler.handle(new BookRequested(5), publisher);
        handler.handle(new BookRequested(5), publisher);

        assertEquals(1, source.calls.get());
        assertEquals(2, publisher.events.size());
        assertEquals(publisher.events.get(0), publisher.events.get(1));
        assertEquals(List.of(5), new TimeHierarchyBookLocator(root).list());
    }

    @Test
    void ignoresABookThatDoesNotExist() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();

        handlerWith(id -> Optional.empty()).handle(new BookRequested(404), publisher);

        assertTrue(publisher.events.isEmpty());
        assertTrue(new TimeHierarchyBookLocator(root).list().isEmpty());
    }

    @Test
    void ignoresABookWithoutGutenbergMarkers() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();

        handlerWith(id -> Optional.of("plain text without markers")).handle(new BookRequested(6), publisher);

        assertTrue(publisher.events.isEmpty());
        assertTrue(new TimeHierarchyBookLocator(root).list().isEmpty());
    }

    @Test
    void rethrowsTemporaryErrorsSoTheBrokerRetries() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        IngestionHandler handler = handlerWith(id -> {
            throw new IOException("Project Gutenberg answered HTTP 503 for book " + id);
        });

        assertThrows(IOException.class, () -> handler.handle(new BookRequested(7), publisher));

        assertTrue(publisher.events.isEmpty());
        assertTrue(new TimeHierarchyBookLocator(root).list().isEmpty());
    }

    @Test
    void usesTheDiskAndNotTheMemoryOfTheProcessToFindExistingBooks() throws Exception {
        CountingSource sourceOfB = new CountingSource(id -> Optional.of(TEXT));
        IngestionHandler replicaB = handlerWith(sourceOfB);
        CapturingPublisher publisher = new CapturingPublisher();
        handlerWith(id -> Optional.of(TEXT)).handle(new BookRequested(8), new CapturingPublisher());

        replicaB.handle(new BookRequested(8), publisher);

        assertEquals(0, sourceOfB.calls.get());
        assertEquals(1, publisher.events.size());
        assertFalse(((BookDownloaded) publisher.events.get(0)).path().isBlank());
    }

    @Test
    void concurrentWorkersDownloadTheSameBookOnlyOnce() throws Exception {
        CountingSource source = new CountingSource(id -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            return Optional.of(TEXT);
        });
        IngestionHandler handler = handlerWith(source);
        CapturingPublisher publisher = new CapturingPublisher();
        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                handler.handle(new BookRequested(9), publisher);
                return null;
            }));
        }

        start.countDown();
        for (Future<?> result : results) result.get();
        pool.shutdown();

        assertEquals(1, source.calls.get());
        assertEquals(workers, publisher.events.size());
        assertEquals(List.of(9), new TimeHierarchyBookLocator(root).list());
    }
}
