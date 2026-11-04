package bdsm.ingestion.storage;

import bdsm.common.datalake.TimeHierarchyDatalake;
import bdsm.common.model.Book;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeHierarchyBookLocatorTest {

    @TempDir
    Path root;

    private Path writeBook(String day, String hour, int id, boolean header, boolean body) throws Exception {
        Path directory = Files.createDirectories(root.resolve(day).resolve(hour));
        if (header) Files.writeString(directory.resolve(id + ".header.txt"), "Title: X");
        if (body) Files.writeString(directory.resolve(id + ".body.txt"), "text");
        return directory;
    }

    @Test
    void findsABookStoredByAnotherInstanceAfterTheLocatorWasCreated() throws Exception {
        TimeHierarchyBookLocator locator = new TimeHierarchyBookLocator(root);
        assertTrue(locator.find(3).isEmpty());

        Path directory = new TimeHierarchyDatalake(root).store(new Book(3, "Title: X", "text"));

        assertEquals(directory, locator.find(3).orElseThrow());
        assertEquals(List.of(3), locator.list());
    }

    @Test
    void ignoresAnIncompleteBookUntilItsBodyArrives() throws Exception {
        TimeHierarchyBookLocator locator = new TimeHierarchyBookLocator(root);
        Path directory = writeBook("20261028", "10", 4, true, false);

        assertTrue(locator.find(4).isEmpty());
        assertTrue(locator.list().isEmpty());

        Files.writeString(directory.resolve("4.body.txt"), "text");

        assertEquals(directory, locator.find(4).orElseThrow());
    }

    @Test
    void forgetsABookWhoseFilesWereDeleted() throws Exception {
        Path directory = new TimeHierarchyDatalake(root).store(new Book(5, "Title: X", "text"));
        TimeHierarchyBookLocator locator = new TimeHierarchyBookLocator(root);
        assertTrue(locator.find(5).isPresent());

        Files.delete(directory.resolve("5.body.txt"));

        assertTrue(locator.find(5).isEmpty());
        assertTrue(locator.list().isEmpty());
    }

    @Test
    void reloadsTheSavedIndexAndKeepsTheSameBooks() throws Exception {
        TimeHierarchyDatalake datalake = new TimeHierarchyDatalake(root);
        datalake.store(new Book(12, "h", "b"));
        datalake.store(new Book(2, "h", "b"));
        TimeHierarchyBookLocator first = new TimeHierarchyBookLocator(root);
        first.save();

        TimeHierarchyBookLocator second = new TimeHierarchyBookLocator(root);

        assertTrue(Files.isRegularFile(root.resolve(".ingestion-index.tsv")));
        assertEquals(List.of(2, 12), second.list());
        assertEquals(first.find(2), second.find(2));
    }

    @Test
    void catchesUpWithBooksStoredWhileTheServiceWasStopped() throws Exception {
        writeBook("20260101", "05", 1, true, true);
        writeBook("20260301", "09", 10, true, true);
        writeBook("20260301", "10", 11, true, true);
        new TimeHierarchyBookLocator(root).save();
        Path oldFolder = writeBook("20260101", "05", 2, true, true);
        Files.setLastModifiedTime(oldFolder, FileTime.fromMillis(System.currentTimeMillis() + 10_000));

        TimeHierarchyBookLocator restarted = new TimeHierarchyBookLocator(root);

        assertEquals(List.of(1, 2, 10, 11), restarted.list());
    }

    @Test
    void rebuildsItselfWhenTheSavedIndexIsCorrupt() throws Exception {
        writeBook("20261028", "10", 8, true, true);
        Files.writeString(root.resolve(".ingestion-index.tsv"), "ingestion-index-v1\nB\tnot-a-number\n");

        TimeHierarchyBookLocator locator = new TimeHierarchyBookLocator(root);

        assertEquals(List.of(8), locator.list());
        assertTrue(locator.find(8).isPresent());
    }

    @Test
    void reconcileRepairsAnIndexThatMissesABookInAnOldFolder() throws Exception {
        writeBook("20261028", "10", 1, true, true);
        TimeHierarchyBookLocator locator = new TimeHierarchyBookLocator(root);
        writeBook("20200101", "05", 9, true, true);
        writeBook("20261028", "11", 2, true, true);
        writeBook("20261028", "12", 3, true, true);

        assertTrue(locator.find(9).isEmpty());

        locator.reconcile();

        assertTrue(locator.find(9).isPresent());
        assertEquals(List.of(1, 2, 3, 9), locator.list());
    }

    @Test
    void savesTheIndexWhenItIsClosed() throws Exception {
        TimeHierarchyBookLocator locator = new TimeHierarchyBookLocator(root);
        new TimeHierarchyDatalake(root).store(new Book(6, "Title: X", "text"));
        assertTrue(locator.find(6).isPresent());

        locator.close();

        IndexSnapshot saved = new TsvIndexRepository(root).load().orElseThrow();
        assertEquals(List.of(6), saved.entries().stream().map(BookEntry::id).toList());
    }
}
