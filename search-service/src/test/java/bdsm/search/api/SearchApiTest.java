package bdsm.search.api;

import bdsm.common.json.Json;
import bdsm.common.model.BookMetadata;
import bdsm.common.text.Tokenizer;
import bdsm.search.App;
import bdsm.search.query.BookFilter;
import bdsm.search.query.SearchService;
import bdsm.search.repository.BookPage;
import bdsm.search.repository.InMemoryBookRepository;
import bdsm.search.repository.RepositoryUnavailableException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchApiTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final InMemoryBookRepository repository = new InMemoryBookRepository();
    private Javalin app;

    @BeforeEach
    void start() {
        repository.add(new BookMetadata(11, "Alice's Adventures in Wonderland", "Lewis Carroll", "en", 1865), true, "alice", "rabbit");
        repository.add(new BookMetadata(12, "Through the Looking-Glass", "Lewis Carroll", "en", null), true, "alice");
        repository.add(new BookMetadata(13, "Unfinished", "Someone", "fr", 1900), false, "alice");
        startWith(new SearchService(repository, new Tokenizer()));
    }

    private void startWith(SearchService service) {
        if (app != null) app.stop();
        app = App.createServer(service, 8, "*").start(0);
    }

    @AfterEach
    void stop() {
        app.stop();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).header("Origin", "http://frontend.test").GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + app.port() + path);
    }

    private static JsonObject json(HttpResponse<String> response) {
        return Json.GSON.fromJson(response.body(), JsonObject.class);
    }

    @Test
    void searchReturnsTheContractShape() throws Exception {
        HttpResponse<String> response = get("/search?q=alice&language=en&limit=1");
        JsonObject body = json(response);

        assertEquals(200, response.statusCode());
        assertEquals("alice", body.get("query").getAsString());
        assertEquals("en", body.getAsJsonObject("filters").get("language").getAsString());
        assertEquals(2, body.get("total").getAsInt());
        assertEquals(0, body.get("offset").getAsInt());
        assertEquals(1, body.get("limit").getAsInt());
        JsonArray results = body.getAsJsonArray("results");
        assertEquals(1, results.size());
        JsonObject book = results.get(0).getAsJsonObject();
        assertEquals(11, book.get("book_id").getAsInt());
        assertEquals("Alice's Adventures in Wonderland", book.get("title").getAsString());
        assertEquals("Lewis Carroll", book.get("author").getAsString());
        assertEquals("en", book.get("language").getAsString());
        assertEquals(1865, book.get("year").getAsInt());
    }

    @Test
    void searchPaginatesWithOffset() throws Exception {
        JsonObject body = json(get("/search?q=alice&offset=1&limit=1"));

        assertEquals(2, body.get("total").getAsInt());
        assertEquals(12, body.getAsJsonArray("results").get(0).getAsJsonObject().get("book_id").getAsInt());
    }

    @Test
    void searchWithoutMatchesReturnsEmptyResults() throws Exception {
        HttpResponse<String> response = get("/search?q=dragon");
        JsonObject body = json(response);

        assertEquals(200, response.statusCode());
        assertEquals(0, body.get("total").getAsInt());
        assertEquals(0, body.getAsJsonArray("results").size());
    }

    @Test
    void invalidParametersReturnBadRequestError() throws Exception {
        for (String path : new String[]{"/search", "/search?q=", "/search?q=alice&limit=101",
                "/search?q=alice&offset=-1", "/search?q=alice&year=abc", "/search?q=alice&language=english"}) {
            HttpResponse<String> response = get(path);

            assertEquals(400, response.statusCode(), path);
            assertEquals("bad_request", json(response).get("error").getAsString(), path);
            assertFalse(json(response).get("message").getAsString().isBlank(), path);
        }
    }

    @Test
    void bookEndpointReturnsPublishedBook() throws Exception {
        HttpResponse<String> response = get("/books/11");

        assertEquals(200, response.statusCode());
        assertEquals("Alice's Adventures in Wonderland", json(response).get("title").getAsString());
    }

    @Test
    void bookEndpointReturnsNotFoundForMissingOrUnpublishedBooks() throws Exception {
        for (String path : new String[]{"/books/999", "/books/13"}) {
            HttpResponse<String> response = get(path);

            assertEquals(404, response.statusCode(), path);
            assertEquals("not_found", json(response).get("error").getAsString(), path);
        }
    }

    @Test
    void bookEndpointRejectsNonNumericId() throws Exception {
        HttpResponse<String> response = get("/books/abc");

        assertEquals(400, response.statusCode());
        assertEquals("bad_request", json(response).get("error").getAsString());
    }

    @Test
    void filtersEndpointListsValuesOfPublishedBooks() throws Exception {
        HttpResponse<String> response = get("/search/filters");
        JsonObject body = json(response);

        assertEquals(200, response.statusCode());
        assertEquals("[\"en\"]", body.getAsJsonArray("languages").toString());
        assertEquals("[1865]", body.getAsJsonArray("years").toString());
    }

    @Test
    void responsesAllowCrossOriginRequests() throws Exception {
        HttpResponse<String> response = get("/search?q=alice");

        assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    }

    @Test
    void preflightRequestsAreAnswered() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/search"))
                .header("Origin", "http://frontend.test")
                .header("Access-Control-Request-Method", "GET")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(204, response.statusCode());
        assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertTrue(response.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("GET"));
    }

    @Test
    void unavailableStorageReturnsServiceUnavailable() throws Exception {
        InMemoryBookRepository failing = new InMemoryBookRepository() {
            @Override
            public BookPage findPublished(Set<Integer> bookIds, BookFilter filter, int offset, int limit) {
                throw new RepositoryUnavailableException("MongoDB is not reachable", new RuntimeException());
            }
        };
        failing.add(new BookMetadata(11, "Alice", "Lewis Carroll", "en", 1865), true, "alice");
        startWith(new SearchService(failing, new Tokenizer()));

        HttpResponse<String> response = get("/search?q=alice");

        assertEquals(503, response.statusCode());
        assertEquals("service_unavailable", json(response).get("error").getAsString());
    }
}
