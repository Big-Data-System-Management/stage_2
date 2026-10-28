package bdsm.common.events;

import jakarta.jms.JMSException;

public interface Publisher {
    void publish(Object event) throws JMSException;
}
