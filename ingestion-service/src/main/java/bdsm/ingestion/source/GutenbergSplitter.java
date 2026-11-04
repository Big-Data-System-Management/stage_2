package bdsm.ingestion.source;

import bdsm.common.model.Book;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GutenbergSplitter {

    private static final Pattern START_MARKER = Pattern.compile(
            "\\*\\*\\* ?START OF (THE|THIS) PROJECT GUTENBERG E-?BOOK[^\\r\\n]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern END_MARKER = Pattern.compile(
            "\\*\\*\\* ?END OF (THE|THIS) PROJECT GUTENBERG E-?BOOK", Pattern.CASE_INSENSITIVE);

    private GutenbergSplitter() {
        throw new UnsupportedOperationException("Static utility class");
    }

    public static Optional<Book> split(int bookId, String text) {
        if (text == null)
            return Optional.empty();
        Matcher start = START_MARKER.matcher(text);
        Matcher end = END_MARKER.matcher(text);
        if (patternDoesntExistOnText(start, end))
            return Optional.empty();
        return createBook(bookId, text, start, end);
    }

    private static boolean patternDoesntExistOnText(Matcher start, Matcher end) {
        return !start.find() || !end.find(start.end());
    }

    @NotNull
    private static Optional<Book> createBook(int bookId, String text, Matcher start, Matcher end) {
        String header = extractMetadata(text, start);
        String body = extractBookText(text, start, end);
        return Optional.of(new Book(bookId, header, body));
    }

    @NotNull
    private static String extractBookText(String text, Matcher start, Matcher end) {
        return text.substring(start.end(), end.start()).strip();
    }

    @NotNull
    private static String extractMetadata(String text, Matcher start) {
        return text.substring(0, start.start()).strip();
    }
}