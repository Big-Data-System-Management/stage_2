package bdsm.integration.load;

import bdsm.common.events.BookDownloaded;
import bdsm.common.events.BookIndexed;
import bdsm.common.events.BookRequested;
import bdsm.common.events.EventBus;
import com.google.gson.JsonParser;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.command.ActiveMQQueue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class PipelineProbe {

    private static final String GROUP = "pipeline_probe";
    private static final List<String> EVENTS = List.of(
            BookRequested.class.getSimpleName(), BookDownloaded.class.getSimpleName(), BookIndexed.class.getSimpleName());
    private static final double MS_PER_SECOND = 1000.0;

    private final Map<String, Map<Integer, Long>> timestamps = new ConcurrentHashMap<>();
    private final List<Integer> books;
    private final CountDownLatch indexed;

    private PipelineProbe(List<Integer> books) {
        this.books = books;
        this.indexed = new CountDownLatch(books.size());
        EVENTS.forEach(event -> timestamps.put(event, new ConcurrentHashMap<>()));
    }

    public static void main(String[] args) throws Exception {
        Options options = new Options(args);
        List<Integer> books = baseBooks(Path.of(options.get("books-csv")), options.getInt("books"));
        PipelineProbe probe = new PipelineProbe(books);
        String brokerUrl = options.get("broker", "tcp://localhost:61616");
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        boolean finished;
        try (Connection connection = factory.createConnection()) {
            connection.start();
            for (String event : EVENTS) probe.listen(connection, event);
            try (EventBus bus = new EventBus(brokerUrl, 0, 0)) {
                for (int book : books) bus.publish(new BookRequested(book));
            }
            System.out.printf("[probe] Requested %d books%n", books.size());
            finished = probe.indexed.await(options.getInt("timeout-s", 3600), TimeUnit.SECONDS);
            for (String event : EVENTS) ((ActiveMQConnection) connection).destroyDestination(new ActiveMQQueue(queue(event)));
        }
        probe.report(new ResultWriter(Path.of(options.get("out")), options.get("labels", "")), finished);
        System.exit(0);
    }

    private static List<Integer> baseBooks(Path listing, int count) throws Exception {
        List<Integer> ids = new ArrayList<>();
        for (String line : Files.readAllLines(listing)) {
            String[] fields = line.split(",");
            if (fields[1].equals("base") && ids.size() < count) ids.add(Integer.parseInt(fields[0]));
        }
        return ids;
    }

    private static String queue(String event) {
        return "Consumer." + GROUP + ".VirtualTopic." + event;
    }

    private void listen(Connection connection, String event) throws JMSException {
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        session.createConsumer(session.createQueue(queue(event))).setMessageListener(message -> record(event, message));
    }

    private void record(String event, Message message) {
        try {
            int book = JsonParser.parseString(((TextMessage) message).getText()).getAsJsonObject().get("book_id").getAsInt();
            boolean first = timestamps.get(event).putIfAbsent(book, message.getJMSTimestamp()) == null;
            if (first && event.equals(BookIndexed.class.getSimpleName())) indexed.countDown();
        } catch (JMSException e) {
            System.err.println("[probe] Message could not be read: " + e.getMessage());
        }
    }

    private long[] gaps(String from, String to) {
        return books.stream()
                .filter(book -> timestamps.get(from).containsKey(book) && timestamps.get(to).containsKey(book))
                .mapToLong(book -> timestamps.get(to).get(book) - timestamps.get(from).get(book))
                .toArray();
    }

    private void report(ResultWriter writer, boolean finished) throws Exception {
        Map<Integer, Long> requested = timestamps.get(EVENTS.get(0));
        Map<Integer, Long> done = timestamps.get(EVENTS.get(2));
        long completed = done.size();
        long first = requested.values().stream().mapToLong(Long::longValue).min().orElse(0);
        long last = done.values().stream().mapToLong(Long::longValue).max().orElse(first);
        double seconds = Math.max(last - first, 1) / MS_PER_SECOND;
        double booksPerSecond = completed / seconds;
        writer.write(Map.of("benchmark", "pipeline.throughput", "metric", "thrpt", "unit", "books/s",
                "mean", booksPerSecond, "stddev", 0.0, "samples", completed, "errors", books.size() - completed));
        stage(writer, "pipeline.ingestion_stage", gaps(EVENTS.get(0), EVENTS.get(1)));
        stage(writer, "pipeline.indexing_stage", gaps(EVENTS.get(1), EVENTS.get(2)));
        stage(writer, "pipeline.end_to_end", gaps(EVENTS.get(0), EVENTS.get(2)));
        System.out.printf(Locale.ROOT, "[probe] %d of %d books in %.1f s: %.2f books/s%s%n", completed, books.size(),
                seconds, booksPerSecond, finished ? "" : " (timed out)");
    }

    private static void stage(ResultWriter writer, String name, long[] gaps) throws Exception {
        ResultWriter.Statistics statistics = ResultWriter.statistics(gaps);
        writer.write(Map.of("benchmark", name, "metric", "avgt", "unit", "ms/book",
                "mean", statistics.mean(), "stddev", statistics.stddev(), "samples", gaps.length,
                "p50", statistics.p50(), "p95", statistics.p95(), "p99", statistics.p99()));
    }
}
