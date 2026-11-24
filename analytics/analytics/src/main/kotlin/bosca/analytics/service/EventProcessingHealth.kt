package bosca.analytics.service

import bosca.analytics.queue.AnalyticsEventQueue
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/** Tracks processing and event-queue failures independently, including their restart grace period. */
class EventProcessingHealth(private val nanoTime: () -> Long = System::nanoTime) {
    private val processingFailure = AtomicLong(Long.MIN_VALUE)
    private val queueFailure = AtomicLong(Long.MIN_VALUE)

    val isReady: Boolean
        get() = processingFailure.get() == Long.MIN_VALUE && queueFailure.get() == Long.MIN_VALUE

    val isLive: Boolean
        get() {
            val now = nanoTime()
            return withinGracePeriod(processingFailure.get(), now) && withinGracePeriod(queueFailure.get(), now)
        }

    fun processingFailed() {
        processingFailure.compareAndSet(Long.MIN_VALUE, nanoTime())
    }

    fun processingRecovered() {
        processingFailure.set(Long.MIN_VALUE)
    }

    /** Records an unavailable event queue without extending the restart grace period. */
    fun queueFailed() {
        queueFailure.compareAndSet(Long.MIN_VALUE, nanoTime())
    }

    /** Clears a queue failure after an independent readiness check or a durable enqueue. */
    fun queueRecovered() {
        queueFailure.set(Long.MIN_VALUE)
    }

    /** Runs [queue]'s readiness check and records the outcome; caller cancellation propagates. */
    suspend fun recordQueueReadiness(queue: AnalyticsEventQueue) {
        try {
            queue.checkReadiness()
            queueRecovered()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            queueFailed()
            log.warn("Analytics event queue readiness check failed", e)
        }
    }

    private fun withinGracePeriod(failedAt: Long, now: Long): Boolean =
        failedAt == Long.MIN_VALUE || now - failedAt < 60.seconds.inWholeNanoseconds

    private companion object {
        private val log = LoggerFactory.getLogger(EventProcessingHealth::class.java)
    }
}
