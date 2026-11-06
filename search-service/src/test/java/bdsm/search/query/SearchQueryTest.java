package bdsm.search.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchQueryTest {

    @Test
    void appliesDefaultPagination() {
        SearchQuery query = SearchQuery.from("whale", null, null, null, null, null);

        assertEquals(0, query.offset());
        assertEquals(20, query.limit());
        assertEquals(new BookFilter(null, null, null), query.filter());
    }

    @Test
    void keepsGivenValuesAndNormalizesLanguage() {
        SearchQuery query = SearchQuery.from("  whale  ", " Melville ", "EN", "1851", "40", "10");

        assertEquals("whale", query.text());
        assertEquals(new BookFilter("Melville", "en", 1851), query.filter());
        assertEquals(40, query.offset());
        assertEquals(10, query.limit());
    }

    @Test
    void treatsBlankOptionalParametersAsAbsent() {
        SearchQuery query = SearchQuery.from("whale", " ", "", "", "", "");

        assertEquals(new BookFilter(null, null, null), query.filter());
        assertEquals(0, query.offset());
        assertEquals(20, query.limit());
    }

    @Test
    void acceptsLimitBounds() {
        assertEquals(1, SearchQuery.from("whale", null, null, null, null, "1").limit());
        assertEquals(100, SearchQuery.from("whale", null, null, null, null, "100").limit());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsMissingQuery(String q) {
        assertThrows(IllegalArgumentException.class, () -> SearchQuery.from(q, null, null, null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"19x", "1.5"})
    void rejectsNonIntegerYear(String year) {
        assertThrows(IllegalArgumentException.class, () -> SearchQuery.from("whale", null, null, year, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "-1"})
    void rejectsInvalidOffset(String offset) {
        assertThrows(IllegalArgumentException.class, () -> SearchQuery.from("whale", null, null, null, offset, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "101", "-5"})
    void rejectsInvalidLimit(String limit) {
        assertThrows(IllegalArgumentException.class, () -> SearchQuery.from("whale", null, null, null, null, limit));
    }

    @ParameterizedTest
    @ValueSource(strings = {"english", "e1", "e"})
    void rejectsMalformedLanguage(String language) {
        assertThrows(IllegalArgumentException.class, () -> SearchQuery.from("whale", null, language, null, null, null));
    }

    @Test
    void filterMapContainsOnlyProvidedFilters() {
        assertEquals(Map.of(), new BookFilter(null, null, null).asMap());
        assertEquals(Map.of("language", "en", "year", 1851), new BookFilter(null, "en", 1851).asMap());
    }
}
