package bdsm.search.query;

import java.util.Locale;
import java.util.regex.Pattern;

public record SearchQuery(String text, BookFilter filter, int offset, int limit) {

    public static final int DEFAULT_OFFSET = 0;
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private static final Pattern LANGUAGE_CODE = Pattern.compile("[a-z]{2}");

    public static SearchQuery from(String q, String author, String language, String year, String offset, String limit) {
        String text = blankToNull(q);
        if (text == null) throw new IllegalArgumentException("Parameter q is required");
        BookFilter filter = new BookFilter(blankToNull(author), parseLanguage(language), parseInteger("year", year));
        return new SearchQuery(text, filter, parseOffset(offset), parseLimit(limit));
    }

    private static String parseLanguage(String value) {
        String language = blankToNull(value);
        if (language == null) return null;
        language = language.toLowerCase(Locale.ROOT);
        if (!LANGUAGE_CODE.matcher(language).matches()) {
            throw new IllegalArgumentException("Parameter language must be a two-letter ISO 639-1 code, got: " + value);
        }
        return language;
    }

    private static int parseOffset(String value) {
        Integer offset = parseInteger("offset", value);
        if (offset == null) return DEFAULT_OFFSET;
        if (offset < 0) throw new IllegalArgumentException("Parameter offset must be greater than or equal to 0, got: " + offset);
        return offset;
    }

    private static int parseLimit(String value) {
        Integer limit = parseInteger("limit", value);
        if (limit == null) return DEFAULT_LIMIT;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Parameter limit must be between 1 and " + MAX_LIMIT + ", got: " + limit);
        }
        return limit;
    }

    private static Integer parseInteger(String name, String value) {
        String text = blankToNull(value);
        if (text == null) return null;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Parameter " + name + " must be an integer, got: " + value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
