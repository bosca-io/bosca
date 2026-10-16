package bosca.git.model

import bosca.events.annotation.JobEvent
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/** Announces persisted trigger evaluation, including decisions that create no CI run. */
@JobEvent(jobs = [])
@Serializable
data class PipelineEvaluationEvent(
    override val repositoryId: UUID,
    val evaluationId: UUID,
    /** Persisted source modification time identifying the evaluation revision that emitted this event. */
    val modified: OffsetDateTime,
) : GitEvent() {
    override fun identityKey(): Any = evaluationId
}
