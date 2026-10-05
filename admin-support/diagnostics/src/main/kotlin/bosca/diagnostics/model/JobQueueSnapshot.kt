package bosca.diagnostics.model

import bosca.sharedqueue.jobs.SerializedJob
import kotlinx.serialization.Serializable

@Serializable
data class JobQueueSnapshot(
    val queue: String,
    val jobs: List<SerializedJob>
)
