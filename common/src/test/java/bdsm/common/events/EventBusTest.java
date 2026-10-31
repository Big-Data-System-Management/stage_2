package bdsm.common.events;

import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventBusTest {

    private BrokerService broker;
    private EventBus bus;

    @BeforeEach
    void startBroker() throws Exception {
        broker = new BrokerService();
        broker.setBrokerName("test");
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.start();
        bus = new EventBus("vm://test?create=false", 2, 0);
    }

    @AfterEach
    void stopBroker() throws Exception {
        bus.close();
        broker.stop();
    }

    @Test
    void everyGroupReceivesItsOwnCopyOfAnEvent() throws Exception {
        CountDownLatch received = new CountDownLatch(2);
        List<String> groups = new CopyOnWriteArrayList<>();
        bus.subscribe(BookIndexed.class, "first", 1, (event, publisher) -> {
            groups.add("first:" + event.bookId());
            received.countDown();
        });
        bus.subscribe(BookIndexed.class, "second", 1, (event, publisher) -> {
            groups.add("second:" + event.bookId());
            received.countDown();
        });

        bus.publish(new BookIndexed(5));

        assertTrue(received.await(5, TimeUnit.SECONDS));
        assertTrue(groups.containsAll(List.of("first:5", "second:5")));
    }

    @Test
    void aFailedEventIsRedeliveredAndItsPublicationsDiscarded() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch indexed = new CountDownLatch(1);
        List<Integer> published = new CopyOnWriteArrayList<>();
        bus.subscribe(BookIndexed.class, "listener", 1, (event, publisher) -> {
            published.add(event.bookId());
            indexed.countDown();
        });
        bus.subscribe(BookDownloaded.class, "indexing", 1, (event, publisher) -> {
            publisher.publish(new BookIndexed(event.bookId()));
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("first attempt fails");
        });

        bus.publish(new BookDownloaded(7, "datalake/20261028/10"));

        assertTrue(indexed.await(5, TimeUnit.SECONDS));
        Thread.sleep(300);
        assertEquals(2, attempts.get());
        assertEquals(List.of(7), published);
    }

    @Test
    void retriesWithDoublingDelaysAndThenMovesTheEventToTheDeadLetterQueue() throws Exception {
        List<Long> attempts = new CopyOnWriteArrayList<>();
        try (EventBus retrying = new EventBus("vm://test?create=false", 3, 100)) {
            retrying.subscribe(BookDownloaded.class, "indexing", 1, (event, publisher) -> {
                attempts.add(System.nanoTime());
                throw new IllegalStateException("always fails");
            });
            retrying.publish(new BookDownloaded(1, "20261029/10"));

            assertEquals("{\"book_id\":1,\"path\":\"20261029/10\"}", receiveFromDeadLetterQueue());
        }
        assertEquals(4, attempts.size());
        for (int retry = 1; retry < attempts.size(); retry++) {
            long gapMs = (attempts.get(retry) - attempts.get(retry - 1)) / 1_000_000;
            long expectedMs = 100L << (retry - 1);
            assertTrue(gapMs >= expectedMs - 20 && gapMs < expectedMs * 2, "retry " + retry + " after " + gapMs + " ms");
        }
    }

    @Test
    void anEventWaitingForRedeliveryDoesNotBlockTheNextOnes() throws Exception {
        CountDownLatch otherProcessed = new CountDownLatch(1);
        try (EventBus retrying = new EventBus("vm://test?create=false", 3, 2000)) {
            retrying.subscribe(BookDownloaded.class, "indexing", 1, (event, publisher) -> {
                if (event.bookId() == 1) throw new IllegalStateException("always fails");
                otherProcessed.countDown();
            });
            retrying.publish(new BookDownloaded(1, "a"));
            retrying.publish(new BookDownloaded(2, "b"));

            assertTrue(otherProcessed.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void aReplicaThatConnectsLaterStillGetsPartOfTheWork() throws Exception {
        int events = 20;
        CountDownLatch done = new CountDownLatch(events);
        AtomicInteger late = new AtomicInteger();
        bus.subscribe(BookDownloaded.class, "indexing", 1, (event, publisher) -> {
            Thread.sleep(50);
            done.countDown();
        });
        for (int book = 0; book < events; book++) bus.publish(new BookDownloaded(book, "p"));
        Thread.sleep(100);
        try (EventBus replica = new EventBus("vm://test?create=false", 2, 0)) {
            replica.subscribe(BookDownloaded.class, "indexing", 1, (event, publisher) -> {
                Thread.sleep(50);
                late.incrementAndGet();
                done.countDown();
            });

            assertTrue(done.await(10, TimeUnit.SECONDS));
        }
        assertTrue(late.get() >= events / 4, "the late replica processed " + late.get() + " of " + events);
    }

    private static String receiveFromDeadLetterQueue() throws Exception {
        Connection connection = new ActiveMQConnectionFactory("vm://test?create=false").createConnection();
        try {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Message message = session.createConsumer(session.createQueue("ActiveMQ.DLQ")).receive(5000);
            return message == null ? null : ((TextMessage) message).getText();
        } finally {
            connection.close();
        }
    }
}
