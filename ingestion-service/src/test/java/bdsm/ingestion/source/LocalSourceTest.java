package bdsm.ingestion.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSourceTest {

    @TempDir
    Path folder;

    @Test
    void readsTheBookFileAsUtf8() throws Exception {
        Files.writeString(folder.resolve("pg11.txt"), "café ñ", StandardCharsets.UTF_8);

        assertEquals("café ñ", new LocalSource(folder).fetch(11).orElseThrow());
    }

    @Test
    void answersEmptyForABookThatIsNotInTheFolder() throws Exception {
        Files.writeString(folder.resolve("pg11.txt"), "text");

        assertTrue(new LocalSource(folder).fetch(12).isEmpty());
    }

    @Test
    void doesNotMixBooksWhoseIdsShareDigits() throws Exception {
        Files.writeString(folder.resolve("pg1.txt"), "one");
        Files.writeString(folder.resolve("pg11.txt"), "eleven");

        assertEquals("one", new LocalSource(folder).fetch(1).orElseThrow());
        assertEquals("eleven", new LocalSource(folder).fetch(11).orElseThrow());
    }

    @Test
    void answersEmptyWhenTheFolderDoesNotExist() throws Exception {
        assertTrue(new LocalSource(folder.resolve("missing")).fetch(1).isEmpty());
    }
}
