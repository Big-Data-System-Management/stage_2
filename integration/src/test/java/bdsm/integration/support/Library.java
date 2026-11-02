package bdsm.integration.support;

import bdsm.common.datalake.TimeHierarchyDatalake;
import bdsm.common.events.BookDownloaded;
import bdsm.common.model.Book;
import bdsm.ingestion.source.GutenbergSplitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class Library {

    public static final int SAMPLE_BOOKS = 27;

    private Library() {
    }

    public static BookDownloaded store(int bookId) throws IOException {
        TimeHierarchyDatalake datalake = new TimeHierarchyDatalake(Settings.DATALAKE);
        String text = Files.readString(Settings.SAMPLE_DATASET.resolve("pg" + bookId + ".txt"));
        Book book = GutenbergSplitter.split(bookId, text).orElseThrow();
        Path directory = datalake.store(book);
        return new BookDownloaded(bookId, Settings.DATALAKE.relativize(directory).toString().replace('\\', '/'));
    }

    public static void seed(List<Integer> bookIds) throws Exception {
        for (int bookId : bookIds) Broker.publish(store(bookId));
    }

    public static List<Integer> sampleIds() throws IOException {
        List<Integer> ids = new ArrayList<>();
        try (Stream<Path> files = Files.list(Settings.SAMPLE_DATASET)) {
            files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("pg") && name.endsWith(".txt"))
                    .map(name -> Integer.valueOf(name.substring(2, name.length() - 4)))
                    .sorted()
                    .forEach(ids::add);
        }
        return ids;
    }
}
