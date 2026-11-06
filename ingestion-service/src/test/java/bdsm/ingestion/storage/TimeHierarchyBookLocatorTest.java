package bdsm.ingestion.storage;

import bdsm.common.datalake.TimeHierarchyDatalake;
import bdsm.common.model.Book;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
    void aRestartFindsBooksStoredInOldFoldersWhileTheServiceWasStopped() throws Exception {
        writeBook("20260101", "05", 1, true, true);
        writeBook("20260301", "09", 10, true, true);
        writeBook("20260301", "10", 11, true, true);
        new TimeHierarchyBookLocator(root);
        writeBook("20260101", "05", 2, true, true);

        TimeHierarchyBookLocator restarted = new TimeHierarchyBookLocator(root);

        assertTrue(restarted.find(2).isPresent());
        assertEquals(List.of(1, 2, 10, 11), restarted.list());
    }

    @Test
    void ignoresAnOldIndexFileLeftInTheDatalakeRoot() throws Exception {
        writeBook("20261028", "10", 8, true, true);
        Files.writeString(root.resolve(".ingestion-index.tsv"), "ingestion-index-v1\nB\tnot-a-number\n");

        TimeHierarchyBookLocator locator = new TimeHierarchyBookLocator(root);

        assertEquals(List.of(8), locator.list());
        assertTrue(locator.find(8).isPresent());
    }

}
