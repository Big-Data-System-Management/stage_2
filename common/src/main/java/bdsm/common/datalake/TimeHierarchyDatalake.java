package bdsm.common.datalake;

import bdsm.common.model.Book;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class TimeHierarchyDatalake implements Datalake {

    private static final String HEADER_SUFFIX = ".header.txt";
    private static final String BODY_SUFFIX = ".body.txt";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH");

    private final Path root;
    private final Map<Integer, Path> bookDirectories = new ConcurrentHashMap<>();

    public TimeHierarchyDatalake(Path root) throws IOException {
        this.root = root;
        scan();
    }

    public Path root() {
        return root;
    }

    public void scan() throws IOException {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(BODY_SUFFIX))
                    .forEach(this::register);
        }
    }

    @Override
    public Path store(Book book) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        Path directory = root.resolve(now.format(DATE)).resolve(now.format(HOUR));
        Files.createDirectories(directory);
        writeAtomically(directory.resolve(book.id() + HEADER_SUFFIX), book.header());
        writeAtomically(directory.resolve(book.id() + BODY_SUFFIX), book.body());
        bookDirectories.put(book.id(), directory);
        return directory;
    }

    @Override
    public boolean exists(int bookId) {
        return bookDirectories.containsKey(bookId);
    }

    @Override
    public Optional<Book> read(int bookId) throws IOException {
        Path directory = bookDirectories.get(bookId);
        return directory == null ? Optional.empty() : Optional.of(readFrom(directory, bookId));
    }

    @Override
    public SortedSet<Integer> bookIds() {
        return new TreeSet<>(bookDirectories.keySet());
    }

    public Optional<Path> directory(int bookId) {
        return Optional.ofNullable(bookDirectories.get(bookId));
    }

    public static Book readFrom(Path directory, int bookId) throws IOException {
        String header = Files.readString(directory.resolve(bookId + HEADER_SUFFIX));
        String body = Files.readString(directory.resolve(bookId + BODY_SUFFIX));
        return new Book(bookId, header, body);
    }

    private void register(Path bodyFile) {
        String name = bodyFile.getFileName().toString();
        try {
            bookDirectories.put(Integer.parseInt(name.substring(0, name.length() - BODY_SUFFIX.length())), bodyFile.getParent());
        } catch (NumberFormatException ignored) {
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
