package com.ef.timers.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.util.SafeEncoder;

/**
 * The type Redis client.
 */
public class RedisClientImpl implements RedisClient {
    /**
     * The constant objectMapper.
     */
    private static final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * The constant JSON_ROOT_PATH.
     */
    private static final String JSON_ROOT_PATH = ".";
    /**
     * The Jedis pool.
     */
    private final JedisPool jedisPool;


    /**
     * Instantiates a new Redis client.
     *
     * @param jedisPool the jedis pool
     */
    public RedisClientImpl(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    /**
     * Helper to check for an OK reply.
     *
     * @param str the reply string to "scrutinize"
     */
    private static void assertReplyOk(final String str) {
        if (!str.equals("OK")) {
            throw new RuntimeException(str);
        }
    }

    /**
     * Helper to check for errors and throw them as an exception.
     *
     * @param str the reply string to "analyze"
     * @throws RuntimeException exception
     */
    private static void assertReplyNotError(final String str) {
        if (str.startsWith("-ERR")) {
            throw new RuntimeException(str.substring(5));
        }
    }

    @Override
    public boolean setJson(String key, Object object) {
        return this.setJson(key, JSON_ROOT_PATH, object);
    }

    @Override
    public boolean setJson(String key, String path, Object object) {
        try (Jedis conn = getConnection()) {
            String value = objectMapper.writeValueAsString(object);
            conn.getClient().sendCommand(Command.SET, SafeEncoder.encodeMany(key, path, value));
            String status = conn.getClient().getStatusCodeReply();
            assertReplyOk(status);
            return true;
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean setJsonWithSet(String type, String id, Object object) {
        Transaction transaction = null;
        try (Jedis conn = getConnection()) {
            transaction = conn.multi();
            transaction.sadd(type, id);
            String value = objectMapper.writeValueAsString(object);
            transaction.sendCommand(Command.SET, SafeEncoder.encodeMany(getKey(type, id), JSON_ROOT_PATH, value));
            transaction.exec();
            return true;
        } catch (Exception e) {
            if (transaction != null) {
                transaction.discard();
            }
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean setAllJsonForType(String type, List<String> idList, List<Object> objectList) {
        Transaction transaction = null;
        try (Jedis conn = getConnection()) {
            transaction = conn.multi();
            for (int i = 0; i < idList.size(); i++) {
                String id = idList.get(i);
                Object object = objectList.get(i);

                transaction.sadd(type, id);
                String value = objectMapper.writeValueAsString(object);
                transaction.sendCommand(Command.SET, SafeEncoder.encodeMany(getKey(type, id), JSON_ROOT_PATH, value));
            }
            transaction.exec();
            return true;
        } catch (Exception e) {
            if (transaction != null) {
                transaction.discard();
            }
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Sets multiple json object along with storing their keys in a set.
     *
     * @param type        the type of the field or the name of the set.
     * @param jsonObjects Map of keys and their correspondent json objects.
     * @return true if operation successful, false otherwise.
     */
    public boolean setMultiJsonWithSet(String type, Map<UUID, Object> jsonObjects) {
        Transaction transaction = null;
        try (Jedis conn = getConnection()) {
            transaction = conn.multi();
            for (Map.Entry<UUID, Object> entry : jsonObjects.entrySet()) {
                String id = entry.getKey().toString();
                transaction.sadd(type, id);
                String value = objectMapper.writeValueAsString(entry.getValue());
                transaction.sendCommand(Command.SET, SafeEncoder.encodeMany(getKey(type, id), JSON_ROOT_PATH, value));
            }
            transaction.exec();
            return true;
        } catch (Exception e) {
            if (transaction != null) {
                transaction.discard();
            }
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public <T> T getJson(String key, Class<T> clazz) {
        try (Jedis conn = getConnection()) {
            conn.getClient().sendCommand(Command.GET, SafeEncoder.encodeMany(key, JSON_ROOT_PATH));
            String response = conn.getClient().getBulkReply();
            if (response != null) {
                assertReplyNotError(response);
                return objectMapper.readValue(response, clazz);
            }
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public <T> List<T> getJsonArray(String key, Class<T> clazz) throws JsonProcessingException {
        String response;
        try (Jedis conn = getConnection()) {
            conn.getClient().sendCommand(Command.GET, SafeEncoder.encodeMany(key, JSON_ROOT_PATH));
            response = conn.getClient().getBulkReply();
        }
        if (response != null) {
            assertReplyNotError(response);
            JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, clazz);
            return objectMapper.readValue(response, type);
        } else {
            return new ArrayList<>();
        }
    }

    @Override
    public <T> List<T> multiGetJson(Class<T> clazz, String... keys) {
        List<T> responseList = new ArrayList<>();
        String[] args = Stream
                .of(keys, new String[] {JSON_ROOT_PATH})
                .flatMap(Stream::of)
                .toArray(String[]::new);

        try (Jedis conn = getConnection()) {
            List<String> rep;
            conn.getClient().sendCommand(Command.MGET, args);
            rep = conn.getClient().getMultiBulkReply();
            if (rep != null) {
                for (String object : rep) {
                    responseList.add(objectMapper.readValue(object, clazz));
                }
            }
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return responseList;
    }

    @Override
    public Long delJson(String key) {
        try (Jedis conn = getConnection()) {
            conn.getClient().sendCommand(Command.DEL, SafeEncoder.encodeMany(key, JSON_ROOT_PATH));
            return conn.getClient().getIntegerReply();
        }
    }

    @Override
    public boolean delJsonWithSet(String type, String id) {
        Transaction transaction = null;
        try (Jedis conn = getConnection()) {
            transaction = conn.multi();
            transaction.sendCommand(Command.DEL, SafeEncoder.encodeMany(getKey(type, id), JSON_ROOT_PATH));
            transaction.srem(type, id);
            transaction.exec();
            return true;
        } catch (Exception e) {
            if (transaction != null) {
                transaction.discard();
            }
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean delAllJsonForType(String type) {
        Transaction transaction = null;
        try (Jedis conn = getConnection()) {
            Set<String> idList = conn.smembers(type);
            if (idList == null) {
                return false;
            }
            transaction = conn.multi();
            for (String id : idList) {
                transaction.sendCommand(Command.DEL, SafeEncoder.encodeMany(getKey(type, id), JSON_ROOT_PATH));
            }
            transaction.del(type);
            transaction.exec();
            return true;
        } catch (Exception e) {
            if (transaction != null) {
                transaction.discard();
            }
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public Long setAdd(String key, String member) {
        try (Jedis conn = getConnection()) {
            return conn.sadd(key, member);
        }
    }

    @Override
    public Set<String> setMembers(String key) {
        try (Jedis conn = getConnection()) {
            return conn.smembers(key);
        }
    }

    @Override
    public Long setRem(String key, String... member) {
        try (Jedis conn = getConnection()) {
            return conn.srem(key, member);
        }
    }

    @Override
    public void set(String key, String value) {
        String status;
        try (Jedis conn = getConnection()) {
            status = conn.set(key, value);
        }
        assertReplyOk(status);
    }

    @Override
    public String get(String key) {
        try (Jedis conn = getConnection()) {
            return conn.get(key);
        }
    }

    @Override
    public Long del(String key) {
        try (Jedis conn = getConnection()) {

            return conn.del(key);
        }
    }

    @Override
    public boolean exists(String key) {
        try (Jedis conn = getConnection()) {
            return conn.exists(key);
        }
    }

    @Override
    public ScanResult<String> scan(String cursor, ScanParams params) {
        try (Jedis conn = getConnection()) {
            return conn.scan(cursor, params);
        }
    }

    /**
     * Gets connection.
     *
     * @return the connection
     */
    private Jedis getConnection() {
        return this.jedisPool.getResource();
    }

    /**
     * Gets key.
     *
     * @param type the type
     * @param id   the id
     * @return the key
     */
    private String getKey(String type, String id) {
        return type + ":" + id;
    }

    /**
     * The enum Command.
     */
    private enum Command implements ProtocolCommand {
        /**
         * Del command.
         */
        DEL("JSON.DEL"),
        /**
         * Get command.
         */
        GET("JSON.GET"),
        /**
         * Set command.
         */
        SET("JSON.SET"),
        /**
         * Multiple get command.
         */
        MGET("JSON.MGET"),
        /**
         * Type command.
         */
        TYPE("JSON.TYPE");
        /**
         * The Raw.
         */
        private final byte[] raw;

        /**
         * Instantiates a new Command.
         *
         * @param alt the alt
         */
        Command(String alt) {
            raw = SafeEncoder.encode(alt);
        }

        public byte[] getRaw() {
            return raw;
        }
    }
}
