package bosca.git.model

import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Emitted when a push to a [RepositoryContentType.SCRIPT_PROJECT] repository
 * modifies a script source file. The scripting engine uses this to invalidate
 * caches and reload the affected script from the repository.
 */
@JobEvent(jobs = [], pubsubChannel = "bosca.git.script_source_updated")
@Serializable
data class ScriptSourceUpdatedEvent(
    override val repositoryId: UUID,
    val ref: String,
    val scriptKey: String,
    val path: String
) : GitEvent()
