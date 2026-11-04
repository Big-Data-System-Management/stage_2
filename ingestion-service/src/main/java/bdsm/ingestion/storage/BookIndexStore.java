package bdsm.ingestion.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentNavigableMap;

public class BookIndexStore {

    private static final String INDEX_FILE = ".ingestion-index.tsv";
    private static final String INDEX_VERSION = "ingestion-index-v1";

    private final Path rootDirectory;
    private final Path indexFilePath;

    public BookIndexStore(Path rootDirectory) {
        this.rootDirectory = rootDirectory;
        this.indexFilePath = rootDirectory.resolve(INDEX_FILE);
    }

    public boolean exists() {
        return Files.isRegularFile(indexFilePath);
    }

    public synchronized void save(Map<String, BookLocator.Folder> folders, ConcurrentNavigableMap<Integer, BookLocator.Entry> entries) throws IOException {
        if (!Files.isDirectory(rootDirectory)) return;

        StringBuilder content = new StringBuilder(INDEX_VERSION).append('\n');

        for (Map.Entry<String, BookLocator.Folder> folder : folders.entrySet()) {
            content.append("D\t").append(folder.getKey()).append('\t').append(folder.getValue().modified()).append('\n');
        }

        for (BookLocator.Entry entry : entries.values()) {
            content.append("B\t").append(entry.id()).append('\t').append(entry.folder()).append('\t')
                    .append(entry.header() ? 1 : 0).append('\t').append(entry.body() ? 1 : 0).append('\n');
        }

        Path temporary = rootDirectory.resolve(".ingestion-index-" + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(temporary, indexFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
    }

    public boolean load(Map<String, BookLocator.Folder> folders, ConcurrentNavigableMap<Integer, BookLocator.Entry> entries) {
        if (!exists()) return false;

        try {
            List<String> lines = Files.readAllLines(indexFilePath, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).equals(INDEX_VERSION)) return false;

            for (String line : lines.subList(1, lines.size())) {
                String[] parts = line.split("\t", -1);
                if (parts[0].equals("D") && parts.length == 3) {
                    folders.put(parts[1], new BookLocator.Folder(Long.parseLong(parts[2]), new HashSet<>()));
                } else if (parts[0].equals("B") && parts.length == 5) {
                    BookLocator.Entry entry = new BookLocator.Entry(
                            Integer.parseInt(parts[1]), parts2OrGet(parts[2]), parseFlag(parts[3]), parseFlag(parts[4])
                    );
                    entries.put(entry.id(), entry);
                } else {
                    throw new NumberFormatException(line);
                }
            }

            for (BookLocator.Entry entry : entries.values()) {
                BookLocator.Folder folder = folders.get(entry.folder());
                if (folder == null) throw new NumberFormatException(entry.folder());
                folder.ids().add(entry.id());
            }
            return true;
        } catch (IOException | NumberFormatException e) {
            folders.clear();
            entries.clear();
            return false;
        }
    }

    private String parts2OrGet(String part) {
        return part;
    }

    private boolean parseFlag(String value) {
        if (value.equals("1")) return true;
        if (value.equals("0")) return false;
        throw new NumberFormatException(value);
    }
}