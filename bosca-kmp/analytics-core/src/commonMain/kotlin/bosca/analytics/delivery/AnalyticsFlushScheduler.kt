package bosca.analytics.delivery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

internal class AnalyticsFlushScheduler(
    private val scope: CoroutineScope,
    private val logger: AnalyticsLogger,
    private val flush: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private var job: Job? = null

    suspend fun schedule(delay: Duration) {
        mutex.withLock {
            val currentJob = currentCoroutineContext()[Job]
            if (job !== currentJob) job?.cancel()
            job = scope.launch {
                try {
                    delay(delay)
                    flush()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.log("[bosca-analytics] flush failed", error)
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
