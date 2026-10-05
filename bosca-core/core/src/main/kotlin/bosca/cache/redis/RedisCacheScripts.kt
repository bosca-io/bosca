package bosca.cache.redis

import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.redis.RedisConnectionPool
import bosca.redis.RedisScriptExecutor
import io.lettuce.core.ScriptOutputType
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * The Lua scripts behind the Redis caches.
 *
 * @param publishEvictions whether scripts publish each written, removed, or expired key to the cache's eviction
 * channel. Only [NearCacheRedisCache] subscribes, to drop its local copies; without it the publishes reach no one and
 * cost Redis about 0.2 µs per key, so [RedisCache] runs with this off.
 */
@OptIn(ExperimentalSerializationApi::class)
class RedisCacheScripts(
    connections: RedisConnectionPool,
    val publishEvictions: Boolean = true,
) {

    private fun script(text: String) = if (publishEvictions) text else text.withoutPublishes()

    private val put = RedisScriptExecutor(connections, script(putScript))
    private val putBatch = RedisScriptExecutor(connections, script(putBatchScript))
    private val putIfAbsent = RedisScriptExecutor(connections, script(putIfAbsentScript))
    private val getBatch = RedisScriptExecutor(connections, script(getBatchScript))
    private val getAndTouchBatch = RedisScriptExecutor(connections, script(getAndTouchBatchScript))
    private val remove = RedisScriptExecutor(connections, script(removeScript))
    private val removePrefix = RedisScriptExecutor(connections, script(removePrefixScript))
    private val removeBatch = RedisScriptExecutor(connections, script(removeBatchScript))
    private val expiration = RedisScriptExecutor(connections, script(expirationScript))
    private val clear = RedisScriptExecutor(connections, script(clearScript))

    suspend fun <K> put(
        cacheName: String,
        channel: String,
        keySerializer: CacheKeySerializer<K>,
        key: CacheKey<K>,
        ttlMillis: Long,
        value: String?,
    ) {
        put.execute<Long>(
            ScriptOutputType.INTEGER,
            arrayOf(cacheName, keySerializer.toRemoteKey(key), "$cacheName:expirations"),
            ttlMillis.toString(),
            value ?: "",
            channel
        )
    }

    suspend fun <K> putBatch(
        cacheName: String,
        channel: String,
        keySerializer: CacheKeySerializer<K>,
        entries: List<Triple<CacheKey<K>, Long, String>>
    ) {
        if (entries.isEmpty()) return

        val args = mutableListOf<String>()

        // Add all key-value pairs
        entries.forEach { (key, ttlMillis, value) ->
            args.add(keySerializer.toRemoteKey(key))
            args.add(value)
            args.add(ttlMillis.toString())
        }

        // Add channel at the end
        args.add(channel)

        putBatch.execute<Long>(
            ScriptOutputType.INTEGER,
            arrayOf(cacheName, "$cacheName:expirations"),
            *args.toTypedArray()
        )
    }

    suspend fun <K> putIfAbsent(
        cacheName: String,
        channel: String,
        keySerializer: CacheKeySerializer<K>,
        key: CacheKey<K>,
        value: String,
        ttlMillis: Long,
    ): Boolean = putIfAbsent.execute<Long>(
        ScriptOutputType.INTEGER,
        arrayOf(cacheName, keySerializer.toRemoteKey(key), "$cacheName:expirations"),
        value,
        ttlMillis.toString(),
        channel,
    ) == 1L

    suspend fun <K> getBatch(
        cacheName: String,
        channel: String,
        keySerializer: CacheKeySerializer<K>,
        keys: List<CacheKey<K>>,
    ): List<Pair<Boolean, String?>> {
        if (keys.isEmpty()) return emptyList()
        val result = getBatch.execute<List<String>>(
            ScriptOutputType.MULTI,
            arrayOf(cacheName, "$cacheName:expirations"),
            channel,
            *keys.map(keySerializer::toRemoteKey).toTypedArray(),
        ).orEmpty()
        check(result.size == keys.size * 2) { "Redis cache returned an incomplete batch" }
        return result.chunked(2).map { (exists, value) -> (exists == "1") to value.takeIf { exists == "1" } }
    }

    suspend fun <K> getAndTouchBatch(
        cacheName: String,
        channel: String,
        keySerializer: CacheKeySerializer<K>,
        keys: List<CacheKey<K>>,
        ttlMillis: Long,
    ): List<Pair<Boolean, String?>> {
        if (keys.isEmpty()) return emptyList()
        val result = getAndTouchBatch.execute<List<String>>(
            ScriptOutputType.MULTI,
            arrayOf(cacheName, "$cacheName:expirations"),
            ttlMillis.toString(),
            channel,
            *keys.map(keySerializer::toRemoteKey).toTypedArray(),
        ).orEmpty()
        check(result.size == keys.size * 2) { "Redis cache returned an incomplete get-and-touch batch" }
        return result.chunked(2).map { (exists, value) -> (exists == "1") to value.takeIf { exists == "1" } }
    }

    suspend fun <K> removePrefix(
        cacheName: String,
        channel: String,
        keySerializer: CacheKeySerializer<K>,
        key: CacheKey<K>
    ): List<CacheKey<K>> {
        val prefix = keySerializer.toRemoteKeyPrefix(key)
        val removed = removePrefix.execute<List<String>>(
            ScriptOutputType.MULTI,
            arrayOf(cacheName, "$cacheName:expirations"),
            prefix,
            "1000",
            channel
        )
        return removed?.map {
            keySerializer.fromRemoteKey(it)
        } ?: emptyList()
    }

    /**
     * Removes [keys] exactly and every field matching each of [prefixes] in one script call.
     *
     * @return the number of distinct fields removed or, for exact keys, targeted
     */
    suspend fun <K> removeBatch(
        cacheName: String,
        channel: String,
        keySerializer: CacheKeySerializer<K>,
        keys: List<CacheKey<K>>,
        prefixes: List<CacheKey<K>>,
    ): Long {
        if (keys.isEmpty() && prefixes.isEmpty()) return 0
        val args = ArrayList<String>(3 + keys.size + prefixes.size)
        args += channel
        args += keys.size.toString()
        args += "1000"
        keys.mapTo(args) { keySerializer.toRemoteKey(it) }
        prefixes.mapTo(args) { keySerializer.toRemoteKeyPrefix(it) }
        return removeBatch.execute<Long>(
            ScriptOutputType.INTEGER,
            arrayOf(cacheName, "$cacheName:expirations"),
            *args.toTypedArray(),
        ) ?: 0
    }

    suspend fun <K> remove(
        cacheName: String,
        channel: String,
        keySerializer: CacheKeySerializer<K>,
        key: CacheKey<K>
    ) = remove.execute<String>(
        ScriptOutputType.VALUE,
        arrayOf(cacheName, "$cacheName:expirations", keySerializer.toRemoteKey(key)),
        channel,
    )

    suspend fun expiration(
        cacheName: String,
        channel: String
    ): List<String> = expiration.execute<List<String>>(
        ScriptOutputType.MULTI,
        arrayOf(cacheName, "$cacheName:expirations"),
        channel,
    ) ?: emptyList()

    suspend fun clear(cacheName: String, channel: String, message: String) {
        clear.execute<Long>(
            ScriptOutputType.INTEGER,
            arrayOf(cacheName, "$cacheName:expirations"),
            channel,
            message,
        )
    }

    companion object {

        private const val PUBLISH_CALL = "redis.call('PUBLISH'"

        /**
         * Drops the scripts' eviction publishes. Each is a standalone single-line statement whose result is unused,
         * so removing the line leaves the rest of the script unchanged.
         */
        private fun String.withoutPublishes(): String =
            lines().filterNot { PUBLISH_CALL in it }.joinToString("\n").also { stripped ->
                check("PUBLISH" !in stripped) { "A cache script publishes in a form withoutPublishes() does not remove" }
            }

        private val putScript = """
            local cache_name = tostring(KEYS[1])
            local cache_key = tostring(KEYS[2])
            local cache_expirations = tostring(KEYS[3])
            local ttl_millis = tonumber(ARGV[1])
            local value = ARGV[2]
            local channel = ARGV[3]
            local server_time = redis.call('TIME')
            local current_timestamp = tonumber(server_time[1]) * 1000 + math.floor(tonumber(server_time[2]) / 1000)
            local expire_timestamp = current_timestamp + ttl_millis
            redis.call('HSET', cache_name, cache_key, value)
            redis.call('ZADD', cache_expirations, expire_timestamp, cache_key)      
            redis.call('PUBLISH', channel, cache_key)
            return 0
        """.trimIndent()

        private val putBatchScript = """
            local cache_name = tostring(KEYS[1])
            local cache_expirations = tostring(KEYS[2])
            
            -- ARGV contains: key1, value1, ttl1, key2, value2, ttl2, ..., channel
            local num_args = #ARGV
            local channel = ARGV[num_args]
            local server_time = redis.call('TIME')
            local current_timestamp = tonumber(server_time[1]) * 1000 + math.floor(tonumber(server_time[2]) / 1000)

            -- Lua/Redis can fail if unpack(...) expands too many values; execute in chunks.
            -- For HSET we send field/value pairs; for ZADD we send score/member pairs.
            local function call_in_chunks(cmd, redis_key, args, chunk_size)
                chunk_size = chunk_size or 1000
                local i = 1
                while i <= #args do
                    local j = math.min(i + chunk_size - 1, #args)
                    redis.call(cmd, redis_key, unpack(args, i, j))
                    i = j + 1
                end
            end
            
            -- Build argument arrays (excluding the redis key itself)
            local hset_args = {}
            local zadd_args = {}
            local keys_to_publish = {}
            
            -- Process entries in groups of 3 (key, value, TTL)
            for i = 1, num_args - 1, 3 do
                local cache_key = ARGV[i]
                local value = ARGV[i + 1]
                local expire_timestamp = current_timestamp + tonumber(ARGV[i + 2])
                
                table.insert(hset_args, cache_key)
                table.insert(hset_args, value)
                
                table.insert(zadd_args, expire_timestamp)
                table.insert(zadd_args, cache_key)
                
                table.insert(keys_to_publish, cache_key)
            end
            
            -- Execute batch operations (chunked to avoid "too many results to unpack")
            if #hset_args > 0 then
                -- chunk_size must be even-sized for pair lists. 1000 keeps calls small and safe.
                call_in_chunks('HSET', cache_name, hset_args, 1000)
                call_in_chunks('ZADD', cache_expirations, zadd_args, 1000)
                
                -- Publish notifications for all keys
                for _, key in ipairs(keys_to_publish) do
                    redis.call('PUBLISH', channel, key)
                end
            end
            
            return #keys_to_publish
        """.trimIndent()

        private val putIfAbsentScript = """
            local cache_name = tostring(KEYS[1])
            local cache_key = tostring(KEYS[2])
            local cache_expirations = tostring(KEYS[3])
            local value = ARGV[1]
            local ttl_millis = tonumber(ARGV[2])
            local channel = ARGV[3]
            local server_time = redis.call('TIME')
            local current_timestamp = tonumber(server_time[1]) * 1000 + math.floor(tonumber(server_time[2]) / 1000)
            local current_expiration = redis.call('ZSCORE', cache_expirations, cache_key)
            local current_value = redis.call('HGET', cache_name, cache_key)

            if current_value ~= false and current_expiration ~= false and tonumber(current_expiration) > current_timestamp then
                return 0
            end

            redis.call('HDEL', cache_name, cache_key)
            redis.call('ZREM', cache_expirations, cache_key)
            local expire_timestamp = current_timestamp + ttl_millis
            redis.call('HSET', cache_name, cache_key, value)
            redis.call('ZADD', cache_expirations, expire_timestamp, cache_key)
            redis.call('PUBLISH', channel, cache_key)
            return 1
        """.trimIndent()

        private val getBatchScript = """
            local cache_name = tostring(KEYS[1])
            local cache_expirations = tostring(KEYS[2])
            local channel = ARGV[1]
            local server_time = redis.call('TIME')
            local current_timestamp = tonumber(server_time[1]) * 1000 + math.floor(tonumber(server_time[2]) / 1000)
            local result = {}

            for i = 2, #ARGV do
                local cache_key = ARGV[i]
                local value = redis.call('HGET', cache_name, cache_key)
                local current_expiration = redis.call('ZSCORE', cache_expirations, cache_key)
                if value == false or current_expiration == false or tonumber(current_expiration) <= current_timestamp then
                    local removed = redis.call('HDEL', cache_name, cache_key)
                    redis.call('ZREM', cache_expirations, cache_key)
                    if removed > 0 or current_expiration ~= false then
                        redis.call('PUBLISH', channel, cache_key)
                    end
                    table.insert(result, '0')
                    table.insert(result, '')
                else
                    table.insert(result, '1')
                    table.insert(result, value)
                end
            end
            return result
        """.trimIndent()

        private val getAndTouchBatchScript = """
            local cache_name = tostring(KEYS[1])
            local cache_expirations = tostring(KEYS[2])
            local ttl_millis = tonumber(ARGV[1])
            local channel = ARGV[2]
            local server_time = redis.call('TIME')
            local current_timestamp = tonumber(server_time[1]) * 1000 + math.floor(tonumber(server_time[2]) / 1000)
            local expire_timestamp = current_timestamp + ttl_millis
            local result = {}

            for i = 3, #ARGV do
                local cache_key = ARGV[i]
                local value = redis.call('HGET', cache_name, cache_key)
                local current_expiration = redis.call('ZSCORE', cache_expirations, cache_key)
                if value == false or current_expiration == false or tonumber(current_expiration) <= current_timestamp then
                    local removed = redis.call('HDEL', cache_name, cache_key)
                    redis.call('ZREM', cache_expirations, cache_key)
                    if removed > 0 or current_expiration ~= false then
                        redis.call('PUBLISH', channel, cache_key)
                    end
                    table.insert(result, '0')
                    table.insert(result, '')
                else
                    redis.call('ZADD', cache_expirations, expire_timestamp, cache_key)
                    table.insert(result, '1')
                    table.insert(result, value)
                end
            end
            return result
        """.trimIndent()

        private val removeScript = """
            local cache_name = tostring(KEYS[1])
            local cache_expirations = tostring(KEYS[2])
            local cache_key = tostring(KEYS[3])
            local channel = ARGV[1]
            local removed = redis.call('HGET', cache_name, cache_key)
            local current_expiration = redis.call('ZSCORE', cache_expirations, cache_key)
            local server_time = redis.call('TIME')
            local current_timestamp = tonumber(server_time[1]) * 1000 + math.floor(tonumber(server_time[2]) / 1000)
            local was_live = removed ~= false and current_expiration ~= false and tonumber(current_expiration) > current_timestamp
            redis.call('HDEL', cache_name, cache_key)
            redis.call('ZREM', cache_expirations, cache_key)          
            redis.call('PUBLISH', channel, cache_key)
            if was_live then
                return removed
            end
            return false
        """.trimIndent()

        // Exact keys are removed like removeScript and prefixes are matched like removePrefixScript (HSCAN MATCH
        // prefix*), in one call. Every field is collected before any is deleted, so no HSCAN cursor sees a mutation.
        private val removeBatchScript = """
            local cache_name = tostring(KEYS[1])
            local cache_expirations = tostring(KEYS[2])
            local channel = ARGV[1]
            local exact_count = tonumber(ARGV[2])
            local scan_count = tonumber(ARGV[3])
            local first_prefix = 4 + exact_count

            local removed = {}
            local seen = {}
            local function add(field)
                if not seen[field] then
                    seen[field] = true
                    table.insert(removed, field)
                end
            end

            for i = 4, first_prefix - 1 do
                add(ARGV[i])
            end

            for i = first_prefix, #ARGV do
                local match_pattern = ARGV[i] .. "*"
                local cursor = "0"
                repeat
                    local res = redis.call('HSCAN', cache_name, cursor, 'MATCH', match_pattern, 'COUNT', scan_count)
                    cursor = res[1]
                    local kv = res[2]
                    for j = 1, #kv, 2 do
                        add(kv[j])
                    end
                until cursor == "0"
            end

            -- HDEL and ZREM take a variable number of members; chunk to avoid unpack overflow.
            local function call_in_chunks(cmd, redis_key, args, chunk_size)
                local i = 1
                while i <= #args do
                    local j = math.min(i + chunk_size - 1, #args)
                    redis.call(cmd, redis_key, unpack(args, i, j))
                    i = j + 1
                end
            end

            if #removed > 0 then
                call_in_chunks('HDEL', cache_name, removed, 1000)
                call_in_chunks('ZREM', cache_expirations, removed, 1000)
                for i = 1, #removed do
                    redis.call('PUBLISH', channel, removed[i])
                end
            end

            return #removed
        """.trimIndent()

        private val removePrefixScript = """
            local cache_name = tostring(KEYS[1])
            local cache_expirations = tostring(KEYS[2])
            local prefix = tostring(ARGV[1])
            local count = tonumber(ARGV[2] or "1000")
            local channel = ARGV[3]
            
            local match_pattern = prefix .. "*"
            local cursor = "0"
            local removed = {}

            -- Chunked unpack helper to avoid "too many results to unpack"
            local function call_in_chunks(cmd, redis_key, args, chunk_size)
                chunk_size = chunk_size or 1000
                local i = 1
                while i <= #args do
                    local j = math.min(i + chunk_size - 1, #args)
                    redis.call(cmd, redis_key, unpack(args, i, j))
                    i = j + 1
                end
            end

            repeat
              local res = redis.call('HSCAN', cache_name, cursor, 'MATCH', match_pattern, 'COUNT', count)
              cursor = res[1]
              local kv = res[2]

              if kv and #kv > 0 then
                -- Collect first and mutate only after the scan completes. Deleting fields while HSCAN's
                -- cursor is in flight can move hash buckets and skip matching entries.
                for i = 1, #kv, 2 do
                  table.insert(removed, kv[i])
                end
              end
            until cursor == "0"

            if #removed > 0 then
              -- HDEL and ZREM take a variable number of members; chunk to avoid unpack overflow.
              call_in_chunks('HDEL', cache_name, removed, 1000)
              call_in_chunks('ZREM', cache_expirations, removed, 1000)

              for i = 1, #removed do
                redis.call('PUBLISH', channel, removed[i])
              end
            end

            return removed
        """.trimIndent()

        private val expirationScript = """
            local cache_name = tostring(KEYS[1])
            local cache_expirations = tostring(KEYS[2])
            local channel = ARGV[1]
            local server_time = redis.call('TIME')
            local current_timestamp = tonumber(server_time[1]) * 1000 + math.floor(tonumber(server_time[2]) / 1000)
            local expired_items = redis.call('ZRANGEBYSCORE', cache_expirations, 0, current_timestamp)
            if #expired_items > 0 then
                for i, item in ipairs(expired_items) do
                    redis.call('HDEL', cache_name, item)
                    redis.call('ZREM', cache_expirations, item)
                    redis.call('INCR', 'cache::expired::count')
                    redis.call('PUBLISH', channel, item)
                end
            end
            return expired_items
        """.trimIndent()

        private val clearScript = """
            local cache_name = tostring(KEYS[1])
            local cache_expirations = tostring(KEYS[2])
            local channel = ARGV[1]
            local message = ARGV[2]
            local removed = redis.call('DEL', cache_name, cache_expirations)
            redis.call('PUBLISH', channel, message)
            return removed
        """.trimIndent()
    }
}
