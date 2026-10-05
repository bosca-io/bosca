package bosca.ai.kit.agents.session

import bosca.serialization.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine-scoped pointer to the owning **chat session** ([parentSessionId]). A sub-agent's own run id
 * is a per-action UUID (not the chat session), so the planner's actions set this around a sub-agent
 * invocation; the persistence store reads it to index that sub-run's checkpoints under the chat session.
 * The parent planner run sets none and falls back to its own run id (which already IS the chat session).
 */
class KitSessionContext(val parentSessionId: UUID) : AbstractCoroutineContextElement(KitSessionContext) {
    companion object Key : CoroutineContext.Key<KitSessionContext>
}
