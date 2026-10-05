package bosca.security.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(PermissionActionMapper::class)
@Serializable
enum class PermissionAction {
    DELETE,
    EDIT,
    EXECUTE,
    IMPERSONATE,
    LIST,
    MANAGE,
    VIEW
}

object PermissionActionMapper : EnumMapper<PermissionAction>({ PermissionAction.valueOf(it.uppercase()) })
