package bosca.analytics.transform

import bosca.analytics.events.AnalyticsScriptEvent
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.Events
import bosca.di.provide
import bosca.di.provideProvider
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineService
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Inline pipeline transform that runs **triggered platform pipelines** over the event batch.
 *
 * Each instance is configured with an [eventName]; pipelines whose `acceptedInputType` matches it
 * and are Active run inline (synchronously, via [PipelineService.run]) with the batch wrapped in
 * [AnalyticsScriptEvent]. A pipeline whose Output returns an [AnalyticsScriptEvent] (directly, or
 * as JSON convertible back via its origin serializer) replaces the batch for the next pipeline /
 * the rest of the processing flow; any other output leaves the batch unchanged.
 *
 * Multiple instances are wired into the processing flow for different event names
 * (e.g. `analytics.transform.interaction`).
 *
 * If the pipelines module is not present, this transform is a no-op.
 */
class ScriptTransformPipelineTransform(private val eventName: String) : EventPipelineTransform {

    private val log = LoggerFactory.getLogger(ScriptTransformPipelineTransform::class.java)

    override suspend fun transform(context: EventPipelineContext, events: Events): Events {
        val provider = provideProvider<PipelineService>()
        if (!provider.exists) return events

        val service = provider.get()
        val pipelines = service.triggeredFor(eventName)
        if (pipelines.isEmpty()) return events

        val json = provide<Json>()
        var current = events
        for (pipeline in pipelines) {
            val output = service.run(
                pipeline,
                PipelineValue.of(AnalyticsScriptEvent(current), AnalyticsScriptEvent.serializer()),
            )
            val typed = output?.value as? AnalyticsScriptEvent
                ?: output?.decodeOrigin(json)?.value as? AnalyticsScriptEvent
            if (typed != null) {
                current = typed.events
            } else if (output != null) {
                log.warn(
                    "Transform pipeline {} for {} returned a value that is not an AnalyticsScriptEvent; batch unchanged",
                    pipeline.id, eventName,
                )
            }
        }
        return current
    }
}
