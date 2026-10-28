package bdsm.ingestion.source;

import bdsm.common.model.Book;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GutenbergSplitter {

    private static final Pattern START_MARKER = Pattern.compile(
            "\\*\\*\\* ?START OF (THE|THIS) PROJECT GUTENBERG E-?BOOK[^\\r\\n]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern END_MARKER = Pattern.compile(
            "\\*\\*\\* ?END OF (THE|THIS) PROJECT GUTENBERG E-?BOOK", Pattern.CASE_INSENSITIVE);

    private GutenbergSplitter() {
    }

    public static Optional<Book> split(int bookId, String text) {
        if (text == null) return Optional.empty();
        Matcher start = START_MARKER.matcher(text);
        Matcher end = END_MARKER.matcher(text);
        if (!start.find() || !end.find(start.end())) return Optional.empty();
        String header = text.substring(0, start.start()).strip();
        String body = text.substring(start.end(), end.start()).strip();
        return Optional.of(new Book(bookId, header, body));
    }
}
