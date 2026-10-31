package bdsm.common.events;

import bdsm.common.config.Config;
import bdsm.common.json.Json;
import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.RedeliveryPolicy;

public class EventBus implements Publisher, AutoCloseable {

    private static final String TOPIC_PREFIX = "VirtualTopic.";
    private static final int DEFAULT_QUEUE_PREFETCH = 1;

    private final Connection connection;
    private final Session publishSession;
    private final MessageProducer producer;

    public EventBus(String brokerUrl, int maxRedeliveries, long redeliveryDelayMs) throws JMSException {
        this(brokerUrl, maxRedeliveries, redeliveryDelayMs, DEFAULT_QUEUE_PREFETCH);
    }

    public EventBus(String brokerUrl, int maxRedeliveries, long redeliveryDelayMs, int queuePrefetch) throws JMSException {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        factory.getPrefetchPolicy().setQueuePrefetch(queuePrefetch);
        RedeliveryPolicy policy = factory.getRedeliveryPolicy();
        policy.setMaximumRedeliveries(maxRedeliveries);
        policy.setInitialRedeliveryDelay(redeliveryDelayMs);
        policy.setRedeliveryDelay(redeliveryDelayMs);
        policy.setUseExponentialBackOff(redeliveryDelayMs > 0);
        policy.setBackOffMultiplier(2);
        factory.setNonBlockingRedelivery(true);
        connection = factory.createConnection();
        connection.start();
        publishSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        producer = createProducer(publishSession);
    }

    public static EventBus fromConfig() throws JMSException {
        return new EventBus(
                Config.get("BROKER_URL", "tcp://localhost:61616"),
                Config.getInt("MAX_REDELIVERIES", 5),
                Config.getInt("REDELIVERY_DELAY_MS", 1000),
                Config.getInt("QUEUE_PREFETCH", DEFAULT_QUEUE_PREFETCH));
    }

    @Override
    public synchronized void publish(Object event) throws JMSException {
        send(publishSession, producer, event);
    }

    public <T> void subscribe(Class<T> type, String group, int workers, EventHandler<T> handler) throws JMSException {
        String queue = "Consumer." + group + "." + TOPIC_PREFIX + type.getSimpleName();
        for (int worker = 0; worker < workers; worker++) {
            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
            MessageProducer sessionProducer = createProducer(session);
            Publisher transactional = event -> send(session, sessionProducer, event);
            MessageConsumer consumer = session.createConsumer(session.createQueue(queue));
            consumer.setMessageListener(message -> dispatch(session, message, type, handler, transactional));
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (JMSException e) {
            System.err.println("[Events] Error closing the broker connection: " + e.getMessage());
        }
    }

    private static MessageProducer createProducer(Session session) throws JMSException {
        MessageProducer producer = session.createProducer(null);
        producer.setDeliveryMode(DeliveryMode.PERSISTENT);
        return producer;
    }

    private static void send(Session session, MessageProducer producer, Object event) throws JMSException {
        String topic = TOPIC_PREFIX + event.getClass().getSimpleName();
        producer.send(session.createTopic(topic), session.createTextMessage(Json.GSON.toJson(event)));
    }

    private static <T> void dispatch(Session session, Message message, Class<T> type, EventHandler<T> handler, Publisher publisher) {
        try {
            T event = Json.GSON.fromJson(((TextMessage) message).getText(), type);
            handler.handle(event, publisher);
            session.commit();
        } catch (Exception e) {
            System.err.printf("[Events] %s failed, it will be redelivered: %s%n", type.getSimpleName(), e.getMessage());
            rollback(session);
        }
    }

    private static void rollback(Session session) {
        try {
            session.rollback();
        } catch (JMSException e) {
            System.err.println("[Events] Rollback failed: " + e.getMessage());
        }
    }
}
