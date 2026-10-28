package bdsm.indexing.metadata;

import bdsm.common.model.Book;
import bdsm.common.model.BookMetadata;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MetadataExtractor {

    private static final String UNKNOWN = "Unknown";
    private static final Pattern TITLE = field("Title");
    private static final Pattern LANGUAGE = field("Language");
    private static final List<Pattern> AUTHOR_FIELDS = List.of(
            field("Author"), field("Editor"), field("Translator"), field("Compiler"));

    private MetadataExtractor() {
    }

    public static BookMetadata extract(Book book) {
        String header = book.header();
        String title = find(TITLE, header, " ").orElse(UNKNOWN);
        String author = findAuthor(header).orElse(UNKNOWN);
        String language = find(LANGUAGE, header, " ").orElse(UNKNOWN);
        return new BookMetadata(book.id(), title, author, language, null);
    }

    private static Optional<String> findAuthor(String header) {
        return AUTHOR_FIELDS.stream()
                .map(pattern -> find(pattern, header, "; "))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Pattern field(String name) {
        return Pattern.compile("^" + name + ":[ \\t]*(.+(?:\\R[ \\t]+.+)*)", Pattern.MULTILINE);
    }

    private static Optional<String> find(Pattern pattern, String header, String lineSeparator) {
        Matcher matcher = pattern.matcher(header);
        if (!matcher.find()) return Optional.empty();
        String value = matcher.group(1)
                .replaceAll("\\R[ \\t]+", lineSeparator)
                .replaceAll("[ \\t]+", " ")
                .strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
