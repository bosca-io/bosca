package bosca.content.metadata.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Deletes expired or evicted media after its playback grace period. */
@Serializable
data class DeleteBibleAudioJob(@Contextual val id: UUID) : IJobDefinition
