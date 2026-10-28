package bdsm.common.datalake;

import bdsm.common.model.Book;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeHierarchyDatalakeTest {

    @TempDir
    Path root;

    @Test
    void storesHeaderAndBodyInADateAndHourFolder() throws Exception {
        Path directory = new TimeHierarchyDatalake(root).store(new Book(5, "Title: X", "body"));

        assertEquals(root, directory.getParent().getParent());
        assertTrue(Files.exists(directory.resolve("5.header.txt")));
        assertTrue(Files.exists(directory.resolve("5.body.txt")));
    }

    @Test
    void findsBooksStoredByAnotherInstance() throws Exception {
        new TimeHierarchyDatalake(root).store(new Book(7, "Title: Y", "text"));

        TimeHierarchyDatalake reopened = new TimeHierarchyDatalake(root);

        assertTrue(reopened.exists(7));
        assertEquals(new Book(7, "Title: Y", "text"), reopened.read(7).orElseThrow());
        assertFalse(reopened.exists(8));
    }
}
