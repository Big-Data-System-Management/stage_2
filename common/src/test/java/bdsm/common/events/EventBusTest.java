package bdsm.common.events;

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
}
