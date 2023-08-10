package com.ef.timers;

import com.ef.timers.jms.DelayedMessageProducer;
import com.ef.timers.model.TimerEntity;
import com.ef.timers.model.TimerMessage;
import com.ef.timers.model.TimerType;
import com.ef.timers.repository.TimerEntityRepository;
import javax.jms.Connection;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

public class Timer implements MessageListener, ExceptionListener {
    private static final Logger logger = LoggerFactory.getLogger(Timer.class);

    private final TimerEntityRepository repository;
    private final DelayedMessageProducer delayedMessageProducer;
    private ExpiryHandler expiryHandler;

    public Timer(Pool<Jedis> jedisPool, Connection connection, String queue) throws JMSException {
        this.repository = new TimerEntityRepository(jedisPool, queue + "-timer");
        this.delayedMessageProducer = new DelayedMessageProducer(connection, queue);

        Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
        MessageConsumer consumer = session.createConsumer(session.createQueue(queue));
        consumer.setMessageListener(this);
    }

    public void start(String id, long delay, TimerType type, Object data) throws JMSException {
        if (delay < 0) {
            logger.warn("Delay value is less than 0, ignoring start timer request");
            return;
        }

        String timerId = this.getJsonKey(id, type);
        TimerEntity timerEntity = this.repository.findById(timerId);

        if (timerEntity == null) {
            if (delay == 0) {
                this.callExpiryHandler(type, data);
                return;
            }

            timerEntity = new TimerEntity(timerId, delay);
            this.repository.save(timerId, timerEntity);
            this.delayedMessageProducer.produce(type, data, timerEntity);
        }
    }

    public boolean isRunning(String id, TimerType type) {
        return this.repository.findById(this.getJsonKey(id, type)) != null;
    }

    public void stop(String id, TimerType type) {
        this.repository.deleteById(this.getJsonKey(id, type));
    }

    public void setExpiryHandler(ExpiryHandler expiryHandler) {
        this.expiryHandler = expiryHandler;
    }

    @Override
    public synchronized void onException(JMSException ex) {
        logger.error(ExceptionUtils.getMessage(ex));
        logger.error(ExceptionUtils.getStackTrace(ex));
    }

    @Override
    public void onMessage(Message message) {
        try {
            String timerId = message.getJMSType();
            TimerMessage timerMessage = (TimerMessage) ((ObjectMessage) message).getObject();

            TimerEntity timerEntity = this.repository.findById(timerId);
            if (timerEntity == null || !timerMessage.getId().equals(timerEntity.getDelayedMessageId())) {
                message.acknowledge();
                logger.debug("Timer: {} with dMessageId: {} is dispensable, ignoring it",
                        timerId, timerMessage.getId());
                return;
            }

            this.repository.deleteById(timerId);
            message.acknowledge();

            this.callExpiryHandler(timerMessage.getType(), timerMessage.getData());
        } catch (Exception e) {
            logger.error(ExceptionUtils.getMessage(e));
            logger.error(ExceptionUtils.getStackTrace(e));
        }
    }

    private void callExpiryHandler(TimerType type, Object data) {
        if (this.expiryHandler != null) {
            this.expiryHandler.handle(type, data);
        }
    }

    private String getJsonKey(String id, TimerType type) {
        return type.name() + "-" + id;
    }
}
