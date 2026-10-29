package bdsm.search.query;

import java.util.LinkedHashMap;
import java.util.Map;

public record BookFilter(String author, String language, Integer year) {

    public Map<String, Object> asMap() {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (author != null) filters.put("author", author);
        if (language != null) filters.put("language", language);
        if (year != null) filters.put("year", year);
        return filters;
    }
}
