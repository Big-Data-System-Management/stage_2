package bdsm.search.query;

import bdsm.common.model.BookMetadata;

import java.util.List;
import java.util.Map;

public record SearchResult(String query, Map<String, Object> filters, long total, int offset, int limit,
                           List<BookMetadata> results) {
}
