package bdsm.ingestion.storage;

import bdsm.common.datalake.TimeHierarchyDatalake;
import bdsm.common.model.Book;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeHierarchyBookLocatorResilienceTest {

    @TempDir
    Path root;

    private Path writeBook(String day, String hour, int id) throws IOException {
        Path directory = Files.createDirectories(root.resolve(day).resolve(hour));
        Files.writeString(directory.resolve(id + ".header.txt"), "Title: X");
        Files.writeString(directory.resolve(id + ".body.txt"), "text");
        return directory;
    }

    @Test
    void ignoresTemporaryAndForeignFiles() throws Exception {
        Path directory = Files.createDirectories(root.resolve("20261028").resolve("10"));
        Files.writeString(directory.resolve("7.header.txt"), "Title: X");
        Files.writeString(directory.resolve("7.body.txt.tmp"), "half written");
        Files.writeString(directory.resolve("abc.body.txt"), "text");
        Files.writeString(directory.resolve("abc.header.txt"), "Title: X");
        Files.writeString(directory.resolve("notes.txt"), "text");
        TimeHierarchyBookLocator locator = new TimeHierarchyBookLocator(root);

        assertTrue(locator.list().isEmpty());
        assertTrue(locator.find(7).isEmpty());
    }

    @Test
    void listsManyBooksInAscendingOrder() throws Exception {
        List<Integer> ids = new ArrayList<>();
        for (int id = 1; id <= 300; id++) ids.add(id * 7);
        List<Integer> shuffled = new ArrayList<>(ids);
        Collections.shuffle(shuffled, new java.util.Random(42));
        int position = 0;
        for (int id : shuffled) writeBook("202610" + String.format("%02d", 1 + position / 30 % 28), String.format("%02d", position % 24), id);
        TimeHierarchyBookLocator locator = new TimeHierarchyBookLocator(root);

        assertEquals(ids, locator.list());
        for (int id : ids) assertTrue(locator.find(id).isPresent(), "book " + id);
    }

    @Test
    void supportsTheLargestBookId() throws Exception {
        Path directory = writeBook("20261028", "10", Integer.MAX_VALUE);
        TimeHierarchyBookLocator locator = new TimeHierarchyBookLocator(root);

        assertEquals(directory, locator.find(Integer.MAX_VALUE).orElseThrow());
    }

    @Test
    void aBookPresentInTwoFoldersIsListedOnce() throws Exception {
        writeBook("20261028", "10", 5);
        writeBook("20261028", "11", 5);
        TimeHierarchyBookLocator locator = new TimeHierarchyBookLocator(root);

        assertEquals(List.of(5), locator.list());
        assertTrue(locator.find(5).isPresent());
    }

    @Test
    void findsABookThatWasDeletedAndStoredAgainInANewFolder() throws Exception {
        Path first = writeBook("20261028", "10", 6);
        TimeHierarchyBookLocator locator = new TimeHierarchyBookLocator(root);
        assertEquals(first, locator.find(6).orElseThrow());

        Files.delete(first.resolve("6.header.txt"));
        Files.delete(first.resolve("6.body.txt"));
        Path second = writeBook("20261028", "11", 6);

        assertEquals(second, locator.find(6).orElseThrow());
        assertEquals(List.of(6), locator.list());
    }

    @Test
    void relativeUsesForwardSlashesAndTheTimeHierarchy() throws Exception {
        Path directory = new TimeHierarchyDatalake(root).store(new Book(9, "h", "b"));
        TimeHierarchyBookLocator locator = new TimeHierarchyBookLocator(root);

        String relative = locator.relative(locator.find(9).orElseThrow());

        assertTrue(relative.matches("\\d{8}/\\d{2}"), relative);
        assertEquals(directory, root.resolve(relative));
    }

    @Test
    void closeWritesAVersionedSnapshotThatTheNextStartReads() throws Exception {
        writeBook("20261028", "10", 1);
        writeBook("20261028", "11", 2);
        TimeHierarchyBookLocator first = new TimeHierarchyBookLocator(root);
        first.close();

        List<String> lines = Files.readAllLines(root.resolve(".ingestion-index.tsv"));
        assertEquals("ingestion-index-v1", lines.get(0));
        assertEquals(2L, lines.stream().filter(line -> line.startsWith("B\t")).count());

        TimeHierarchyBookLocator second = new TimeHierarchyBookLocator(root);
        assertEquals(List.of(1, 2), second.list());
    }

    @Test
    void twoInstancesSavingAtTheSameTimeLeaveAValidSnapshotAndNoLeftovers() throws Exception {
        writeBook("20261028", "10", 1);
        TimeHierarchyBookLocator first = new TimeHierarchyBookLocator(root);
        TimeHierarchyBookLocator second = new TimeHierarchyBookLocator(root);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        for (TimeHierarchyBookLocator locator : List.of(first, second)) {
            tasks.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 100; i++) locator.save();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> task : tasks) task.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals("ingestion-index-v1", Files.readAllLines(root.resolve(".ingestion-index.tsv")).get(0));
        try (Stream<Path> files = Files.list(root)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
        assertEquals(List.of(1), new TimeHierarchyBookLocator(root).list());
    }

    @Test
    void readersKeepWorkingWhileBooksAreBeingStored() throws Exception {
        TimeHierarchyDatalake datalake = new TimeHierarchyDatalake(root);
        TimeHierarchyBookLocator locator = new TimeHierarchyBookLocator(root);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        tasks.add(pool.submit(() -> {
            start.await();
            for (int id = 1; id <= 60; id++) datalake.store(new Book(id, "h", "b"));
            return null;
        }));
        for (int reader = 0; reader < 3; reader++) {
            tasks.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 200; i++) {
                    locator.find(1 + i % 60);
                    locator.list();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> task : tasks) task.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        List<Integer> expected = new ArrayList<>();
        for (int id = 1; id <= 60; id++) expected.add(id);
        assertEquals(expected, locator.list());
    }
}
