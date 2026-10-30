package bdsm.integration.validation;

import bdsm.common.events.BookDownloaded;
import bdsm.common.events.EventBus;
import bdsm.integration.support.Api;
import bdsm.integration.support.Await;
import bdsm.integration.support.Broker;
import bdsm.integration.support.Datamart;
import bdsm.integration.support.Library;
import bdsm.integration.support.Stack;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdempotencyIT {

    private static final Duration PROCESSING = Duration.ofSeconds(60);
    private static final long SETTLE_MS = 3000;

    @Test
    void requestingABookTwiceDoesNotDuplicateAnything() throws Exception {
        int book = 11;
        String term = "alice";
        try (Datamart datamart = new Datamart()) {
            datamart.clear();

            Stack.feeder("request", String.valueOf(book));
            Await.until("the book is searchable", PROCESSING, () -> Api.isSearchable(book));
            long terms = datamart.termsOf(book);

            Stack.feeder("request", String.valueOf(book));
            Await.pause(SETTLE_MS);

            assertNoDuplicates(datamart, book, term, terms);
        }
    }

    @Test
    void redeliveringAnIndexedBookDoesNotDuplicateAnything() throws Exception {
        int book = 12;
        String term = "alice";
        try (Datamart datamart = new Datamart()) {
            datamart.clear();

            BookDownloaded event = Library.store(book);
            Broker.publish(event);
            Await.until("the book is searchable", PROCESSING, () -> Api.isSearchable(book));
            long terms = datamart.termsOf(book);

            Broker.publish(event);
            Await.pause(SETTLE_MS);

            assertNoDuplicates(datamart, book, term, terms);
        }
    }

    @Test
    void twoCopiesAtTheSameTimeDoNotDuplicateAnything() throws Exception {
        int book = 15;
        String term = "whale";
        try (Datamart datamart = new Datamart()) {
            datamart.clear();

            BookDownloaded event = Library.store(book);
            try (EventBus bus = Broker.bus()) {
                bus.publish(event);
                bus.publish(event);
            }
            Await.until("the book is searchable", PROCESSING, () -> Api.isSearchable(book));
            Await.pause(SETTLE_MS);

            long terms = datamart.termsOf(book);
            assertTrue(terms > 0);
            assertNoDuplicates(datamart, book, term, terms);
        }
    }

    private static void assertNoDuplicates(Datamart datamart, int book, String term, long terms) {
        assertEquals(1, datamart.bookRecords(book));
        assertEquals(1, datamart.publishedBooks());
        assertEquals(1, datamart.termRecords(term));
        assertEquals(1, datamart.occurrencesInPostings(term, book));
        assertEquals(terms, datamart.termsOf(book));
        assertEquals(1, Api.booksIndexed());
    }
}
