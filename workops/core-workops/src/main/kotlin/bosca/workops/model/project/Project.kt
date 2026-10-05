package bosca.workops.model.project

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.security.model.PermissibleEntity
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Bottom tier of the delivery hierarchy (R1) and the unit teams
 * actually work in. Every task lives in exactly one project; the
 * project's [key] is the prefix of every task key the project mints
 * (e.g. project key `BOS` produces `BOS-1`, `BOS-2`, …). Project keys
 * are globally unique because the task key alone must point back to
 * exactly one project regardless of which portfolio or program owns
 * the project today.
 *
 * @property key globally unique uppercase code (2–10 chars). Renaming
 *               the key updates the `project_key_counter` row but
 *               leaves historical task keys untouched —
 *               `task_key_alias` rows preserve old keys as redirects
 *               (R1 implementation decision).
 */
@BatchKey("id")
@Serializable
data class Project(
    @Contextual
    override val id: UUID = UUID.NIL,
    @ColumnName("program_id")
    @Contextual
    val programId: UUID,
    val key: String,
    val name: String,
    val description: String? = null,
    @ColumnName("owner_profile_id")
    @Contextual
    val ownerProfileId: UUID,
    @ColumnName("default_task_type_scheme_id")
    @Contextual
    val defaultTaskTypeSchemeId: UUID? = null,
    @ColumnName("default_workflow_scheme_id")
    @Contextual
    val defaultWorkflowSchemeId: UUID? = null,
    @ColumnName("default_notification_scheme_id")
    @Contextual
    val defaultNotificationSchemeId: UUID? = null,
    @ColumnName("default_field_configuration_scheme_id")
    @Contextual
    val defaultFieldConfigurationSchemeId: UUID? = null,
    @ColumnName("task_creation_form_schema_key")
    val taskCreationFormSchemaKey: String = "workops.create-task",
    override val public: Boolean = false,
    @ColumnName("public_content")
    override val publicContent: Boolean = false,
    @ColumnName("public_list")
    override val publicList: Boolean = false,
    @ColumnName("public_supplementary")
    override val publicSupplementary: Boolean = false,
    @ColumnName("archived_at")
    @Contextual
    val archivedAt: OffsetDateTime? = null,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("modified_at")
    @Contextual
    val modifiedAt: OffsetDateTime = OffsetDateTime.now(),
    val version: Long = 0,
) : PermissibleEntity<UUID> {

    @Transient
    override val isPublished: Boolean = true

    @Transient
    override val isAdvertised: Boolean = false

    @Transient
    override val isDeleted: Boolean = archivedAt != null
}

/** Input for [Project] create / update mutations. */
@Serializable
data class ProjectInput(
    @Contextual
    val programId: UUID,
    val key: String,
    val name: String,
    val description: String? = null,
    @Contextual
    val ownerProfileId: UUID,
    @Contextual
    val defaultTaskTypeSchemeId: UUID? = null,
    val taskCreationFormSchemaKey: String? = null,
)
