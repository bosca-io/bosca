package bosca.analytics.events

import bosca.analytics.model.Events
import bosca.events.Event
import kotlinx.serialization.Serializable

/**
 * Bridges analytics processing batches to the platform pipeline subsystem.
 *
 * When analytics events flow through the processing pipeline, this wrapper carries the batch into
 * triggered platform pipelines — inline via `PipelineService.run` for transforms, or dispatched via
 * [bosca.pipelines.PipelineEventDispatcher] for notifications — so user-defined pipelines can react to
 * analytics activity such as sessions, interactions, impressions, and completions.
 */
@Serializable
class AnalyticsScriptEvent(
    val events: Events
) : Event
