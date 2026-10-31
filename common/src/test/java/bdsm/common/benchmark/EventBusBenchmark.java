package bdsm.common.benchmark;

import bdsm.common.config.Config;
import bdsm.common.events.BookIndexed;
import bdsm.common.events.EventBus;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.command.ActiveMQQueue;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 3)
@Measurement(iterations = 10, time = 3)
@Threads(1)
@Fork(1)
public class EventBusBenchmark {

    private static final int EVENTS = 200;
    private static final String EMBEDDED_URL = "vm://benchmark?create=false";
    private static final String GROUP_PREFIX = "bench_";

    @Param({"1", "2", "4"})
    public int consumers;

    @Param({"0", "1"})
    public int handlerMs;

    @Param({"embedded"})
    public String broker;

    private final String group = GROUP_PREFIX + UUID.randomUUID().toString().replace("-", "");
    private BrokerService embedded;
    private String brokerUrl;
    private EventBus bus;
    private volatile CountDownLatch pending;

    @Setup(Level.Trial)
    public void connect() throws Exception {
        if (broker.equals("embedded")) {
            embedded = new BrokerService();
            embedded.setBrokerName("benchmark");
            embedded.setPersistent(false);
            embedded.setUseJmx(false);
            embedded.start();
            brokerUrl = EMBEDDED_URL;
        } else {
            brokerUrl = Config.get("BROKER_URL", "tcp://localhost:61616");
        }
        bus = new EventBus(brokerUrl, 0, 0);
        bus.subscribe(BookIndexed.class, group, consumers, (event, publisher) -> {
            if (handlerMs > 0) Thread.sleep(handlerMs);
            pending.countDown();
        });
    }

    @Benchmark
    @OperationsPerInvocation(EVENTS)
    public void publishAndConsume() throws Exception {
        pending = new CountDownLatch(EVENTS);
        for (int event = 0; event < EVENTS; event++) bus.publish(new BookIndexed(event));
        if (!pending.await(120, TimeUnit.SECONDS)) throw new IllegalStateException("Events were not consumed in time");
    }

    @TearDown(Level.Trial)
    public void disconnect() throws Exception {
        bus.close();
        destroyQueue();
        if (embedded != null) embedded.stop();
    }

    private void destroyQueue() throws JMSException {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        try (Connection connection = factory.createConnection()) {
            connection.start();
            String queue = "Consumer." + group + ".VirtualTopic." + BookIndexed.class.getSimpleName();
            ((ActiveMQConnection) connection).destroyDestination(new ActiveMQQueue(queue));
        }
    }
}
