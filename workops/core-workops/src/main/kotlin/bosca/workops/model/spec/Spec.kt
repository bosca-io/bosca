package bosca.workops.model.spec

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

@DbMapper(SpecVersionSourceMapper::class)
@Serializable
enum class SpecVersionSource {
    WORKOPS,
    GIT_SYNC,
}

object SpecVersionSourceMapper : EnumMapper<SpecVersionSource>({ SpecVersionSource.valueOf(it.uppercase()) })

@DbMapper(GenerationSourceMapper::class)
@Serializable
enum class GenerationSource {
    CLAUDE_CODE,
    MANUAL,
    KIT,
}

object GenerationSourceMapper : EnumMapper<GenerationSource>({ GenerationSource.valueOf(it.uppercase()) })

@BatchKey("id")
@Serializable
data class Spec(
    @Contextual
    override val id: UUID = UUID.NIL,
    val key: String,
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    @ColumnName("program_id")
    @Contextual
    val programId: UUID? = null,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID? = null,
    @ColumnName("status_id")
    @Contextual
    val statusId: UUID,
    @ColumnName("workflow_id")
    @Contextual
    val workflowId: UUID,
    @ColumnName("owner_profile_id")
    @Contextual
    val ownerProfileId: UUID,
    @ColumnName("parent_spec_id")
    @Contextual
    val parentSpecId: UUID? = null,
    @ColumnName("sort_order")
    val sortOrder: Int = 0,
    @ColumnName("child_count")
    val childCount: Int = 0,
    @ColumnName("child_done_count")
    val childDoneCount: Int = 0,
    @ColumnName("git_repository_id")
    @Contextual
    val gitRepositoryId: UUID? = null,
    @ColumnName("git_path")
    val gitPath: String? = null,
    @ColumnName("watcher_profile_ids")
    val watcherProfileIds: List<@Contextual UUID> = emptyList(),
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
        require(key.isNotBlank()) { "spec key must not be blank" }
    }

    @Transient
    override val isPublished: Boolean = true

    @Transient
    override val isAdvertised: Boolean = false

    @Transient
    override val isDeleted: Boolean = deletedAt != null
}
