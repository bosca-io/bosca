@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.repository

import bosca.db.annotation.ColumnName
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * A permission grant linking a pipeline to a security group with a [PermissionAction] — controls
 * who can execute a non-public endpoint pipeline (and view/manage grants). Mirrors script grants.
 */
@Serializable
data class PipelinePermission(
    @ColumnName("pipeline_id")
    @Contextual
    val pipelineId: UUID,
    @ColumnName("group_id")
    @Contextual
    override val groupId: UUID,
    override val action: PermissionAction,
) : EntityPermission {

    override val entityId: UUID
        get() = pipelineId
}

/** Data access for pipeline permission grants (`pipelines.pipeline_permissions`). */
@Repository
interface PipelinePermissionRepository {

    @Query("insert into pipelines.pipeline_permissions (group_id, pipeline_id, action) values (:groupId, :pipelineId, (:action)::permission_action) on conflict do nothing")
    suspend fun addPermission(pipelineId: UUID, groupId: UUID, action: PermissionAction)

    @Query("delete from pipelines.pipeline_permissions where group_id = :groupId and pipeline_id = :pipelineId and action = (:action)::permission_action")
    suspend fun deletePermission(pipelineId: UUID, groupId: UUID, action: PermissionAction)

    @Query("select * from pipelines.pipeline_permissions where pipeline_id = :id")
    suspend fun getPermissionsByPipelineId(id: UUID): List<PipelinePermission>

    @Query("select * from pipelines.pipeline_permissions where pipeline_id = any(:id)")
    suspend fun getPermissionsByPipelineIds(id: List<UUID>): List<PipelinePermission>
}
