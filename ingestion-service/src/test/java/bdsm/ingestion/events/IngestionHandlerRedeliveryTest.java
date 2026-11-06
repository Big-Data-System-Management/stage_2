package bdsm.ingestion.events;

import bdsm.common.datalake.TimeHierarchyDatalake;
import bdsm.common.events.BookDownloaded;
import bdsm.common.events.BookRequested;
import bdsm.common.events.Publisher;
import bdsm.common.model.Book;
import bdsm.ingestion.source.BookSource;
import bdsm.ingestion.storage.TimeHierarchyBookLocator;
import jakarta.jms.JMSException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionHandlerRedeliveryTest {

    private static final String TEXT = "Title: Pi\n\n*** START OF THE PROJECT GUTENBERG EBOOK PI ***\n\n3.14159\n\n*** END OF THE PROJECT GUTENBERG EBOOK PI ***\nlicense";

    @TempDir
    Path root;

    private static class ScriptedSource implements BookSource {
        final AtomicInteger calls = new AtomicInteger();
        private final int failures;
        private final Runnable beforeAnswering;

        ScriptedSource(int failures, Runnable beforeAnswering) {
            this.failures = failures;
            this.beforeAnswering = beforeAnswering;
        }

        @Override
        public Optional<String> fetch(int bookId) throws IOException {
            if (calls.incrementAndGet() <= failures) throw new IOException("Project Gutenberg answered HTTP 503 for book " + bookId);
            beforeAnswering.run();
            return Optional.of(TEXT);
        }
    }

    private static class FlakyPublisher implements Publisher {
        final List<BookDownloaded> events = new ArrayList<>();
        private int failures;

        FlakyPublisher(int failures) {
            this.failures = failures;
        }

        @Override
        public void publish(Object event) throws JMSException {
            if (failures-- > 0) throw new JMSException("The broker is not reachable");
            events.add((BookDownloaded) event);
        }
    }

    private IngestionHandler handler(BookSource source) throws IOException {
        return new IngestionHandler(new TimeHierarchyDatalake(root), new TimeHierarchyBookLocator(root), source);
    }

    private long bodies() throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".body.txt")).count();
        }
    }

    @Test
    void aTemporaryFailureLeavesNothingBehindAndTheRedeliveryWorks() throws Exception {
        ScriptedSource source = new ScriptedSource(1, () -> { });
        IngestionHandler handler = handler(source);
        FlakyPublisher publisher = new FlakyPublisher(0);

        assertThrows(IOException.class, () -> handler.handle(new BookRequested(5), publisher));
        assertTrue(publisher.events.isEmpty());
        assertEquals(0L, bodies());

        handler.handle(new BookRequested(5), publisher);

        assertEquals(1, publisher.events.size());
        assertEquals(1L, bodies());
        assertEquals(2, source.calls.get());
    }

    @Test
    void aFailureWhilePublishingKeepsTheBookAndTheRetryDoesNotDownloadItAgain() throws Exception {
        ScriptedSource source = new ScriptedSource(0, () -> { });
        IngestionHandler handler = handler(source);
        FlakyPublisher publisher = new FlakyPublisher(1);

        assertThrows(JMSException.class, () -> handler.handle(new BookRequested(6), publisher));
        assertEquals(1L, bodies());

        handler.handle(new BookRequested(6), publisher);

        assertEquals(1, publisher.events.size());
        assertEquals(1L, bodies());
        assertEquals(1, source.calls.get());
    }

    @Test
    void aBookStoredByAnotherInstanceDuringTheDownloadIsNotStoredAgain() throws Exception {
        Runnable otherInstance = () -> {
            try {
                new TimeHierarchyDatalake(root).store(new Book(7, "Title: Pi", "3.14159"));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
        ScriptedSource source = new ScriptedSource(0, otherInstance);
        IngestionHandler handler = handler(source);
        FlakyPublisher publisher = new FlakyPublisher(0);

        handler.handle(new BookRequested(7), publisher);

        assertEquals(1L, bodies());
        assertEquals(1, publisher.events.size());
        assertTrue(Files.isRegularFile(root.resolve(publisher.events.get(0).path()).resolve("7.body.txt")));
    }

    @Test
    void anInterruptedDownloadIsPropagatedWithoutPublishing() throws Exception {
        BookSource interrupted = bookId -> {
            throw new InterruptedException("shutting down");
        };
        IngestionHandler handler = handler(interrupted);
        FlakyPublisher publisher = new FlakyPublisher(0);

        assertThrows(InterruptedException.class, () -> handler.handle(new BookRequested(8), publisher));
        assertTrue(publisher.events.isEmpty());
        assertEquals(0L, bodies());
    }
}
