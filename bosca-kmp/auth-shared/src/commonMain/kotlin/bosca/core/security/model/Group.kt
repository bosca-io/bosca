package bosca.core.security.model

import bosca.core.security.type.GroupType
import kotlin.uuid.Uuid

/**
 * A security group a [Principal] can belong to, used for role-based access
 * control. Port of `Group` from types.ts; [type] reuses the generated
 * [GroupType] enum.
 */
data class Group(
    val id: Uuid,
    val name: String,
    val description: String,
    val type: GroupType,
)
