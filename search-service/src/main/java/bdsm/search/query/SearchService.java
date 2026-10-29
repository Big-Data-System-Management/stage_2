package bdsm.search.query;

import bdsm.common.model.BookMetadata;
import bdsm.common.text.Tokenizer;
import bdsm.search.repository.BookPage;
import bdsm.search.repository.BookRepository;
import bdsm.search.repository.FilterValues;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class SearchService {

    private final BookRepository repository;
    private final Tokenizer tokenizer;

    public SearchService(BookRepository repository, Tokenizer tokenizer) {
        this.repository = repository;
        this.tokenizer = tokenizer;
    }

    public SearchResult search(SearchQuery query) {
        BookPage page = findPage(query);
        return new SearchResult(query.text(), query.filter().asMap(), page.total(),
                query.offset(), query.limit(), page.books());
    }

    public Optional<BookMetadata> findBook(int bookId) {
        return repository.findPublishedById(bookId);
    }

    public FilterValues filterValues() {
        return repository.findFilterValues();
    }

    private BookPage findPage(SearchQuery query) {
        Set<Integer> bookIds = booksContainingAll(Set.copyOf(tokenizer.tokenize(query.text())));
        if (bookIds.isEmpty()) return BookPage.EMPTY;
        return repository.findPublished(bookIds, query.filter(), query.offset(), query.limit());
    }

    private Set<Integer> booksContainingAll(Set<String> terms) {
        if (terms.isEmpty()) return Set.of();
        Map<String, Set<Integer>> postings = repository.findPostings(terms);
        if (postings.size() < terms.size()) return Set.of();
        List<Set<Integer>> lists = postings.values().stream()
                .sorted(Comparator.comparingInt(Set::size))
                .toList();
        Set<Integer> result = new HashSet<>(lists.get(0));
        for (Set<Integer> list : lists.subList(1, lists.size())) {
            result.retainAll(list);
            if (result.isEmpty()) break;
        }
        return result;
    }
}
