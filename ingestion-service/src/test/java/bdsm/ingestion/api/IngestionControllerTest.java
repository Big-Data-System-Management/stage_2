package bdsm.ingestion.api;

import bdsm.common.datalake.TimeHierarchyDatalake;
import bdsm.common.http.ServiceApp;
import bdsm.common.model.Book;
import bdsm.ingestion.storage.TimeHierarchyBookLocator;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionControllerTest {

    @TempDir
    Path root;

    private Javalin app;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void startServer() throws Exception {
        app = ServiceApp.create("ingestion-service-test", 4);
        new IngestionController(new TimeHierarchyBookLocator(root)).register(app);
        app.start(0);
    }

    @AfterEach
    void stopServer() {
        app.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    @Test
    void reportsABookStoredAfterTheServiceStartedAsAvailable() throws Exception {
        assertEquals("missing", json(get("/ingest/status/3")).get("status").getAsString());

        new TimeHierarchyDatalake(root).store(new Book(3, "Title: X", "text"));

        HttpResponse<String> response = get("/ingest/status/3");
        assertEquals(200, response.statusCode());
        assertEquals(3, json(response).get("book_id").getAsInt());
        assertEquals("available", json(response).get("status").getAsString());
    }

    @Test
    void listsTheBooksOnDiskInAscendingOrder() throws Exception {
        TimeHierarchyDatalake datalake = new TimeHierarchyDatalake(root);
        datalake.store(new Book(12, "h", "b"));
        datalake.store(new Book(2, "h", "b"));

        JsonObject body = json(get("/ingest/list"));

        assertEquals(2, body.get("count").getAsInt());
        assertEquals("[2,12]", body.get("books").toString());
    }

    @Test
    void doesNotCountABookWhoseBodyIsMissing() throws Exception {
        Path directory = Files.createDirectories(root.resolve("20261028").resolve("10"));
        Files.writeString(directory.resolve("4.header.txt"), "Title: X");

        assertEquals("missing", json(get("/ingest/status/4")).get("status").getAsString());
        assertEquals(0, json(get("/ingest/list")).get("count").getAsInt());
    }

    @Test
    void listsNothingWhenTheDatalakeIsEmpty() throws Exception {
        JsonObject body = json(get("/ingest/list"));

        assertEquals(0, body.get("count").getAsInt());
        assertEquals("[]", body.get("books").toString());
    }

    @Test
    void rejectsABookIdThatIsNotANumber() throws Exception {
        HttpResponse<String> response = get("/ingest/status/abc");

        assertEquals(400, response.statusCode());
        assertTrue(json(response).get("message").getAsString().contains("book_id"));
        assertEquals(400, get("/ingest/status/0").statusCode());
    }
}
