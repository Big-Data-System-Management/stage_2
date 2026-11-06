package bdsm.ingestion.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

final class TimeHierarchyScanner {

    private static final String HEADER_SUFFIX = ".header.txt";
    private static final String BODY_SUFFIX = ".body.txt";
    private static final int RECENT_FOLDER_COUNT = 2;

    private final Path root;

    TimeHierarchyScanner(Path root) {
        this.root = root;
    }

    String relative(Path directory) {
        return root.relativize(directory).toString().replace('\\', '/');
    }

    Path resolve(String folder) {
        return root.resolve(folder);
    }

    List<String> allFolders() throws IOException {
        List<String> folders = new ArrayList<>();
        for (Path day : subdirectories(root)) {
            for (Path hour : subdirectories(day)) folders.add(relative(hour));
        }
        return folders;
    }

    List<String> recentFolders() throws IOException {
        List<String> recent = new ArrayList<>();
        for (Path day : newestFirst(subdirectories(root))) {
            if (recent.size() >= RECENT_FOLDER_COUNT) break;
            for (Path hour : newestFirst(subdirectories(day))) {
                if (recent.size() >= RECENT_FOLDER_COUNT) break;
                recent.add(relative(hour));
            }
        }
        return recent;
    }

    long modifiedOf(String folder) throws IOException {
        try {
            return Files.getLastModifiedTime(resolve(folder)).to(TimeUnit.NANOSECONDS);
        } catch (NoSuchFileException e) {
            return FolderState.MISSING;
        }
    }

    boolean hasCompleteBook(String folder, int bookId) {
        Path directory = resolve(folder);
        return Files.isRegularFile(directory.resolve(bookId + BODY_SUFFIX))
                && Files.isRegularFile(directory.resolve(bookId + HEADER_SUFFIX));
    }

    Map<Integer, BookFiles> booksIn(String folder) throws IOException {
        Map<Integer, BookFiles> books = new HashMap<>();
        for (String fileName : fileNamesIn(resolve(folder))) {
            if (fileName.endsWith(BODY_SUFFIX)) {
                register(books, fileName, BODY_SUFFIX, BookFiles.BODY_ONLY);
            } else if (fileName.endsWith(HEADER_SUFFIX)) {
                register(books, fileName, HEADER_SUFFIX, BookFiles.HEADER_ONLY);
            }
        }
        return books;
    }

    private static void register(Map<Integer, BookFiles> books, String fileName, String suffix, BookFiles present) {
        parseBookId(fileName, suffix).ifPresent(id -> books.merge(id, present, BookFiles::union));
    }

    private static OptionalInt parseBookId(String fileName, String suffix) {
        try {
            return OptionalInt.of(Integer.parseInt(fileName.substring(0, fileName.length() - suffix.length())));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private static List<String> fileNamesIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).toList();
        } catch (NoSuchFileException e) {
            return List.of();
        }
    }

    private static List<Path> subdirectories(Path parent) throws IOException {
        if (!Files.isDirectory(parent)) return List.of();
        try (Stream<Path> children = Files.list(parent)) {
            return children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private static List<Path> newestFirst(List<Path> sortedAscending) {
        List<Path> copy = new ArrayList<>(sortedAscending);
        Collections.reverse(copy);
        return copy;
    }
}
