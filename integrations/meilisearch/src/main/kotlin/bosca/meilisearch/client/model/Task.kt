package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable

/**
 * Full details of a Meilisearch asynchronous task including timing
 * information and any error that occurred during execution.
 */
@Serializable
data class Task(
    val uid: Int,
    val indexUid: String? = null,
    val status: String = "",
    val type: String? = null,
    val duration: String? = null,
    val enqueuedAt: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val error: MeilisearchError? = null,
)
