package bdsm.search.repository;

import bdsm.common.model.BookMetadata;
import bdsm.search.query.BookFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

abstract class BookRepositoryContractTest {

    private static final BookFilter NO_FILTER = new BookFilter(null, null, null);
    private static final BookMetadata ALICE = new BookMetadata(11, "Alice's Adventures in Wonderland", "Lewis Carroll", "en", 1865);
    private static final BookMetadata LOOKING_GLASS = new BookMetadata(12, "Through the Looking-Glass", "Lewis Carroll", "en", 1871);
    private static final BookMetadata QUIJOTE = new BookMetadata(2000, "Don Quijote", "Miguel de Cervantes", "es", 1605);
    private static final BookMetadata NO_YEAR = new BookMetadata(30, "Anonymous Tales", null, "en", null);
    private static final BookMetadata UNPUBLISHED = new BookMetadata(13, "Unfinished", "Lewis Carroll", "fr", 1900);

    private BookRepository repository;

    protected abstract BookRepository createRepository();

    protected abstract void store(BookMetadata book, boolean published, String... terms);

    @BeforeEach
    void seed() {
        repository = createRepository();
        store(ALICE, true, "alice", "rabbit", "adventures");
        store(LOOKING_GLASS, true, "alice", "mirror");
        store(QUIJOTE, true, "quijote", "adventures");
        store(NO_YEAR, true, "tales", "adventures");
        store(UNPUBLISHED, false, "alice");
    }

    @Test
    void findPostingsReturnsBookIdsOfKnownTermsOnly() {
        Map<String, Set<Integer>> postings = repository.findPostings(Set.of("alice", "mirror", "dragon"));

        assertEquals(Map.of("alice", Set.of(11, 12, 13), "mirror", Set.of(12)), postings);
    }

    @Test
    void findPublishedExcludesUnpublishedBooksAndOrdersById() {
        BookPage page = repository.findPublished(Set.of(13, 12, 11), NO_FILTER, 0, 20);

        assertEquals(new BookPage(2, List.of(ALICE, LOOKING_GLASS)), page);
    }

    @Test
    void findPublishedMatchesAuthorPartiallyIgnoringCase() {
        BookPage page = repository.findPublished(Set.of(11, 2000, 30), new BookFilter("CARROLL", null, null), 0, 20);

        assertEquals(new BookPage(1, List.of(ALICE)), page);
    }

    @Test
    void findPublishedTreatsAuthorAsLiteralText() {
        BookPage page = repository.findPublished(Set.of(11, 12, 2000), new BookFilter(".*", null, null), 0, 20);

        assertEquals(new BookPage(0, List.of()), page);
    }

    @Test
    void findPublishedFiltersByLanguageAndYear() {
        assertEquals(new BookPage(1, List.of(QUIJOTE)),
                repository.findPublished(Set.of(11, 2000, 30), new BookFilter(null, "es", null), 0, 20));
        assertEquals(new BookPage(1, List.of(LOOKING_GLASS)),
                repository.findPublished(Set.of(11, 12), new BookFilter(null, null, 1871), 0, 20));
    }

    @Test
    void findPublishedCombinesFilters() {
        BookPage page = repository.findPublished(Set.of(11, 12, 2000, 30), new BookFilter("lewis", "en", 1865), 0, 20);

        assertEquals(new BookPage(1, List.of(ALICE)), page);
    }

    @Test
    void findPublishedPaginatesAndReportsTotalOfAllMatches() {
        Set<Integer> ids = Set.of(11, 12, 2000, 30);

        assertEquals(new BookPage(4, List.of(LOOKING_GLASS, NO_YEAR)), repository.findPublished(ids, NO_FILTER, 1, 2));
        assertEquals(new BookPage(4, List.of()), repository.findPublished(ids, NO_FILTER, 10, 2));
    }

    @Test
    void findPublishedByIdIgnoresUnpublishedAndMissingBooks() {
        assertEquals(Optional.of(NO_YEAR), repository.findPublishedById(30));
        assertEquals(Optional.empty(), repository.findPublishedById(13));
        assertEquals(Optional.empty(), repository.findPublishedById(999));
    }

    @Test
    void findFilterValuesListsSortedValuesOfPublishedBooks() {
        assertEquals(new FilterValues(List.of("en", "es"), List.of(1605, 1865, 1871)), repository.findFilterValues());
    }
}
