package com.ef.timers.jms;

import com.ef.timers.model.TimerEntity;
import com.ef.timers.model.TimerMessage;
import com.ef.timers.model.TimerType;
import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import org.apache.activemq.ScheduledMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The type Activemq publisher.
 */
public class DelayedMessageProducer {
    private static final Logger logger = LoggerFactory.getLogger(DelayedMessageProducer.class);
    private final MessageProducer producer;
    private final Session session;

    /**
     * Instantiates a new Activemq publisher.
     *
     * @param connection the connection
     * @throws JMSException the jms exception
     */
    public DelayedMessageProducer(Connection connection, String queue) throws JMSException {
        this.session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        this.producer = this.session.createProducer(this.session.createQueue(queue));
        this.producer.setDeliveryMode(DeliveryMode.PERSISTENT);
    }

    public void produce(TimerType type, Object data, TimerEntity timerEntity) throws JMSException {
        ObjectMessage message = this.session.createObjectMessage();
        message.setJMSType(timerEntity.getId());
        message.setObject(new TimerMessage(timerEntity.getDelayedMessageId(), type, data));
        message.setLongProperty(ScheduledMessage.AMQ_SCHEDULED_DELAY, timerEntity.getDelay() * 1000);

        this.producer.send(message);
        logger.debug("Timer: {} started with delay: {} | dMessageId: {}",
                timerEntity.getId(), timerEntity.getDelay(), timerEntity.getDelayedMessageId());
    }
}
