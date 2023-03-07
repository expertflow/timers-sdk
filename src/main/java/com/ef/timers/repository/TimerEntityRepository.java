package com.ef.timers.repository;

import com.ef.timers.model.TimerEntity;
import com.ef.timers.redis.RedisClientImpl;
import com.ef.timers.redis.RedisJsonDao;
import redis.clients.jedis.JedisPool;

public class TimerEntityRepository extends RedisJsonDao<TimerEntity> {
    public TimerEntityRepository(JedisPool jedisPool, String collection) {
        super(new RedisClientImpl(jedisPool), collection);
    }
}
