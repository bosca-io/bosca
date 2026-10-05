package bosca.recommendations.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.recommendations.model.PersonalizationSignalSourceType
import kotlinx.serialization.Serializable

/**
 * Job payload for backfilling cached Personalization Signals after a signal definition changes.
 * Recomputes every profile attribute drawn from the definition's source, so an
 * added/edited/removed definition is reflected on already-persisted attributes without waiting for
 * the next write-time event. Runs off the mutation hot path because a source can span many profiles.
 */
@Serializable
data class RecomputeProfileSignalsJob(
    val sourceType: PersonalizationSignalSourceType,
    val sourceId: String,
) : IJobDefinition
