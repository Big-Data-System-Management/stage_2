package bdsm.ingestion.events;

import bdsm.common.datalake.TimeHierarchyDatalake;
import bdsm.common.events.BookDownloaded;
import bdsm.common.events.BookRequested;
import bdsm.common.events.Publisher;
import bdsm.common.model.Book;
import bdsm.ingestion.source.LocalSource;
import bdsm.ingestion.storage.TimeHierarchyBookLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionPipelineTest {

    @TempDir
    Path workspace;

    private static class CapturingPublisher implements Publisher {
        final List<BookDownloaded> events = new ArrayList<>();

        @Override
        public synchronized void publish(Object event) {
            events.add((BookDownloaded) event);
        }
    }

    private static String gutenbergText(int bookId) {
        return "Title: Book " + bookId + "\nAuthor: Someone\n\n"
                + "*** START OF THE PROJECT GUTENBERG EBOOK BOOK " + bookId + " ***\n\n"
                + "Body of book " + bookId + " with an accent: café\n\n"
                + "*** END OF THE PROJECT GUTENBERG EBOOK BOOK " + bookId + " ***\nlicense";
    }

    private Path source() throws IOException {
        Path source = Files.createDirectories(workspace.resolve("source"));
        for (int id = 1; id <= 3; id++) Files.writeString(source.resolve("pg" + id + ".txt"), gutenbergText(id));
        Files.writeString(source.resolve("pg4.txt"), "text without any marker");
        return source;
    }

    private IngestionHandler handler(Path datalakeRoot, Path source) throws IOException {
        TimeHierarchyDatalake datalake = new TimeHierarchyDatalake(datalakeRoot);
        return new IngestionHandler(datalake, new TimeHierarchyBookLocator(datalakeRoot), new LocalSource(source));
    }

    @Test
    void thePublishedPathLetsAnotherServiceReadTheBookFromTheDatalakeRoot() throws Exception {
        Path root = workspace.resolve("datalake");
        CapturingPublisher publisher = new CapturingPublisher();

        handler(root, source()).handle(new BookRequested(1), publisher);

        BookDownloaded event = publisher.events.get(0);
        assertEquals(1, event.bookId());
        assertTrue(event.path().matches("\\d{8}/\\d{2}"), event.path());
        Book read = TimeHierarchyDatalake.readFrom(root.resolve(event.path()), 1);
        assertEquals("Title: Book 1\nAuthor: Someone", read.header());
        assertEquals("Body of book 1 with an accent: café", read.body());
    }

    @Test
    void anotherServiceFindsEveryStoredBookInTheDatalake() throws Exception {
        Path root = workspace.resolve("datalake");
        CapturingPublisher publisher = new CapturingPublisher();
        IngestionHandler handler = handler(root, source());
        for (int id = 1; id <= 3; id++) handler.handle(new BookRequested(id), publisher);

        TimeHierarchyDatalake indexingView = new TimeHierarchyDatalake(root);
        assertEquals(List.of(1, 2, 3), new ArrayList<>(indexingView.bookIds()));
    }

    @Test
    void requestingTheSameBookTwiceStoresItOnceAndPublishesTwice() throws Exception {
        Path root = workspace.resolve("datalake");
        CapturingPublisher publisher = new CapturingPublisher();
        IngestionHandler handler = handler(root, source());

        handler.handle(new BookRequested(2), publisher);
        handler.handle(new BookRequested(2), publisher);

        assertEquals(2, publisher.events.size());
        assertEquals(publisher.events.get(0), publisher.events.get(1));
        assertEquals(1L, countFiles(root, "2.body.txt"));
        assertEquals(1L, countFiles(root, "2.header.txt"));
    }

    @Test
    void aBatchOfRequestsInAnyOrderIsStoredAndListedInAscendingOrder() throws Exception {
        Path root = workspace.resolve("datalake");
        CapturingPublisher publisher = new CapturingPublisher();
        TimeHierarchyBookLocator locator = new TimeHierarchyBookLocator(root);
        IngestionHandler handler = new IngestionHandler(new TimeHierarchyDatalake(root), locator, new LocalSource(source()));

        for (int id : new int[]{3, 1, 2}) handler.handle(new BookRequested(id), publisher);

        assertEquals(List.of(1, 2, 3), locator.list());
        assertEquals(3, publisher.events.size());
    }

    @Test
    void nothingIsStoredOrPublishedForBooksThatDoNotExistOrHaveNoMarkers() throws Exception {
        Path root = workspace.resolve("datalake");
        CapturingPublisher publisher = new CapturingPublisher();
        TimeHierarchyBookLocator locator = new TimeHierarchyBookLocator(root);
        IngestionHandler handler = new IngestionHandler(new TimeHierarchyDatalake(root), locator, new LocalSource(source()));

        handler.handle(new BookRequested(99), publisher);
        handler.handle(new BookRequested(4), publisher);

        assertTrue(publisher.events.isEmpty());
        assertTrue(locator.list().isEmpty());
        assertEquals(0L, countFiles(root, ".body.txt"));
    }

    private static long countFiles(Path root, String suffix) throws IOException {
        if (!Files.isDirectory(root)) return 0;
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(suffix)).count();
        }
    }
}
