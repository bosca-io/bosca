package bosca.analytics.service

import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/** Tracks processing and NATS failures independently, including their restart grace period. */
class EventProcessingHealth(private val nanoTime: () -> Long = System::nanoTime) {
    private val processingFailure = AtomicLong(Long.MIN_VALUE)
    private val natsFailure = AtomicLong(Long.MIN_VALUE)

    val isReady: Boolean
        get() = processingFailure.get() == Long.MIN_VALUE && natsFailure.get() == Long.MIN_VALUE

    val isLive: Boolean
        get() {
            val now = nanoTime()
            return withinGracePeriod(processingFailure.get(), now) && withinGracePeriod(natsFailure.get(), now)
        }

    fun processingFailed() {
        processingFailure.compareAndSet(Long.MIN_VALUE, nanoTime())
    }

    fun processingRecovered() {
        processingFailure.set(Long.MIN_VALUE)
    }

    fun natsFailed() {
        natsFailure.compareAndSet(Long.MIN_VALUE, nanoTime())
    }

    fun natsRecovered() {
        natsFailure.set(Long.MIN_VALUE)
    }

    private fun withinGracePeriod(failedAt: Long, now: Long): Boolean =
        failedAt == Long.MIN_VALUE || now - failedAt < 60.seconds.inWholeNanoseconds
}
