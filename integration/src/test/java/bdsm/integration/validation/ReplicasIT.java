package bdsm.integration.validation;

import bdsm.common.events.BookIndexed;
import bdsm.common.events.EventBus;
import bdsm.integration.support.Await;
import bdsm.integration.support.Broker;
import bdsm.integration.support.Datamart;
import bdsm.integration.support.Library;
import bdsm.integration.support.Stack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplicasIT {

    private static final String GROUP = "validation_replicas";
    private static final String QUEUE = "Consumer." + GROUP + ".VirtualTopic.BookIndexed";
    private static final List<Integer> BOOKS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 13, 70500, 73666);
    private static final long SETTLE_MS = 3000;

    @BeforeAll
    static void startTwoIndexingReplicas() throws Exception {
        Stack.stop("indexing");
        Stack.start("indexing", Map.of("INDEXING_REPLICAS", "2"));
    }

    @AfterAll
    static void backToOneReplica() throws Exception {
        Stack.stop("indexing");
        Stack.start("indexing");
        Broker.destroyQueue(QUEUE);
    }

    @Test
    void everyBookIsProcessedExactlyOnce() throws Exception {
        List<Integer> indexed = new CopyOnWriteArrayList<>();
        try (Datamart datamart = new Datamart(); EventBus listener = Broker.bus()) {
            datamart.clear();
            listener.subscribe(BookIndexed.class, GROUP, 1, (event, publisher) -> indexed.add(event.bookId()));
            long linesBefore = processedLines();

            Library.seed(BOOKS);

            Await.until("every book is indexed", Duration.ofMinutes(2), () -> indexed.size() >= BOOKS.size());
            Await.pause(SETTLE_MS);

            assertEquals(BOOKS.size(), indexed.size());
            assertTrue(indexed.containsAll(BOOKS));
            assertEquals(BOOKS.size(), datamart.publishedBooks());
            for (int book : BOOKS) assertEquals(1, Collections.frequency(indexed, book));
            assertEquals(BOOKS.size(), processedLines() - linesBefore);
            assertTrue(Stack.logLinesOfReplica("indexing", 1, "[Indexing] Book ") > 0);
            assertTrue(Stack.logLinesOfReplica("indexing", 2, "[Indexing] Book ") > 0);
        }
    }

    private static long processedLines() throws Exception {
        return Stack.logLines("indexing", " indexed");
    }
}
