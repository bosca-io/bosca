package bosca.cache.service

import bosca.cache.ServiceCache
import bosca.cache.requestCache
import bosca.graphql.Batch

class ServiceCacheImpl<K, BV>(
    private val cacheName: String,
    private val batchResolver: suspend (keys: List<K>, batch: Batch<K, BV>) -> Unit = { _, _ -> error("no batch support: $cacheName") },
    private val resolver: suspend (key: K) -> BV?,
) : ServiceCache<K, BV> {

    override suspend fun get(key: K): BV? {
        val cache = requestCache()
        return cache.get(cacheName, key) { resolver(key) }
    }

    override suspend fun put(key: K, value: BV) {
        val cache = requestCache()
        cache.put(cacheName, key, value)
    }

    override suspend fun getAll(keys: List<K>): List<BV?> {
        val batch = Batch<K, BV>(keys)
        addToBatch(batch)
        return batch.getResults()
    }

    override suspend fun addToBatch(batch: Batch<K, BV>) {
        val cache = requestCache()
        return cache.getBatch(cacheName, batch, batchResolver)
    }

    override suspend fun remove(key: K, keyPrefix: Boolean) {
        val cache = requestCache()
        cache.remove(cacheName, key, keyPrefix)
    }

    override suspend fun clear() {
        val cache = requestCache()
        cache.clear(cacheName)
    }
}