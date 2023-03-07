package com.ef.timers.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.Set;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;

/**
 * The interface Redis client.
 */
public interface RedisClient {
    /**
     * Associate Object as JSON value to key.
     *
     * @param key    String
     * @param object Object
     * @return the json
     */
    boolean setJson(String key, Object object);

    /**
     * Sets json.
     *
     * @param key    the key
     * @param path   the path
     * @param object the object
     * @return the json
     */
    boolean setJson(String key, String path, Object object);

    /**
     * Sets json with set.
     *
     * @param type   the type
     * @param id     the id
     * @param object the object
     * @return the json with set
     */
    boolean setJsonWithSet(String type, String id, Object object);

    /**
     * Sets all json for type.
     *
     * @param type       the type
     * @param idList     the id list
     * @param objectList the object list
     * @return the all json for type
     */
    boolean setAllJsonForType(String type, List<String> idList, List<Object> objectList);

    /**
     * Returns the Object associated with the key. If the key does not exist, the special value nil
     * is returned. If the value stored by key is not of Object type, an error is returned, because
     * GET JSON can only be used to process give Object.
     *
     * @param <T>   type T
     * @param key   String
     * @param clazz class type T
     * @return T json
     */
    <T> T getJson(String key, Class<T> clazz);

    /**
     * Gets json array.
     *
     * @param <T>   the type parameter
     * @param key   the key
     * @param clazz the clazz
     * @return the json array
     * @throws JsonProcessingException the json processing exception
     */
    <T> List<T> getJsonArray(String key, Class<T> clazz) throws JsonProcessingException;

    /**
     * Get the list Objects associated with the keys.  If any key does not exist, the special value
     * nil is returned.
     *
     * @param <T>   type T
     * @param clazz the clazz
     * @param keys  String
     * @return T list
     */
    <T> List<T> multiGetJson(Class<T> clazz, String... keys);

    /**
     * Delete an object associated given key.
     *
     * @param key String
     * @return operation status
     */
    Long delJson(String key);

    /**
     * Del json with set boolean.
     *
     * @param type the type
     * @param id   the id
     * @return the boolean
     */
    boolean delJsonWithSet(String type, String id);

    /**
     * Del all json for type boolean.
     *
     * @param type the type
     * @return the boolean
     */
    boolean delAllJsonForType(String type);

    /**
     * Adds one or more members to a set.
     *
     * @param key    String
     * @param member String
     * @return the long
     */
    Long setAdd(String key, String member);

    /**
     * Gets all the members in a set.
     *
     * @param key String
     * @return set of String
     */
    Set<String> setMembers(String key);

    /**
     * Removes one or more members from a set.
     *
     * @param key    String
     * @param member String
     * @return operation status
     */
    Long setRem(String key, String... member);


    /**
     * Associate value as String  to key.
     *
     * @param key   String
     * @param value String
     */
    void set(String key, String value);

    /**
     * Get value associated with key.
     *
     * @param key String
     * @return String string
     */
    String get(String key);

    /**
     * Delete any value associated given key.
     *
     * @param key String
     * @return operation status
     */
    Long del(String key);

    /**
     * Exists boolean.
     *
     * @param key the key
     * @return the boolean
     */
    boolean exists(String key);

    /**
     * To keep the pagination track.
     *
     * @param cursor the cursor
     * @param params the read params.
     * @return the scan result.
     */
    ScanResult<String> scan(String cursor, ScanParams params);
}