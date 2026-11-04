package bdsm.ingestion.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeHierarchyScannerTest {

    @TempDir
    Path root;

    private TimeHierarchyScanner scanner() {
        return new TimeHierarchyScanner(root);
    }

    private void write(String folder, String... fileNames) throws Exception {
        Path directory = Files.createDirectories(root.resolve(folder));
        for (String fileName : fileNames) Files.writeString(directory.resolve(fileName), "x");
    }

    @Test
    void listsTheHourFoldersInChronologicalOrder() throws Exception {
        write("20261028/10");
        write("20261027/23");
        write("20261028/09");

        assertEquals(List.of("20261027/23", "20261028/09", "20261028/10"), scanner().allFolders());
    }

    @Test
    void recentFoldersAreTheNewestOnesFirst() throws Exception {
        write("20261027/23");
        write("20261028/00");
        write("20261028/01");

        assertEquals(List.of("20261028/01", "20261028/00"), scanner().recentFolders());
    }

    @Test
    void recentFoldersCrossTheDayBoundary() throws Exception {
        write("20261027/23");
        write("20261028/01");

        assertEquals(List.of("20261028/01", "20261027/23"), scanner().recentFolders());
    }

    @Test
    void readsWhichFilesEachBookHas() throws Exception {
        write("20261028/10", "1.header.txt", "1.body.txt", "2.header.txt", "3.body.txt",
                "4.body.txt.tmp", "notes.txt", "x.body.txt");

        Map<Integer, BookFiles> books = scanner().booksIn("20261028/10");

        assertEquals(Map.of(1, new BookFiles(true, true), 2, BookFiles.HEADER_ONLY, 3, BookFiles.BODY_ONLY), books);
    }

    @Test
    void aFolderThatDoesNotExistHasNoBooks() throws Exception {
        assertTrue(scanner().booksIn("20261028/10").isEmpty());
    }

    @Test
    void reportsAFolderThatDoesNotExistAsMissing() throws Exception {
        assertEquals(FolderState.MISSING, scanner().modifiedOf("20261028/10"));
    }

    @Test
    void aBookIsCompleteOnlyWithItsHeaderAndItsBody() throws Exception {
        write("20261028/10", "1.header.txt", "1.body.txt", "2.header.txt");

        assertTrue(scanner().hasCompleteBook("20261028/10", 1));
        assertFalse(scanner().hasCompleteBook("20261028/10", 2));
    }

    @Test
    void relativePathsUseForwardSlashes() {
        assertEquals("20261028/10", scanner().relative(root.resolve("20261028").resolve("10")));
    }
}
