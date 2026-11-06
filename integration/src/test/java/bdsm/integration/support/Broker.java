package bdsm.integration.support;

import bdsm.common.events.EventBus;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Queue;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.command.ActiveMQQueue;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public final class Broker {

    public static final String DEAD_LETTER_QUEUE = "ActiveMQ.DLQ";

    private static final int MAX_REDELIVERIES = 0;
    private static final int DRAIN_WAIT_MS = 500;

    private Broker() {
    }

    public static EventBus bus() throws JMSException {
        return new EventBus(Settings.BROKER_URL, MAX_REDELIVERIES, 0);
    }

    public static void publish(Object event) throws JMSException {
        try (EventBus bus = bus()) {
            bus.publish(event);
        }
    }

    public static void publishText(String topic, String text) throws JMSException {
        try (Connection connection = connect()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            session.createProducer(session.createTopic(topic)).send(session.createTextMessage(text));
        }
    }

    public static List<String> browse(String queueName) throws JMSException {
        List<String> messages = new ArrayList<>();
        try (Connection connection = connect()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue(queueName);
            try (QueueBrowser browser = session.createBrowser(queue)) {
                Enumeration<?> enumeration = browser.getEnumeration();
                while (enumeration.hasMoreElements()) {
                    Object message = enumeration.nextElement();
                    if (message instanceof TextMessage text) messages.add(text.getText());
                }
            }
        }
        return messages;
    }

    public static void drain(String queueName) throws JMSException {
        try (Connection connection = connect()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(session.createQueue(queueName));
            Message message = consumer.receive(DRAIN_WAIT_MS);
            while (message != null) message = consumer.receive(DRAIN_WAIT_MS);
        }
    }

    public static void destroyQueue(String queueName) throws JMSException {
        try (Connection connection = connect()) {
            ((ActiveMQConnection) connection).destroyDestination(new ActiveMQQueue(queueName));
        }
    }

    private static Connection connect() throws JMSException {
        Connection connection = new ActiveMQConnectionFactory(Settings.BROKER_URL).createConnection();
        connection.start();
        return connection;
    }
}
