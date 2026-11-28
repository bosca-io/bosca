package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.KitRuntimeJob
import bosca.cache.withRequestCache
import bosca.db.withConnectionManager
import bosca.telemetry.Tracing
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/** Republishes committed work and idle deadlines after queue or Runner outages. */
class KitRuntimeRecovery(private val repository: KitRuntimeRepository, private val telemetry: KitRuntimeTelemetry) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val log = LoggerFactory.getLogger(KitRuntimeRecovery::class.java)

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                try {
                    sweep()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { log.error("Kit runtime recovery failed", failure) }
                delay(10.seconds)
            }
        }
    }

    /** Samples current operational totals and republishes bounded recovery work under fresh request contexts. */
    internal suspend fun sweep() = Tracing.withTrace {
        telemetry.observe(KitRuntimeOperation.RECOVERY) {
            withRequestCache { withConnectionManager {
                telemetry.sample(repository.activeCount(), repository.failedCount())
                repository.pending(100, URGENT_INTERVAL_SECONDS, IDLE_INTERVAL_SECONDS).forEach { session ->
                    KitRuntimeJob(session.sessionId).enqueue()
                    telemetry.record(KitRuntimeOperation.RECOVERY, "started", session)
                }
            } }
        }
    }

    override fun close() { scope.cancel() }

    private companion object {
        const val URGENT_INTERVAL_SECONDS = 5L
        const val IDLE_INTERVAL_SECONDS = 60L
    }
}
