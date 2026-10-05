package bosca.graphql.persistedqueries

import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.di.provideBlocking
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext

class PersistedQueryCacheImpl {

    private val repository: PersistedQueryRepository by lazy { provideBlocking() }
    private val connectionPool: ConnectionPool by lazy { provideBlocking() }

    private val cache = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build<String, String>()

    suspend fun query(sha256: String): String? {
        cache.getIfPresent(sha256)?.let { return it }
        val connection = connectionPool.connection()
        return try {
            withContext(connection.asCoroutineContext()) {
                repository.findBySha256(sha256).firstOrNull()?.query?.also { cache.put(sha256, it) }
            }
        } finally {
            connection.release()
        }
    }

    fun clear() {
        cache.invalidateAll()
    }
}
