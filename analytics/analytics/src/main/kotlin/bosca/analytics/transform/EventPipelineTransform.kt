package bosca.analytics.transform

import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.Events

/**
 * A stage in the analytics event processing chain: it receives the current [Events] batch and returns
 * the (possibly transformed) batch for the next stage.
 *
 * **Transforms must be idempotent.** In the processor (`NatsEventConsumer`) a failure anywhere in the
 * chain NAKs the NATS message; NATS redelivers it and the *entire* chain re-runs from the start on the
 * same batch, so every transform may run more than once on the same events. Side effects — counters,
 * pipeline/job dispatches, external calls, writes — must therefore be safe to repeat (e.g. keyed on a
 * stable identity so a replay is a no-op). A transform whose side effects cannot be made idempotent
 * must instead absorb its own failures so it never triggers a redelivery.
 *
 * The collector (`EventProcessingServiceImpl`) is best-effort and does **not** redeliver — it logs and
 * continues past a failing transform — but transforms should still be written to the processor's
 * replay semantics, since the same chain runs in both.
 */
interface EventPipelineTransform {

    /**
     * Transforms [events] and returns the batch for the next stage. A thrown exception aborts the
     * batch; in the processor that triggers a NAK + redelivery of the whole chain (see the type KDoc),
     * so implementations must be idempotent.
     */
    suspend fun transform(context: EventPipelineContext, events: Events): Events
}
