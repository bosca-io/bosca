package bosca.kubernetes.service

import bosca.lock.DistributedLockFactory
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/** Serializes selection changes, retirement and candidate reservations across controller/runner processes. */
suspend fun <T> withServingModelLock(factory: DistributedLockFactory, block: suspend (ServingModelLease) -> T): T? {
    val lock = factory.create("kubernetes-serving-model-lifecycle")
    if (!lock.tryAcquire(60_000)) return null
    try {
        return coroutineScope {
            val lease = ServingModelLease(lock)
            val renewal = launch {
                while (isActive) {
                    delay(20_000)
                    lease.ensureHeld()
                }
            }
            try {
                block(lease)
            } finally {
                renewal.cancelAndJoin()
            }
        }
    } finally {
        withContext(NonCancellable) {
            try { lock.release() }
            catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { LoggerFactory.getLogger("ServingModelCoordination").warn("Cannot release serving-model lifecycle lock", exception) }
        }
    }
}
