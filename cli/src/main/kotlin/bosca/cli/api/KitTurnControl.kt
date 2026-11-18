package bosca.cli.api

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred

/** Explicit client cancellation intent; transport shutdown and coroutine cancellation do not imply it. */
internal class KitTurnControl : AbstractCoroutineContextElement(Key) {
    private val cancellation = AtomicBoolean()
    private val finished = CompletableDeferred<Unit>()

    val cancellationRequested: Boolean get() = cancellation.get()

    fun requestCancellation() { cancellation.set(true) }

    fun checkBeforeSubmission() {
        if (cancellationRequested) throw KitTurnCancelledBeforeSubmission()
    }

    fun finish(cause: Throwable? = null) {
        if (cause == null) finished.complete(Unit) else finished.completeExceptionally(cause)
    }

    suspend fun awaitFinished() = finished.await()

    companion object Key : CoroutineContext.Key<KitTurnControl>
}
