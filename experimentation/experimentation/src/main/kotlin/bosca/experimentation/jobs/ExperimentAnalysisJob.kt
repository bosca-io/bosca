package bosca.experimentation.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Job payload for triggering an AI-powered analysis of an experiment's results.
 * The analysis job aggregates current metrics, constructs a prompt with the
 * statistical data, and invokes the AI system to generate a recommendation.
 */
@Serializable
data class ExperimentAnalysisJob(
    @Contextual
    val experimentId: UUID
) : IJobDefinition
