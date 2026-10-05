package bosca.db

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext

class ConnectionLimiter(limit: Int = 5) {

    private val semaphore: Semaphore = Semaphore(limit)

    suspend fun acquire() {
        if (!semaphore.tryAcquire()) {
            log.debug("limiting concurrent connections")
            semaphore.acquire()
        }
    }

    fun release() {
        semaphore.release()
    }

    private companion object {

        private val log = LoggerFactory.getLogger(ConnectionLimiter::class.java)
    }
}

fun ConnectionLimiter.asCoroutineContext(): CoroutineContext = ConnectionLimiterContext(this)

private class ConnectionLimiterContext(val limiter: ConnectionLimiter) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ConnectionLimiterContext>

    override val key: CoroutineContext.Key<*> get() = Key
}

suspend fun connectionLimiter(): ConnectionLimiter = currentCoroutineContext().connectionLimiter()

fun CoroutineContext.connectionLimiter(): ConnectionLimiter {
    return this[ConnectionLimiterContext.Key]?.limiter ?: throw IllegalStateException("Connection limiter not found in coroutine context")
}

suspend fun connectionLimiterOrNull(): ConnectionLimiter? = currentCoroutineContext()[ConnectionLimiterContext.Key]?.limiter