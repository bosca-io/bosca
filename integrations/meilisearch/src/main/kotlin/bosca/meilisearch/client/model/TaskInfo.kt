package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable

/**
 * Lightweight acknowledgement returned when an asynchronous operation
 * is enqueued. Contains the [taskUid] needed to poll for completion.
 */
@Serializable
data class TaskInfo(
    val taskUid: Int,
    val indexUid: String? = null,
    val status: String? = null,
    val type: String? = null,
    val enqueuedAt: String? = null,
)
