package bdsm.integration.validation;

import bdsm.integration.support.Api;
import bdsm.integration.support.Await;
import bdsm.integration.support.Datamart;
import bdsm.integration.support.Stack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PipelineIT {

    private static final int BOOK = 13;

    @BeforeAll
    static void startWithAnEmptyIndex() {
        try (Datamart datamart = new Datamart()) {
            datamart.clear();
        }
    }

    @Test
    void aRequestedBookEndsUpInTheSearchResults() throws Exception {
        Stack.feeder("request", String.valueOf(BOOK));

        Await.until("book " + BOOK + " is searchable", Duration.ofSeconds(60), () -> Api.isSearchable(BOOK));
        assertEquals(List.of(BOOK), Api.search("q", "snark").resultIds());
        assertEquals("Lewis Carroll", Api.book(BOOK).json().get("author").getAsString());
    }
}
