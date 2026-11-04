package bdsm.ingestion.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class BookDirectoryScanner {

    private static final String HEADER_SUFFIX = ".header.txt";
    private static final String BODY_SUFFIX = ".body.txt";
    private static final int RECENT_FOLDERS_LIMIT = 2;

    private final Path rootDirectory;

    public BookDirectoryScanner(Path rootDirectory) {
        this.rootDirectory = rootDirectory;
    }

    public String relative(Path directory) {
        return rootDirectory.relativize(directory).toString().replace('\\', '/');
    }

    public long getModificationTime(Path directory) throws IOException {
        try {
            return Files.getLastModifiedTime(directory).to(TimeUnit.NANOSECONDS);
        } catch (NoSuchFileException e) {
            return -1L;
        }
    }

    public boolean isBookCompleteOnDisk(Path directory, int bookId) {
        return Files.isRegularFile(directory.resolve(bookId + BODY_SUFFIX))
                && Files.isRegularFile(directory.resolve(bookId + HEADER_SUFFIX));
    }

    public List<Path> getAllSubdirectories() throws IOException {
        List<Path> all = new ArrayList<>();
        for (Path dayDir : getSubdirectoriesOf(rootDirectory)) {
            all.addAll(getSubdirectoriesOf(dayDir));
        }
        return all;
    }

    public List<Path> getRecentFolders() throws IOException {
        List<Path> recent = new ArrayList<>();
        List<Path> days = getSubdirectoriesOf(rootDirectory);

        for (int day = days.size() - 1; day >= 0 && recent.size() < RECENT_FOLDERS_LIMIT; day--) {
            List<Path> hours = getSubdirectoriesOf(days.get(day));
            for (int hour = hours.size() - 1; hour >= 0 && recent.size() < RECENT_FOLDERS_LIMIT; hour--) {
                recent.add(hours.get(hour));
            }
        }
        return recent;
    }

    public Map<Integer, boolean[]> scanDirectoryForBooks(Path directory) throws IOException {
        Map<Integer, boolean[]> found = new HashMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.map(path -> path.getFileName().toString()).forEach(fileName -> {
                if (fileName.endsWith(BODY_SUFFIX)) {
                    markComponent(found, fileName, BODY_SUFFIX, 1);
                } else if (fileName.endsWith(HEADER_SUFFIX)) {
                    markComponent(found, fileName, HEADER_SUFFIX, 0);
                }
            });
        } catch (NoSuchFileException ignored) {}
        return found;
    }

    private void markComponent(Map<Integer, boolean[]> found, String fileName, String suffix, int position) {
        try {
            int id = Integer.parseInt(fileName.substring(0, fileName.length() - suffix.length()));
            found.computeIfAbsent(id, key -> new boolean[2])[position] = true;
        } catch (NumberFormatException ignored) {
        }
    }

    private List<Path> getSubdirectoriesOf(Path parent) throws IOException {
        if (!Files.isDirectory(parent)) return List.of();
        try (Stream<Path> children = Files.list(parent)) {
            return children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }
}