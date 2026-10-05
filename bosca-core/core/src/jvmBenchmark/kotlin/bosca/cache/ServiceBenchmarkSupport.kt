package bosca.cache

import bosca.cache.serializers.UUIDKeySerializer
import bosca.cache.serializers.buildCacheKey
import bosca.cache.serializers.separateForCacheKey
import bosca.cache.service.ServiceCacheImpl
import bosca.db.ConnectionFactory
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.graphql.Batch
import bosca.serialization.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.flywaydb.core.api.configuration.FluentConfiguration
import java.sql.Connection

/** Composite cache id shaped like `MetadataCacheKeyId`: one entity, optionally narrowed by version and sub-key. */
data class BenchmarkEntityKeyId(val id: UUID, val version: Int? = null, val key: String? = null)

/** Shaped like `MetadataCacheKey`: the prefix is the cache name plus the entity id, so prefix removal drops every variant. */
data class BenchmarkEntityCacheKey(
    override val cacheName: String,
    override val key: BenchmarkEntityKeyId,
) : CacheKey<BenchmarkEntityKeyId> {

    override fun toRemoteKey(prefix: Boolean): String = buildCacheKey(prefix) {
        appendKeyPrefix("bench", cacheName)
        appendKeyPart(key.id)
        appendKeyPart(key.version)
        appendKeyPart(key.key)
    }

    override fun toRemoteKeyPrefix(): String = buildCacheKey(true) {
        appendKeyPrefix("bench", cacheName)
        appendKeyPart(key.id)
    }
}

object BenchmarkEntityKeySerializer : CacheKeySerializer<BenchmarkEntityKeyId> {

    override fun toLocalKey(cacheName: String, value: BenchmarkEntityKeyId): CacheKey<BenchmarkEntityKeyId> =
        BenchmarkEntityCacheKey(cacheName, value)

    override fun fromRemoteKey(key: String): CacheKey<BenchmarkEntityKeyId> {
        val parts = key.separateForCacheKey()
        return BenchmarkEntityCacheKey(
            parts.first(),
            BenchmarkEntityKeyId(UUID.parse(parts[1]), parts.getOrNull(2)?.toIntOrNull(), parts.getOrNull(3)?.takeIf { it.isNotEmpty() }),
        )
    }
}

/**
 * A service wired the way `MetadataServiceImpl` wires its caches: eight composite-key caches that are all
 * invalidated by entity-id prefix, plus a UUID-keyed permission cache that is invalidated exactly. Resolvers return
 * prebuilt values, so the figures contain RequestCache, serializer, and in-memory backend work only; no database.
 */
class BenchmarkEntityService(private val manager: CacheManager) {

    private val document = CacheBenchmarkPayloads.document()
    private val list = CacheBenchmarkPayloads.permissionList()

    private fun <V> entityCache(name: String, value: V) = ServiceCacheImpl<BenchmarkEntityKeyId, V>(
        name,
        batchResolver = { keys, batch -> keys.forEach { batch.setData(it, value) } },
    ) { value }

    private val entities = entityCache("entity", document)
    private val categories = entityCache("entity:category", list)
    private val traits = entityCache("entity:trait", list)
    private val relationships = entityCache("entity:relationships", list)
    private val profiles = entityCache("entity:profiles", list)
    private val supplementary = entityCache("entity:supplementary", list)
    private val supplementaryByKey = entityCache("entity:supplementary:key", list)
    private val versions = entityCache("entity:version", document)

    private val permissions = ServiceCacheImpl<UUID, List<BenchmarkPermission>>(
        "entity:permissions",
        batchResolver = { keys, batch -> keys.forEach { batch.setData(it, list) } },
    ) { list }

    private val prefixCaches = listOf(entities, categories, traits, relationships, profiles, supplementary, supplementaryByKey, versions)

    /** The five caches a GraphQL entity selection typically touches: the entity, its permissions, and three child lists. */
    suspend fun readEntity(id: UUID): Int {
        val key = BenchmarkEntityKeyId(id)
        var found = 0
        if (entities.get(key) != null) found++
        if (permissions.get(id) != null) found++
        if (categories.get(key) != null) found++
        if (traits.get(key) != null) found++
        if (relationships.get(key) != null) found++
        return found
    }

    /** The same five caches resolved through DataLoader-style batches, as a GraphQL list selection does. */
    suspend fun readListing(ids: List<UUID>): Int {
        val keys = ids.map { BenchmarkEntityKeyId(it) }
        return entities.getAll(keys).count { it != null } +
            permissions.getAll(ids).count { it != null } +
            categories.getAll(keys).count { it != null } +
            traits.getAll(keys).count { it != null } +
            relationships.getAll(keys).count { it != null }
    }

    /** [readListing] with the five caches' batches issued concurrently rather than one after another. */
    suspend fun readListingConcurrently(ids: List<UUID>): Int = coroutineScope {
        val keys = ids.map { BenchmarkEntityKeyId(it) }
        listOf(
            async { entities.getAll(keys).count { it != null } },
            async { permissions.getAll(ids).count { it != null } },
            async { categories.getAll(keys).count { it != null } },
            async { traits.getAll(keys).count { it != null } },
            async { relationships.getAll(keys).count { it != null } },
        ).awaitAll().sum()
    }

    /**
     * [fields] sibling field resolvers on one entity, resolved concurrently as the GraphQL executor does for query
     * fields, each checking the entity and its permissions.
     */
    suspend fun readSiblingFields(id: UUID, fields: Int): Int = coroutineScope {
        val key = BenchmarkEntityKeyId(id)
        List(fields) {
            async {
                var found = 0
                if (permissions.get(id) != null) found++
                if (entities.get(key) != null) found++
                found
            }
        }.awaitAll().sum()
    }

    /** Mirrors `MetadataServiceImpl.removeFromCache`: one exact removal plus a prefix removal on every entity cache. */
    suspend fun removeFromCache(id: UUID) {
        val key = BenchmarkEntityKeyId(id)
        permissions.remove(id)
        for (cache in prefixCaches) {
            cache.remove(key, keyPrefix = true)
        }
    }

    private companion object {
        val PREFIX_CACHE_NAMES = listOf(
            "entity", "entity:category", "entity:trait", "entity:relationships", "entity:profiles",
            "entity:supplementary", "entity:supplementary:key", "entity:version",
        )
        val DOCUMENT_CACHE_NAMES = setOf("entity", "entity:version")
    }

    /** Registers the caches and fills the backend for [ids], so reads start as remote hits. */
    suspend fun preload(ids: List<UUID>, serializer: RequestCacheSerializer) {
        val documentValue = serializer.serialize(document)
        val listValue = serializer.serialize(list)
        for (name in PREFIX_CACHE_NAMES) {
            val remote = manager.maybeAddCache(name, BenchmarkEntityKeySerializer)
            val value = if (name in DOCUMENT_CACHE_NAMES) documentValue else listValue
            remote.putBatch(ids.map { BenchmarkEntityKeySerializer.toLocalKey(name, BenchmarkEntityKeyId(it)) to value })
        }
        val remotePermissions = manager.maybeAddCache("entity:permissions", UUIDKeySerializer)
        remotePermissions.putBatch(ids.map { UUIDKeySerializer.toLocalKey("entity:permissions", it) to listValue })
    }
}

/**
 * Runs service work as one production-shaped request: a fresh [RequestCache] and [ConnectionManager] in context,
 * so cache write-backs and removals are deferred, and a final [ConnectionManager.release] that flushes them.
 * No statement runs, so the pool never opens a database connection.
 */
class ServiceRequestHarness(private val manager: CacheManager, private val serializer: RequestCacheSerializer) {

    private val pool = ConnectionPool(object : ConnectionFactory {
        override val key: String = "benchmark"
        override val maxConnections: Int = 1
        override fun create(): Connection = error("Service benchmarks never open a database connection")
        override fun setDataSource(flyway: FluentConfiguration): FluentConfiguration = error("Not used by benchmarks")
    })

    fun <T> request(block: suspend () -> T): T = runBlocking {
        val requestCache = RequestCache(manager, serializer)
        val connection = ConnectionManager(pool)
        try {
            withContext(connection.asCoroutineContext() + requestCache.asCoroutineContext()) { block() }
        } finally {
            connection.release()
        }
    }

    fun close() = runBlocking { pool.close() }
}
