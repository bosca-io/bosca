@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.model

import bosca.db.annotation.ColumnName
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * Represents a permission grant linking a script to a security group with
 * a specific [PermissionAction], controlling who can view, execute, or manage the script.
 */
@Serializable
data class ScriptPermission(
    @ColumnName("script_id")
    @Contextual
    val scriptId: UUID,
    @ColumnName("group_id")
    @Contextual
    override val groupId: UUID,
    override val action: PermissionAction
) : EntityPermission {

    override val entityId: UUID
        get() = scriptId
}
