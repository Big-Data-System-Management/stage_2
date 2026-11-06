package bdsm.indexing.metadata;

import bdsm.common.model.Book;
import bdsm.common.model.BookMetadata;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class MetadataExtractor {

    private static final String UNKNOWN = "Unknown";
    private static final Pattern TITLE = field("Title");
    private static final Pattern LANGUAGE = field("Language");
    private static final Pattern ORIGINAL_PUBLICATION = field("Original publication");
    private static final Pattern YEAR = Pattern.compile(",\\s*(\\d{4})\\.?$");
    private static final List<Pattern> AUTHOR_FIELDS = List.of(
            field("Author"), field("Editor"), field("Translator"), field("Compiler"));
    private static final Map<String, String> LANGUAGE_CODES = Arrays.stream(Locale.getISOLanguages())
            .collect(Collectors.toMap(
                    code -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).toLowerCase(Locale.ROOT),
                    Function.identity(),
                    (first, second) -> first));

    private MetadataExtractor() {
    }

    public static BookMetadata extract(Book book) {
        String header = book.header();
        String title = find(TITLE, header, " ").orElse(UNKNOWN);
        String author = findAuthor(header).orElse(UNKNOWN);
        String language = find(LANGUAGE, header, " ").flatMap(MetadataExtractor::languageCode).orElse(null);
        Integer year = find(ORIGINAL_PUBLICATION, header, " ").flatMap(MetadataExtractor::publicationYear).orElse(null);
        return new BookMetadata(book.id(), title, author, language, year);
    }

    static Optional<String> languageCode(String language) {
        String first = language.split("[,;]")[0].strip().toLowerCase(Locale.ROOT);
        return Optional.ofNullable(LANGUAGE_CODES.get(first));
    }

    static Optional<Integer> publicationYear(String publication) {
        Matcher matcher = YEAR.matcher(publication);
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
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
