package bosca.workops.model.requirement

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.graphql.annotations.BatchKey
import bosca.security.model.PermissibleEntity
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

@DbMapper(RequirementParentMapper::class)
@Serializable
enum class RequirementParent {
    SPEC,
    TASK,
}

object RequirementParentMapper : EnumMapper<RequirementParent>({ RequirementParent.valueOf(it.uppercase()) })

@BatchKey("id")
@Serializable
data class Requirement(
    @Contextual
    override val id: UUID = UUID.NIL,
    val key: String,
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    @ColumnName("parent_type")
    val parentType: RequirementParent,
    @ColumnName("parent_id")
    @Contextual
    val parentId: UUID,
    @ColumnName("status_id")
    @Contextual
    val statusId: UUID,
    @ColumnName("workflow_id")
    @Contextual
    val workflowId: UUID,
    @ColumnName("priority_id")
    @Contextual
    val priorityId: UUID,
    @ColumnName("assignee_profile_id")
    @Contextual
    val assigneeProfileId: UUID? = null,
    @ColumnName("task_id")
    @Contextual
    val taskId: UUID? = null,
    @ColumnName("sort_order")
    val sortOrder: Int = 0,
    @ColumnName("label_ids")
    val labelIds: List<@Contextual UUID> = emptyList(),
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
    val version: Long = 0,
) : PermissibleEntity<UUID> {

    init {
        require(key.isNotBlank()) { "requirement key must not be blank" }
    }

    @Transient
    override val isPublished: Boolean = true

    @Transient
    override val isAdvertised: Boolean = false

    @Transient
    override val isDeleted: Boolean = deletedAt != null
}
