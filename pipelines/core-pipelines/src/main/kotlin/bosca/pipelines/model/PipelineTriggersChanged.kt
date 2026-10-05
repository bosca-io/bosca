package bosca.pipelines.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Pub/sub channel that broadcasts triggered-pipeline cache invalidations across instances. When any
 * instance saves or deletes a pipeline it publishes a [PipelineTriggersChanged]; every instance
 * subscribes and clears its cached `triggeredFor` / `triggeredEventTypes` lookups immediately, rather
 * than waiting out the local TTL backstop.
 */
const val PIPELINE_TRIGGERS_CHANGED_CHANNEL = "bosca.pipelines.triggers.changed"

/**
 * Signals that a pipeline's trigger configuration may have changed (created, updated, or deleted).
 *
 * A change can move a pipeline between accepted input types or flip its `triggered` flag, so a
 * subscriber cannot know which cache keys are affected and clears the whole triggered cache.
 * [pipelineId] is carried for diagnostics only.
 */
@Serializable
data class PipelineTriggersChanged(
    @Contextual val pipelineId: UUID,
)
