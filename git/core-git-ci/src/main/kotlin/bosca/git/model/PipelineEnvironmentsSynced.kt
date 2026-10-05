package bosca.git.model

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Emitted when a default-branch pipeline sync parses a definition that declares an `environments:`
 * block. The YAML is the source of truth for environment topology, and the parsed
 * definitions are not persisted on the pipeline row, so the event carries them — WorkOps listens
 * and upserts its program environments by KEY from this payload.
 */
@JobEvent(jobs = [], pubsubChannel = "bosca.git.pipeline.environments")
@Serializable
data class PipelineEnvironmentsSynced(
    val repositoryId: UUID,
    val pipelineId: UUID,
    val environments: Map<String, EnvironmentDefinition>,
) : Event {
    override fun identityKey(): Any = pipelineId
}
