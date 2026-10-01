package bosca.analytics.lifecycle

import bosca.analytics.api.AnalyticsRuntimeScope
import bosca.analytics.persistence.AnalyticsPersistence
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Resources owned by the analytics DI graph and closed with its service lifecycle. */
internal class AnalyticsResources(
    private val runtimeScope: AnalyticsRuntimeScope,
    private val client: HttpClient,
    private val persistence: AnalyticsPersistence,
) {
    private val mutex = Mutex()
    private var closed = false

    suspend fun close() = mutex.withLock {
        if (closed) return@withLock
        closed = true
        runtimeScope.close()
        try {
            client.close()
        } finally {
            persistence.close()
        }
    }
}
