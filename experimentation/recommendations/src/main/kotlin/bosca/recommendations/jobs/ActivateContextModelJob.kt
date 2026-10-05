package bosca.recommendations.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/** Waits for exact exported versions to load before changing the selected context model. */
@Serializable
data class ActivateContextModelJob(val version: Long, val selectionRevision: Long) : IJobDefinition
