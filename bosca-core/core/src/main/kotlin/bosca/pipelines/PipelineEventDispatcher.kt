package bosca.pipelines

import bosca.events.Event
import kotlinx.serialization.KSerializer

/**
 * Extension point invoked by the KSP-generated event `dispatch()` for every fired `@JobEvent`.
 *
 * The pipelines module implements this to run **triggered pipelines**: pipelines whose
 * `acceptedInputType` matches the event and whose `triggered` flag is on are executed directly.
 * Implementations must keep the in-process cost minimal (a cached gate plus a single durable
 * enqueue); matching and execution happen on the runner. When the pipelines module is absent the
 * generated dispatch skips the hook (provider-exists guard).
 */
interface PipelineEventDispatcher {

    /**
     * Durably hands [event] to triggered-pipeline matching when at least one matching event type is
     * configured. Infrastructure and enqueue failures propagate so durable producers can retry.
     */
    suspend fun <T : Event> dispatch(eventName: String, event: T, serializer: KSerializer<T>)
}
