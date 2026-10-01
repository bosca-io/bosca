package bosca.workops.model.search

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * R23 — denormalized search document. Meilisearch needs every
 * filter / sort / display field on the document directly; the
 * indexer reads the relational rows and produces this projection.
 *
 * `id` is the task's UUID (string-encoded). The Meilisearch
 * primary key is `id`.
 */
@Serializable
data class TaskSearchDocument(
    val id: String,
    val key: String,
    val summary: String,
    val descriptionPlain: String? = null,
    @Contextual val projectId: UUID,
    val projectKey: String,
    @Contextual val taskTypeId: UUID,
    val taskTypeName: String,
    @Contextual val statusId: UUID,
    val statusName: String,
    val statusCategory: String,
    @Contextual val priorityId: UUID,
    val priorityName: String,
    @Contextual val resolutionId: UUID? = null,
    val resolutionName: String? = null,
    @Contextual val assigneeProfileId: UUID? = null,
    @Contextual val reporterProfileId: UUID? = null,
    val labelIds: List<String> = emptyList(),
    val componentIds: List<String> = emptyList(),
    val watcherProfileIds: List<String> = emptyList(),
    @Contextual val sprintId: UUID? = null,
    @Contextual val createdAt: OffsetDateTime,
    @Contextual val modifiedAt: OffsetDateTime,
    @Contextual val dueDate: OffsetDateTime? = null,
    val deleted: Boolean = false,
)
