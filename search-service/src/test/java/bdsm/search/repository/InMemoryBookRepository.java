package bdsm.search.repository;

import bdsm.common.model.BookMetadata;
import bdsm.search.query.BookFilter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public class InMemoryBookRepository implements BookRepository {

    private final Map<Integer, BookMetadata> books = new TreeMap<>();
    private final Set<Integer> published = new HashSet<>();
    private final Map<String, Set<Integer>> postings = new HashMap<>();

    public void add(BookMetadata book, boolean isPublished, String... terms) {
        books.put(book.bookId(), book);
        if (isPublished) published.add(book.bookId());
        for (String term : terms) {
            postings.computeIfAbsent(term, key -> new HashSet<>()).add(book.bookId());
        }
    }

    @Override
    public Map<String, Set<Integer>> findPostings(Set<String> terms) {
        Map<String, Set<Integer>> result = new HashMap<>();
        for (String term : terms) {
            if (postings.containsKey(term)) result.put(term, Set.copyOf(postings.get(term)));
        }
        return result;
    }

    @Override
    public BookPage findPublished(Set<Integer> bookIds, BookFilter filter, int offset, int limit) {
        List<BookMetadata> matches = books.values().stream()
                .filter(book -> bookIds.contains(book.bookId()))
                .filter(book -> published.contains(book.bookId()))
                .filter(book -> matches(book, filter))
                .toList();
        List<BookMetadata> page = matches.stream().skip(offset).limit(limit).toList();
        return new BookPage(matches.size(), page);
    }

    private static boolean matches(BookMetadata book, BookFilter filter) {
        return (filter.author() == null || containsIgnoringCase(book.author(), filter.author()))
                && (filter.language() == null || filter.language().equals(book.language()))
                && (filter.year() == null || filter.year().equals(book.year()));
    }

    private static boolean containsIgnoringCase(String value, String part) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
    }

    @Override
    public Optional<BookMetadata> findPublishedById(int bookId) {
        return published.contains(bookId) ? Optional.of(books.get(bookId)) : Optional.empty();
    }

    @Override
    public FilterValues findFilterValues() {
        List<BookMetadata> publishedBooks = books.values().stream()
                .filter(book -> published.contains(book.bookId()))
                .toList();
        List<String> languages = publishedBooks.stream().map(BookMetadata::language)
                .filter(Objects::nonNull).distinct().sorted().toList();
        List<Integer> years = publishedBooks.stream().map(BookMetadata::year)
                .filter(Objects::nonNull).distinct().sorted().toList();
        return new FilterValues(languages, years);
    }
}
