@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.db.annotation.ColumnName
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * A permission grant linking a [LocalizationProject] to a security group with a
 * specific [PermissionAction]. Persisted in `localization.project_permissions` and
 * consumed by [bosca.localization.security.LocalizationProjectPermissionEvaluator]
 * to answer "can this caller perform this action on this project?".
 */
@Serializable
data class LocalizationProjectPermission(
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    @ColumnName("group_id")
    @Contextual
    override val groupId: UUID,
    override val action: PermissionAction
) : EntityPermission {

    override val entityId: UUID
        get() = projectId
}
