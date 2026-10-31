package bdsm.integration.validation;

import bdsm.integration.support.Api;
import bdsm.integration.support.Api.Response;
import bdsm.integration.support.Await;
import bdsm.integration.support.Datamart;
import bdsm.integration.support.Library;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchApiIT {

    private static final String COMMON_TERM = "said";
    private static final int COMMON_TERM_TOTAL = 17;

    @BeforeAll
    static void indexTheSampleBooks() throws Exception {
        List<Integer> ids = Library.sampleIds();
        assertEquals(Library.SAMPLE_BOOKS, ids.size());
        try (Datamart datamart = new Datamart()) {
            datamart.clear();
            Library.seed(ids);
            Await.until("all the sample books are published", Duration.ofMinutes(3),
                    () -> datamart.publishedBooks() == ids.size());
        }
    }

    @Test
    void findsAKeyword() {
        Response response = Api.search("q", "alice");

        assertEquals(200, response.status());
        assertEquals(List.of(11, 12, 14), response.resultIds());
        JsonObject body = response.json();
        assertEquals("alice", body.get("query").getAsString());
        assertEquals(0, body.getAsJsonObject("filters").size());
        assertEquals(3, body.get("total").getAsInt());
        assertEquals(0, body.get("offset").getAsInt());
        assertEquals(20, body.get("limit").getAsInt());
    }

    @Test
    void returnsEveryFieldOfABook() {
        JsonObject book = Api.search("q", "snark").json().getAsJsonArray("results").get(0).getAsJsonObject();

        assertEquals(13, book.get("book_id").getAsInt());
        assertTrue(book.get("title").getAsString().startsWith("The Hunting of the Snark"));
        assertEquals("Lewis Carroll", book.get("author").getAsString());
        assertEquals("en", book.get("language").getAsString());
        assertFalse(book.has("year"));
    }

    @Test
    void ignoresCaseAndAccentsInTheKeyword() {
        assertEquals(List.of(11, 12, 14), Api.search("q", "ALICE").resultIds());
        assertEquals(Api.search("q", "corazon").resultIds(), Api.search("q", "corazón").resultIds());
    }

    @Test
    void requiresAllTheKeywords() {
        assertEquals(List.of(11), Api.search("q", "alice rabbit").resultIds());
    }

    @Test
    void filtersByAuthor() {
        Response response = Api.search("q", COMMON_TERM, "author", "carroll");

        assertEquals(List.of(11, 12, 13), response.resultIds());
        assertEquals("carroll", response.json().getAsJsonObject("filters").get("author").getAsString());
        assertEquals(List.of(11, 12, 13), Api.search("q", COMMON_TERM, "author", "CARROLL").resultIds());
    }

    @Test
    void treatsTheAuthorAsLiteralText() {
        assertEquals(List.of(), Api.search("q", COMMON_TERM, "author", ".*").resultIds());
    }

    @Test
    void filtersByLanguage() {
        Response response = Api.search("q", "dios", "language", "es");

        assertEquals(List.of(71250, 73500), response.resultIds());
        assertEquals("es", response.json().getAsJsonObject("filters").get("language").getAsString());
        assertEquals(List.of(14), Api.search("q", "dios", "language", "EN").resultIds());
    }

    @Test
    void filtersByYear() {
        Response response = Api.search("q", COMMON_TERM, "year", "1928");

        assertEquals(List.of(74750, 75500), response.resultIds());
        assertEquals(1928, response.json().getAsJsonObject("filters").get("year").getAsInt());
        assertEquals(List.of(70000, 72500), Api.search("q", COMMON_TERM, "year", "1903").resultIds());
    }

    @Test
    void combinesFilters() {
        assertEquals(List.of(11, 12, 13), Api.search("q", COMMON_TERM, "author", "carroll", "language", "en").resultIds());
        assertEquals(List.of(74750, 75500), Api.search("q", COMMON_TERM, "language", "en", "year", "1928").resultIds());
        assertEquals(List.of(74750), Api.search("q", COMMON_TERM, "author", "mead", "language", "en", "year", "1928").resultIds());
    }

    @Test
    void returnsAnEmptyListWhenNothingMatches() {
        assertEmptyPage(Api.search("q", "zzzznotaword"));
        assertEmptyPage(Api.search("q", "the"));
        assertEmptyPage(Api.search("q", COMMON_TERM, "author", "carroll", "language", "es"));
    }

    @ParameterizedTest
    @CsvSource({
            "''",
            "q=",
            "q=%20%20",
            "q=alice&year=abc",
            "q=alice&year=19.5",
            "q=alice&offset=abc",
            "q=alice&offset=-1",
            "q=alice&limit=abc",
            "q=alice&limit=0",
            "q=alice&limit=101",
            "q=alice&language=english",
            "q=alice&language=e1"
    })
    void rejectsInvalidParameters(String query) {
        Response response = Api.get(bdsm.integration.support.Settings.SEARCH_URL + "/search?" + query);

        assertEquals(400, response.status(), response.text());
        assertEquals("bad_request", response.json().get("error").getAsString());
        assertFalse(response.json().get("message").getAsString().isBlank());
    }

    @Test
    void treatsBlankFiltersAsAbsent() {
        assertEquals(List.of(11, 12, 14), Api.search("q", "alice", "author", "", "language", "", "year", "").resultIds());
    }

    @Test
    void pagesCoverEveryResultOnceInAscendingOrder() {
        List<Integer> collected = new ArrayList<>();
        for (int offset = 0; offset < COMMON_TERM_TOTAL; offset += 5) {
            Response page = Api.search("q", COMMON_TERM, "offset", String.valueOf(offset), "limit", "5");
            assertEquals(200, page.status());
            assertEquals(COMMON_TERM_TOTAL, page.json().get("total").getAsInt());
            assertEquals(offset, page.json().get("offset").getAsInt());
            assertEquals(5, page.json().get("limit").getAsInt());
            assertEquals(Math.min(5, COMMON_TERM_TOTAL - offset), page.resultIds().size());
            collected.addAll(page.resultIds());
        }

        assertEquals(Api.search("q", COMMON_TERM, "limit", "100").resultIds(), collected);
        assertEquals(COMMON_TERM_TOTAL, collected.stream().distinct().count());
        assertEquals(collected.stream().sorted().toList(), collected);
    }

    @Test
    void anOffsetBeyondTheTotalReturnsAnEmptyPage() {
        Response response = Api.search("q", COMMON_TERM, "offset", "1000");

        assertEquals(200, response.status());
        assertEquals(COMMON_TERM_TOTAL, response.json().get("total").getAsInt());
        assertEquals(List.of(), response.resultIds());
    }

    @Test
    void acceptsTheLimitBounds() {
        assertEquals(1, Api.search("q", COMMON_TERM, "limit", "1").resultIds().size());
        assertEquals(COMMON_TERM_TOTAL, Api.search("q", COMMON_TERM, "limit", "100").resultIds().size());
    }

    @Test
    void returnsAnExistingBook() {
        Response response = Api.book(74750);

        assertEquals(200, response.status());
        JsonObject book = response.json();
        assertEquals(74750, book.get("book_id").getAsInt());
        assertEquals("Margaret Mead", book.get("author").getAsString());
        assertEquals("en", book.get("language").getAsString());
        assertEquals(1928, book.get("year").getAsInt());
    }

    @Test
    void omitsAnUnknownYear() {
        assertFalse(Api.book(11).json().has("year"));
    }

    @Test
    void aMissingBookIsNotFound() {
        Response response = Api.book(999999);

        assertEquals(404, response.status());
        assertEquals("not_found", response.json().get("error").getAsString());
    }

    @Test
    void aBookIdThatIsNotANumberIsRejected() {
        Response response = Api.book("abc");

        assertEquals(400, response.status());
        assertEquals("bad_request", response.json().get("error").getAsString());
    }

    @Test
    void listsTheFilterValues() {
        Response response = Api.get(bdsm.integration.support.Settings.SEARCH_URL + "/search/filters");

        assertEquals(200, response.status());
        List<String> languages = new ArrayList<>();
        response.json().getAsJsonArray("languages").forEach(value -> languages.add(value.getAsString()));
        List<Integer> years = new ArrayList<>();
        response.json().getAsJsonArray("years").forEach(value -> years.add(value.getAsInt()));
        assertEquals(List.of("de", "en", "es", "fi"), languages);
        assertEquals(years.stream().sorted().distinct().toList(), years);
        assertTrue(years.containsAll(List.of(1903, 1928)));
    }

    @Test
    void aBookThatIsStillBeingIndexedIsNotVisible() {
        int bookId = 999998;
        String term = "unpublishedterm";
        try (Datamart datamart = new Datamart()) {
            datamart.insertUnpublishedBook(bookId, term);
        }

        assertEmptyPage(Api.search("q", term));
        assertEquals(404, Api.book(bookId).status());
    }

    private static void assertEmptyPage(Response response) {
        assertEquals(200, response.status(), response.text());
        assertEquals(0, response.json().get("total").getAsInt());
        assertEquals(0, response.json().getAsJsonArray("results").size());
    }
}
