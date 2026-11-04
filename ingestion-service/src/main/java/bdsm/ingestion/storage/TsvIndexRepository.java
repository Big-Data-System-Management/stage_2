package bdsm.ingestion.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

final class TsvIndexRepository implements IndexRepository {

    private static final String FILE_NAME = ".ingestion-index.tsv";
    private static final String VERSION = "ingestion-index-v1";
    private static final String FOLDER_RECORD = "D";
    private static final String BOOK_RECORD = "B";
    private static final String SEPARATOR = "\t";

    private final Path root;
    private final Path file;

    TsvIndexRepository(Path root) {
        this.root = root;
        this.file = root.resolve(FILE_NAME);
    }

    @Override
    public Optional<IndexSnapshot> load() {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(parse(Files.readAllLines(file, StandardCharsets.UTF_8)));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public synchronized void save(IndexSnapshot snapshot) throws IOException {
        if (!Files.isDirectory(root)) return;
        Path temporary = root.resolve(".ingestion-index-" + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temporary, serialize(snapshot), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
    }

    private static IndexSnapshot parse(List<String> lines) {
        if (lines.isEmpty() || !lines.get(0).equals(VERSION)) {
            throw new IllegalArgumentException("Unknown index version");
        }
        Map<String, Long> folders = new TreeMap<>();
        List<BookEntry> entries = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split(SEPARATOR, -1);
            if (isRecord(fields, FOLDER_RECORD, 3)) {
                folders.put(fields[1], Long.parseLong(fields[2]));
            } else if (isRecord(fields, BOOK_RECORD, 5)) {
                entries.add(parseBook(fields));
            } else {
                throw new IllegalArgumentException("Malformed record: " + line);
            }
        }
        return new IndexSnapshot(folders, entries);
    }

    private static boolean isRecord(String[] fields, String type, int expectedFields) {
        return fields[0].equals(type) && fields.length == expectedFields;
    }

    private static BookEntry parseBook(String[] fields) {
        BookFiles files = new BookFiles(parseFlag(fields[3]), parseFlag(fields[4]));
        return new BookEntry(Integer.parseInt(fields[1]), fields[2], files);
    }

    private static boolean parseFlag(String value) {
        if (value.equals("1")) return true;
        if (value.equals("0")) return false;
        throw new IllegalArgumentException("Invalid flag: " + value);
    }

    private static String serialize(IndexSnapshot snapshot) {
        StringBuilder content = new StringBuilder(VERSION).append('\n');
        snapshot.folderModified().forEach((folder, modified) ->
                content.append(String.join(SEPARATOR, FOLDER_RECORD, folder, String.valueOf(modified))).append('\n'));
        for (BookEntry entry : snapshot.entries()) {
            content.append(String.join(SEPARATOR, BOOK_RECORD, String.valueOf(entry.id()), entry.folder(),
                    flag(entry.files().header()), flag(entry.files().body()))).append('\n');
        }
        return content.toString();
    }

    private static String flag(boolean value) {
        return value ? "1" : "0";
    }
}
