package bosca.meilisearch.admin.model

import kotlinx.serialization.Serializable

/**
 * Represents a Meilisearch task that tracks the progress of asynchronous
 * operations like indexing, settings updates, and index creation. Tasks
 * progress through enqueued, processing, and terminal (succeeded/failed/canceled) states.
 */
@Serializable
data class MeilisearchTask(
    val uid: Int,
    val type: String,
    val status: String,
    val indexUid: String? = null,
    val enqueuedAt: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val duration: String? = null,
    val error: MeilisearchTaskError? = null,
    val canceledBy: Int? = null,
)

/**
 * Error details from a failed Meilisearch task, providing the error message,
 * machine-readable code, type classification, and a documentation link
 * for troubleshooting.
 */
@Serializable
data class MeilisearchTaskError(
    val message: String,
    val code: String,
    val type: String,
    val link: String? = null,
)
