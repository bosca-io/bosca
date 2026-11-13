package bosca.kubernetes.service

import bosca.lock.DistributedLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicBoolean

/** Checks store ownership at operation boundaries and interrupts blocking I/O when ownership is lost. */
class ServingModelLease internal constructor(private val lock: DistributedLock) {
    private val valid = AtomicBoolean(true)

    /** Renews ownership before acting on a previously read selection or resource. */
    suspend fun ensureHeld() {
        currentCoroutineContext().ensureActive()
        check(valid.get()) { "Lost serving-model lifecycle lock" }
        if (!lock.renew(60_000)) valid.set(false)
        check(valid.get()) { "Lost serving-model lifecycle lock" }
        currentCoroutineContext().ensureActive()
    }

    /** Runs one blocking request; cancellation prevents its result from reaching subsequent mutations. */
    suspend fun <T> blocking(request: () -> T): T {
        ensureHeld()
        return runInterruptible(Dispatchers.IO) { request() }
    }
}
