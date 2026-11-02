package bdsm.integration.validation;

import bdsm.integration.support.Api;
import bdsm.integration.support.Api.Response;
import bdsm.integration.support.Await;
import bdsm.integration.support.Datamart;
import bdsm.integration.support.Library;
import bdsm.integration.support.Settings;
import bdsm.integration.support.Stack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationalApiIT {

    private static final int BOOK = 4;
    private static final int MISSING_BOOK = 999997;

    @ParameterizedTest
    @CsvSource({"INGESTION,ingestion-service", "INDEXING,indexing-service", "SEARCH,search-service"})
    void everyServiceReportsItsStatus(String service, String name) {
        String url = switch (service) {
            case "INGESTION" -> Settings.INGESTION_URL;
            case "INDEXING" -> Settings.INDEXING_URL;
            default -> Settings.SEARCH_URL;
        };

        Response response = Api.get(url + "/status");

        assertEquals(200, response.status());
        assertEquals(name, response.json().get("service").getAsString());
        assertEquals("running", response.json().get("status").getAsString());
    }

    @Test
    void indexingReportsItsStatistics() throws Exception {
        try (Datamart datamart = new Datamart()) {
            datamart.clear();
            assertEquals(0, Api.booksIndexed());
            assertFalse(Api.get(Settings.INDEXING_URL + "/index/status").json().has("last_update"));

            Library.seed(List.of(BOOK));
            Await.until("the book is published", Duration.ofSeconds(30), () -> datamart.publishedBooks() == 1);
        }

        Response response = Api.get(Settings.INDEXING_URL + "/index/status");
        assertEquals(1, response.json().get("books_indexed").getAsInt());
        assertTrue(response.json().has("last_update"));
        assertTrue(response.json().get("index_size_mb").getAsDouble() > 0);
    }

    @Test
    void ingestionReportsAStoredBook() throws Exception {
        Stack.feeder("request", String.valueOf(BOOK));
        Await.until("the book is in the datalake", Duration.ofSeconds(30), () -> {
            Response status = Api.get(Settings.INGESTION_URL + "/ingest/status/" + BOOK);
            return status.status() == 200 && status.json().get("status").getAsString().equals("available");
        });

        Response list = Api.get(Settings.INGESTION_URL + "/ingest/list");
        assertEquals(200, list.status());
        assertTrue(list.json().get("count").getAsInt() >= 1);
        assertTrue(list.json().getAsJsonArray("books").toString().contains(String.valueOf(BOOK)));
    }

    @Test
    void ingestionReportsAMissingBook() {
        Response response = Api.get(Settings.INGESTION_URL + "/ingest/status/" + MISSING_BOOK);

        assertEquals(200, response.status());
        assertEquals(MISSING_BOOK, response.json().get("book_id").getAsInt());
        assertEquals("missing", response.json().get("status").getAsString());
    }

    @Test
    void ingestionRejectsABookIdThatIsNotANumber() {
        assertEquals(400, Api.get(Settings.INGESTION_URL + "/ingest/status/abc").status());
    }
}
