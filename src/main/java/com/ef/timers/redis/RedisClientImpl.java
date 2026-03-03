package com.ef.timers.redis;

import com.ef.timers.common.Constants;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.util.Pool;
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
    private final Pool<Jedis> jedisPool;

    @Value("${azure.connections.enable:false}")
    private boolean enableAzure;


    /**
     * Instantiates a new Redis client.
     *
     * @param jedisPool the jedis pool
     */
    public RedisClientImpl(Pool<Jedis> jedisPool) {
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
        } catch (JacksonException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean setJsonWithSet(String type, String id, Object object) {

        if (enableAzure) {
            return setJsonWithSetViaPipeline(type, id, object);
        }

        Transaction transaction = null;
        try (Jedis conn = getConnection()) {
            String tenantIdKey = MDC.get(Constants.TENANT_ID) + ":";
            transaction = conn.multi();
            transaction.sadd(tenantIdKey + type, id);
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

    /**
     * Sets a JSON object in Redis associated with a specified type and id using a pipeline.
     * The method adds the id to a Redis set of type identifiers and stores the JSON object
     * in a key constructed from the type and id. This uses a pipeline to queue commands and
     * execute them in a single request for performance optimization.
     *
     * @param type   The category or type under which the id is stored (e.g., "agentPresence").
     * @param id     The unique identifier to associate with the JSON object.
     * @param object The object to store as JSON in Redis.
     * @return true if the commands execute successfully; false otherwise.
     */
    public boolean setJsonWithSetViaPipeline(String type, String id, Object object) {
        try (Jedis conn = getConnection()) {
            String tenantIdKey = MDC.get(Constants.TENANT_ID) + ":";

            Pipeline pipeline = conn.pipelined();

            pipeline.sadd(tenantIdKey + type, id);
            pipeline.sendCommand(Command.SET, encode(getKey(type, id), JSON_ROOT_PATH, object));

            pipeline.sync(); // Execute all queued commands
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }


    @Override
    public boolean setAllJsonForType(String type, List<String> idList, List<Object> objectList) {
        if (enableAzure) {
            return setAllJsonForTypeViaPipeline(type, idList, objectList);
        }
        Transaction transaction = null;
        try (Jedis conn = getConnection()) {
            String tenantIdKey = MDC.get(Constants.TENANT_ID) + ":";

            transaction = conn.multi();
            for (int i = 0; i < idList.size(); i++) {
                String id = idList.get(i);
                Object object = objectList.get(i);

                transaction.sadd(tenantIdKey + type, id);
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
     * Sets multiple JSON objects in Redis associated with a specified type and list of IDs using a pipeline.
     * This method queues multiple commands for efficient batch processing.
     * It does not guarantee atomic execution, as pipelines do not support rollback on failure.
     *
     * @param type       The category or type under which the IDs are stored (e.g., "agentPresence").
     * @param idList     A list of unique identifiers associated with each JSON object.
     * @param objectList A list of objects to store as JSON in Redis, corresponding in size and order with idList.
     * @return true if all commands execute successfully; false otherwise.
     */
    public boolean setAllJsonForTypeViaPipeline(String type, List<String> idList, List<Object> objectList) {
        try (Jedis conn = getConnection()) {
            String tenantIdKey = MDC.get(Constants.TENANT_ID) + ":";

            Pipeline pipeline = conn.pipelined();
            for (int i = 0; i < idList.size(); i++) {
                String id = idList.get(i);
                Object object = objectList.get(i);

                // Add SADD command
                pipeline.sadd(tenantIdKey + type, id);

                // Serialize object to JSON
//                String jsonData = String.valueOf(RedisJson.encode(getKey(type, id), JSON_ROOT_PATH, object));

                // Add JSON.SET command
                pipeline.sendCommand(Command.SET, encode(getKey(type, id), JSON_ROOT_PATH, object));
            }
            pipeline.sync(); // Send all commands at once
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
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
            String tenantIdKey = MDC.get(Constants.TENANT_ID) + ":";
            transaction = conn.multi();
            for (Map.Entry<UUID, Object> entry : jsonObjects.entrySet()) {
                String id = entry.getKey().toString();
                transaction.sadd(tenantIdKey + type, id);
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
        } catch (JacksonException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public <T> List<T> getJsonArray(String key, Class<T> clazz) throws JacksonException {
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
        } catch (JacksonException e) {
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
        if (enableAzure) {
            return delJsonWithSetViaPipeLine(type, id);
        }

        Transaction transaction = null;
        try (Jedis conn = getConnection()) {
            String tenantIdKey = MDC.get(Constants.TENANT_ID) + ":";

            transaction = conn.multi();
            transaction.sendCommand(Command.DEL, SafeEncoder.encodeMany(getKey(type, id), JSON_ROOT_PATH));
            transaction.srem(tenantIdKey + type, id);
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
     * Deletes a JSON object associated with a specified type and ID from Redis using a pipeline.
     * This method queues commands to delete the JSON entry and remove the ID from the associated set,
     * allowing for efficient batch processing. Note that pipelines do not provide atomic transactions,
     * so commands may partially succeed in case of errors.
     *
     * @param type The category or type under which the ID is stored (e.g., "agentPresence").
     * @param id   The unique identifier of the JSON object to delete.
     * @return true if all commands in the pipeline execute successfully; false otherwise.
     */
    public boolean delJsonWithSetViaPipeLine(String type, String id) {
        try (Jedis conn = getConnection()) {
            String tenantIdKey = MDC.get(Constants.TENANT_ID) + ":";

            Pipeline pipeline = conn.pipelined();

            // Queue delete command for JSON key
            pipeline.sendCommand(Command.DEL, SafeEncoder.encodeMany(getKey(type, id), JSON_ROOT_PATH));

            // Queue removal of id from the set
            pipeline.srem(tenantIdKey + type, id);

            // Execute all queued commands
            pipeline.sync();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean delAllJsonForType(String type) {
        if (enableAzure) {
            return delAllJsonForTypeViaPipeLine(type);
        }

        Transaction transaction = null;
        try (Jedis conn = getConnection()) {
            String tenantIdKey = MDC.get(Constants.TENANT_ID) + ":";

            Set<String> idList = conn.smembers(tenantIdKey + type);
            if (idList == null) {
                return false;
            }
            transaction = conn.multi();
            for (String id : idList) {
                transaction.sendCommand(Command.DEL, SafeEncoder.encodeMany(getKey(type, id), JSON_ROOT_PATH));
            }
            transaction.del(tenantIdKey + type);
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
     * Deletes all JSON objects associated with a specified type in Redis using a pipeline.
     * This method retrieves all IDs stored under the specified type, queues commands to delete each
     * associated JSON entry, and removes the main set containing these IDs. Using pipelining allows for
     * efficient batch processing, though it is not atomic, so partial failures are possible.
     *
     * @param type The category or grouping under which the JSON objects are stored (e.g., "agentPresence").
     * @return true if all commands in the pipeline execute successfully; false otherwise.
     */
    public boolean delAllJsonForTypeViaPipeLine(String type) {
        try (Jedis conn = getConnection()) {
            String tenantIdKey = MDC.get(Constants.TENANT_ID) + ":";
            Set<String> idList = conn.smembers(tenantIdKey + type);
            if (idList == null || idList.isEmpty()) {
                return false;
            }

            Pipeline pipeline = conn.pipelined();
            // Delete the main type
            pipeline.del(tenantIdKey + type);

            for (String id : idList) {
                // Send DEL command for each JSON key
                pipeline.sendCommand(Command.DEL, SafeEncoder.encodeMany(getKey(type, id), JSON_ROOT_PATH));
            }

            pipeline.sync(); // Execute all commands in the pipeline
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
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
        String tenantIdKey = MDC.get(Constants.TENANT_ID);
        return tenantIdKey + ":" + type + ":" + id;
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

    /**
     * Encode byte [ ] [ ].
     *
     * @param key  the key
     * @param path the path
     * @param o    the o
     * @return the byte [ ] [ ]
     * @throws JacksonException the jackson exception
     */
    public static byte[][] encode(String key, String path, Object o) throws JacksonException {
        return SafeEncoder.encodeMany(key, path, objectMapper.writeValueAsString(o));
    }
}
