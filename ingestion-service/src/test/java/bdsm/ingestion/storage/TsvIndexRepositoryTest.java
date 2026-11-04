package bdsm.ingestion.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsvIndexRepositoryTest {

    @TempDir
    Path root;

    private static IndexSnapshot sample() {
        Map<String, Long> folders = new TreeMap<>();
        folders.put("20261028/10", 5L);
        folders.put("20261028/11", FolderState.STALE);
        return new IndexSnapshot(folders, List.of(
                new BookEntry(1, "20261028/10", new BookFiles(true, true)),
                new BookEntry(2, "20261028/11", BookFiles.HEADER_ONLY)));
    }

    private void writeIndex(String content) throws Exception {
        Files.writeString(root.resolve(".ingestion-index.tsv"), content);
    }

    @Test
    void loadsWhatItSaved() throws Exception {
        TsvIndexRepository repository = new TsvIndexRepository(root);

        repository.save(sample());

        assertEquals(Optional.of(sample()), repository.load());
    }

    @Test
    void hasNothingToLoadBeforeTheFirstSave() {
        assertTrue(new TsvIndexRepository(root).load().isEmpty());
    }

    @Test
    void ignoresAFileWrittenByAnotherVersion() throws Exception {
        writeIndex("ingestion-index-v0\n");

        assertTrue(new TsvIndexRepository(root).load().isEmpty());
    }

    @Test
    void ignoresAMalformedRecord() throws Exception {
        writeIndex("ingestion-index-v1\nB\tnot-a-number\n");

        assertTrue(new TsvIndexRepository(root).load().isEmpty());
    }

    @Test
    void ignoresAnInvalidFlag() throws Exception {
        writeIndex("ingestion-index-v1\nD\t20261028/10\t5\nB\t1\t20261028/10\t2\t1\n");

        assertTrue(new TsvIndexRepository(root).load().isEmpty());
    }

    @Test
    void ignoresABookThatPointsToAnUnknownFolder() throws Exception {
        writeIndex("ingestion-index-v1\nB\t1\t20261028/10\t1\t1\n");

        assertTrue(new TsvIndexRepository(root).load().isEmpty());
    }

    @Test
    void leavesNoTemporaryFilesBehind() throws Exception {
        new TsvIndexRepository(root).save(sample());

        try (var files = Files.list(root)) {
            assertEquals(List.of(".ingestion-index.tsv"), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void doesNotCreateTheRootWhenItDoesNotExist() throws Exception {
        Path missing = root.resolve("missing");

        new TsvIndexRepository(missing).save(sample());

        assertFalse(Files.exists(missing));
    }
}
