package bosca.ai.kit.harness.runtime

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Collects follow-up work requested while a reconcile holds the session lease. A job delivered during the lease is
 * discarded by the lease check, so the owner enqueues the follow-up once it has released the lease.
 */
internal class KitRuntimeFollowUp : AbstractCoroutineContextElement(Key) {
    private val requested = AtomicBoolean(false)

    fun request() = requested.set(true)

    fun consume(): Boolean = requested.getAndSet(false)

    companion object Key : CoroutineContext.Key<KitRuntimeFollowUp>
}
