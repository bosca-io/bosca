package bosca.profile.profile.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Durable domain cleanup requested after a hard deletion or administrative principal unlink. */
@Serializable
data class ProfileCleanupJob(
    @Contextual val id: UUID,
    @Contextual val principalId: UUID?,
) : IJobDefinition
