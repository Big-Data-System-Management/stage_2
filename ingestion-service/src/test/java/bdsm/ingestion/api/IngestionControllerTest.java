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

    private static final int TEST_THREADS = 8;
    private static final String SERVICE_NAME = "ingestion-service-test";
    private static final String INGEST_STATUS_PATH = "/ingest/status/";
    private static final String INGEST_LIST_PATH = "/ingest/list";

    @TempDir
    Path root;

    private Javalin app;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void startServer() throws Exception {
        app = ServiceApp.create(SERVICE_NAME, TEST_THREADS);
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
        int specificBookId = 3;

        assertEquals("missing", json(get(INGEST_STATUS_PATH + specificBookId)).get("status").getAsString());

        new TimeHierarchyDatalake(root).store(new Book(specificBookId, "Title: X", "text"));

        HttpResponse<String> response = get(INGEST_STATUS_PATH + specificBookId);
        assertEquals(200, response.statusCode());
        assertEquals(specificBookId, json(response).get("book_id").getAsInt());
        assertEquals("available", json(response).get("status").getAsString());
    }

    @Test
    void listsTheBooksOnDiskInAscendingOrder() throws Exception {
        TimeHierarchyDatalake datalake = new TimeHierarchyDatalake(root);
        datalake.store(new Book(12, "h", "b"));
        datalake.store(new Book(2, "h", "b"));

        JsonObject body = json(get(INGEST_LIST_PATH));

        assertEquals(2, body.get("count").getAsInt());
        assertEquals("[2,12]", body.get("books").toString());
    }

    @Test
    void doesNotCountABookWhoseBodyIsMissing() throws Exception {
        int specificBookId = 4;
        Path directory = Files.createDirectories(root.resolve("20261028").resolve("10"));
        Files.writeString(directory.resolve(specificBookId + ".header.txt"), "Title: X");

        assertEquals("missing", json(get(INGEST_STATUS_PATH + specificBookId)).get("status").getAsString());
        assertEquals(0, json(get(INGEST_LIST_PATH)).get("count").getAsInt());
    }

    @Test
    void listsNothingWhenTheDatalakeIsEmpty() throws Exception {
        JsonObject body = json(get(INGEST_LIST_PATH));
        assertEquals(0, body.get("count").getAsInt());
        assertEquals("[]", body.get("books").toString());
    }

    @Test
    void rejectsABookIdThatIsNotANumber() throws Exception {
        HttpResponse<String> response = get(INGEST_STATUS_PATH + "abc");

        assertEquals(400, response.statusCode());
        assertTrue(json(response).get("message").getAsString().contains("book_id"));
        assertEquals(400, get(INGEST_STATUS_PATH + "0").statusCode());
    }
}