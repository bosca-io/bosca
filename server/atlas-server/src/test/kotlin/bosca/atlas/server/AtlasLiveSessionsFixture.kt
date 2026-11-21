package bosca.atlas.server

import bosca.analytics.livesessions.LiveSessionsService
import bosca.analytics.model.LiveSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Observes real broker subscriptions and permits a controlled completion without replacing their data. */
internal class AtlasLiveSessionsFixture(private val actual: LiveSessionsService) : LiveSessionsService {
    private val generations = ConcurrentHashMap<String, AtomicInteger>()
    private val active = ConcurrentHashMap<Pair<String?, String?>, AtomicInteger>()
    private val started = AtomicInteger()

    override suspend fun publish(appId: String, session: LiveSession) = actual.publish(appId, session)

    override fun subscribe(appId: String?, appVersion: String?): Flow<LiveSession> = flow {
        val generation = generations.computeIfAbsent(appId.orEmpty()) { AtomicInteger() }
        val initial = generation.get()
        val count = active.computeIfAbsent(appId to appVersion) { AtomicInteger() }
        count.incrementAndGet(); started.incrementAndGet()
        try {
            emitAll(actual.subscribe(appId, appVersion).takeWhile { generation.get() == initial })
        } finally {
            count.decrementAndGet()
        }
    }

    fun complete(appId: String) { generations.computeIfAbsent(appId) { AtomicInteger() }.incrementAndGet() }

    fun snapshot(): JsonObject = buildJsonObject {
        put("started", started.get())
        putJsonArray("subscriptions") {
            for ((scope, count) in active) if (count.get() != 0) add(buildJsonObject {
                put("appId", scope.first); put("appVersion", scope.second); put("active", count.get())
            })
        }
    }
}
