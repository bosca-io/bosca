package bosca.analytics.transform

import bosca.analytics.events.AnalyticsScriptEvent
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.Events
import bosca.di.provideProvider
import bosca.pipelines.PipelineEventDispatcher

/**
 * Fire-and-forget pipeline transform that dispatches the batch to triggered platform pipelines.
 *
 * Each instance is configured with an [eventName]; Active pipelines whose `acceptedInputType`
 * matches it run asynchronously on the runner (via the platform pipeline dispatch path) and cannot
 * modify the events flowing through this processing flow.
 *
 * Multiple instances can be wired into the flow to handle different event names
 * (e.g. `analytics.notify.events`, `analytics.notify.session`).
 *
 * If the pipelines module is not present, this transform is a no-op.
 *
 * @param eventName the dispatch event name
 * @see ScriptTransformPipelineTransform for the inline transformation path
 */
class ScriptTriggerPipelineTransform(private val eventName: String) : EventPipelineTransform {

    override suspend fun transform(context: EventPipelineContext, events: Events): Events {
        val provider = provideProvider<PipelineEventDispatcher>()
        if (!provider.exists) return events

        provider.get().dispatch(
            eventName,
            AnalyticsScriptEvent(events),
            AnalyticsScriptEvent.serializer()
        )

        return events
    }
}
