package bosca.cache.postgres

import bosca.cache.*
import bosca.db.ConnectionPool
import bosca.db.use
import kotlin.time.Duration

/** PostgreSQL cache without a process-local tier; expiry is checked on every read. */
class PostgresCache<K>(
    private val pool: ConnectionPool,
    private val name: String,
    override val keySerializer: CacheKeySerializer<K>,
    private val expiration: Duration,
) : Cache<K> {
    init { require(expiration.isPositive() && expiration.isFinite()) }
    override val estimatedSize: Long get() = 0

    private suspend fun read(key: CacheKey<K>, touch: Boolean): CacheValue = pool.connection().use { manager ->
        val sql = if (touch) "update infrastructure.cache_entries set expires = clock_timestamp() + (? * interval '1 millisecond') where namespace = ? and key = ? and expires > clock_timestamp() returning value"
            else "select value from infrastructure.cache_entries where namespace = ? and key = ? and expires > clock_timestamp()"
        manager.useStatement(sql) { stmt ->
            var index = 1
            if (touch) stmt.setLong(index++, expiration.inWholeMilliseconds)
            stmt.setString(index++, name); stmt.setString(index, keySerializer.toRemoteKey(key))
            stmt.executeQuery().use { if (it.next()) PostgresCacheValue(it.getString(1), true) else PostgresCacheValue(null, false) }
        }
    }
    override suspend fun get(key: CacheKey<K>) = read(key, false)
    override suspend fun getAndTouch(key: CacheKey<K>) = read(key, true)
    override suspend fun getBatch(keys: List<CacheKey<K>>) = readBatch(keys, false)
    override suspend fun getBatchAndTouch(keys: List<CacheKey<K>>) = readBatch(keys, true)
    private suspend fun readBatch(keys: List<CacheKey<K>>, touch: Boolean): List<CacheValue> {
        if (keys.isEmpty()) return emptyList()
        val values = keys.indices.joinToString { "(?::text, $it)" }
        val sql = if (touch) """
            with requested(key, position) as (values $values), refreshed as (
                update infrastructure.cache_entries set expires = clock_timestamp() + (? * interval '1 millisecond')
                where namespace = ? and key in (select key from requested) and expires > clock_timestamp() returning key, value
            ) select r.value, r.key is not null from requested k left join refreshed r using(key) order by k.position
        """ else """
            select c.value, c.key is not null from (values $values) k(key, position)
            left join infrastructure.cache_entries c on c.key = k.key and c.namespace = ? and c.expires > clock_timestamp()
            order by k.position
        """
        return pool.connection().use { manager -> manager.useStatement(sql.trimIndent()) { stmt ->
            keys.forEachIndexed { i, key -> stmt.setString(i + 1, keySerializer.toRemoteKey(key)) }
            var index = keys.size + 1
            if (touch) stmt.setLong(index++, expiration.inWholeMilliseconds)
            stmt.setString(index, name)
            stmt.executeQuery().use { result -> buildList { while (result.next()) add(PostgresCacheValue(result.getString(1), result.getBoolean(2))) } }
        } }
    }
    override suspend fun put(key: CacheKey<K>, value: String?) { store(key, value, false) }
    override suspend fun putBatch(entries: List<Pair<CacheKey<K>, String?>>) {
        val distinct = entries.associate { (key, value) -> keySerializer.toRemoteKey(key) to value }
        if (distinct.isEmpty()) return
        val values = distinct.keys.joinToString { "(?, ?, ?, clock_timestamp() + (? * interval '1 millisecond'))" }
        pool.connection().use { manager -> manager.useStatement("insert into infrastructure.cache_entries(namespace, key, value, expires) values $values on conflict (namespace, key) do update set value = excluded.value, expires = excluded.expires") { stmt ->
            distinct.entries.forEachIndexed { i, (key, value) ->
                stmt.setString(i * 4 + 1, name); stmt.setString(i * 4 + 2, key); stmt.setString(i * 4 + 3, value); stmt.setLong(i * 4 + 4, expiration.inWholeMilliseconds)
            }
            stmt.executeUpdate()
        } }
    }
    override suspend fun putIfAbsent(key: CacheKey<K>, value: String) = store(key, value, true)

    private suspend fun store(key: CacheKey<K>, value: String?, absent: Boolean): Boolean = pool.connection().use { manager ->
        manager.useStatement("""
            insert into infrastructure.cache_entries(namespace, key, value, expires)
            values (?, ?, ?, clock_timestamp() + (? * interval '1 millisecond'))
            on conflict (namespace, key) do update set value = excluded.value, expires = excluded.expires
            ${if (absent) "where infrastructure.cache_entries.expires <= clock_timestamp()" else ""}
        """.trimIndent()) { stmt ->
            stmt.setString(1, name); stmt.setString(2, keySerializer.toRemoteKey(key)); stmt.setString(3, value)
            stmt.setLong(4, expiration.inWholeMilliseconds); stmt.executeUpdate() > 0
        }
    }

    override suspend fun remove(key: CacheKey<K>, keyPrefix: Boolean): CacheValue? = pool.connection().use { manager ->
        val remote = if (keyPrefix) keySerializer.toRemoteKeyPrefix(key) else keySerializer.toRemoteKey(key)
        val predicate = if (keyPrefix) "starts_with(key, ?)" else "key = ?"
        manager.useStatement("delete from infrastructure.cache_entries where namespace = ? and $predicate returning value, expires > clock_timestamp()") { stmt ->
            stmt.setString(1, name); stmt.setString(2, remote)
            stmt.executeQuery().use { result ->
                if (keyPrefix) { while (result.next()) { }; null }
                else if (result.next() && result.getBoolean(2)) PostgresCacheValue(result.getString(1), true) else null
            }
        }
    }
    override suspend fun removeBatch(keys: List<CacheKey<K>>, prefixes: List<CacheKey<K>>) {
        if (keys.isEmpty() && prefixes.isEmpty()) return
        val predicates = List(keys.size) { "key = ?" } + List(prefixes.size) { "starts_with(key, ?)" }
        pool.connection().use { manager -> manager.useStatement("delete from infrastructure.cache_entries where namespace = ? and (${predicates.joinToString(" or ")})") { stmt ->
            stmt.setString(1, name)
            val values = keys.map(keySerializer::toRemoteKey) + prefixes.map(keySerializer::toRemoteKeyPrefix)
            values.forEachIndexed { i, value -> stmt.setString(i + 2, value) }
            stmt.executeUpdate()
        } }
    }
    override suspend fun clear() { delete("namespace = ?", name) }
    override suspend fun evictExpiredItems() { delete("namespace = ? and expires <= clock_timestamp()", name) }
    private suspend fun delete(predicate: String, name: String) {
        pool.connection().use { manager -> manager.useStatement("delete from infrastructure.cache_entries where $predicate") { stmt -> stmt.setString(1, name); stmt.executeUpdate() } }
    }
}
