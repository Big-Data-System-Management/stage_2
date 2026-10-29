package bdsm.search.query;

import bdsm.common.model.BookMetadata;
import bdsm.common.text.Tokenizer;
import bdsm.search.repository.FilterValues;
import bdsm.search.repository.InMemoryBookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchServiceTest {

    private static final BookMetadata ALICE = new BookMetadata(11, "Alice's Adventures in Wonderland", "Lewis Carroll", "en", 1865);
    private static final BookMetadata LOOKING_GLASS = new BookMetadata(12, "Through the Looking-Glass", "Lewis Carroll", "en", 1871);
    private static final BookMetadata QUIJOTE = new BookMetadata(2000, "Don Quijote", "Miguel de Cervantes", "es", 1605);
    private static final BookMetadata UNPUBLISHED = new BookMetadata(13, "Unfinished", "Someone", "fr", 1900);

    private SearchService service;

    @BeforeEach
    void setUp() {
        InMemoryBookRepository repository = new InMemoryBookRepository();
        repository.add(ALICE, true, "alice", "rabbit", "adventures");
        repository.add(LOOKING_GLASS, true, "alice", "mirror");
        repository.add(QUIJOTE, true, "quijote", "adventures");
        repository.add(UNPUBLISHED, false, "alice");
        service = new SearchService(repository, new Tokenizer());
    }

    private SearchResult search(String q, String author, String language, String year, String offset, String limit) {
        return service.search(SearchQuery.from(q, author, language, year, offset, limit));
    }

    private SearchResult search(String q) {
        return search(q, null, null, null, null, null);
    }

    @Test
    void singleTermReturnsPublishedBooksOrderedById() {
        SearchResult result = search("alice");

        assertEquals(2, result.total());
        assertEquals(List.of(ALICE, LOOKING_GLASS), result.results());
    }

    @Test
    void multipleTermsReturnBooksContainingAllOfThem() {
        assertEquals(List.of(ALICE), search("alice rabbit").results());
    }

    @Test
    void queryIsNormalizedWithTheSharedTokenizer() {
        assertEquals(List.of(ALICE, LOOKING_GLASS), search("ALICE").results());
        assertEquals(List.of(ALICE, QUIJOTE), search("Ádventures!").results());
    }

    @Test
    void unknownTermReturnsEmptyResult() {
        SearchResult result = search("alice dragon");

        assertEquals(0, result.total());
        assertTrue(result.results().isEmpty());
    }

    @Test
    void queryWithOnlyStopwordsReturnsEmptyResult() {
        SearchResult result = search("the of and");

        assertEquals(0, result.total());
        assertTrue(result.results().isEmpty());
    }

    @Test
    void appliesEachFilterIndividually() {
        assertEquals(List.of(ALICE), search("adventures", "carroll", null, null, null, null).results());
        assertEquals(List.of(QUIJOTE), search("adventures", null, "es", null, null, null).results());
        assertEquals(List.of(LOOKING_GLASS), search("alice", null, null, "1871", null, null).results());
    }

    @Test
    void combinesFilters() {
        assertEquals(List.of(ALICE), search("alice adventures", "LEWIS", "EN", "1865", null, null).results());
        assertEquals(List.of(), search("alice", "carroll", "es", null, null, null).results());
    }

    @Test
    void paginatesWhileReportingTotalOfAllMatches() {
        SearchResult result = search("alice", null, null, null, "1", "1");

        assertEquals(2, result.total());
        assertEquals(1, result.offset());
        assertEquals(1, result.limit());
        assertEquals(List.of(LOOKING_GLASS), result.results());
    }

    @Test
    void offsetBeyondTotalReturnsEmptyPage() {
        SearchResult result = search("alice", null, null, null, "10", null);

        assertEquals(2, result.total());
        assertTrue(result.results().isEmpty());
    }

    @Test
    void echoesQueryAndProvidedFilters() {
        SearchResult result = search("  alice  ", null, "en", null, null, null);

        assertEquals("alice", result.query());
        assertEquals(Map.of("language", "en"), result.filters());
    }

    @Test
    void findsOnlyPublishedBooksById() {
        assertEquals(Optional.of(ALICE), service.findBook(11));
        assertEquals(Optional.empty(), service.findBook(13));
        assertEquals(Optional.empty(), service.findBook(999));
    }

    @Test
    void filterValuesComeFromPublishedBooks() {
        assertEquals(new FilterValues(List.of("en", "es"), List.of(1605, 1865, 1871)), service.filterValues());
    }
}
