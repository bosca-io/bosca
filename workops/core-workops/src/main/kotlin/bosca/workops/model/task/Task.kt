package bosca.workops.model.task

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.security.model.PermissibleEntity
import bosca.workops.model.WorkOpsValidationException
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A unit of work — Bosca's equivalent of a Jira issue, a Linear issue,
 * or a YouTrack issue. Per the Implementation Decisions section, the
 * work-item type is named `Task` (not `Issue`) and lives in
 * `workops.task`.
 *
 * @property key auto-incrementing handle of the form `{PROJECT_KEY}-{N}`
 *               minted from the project's per-project counter row in
 *               `workops.project_key_counter`. Numbers are never
 *               reused — deleted task numbers leave gaps.
 * @property version optimistic lock counter; mutators include the
 *                   client-observed value in their `WHERE` and bump it
 *                   on success. Mismatch surfaces as
 *                   `OPTIMISTIC_LOCK_FAILED`.
 * @property deletedAt soft-delete tombstone. R2 makes soft-delete the
 *                     default; hard delete is gated by a separate
 *                     permission and replays through the audit log.
 */
@BatchKey("id")
@Serializable
data class Task(
    @Contextual
    override val id: UUID = UUID.NIL,
    val key: String,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    @ColumnName("task_type_id")
    @Contextual
    val taskTypeId: UUID,
    @ColumnName("status_id")
    @Contextual
    val statusId: UUID,
    @ColumnName("priority_id")
    @Contextual
    val priorityId: UUID,
    val summary: String,
    @ColumnName("description_markdown")
    val descriptionMarkdown: String? = null,
    @ColumnName("description_html")
    val descriptionHtml: String? = null,
    @ColumnName("reporter_profile_id")
    @Contextual
    val reporterProfileId: UUID,
    @ColumnName("assignee_profile_id")
    @Contextual
    val assigneeProfileId: UUID? = null,
    @ColumnName("parent_task_id")
    @Contextual
    val parentTaskId: UUID? = null,
    @ColumnName("epic_task_id")
    @Contextual
    val epicTaskId: UUID? = null,
    @ColumnName("sprint_id")
    @Contextual
    val sprintId: UUID? = null,
    @ColumnName("milestone_id")
    @Contextual
    val milestoneId: UUID? = null,
    @ColumnName("affects_version_ids")
    val affectsVersionIds: List<@Contextual UUID> = emptyList(),
    @ColumnName("fix_version_ids")
    val fixVersionIds: List<@Contextual UUID> = emptyList(),
    @ColumnName("component_ids")
    val componentIds: List<@Contextual UUID> = emptyList(),
    @ColumnName("label_ids")
    val labelIds: List<@Contextual UUID> = emptyList(),
    @ColumnName("original_estimate_seconds")
    val originalEstimateSeconds: Long? = null,
    @ColumnName("remaining_estimate_seconds")
    val remainingEstimateSeconds: Long? = null,
    @ColumnName("time_spent_seconds")
    val timeSpentSeconds: Long = 0,
    @ColumnName("due_date")
    @Contextual
    val dueDate: OffsetDateTime? = null,
    @ColumnName("start_date")
    @Contextual
    val startDate: OffsetDateTime? = null,
    @ColumnName("resolution_id")
    @Contextual
    val resolutionId: UUID? = null,
    @ColumnName("resolution_at")
    @Contextual
    val resolutionAt: OffsetDateTime? = null,
    @ColumnName("sla_due_at")
    @Contextual
    val slaDueAt: OffsetDateTime? = null,
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID? = null,
    @ColumnName("content_item_id")
    @Contextual
    val contentItemId: UUID? = null,
    @ColumnName("collection_id")
    @Contextual
    val collectionId: UUID? = null,
    @ColumnName("custom_field_values")
    val customFieldValues: JsonObject = JsonObject(emptyMap()),
    @ColumnName("epic_total_estimate_seconds")
    val epicTotalEstimateSeconds: Long = 0,
    @ColumnName("epic_total_remaining_seconds")
    val epicTotalRemainingSeconds: Long = 0,
    @ColumnName("epic_total_spent_seconds")
    val epicTotalSpentSeconds: Long = 0,
    @ColumnName("epic_child_count")
    val epicChildCount: Int = 0,
    @ColumnName("epic_child_done_count")
    val epicChildDoneCount: Int = 0,
    @ColumnName("watcher_profile_ids")
    val watcherProfileIds: List<@Contextual UUID> = emptyList(),
    @ColumnName("vote_count")
    val voteCount: Int = 0,
    @ColumnName("external_references")
    val externalReferences: JsonElement? = null,
    override val public: Boolean = false,
    @ColumnName("public_content")
    override val publicContent: Boolean = false,
    @ColumnName("public_list")
    override val publicList: Boolean = false,
    @ColumnName("public_supplementary")
    override val publicSupplementary: Boolean = false,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("modified_at")
    @Contextual
    val modifiedAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("created_by_principal_id")
    @Contextual
    val createdByPrincipalId: UUID,
    @ColumnName("modified_by_principal_id")
    @Contextual
    val modifiedByPrincipalId: UUID,
    @ColumnName("deleted_at")
    @Contextual
    val deletedAt: OffsetDateTime? = null,
    @ColumnName("due_notified_for")
    @Contextual
    val dueNotifiedFor: OffsetDateTime? = null,
    val version: Long = 0,
) : PermissibleEntity<UUID> {

    init {
        require(summary.isNotBlank()) { "task summary must not be blank" }
        require(timeSpentSeconds >= 0) { "timeSpentSeconds must not be negative" }
        require(epicChildCount >= 0) { "epicChildCount must not be negative" }
        require(epicChildDoneCount >= 0) { "epicChildDoneCount must not be negative" }
        require(epicChildDoneCount <= epicChildCount) { "epicChildDoneCount ($epicChildDoneCount) must not exceed epicChildCount ($epicChildCount)" }
        require(originalEstimateSeconds == null || originalEstimateSeconds >= 0) { "originalEstimateSeconds must not be negative" }
        require(remainingEstimateSeconds == null || remainingEstimateSeconds >= 0) { "remainingEstimateSeconds must not be negative" }
        require((resolutionId == null) == (resolutionAt == null)) { "resolutionId and resolutionAt must both be set or both be null" }
    }

    @Transient
    override val isPublished: Boolean = true

    @Transient
    override val isAdvertised: Boolean = false

    @Transient
    override val isDeleted: Boolean = deletedAt != null
}

/**
 * Input for [Task] create. Only the fields a Phase 2 caller can
 * meaningfully set are exposed; later phases extend this with custom
 * fields, parent / epic linkage, components, labels, and the rest.
 */
@Serializable
data class CreateTaskInput(
    @Contextual
    val projectId: UUID,
    @Contextual
    val taskTypeId: UUID? = null,
    @Contextual
    val statusId: UUID? = null,
    @Contextual
    val priorityId: UUID? = null,
    val summary: String,
    val descriptionMarkdown: String? = null,
    @Contextual
    val assigneeProfileId: UUID? = null,
    @Contextual
    val sprintId: UUID? = null,
    @Contextual
    val dueDate: OffsetDateTime? = null,
    @Contextual
    val startDate: OffsetDateTime? = null,
    @Contextual
    val parentTaskId: UUID? = null,
    val customFields: Map<String, JsonElement> = emptyMap(),
    val affectedProjectIds: List<@Contextual UUID> = emptyList(),
) {
    init {
        if (summary.isBlank()) throw WorkOpsValidationException("summary", "must not be blank")
        if (summary.length > 255) throw WorkOpsValidationException("summary", "must not exceed 255 characters")
    }
}

/**
 * Input for [Task] update. Optimistic-locking version is required —
 * the service rejects mismatches with `OPTIMISTIC_LOCK_FAILED` so
 * lost updates are impossible.
 *
 * Nullable fields use `null` to mean "no change". To clear a
 * nullable field back to null, set the corresponding `clear*` flag
 * to `true` — the flag takes precedence over the value field.
 */
@Serializable
data class UpdateTaskInput(
    val summary: String? = null,
    val descriptionMarkdown: String? = null,
    @Contextual
    val taskTypeId: UUID? = null,
    @Contextual
    val assigneeProfileId: UUID? = null,
    val clearAssignee: Boolean = false,
    @Contextual
    val priorityId: UUID? = null,
    @Contextual
    val sprintId: UUID? = null,
    val clearSprintId: Boolean = false,
    @Contextual
    val dueDate: OffsetDateTime? = null,
    val clearDueDate: Boolean = false,
    @Contextual
    val startDate: OffsetDateTime? = null,
    val clearStartDate: Boolean = false,
    val originalEstimateSeconds: Long? = null,
    val clearOriginalEstimate: Boolean = false,
    val remainingEstimateSeconds: Long? = null,
    val clearRemainingEstimate: Boolean = false,
    @Contextual
    val parentTaskId: UUID? = null,
    val clearParentTask: Boolean = false,
    val expectedVersion: Long,
)
