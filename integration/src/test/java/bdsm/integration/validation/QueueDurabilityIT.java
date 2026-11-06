package bdsm.integration.validation;

import bdsm.common.events.BookDownloaded;
import bdsm.integration.support.Api;
import bdsm.integration.support.Await;
import bdsm.integration.support.Broker;
import bdsm.integration.support.Datamart;
import bdsm.integration.support.Library;
import bdsm.integration.support.Stack;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class QueueDurabilityIT {

    private static final String INDEXING_QUEUE = "Consumer.indexing.VirtualTopic.BookDownloaded";
    private static final String INGESTION_QUEUE = "Consumer.ingestion.VirtualTopic.BookRequested";
    private static final Duration PROCESSING = Duration.ofSeconds(60);
    private static final long WAIT_WHILE_STOPPED_MS = 2000;

    @Test
    void anEventWaitsWhileIndexingIsStopped() throws Exception {
        int book = 5;
        try (Datamart datamart = new Datamart()) {
            datamart.clear();
        }
        BookDownloaded event = Library.store(book);

        Stack.stop("indexing");
        try {
            Broker.drain(INDEXING_QUEUE);
            Broker.publish(event);
            Await.pause(WAIT_WHILE_STOPPED_MS);

            assertEquals(1, Broker.browse(INDEXING_QUEUE).size());
            assertFalse(Api.isSearchable(book));
        } finally {
            Stack.start("indexing");
        }

        Await.until("the waiting event is processed", PROCESSING, () -> Api.isSearchable(book));
        Await.until("the queue is empty", PROCESSING, () -> queueSize(INDEXING_QUEUE) == 0);
    }

    @Test
    void aRequestWaitsWhileIngestionIsStopped() throws Exception {
        int book = 6;
        try (Datamart datamart = new Datamart()) {
            datamart.clear();
        }

        Stack.stop("ingestion");
        try {
            Broker.drain(INGESTION_QUEUE);
            Stack.feeder("request", String.valueOf(book));
            Await.pause(WAIT_WHILE_STOPPED_MS);

            assertEquals(1, Broker.browse(INGESTION_QUEUE).size());
            assertFalse(Api.isSearchable(book));
        } finally {
            Stack.start("ingestion");
        }

        Await.until("the waiting request is processed", PROCESSING, () -> Api.isSearchable(book));
    }

    private static int queueSize(String queue) {
        try {
            return Broker.browse(queue).size();
        } catch (Exception e) {
            throw new AssertionError("The queue could not be read: " + e, e);
        }
    }
}
