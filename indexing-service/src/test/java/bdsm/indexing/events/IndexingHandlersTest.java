package bdsm.indexing.events;

import bdsm.common.datalake.TimeHierarchyDatalake;
import bdsm.common.events.BookDownloaded;
import bdsm.common.events.BookIndexed;
import bdsm.common.events.IndexRebuildRequested;
import bdsm.common.model.Book;
import bdsm.common.model.BookMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexingHandlersTest {

    @TempDir
    Path root;

    private final InMemoryDatamart datamart = new InMemoryDatamart();
    private final List<Object> published = new ArrayList<>();
    private IndexingHandlers handlers;

    @BeforeEach
    void setUp() throws Exception {
        handlers = new IndexingHandlers(new TimeHierarchyDatalake(root), datamart);
    }

    @Test
    void indexesADownloadedBookAndPublishesBookIndexed() throws Exception {
        String path = store(new Book(11, "Title: Alice\nLanguage: English\n", "rabbit hole"));

        handlers.onBookDownloaded(new BookDownloaded(11, path), published::add);

        assertEquals(new BookMetadata(11, "Alice", "Unknown", "en", null), datamart.published.get(11));
        assertEquals(path, datamart.paths.get(11));
        assertEquals(List.of(new BookIndexed(11)), published);
    }

    @Test
    void aRepeatedEventDoesNotIndexTheBookAgainButStillConfirmsIt() throws Exception {
        String path = store(new Book(11, "Title: Alice\n", "rabbit"));

        handlers.onBookDownloaded(new BookDownloaded(11, path), published::add);
        handlers.onBookDownloaded(new BookDownloaded(11, path), published::add);

        assertEquals(List.of(11), datamart.indexCalls);
        assertEquals(List.of(new BookIndexed(11), new BookIndexed(11)), published);
    }

    @Test
    void failsWithoutPublishingWhenTheBookIsNotInTheDatalake() {
        assertThrows(NoSuchFileException.class,
                () -> handlers.onBookDownloaded(new BookDownloaded(99, "20260101/00"), published::add));

        assertTrue(published.isEmpty());
        assertTrue(datamart.published.isEmpty());
    }

    @Test
    void rebuildIndexesEveryBookInTheDatalakeIncludingOnesStoredByOtherProcesses() throws Exception {
        datamart.index(new BookMetadata(500, "Stale", "Unknown", null, null), "old", "");
        store(new Book(1, "Title: One\n", "first"));
        store(new Book(2, "Title: Two\n", "second"));

        handlers.onRebuildRequested(new IndexRebuildRequested(), published::add);

        assertEquals(Set.of(1, 2), datamart.published.keySet());
        assertEquals("Two", datamart.published.get(2).title());
        assertTrue(published.isEmpty());
    }

    private String store(Book book) throws Exception {
        Path directory = new TimeHierarchyDatalake(root).store(book);
        return root.relativize(directory).toString().replace('\\', '/');
    }
}
