package bosca.pipelines.trigger

import bosca.db.connectionOrNull
import bosca.db.withConnectionManager
import bosca.events.Event
import bosca.pipelines.PipelineEventDispatcher
import bosca.pipelines.service.PipelineService
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * The event-firing process's side of triggered pipelines: a cached "any triggered pipeline for
 * this event?" gate plus a single durable [PipelineDispatchJob] enqueue. Matching and execution
 * happen on the runner ([PipelineDispatchJobExecutor] / [PipelineRunJobExecutor]); once the
 * dispatch job is enqueued, the reaction is at-least-once.
 */
class PipelineEventDispatcherImpl(
    private val pipelineService: PipelineService,
    private val json: Json,
) : PipelineEventDispatcher {

    override suspend fun <T : Event> dispatch(eventName: String, event: T, serializer: KSerializer<T>) {
        val eventCreated = OffsetDateTime.now()
        if (connectionOrNull() == null) {
            withConnectionManager {
                if (eventName !in pipelineService.triggeredEventTypes()) return@withConnectionManager
                val payload = json.encodeToJsonElement(serializer, event)
                PipelineDispatchJob(
                    eventName = eventName,
                    eventPayload = payload,
                    eventCreated = eventCreated,
                ).enqueue()
            }
        } else {
            if (eventName !in pipelineService.triggeredEventTypes()) return
            val payload = json.encodeToJsonElement(serializer, event)
            PipelineDispatchJob(
                eventName = eventName,
                eventPayload = payload,
                eventCreated = eventCreated,
            ).enqueue()
        }
    }
}
