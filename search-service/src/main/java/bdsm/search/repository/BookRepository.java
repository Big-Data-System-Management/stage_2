package bdsm.search.repository;

import bdsm.common.model.BookMetadata;
import bdsm.search.query.BookFilter;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface BookRepository {

    Map<String, Set<Integer>> findPostings(Set<String> terms);

    BookPage findPublished(Set<Integer> bookIds, BookFilter filter, int offset, int limit);

    Optional<BookMetadata> findPublishedById(int bookId);

    FilterValues findFilterValues();
}
