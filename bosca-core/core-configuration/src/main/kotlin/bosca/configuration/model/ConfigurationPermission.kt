package bosca.configuration.model

import bosca.db.annotation.ColumnName
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class ConfigurationPermission(
    @Contextual
    @ColumnName("entity_id")
    val configurationId: UUID,
    override val action: PermissionAction,
    @Contextual
    @ColumnName("group_id")
    override val groupId: UUID
) : EntityPermission {

    override val entityId: UUID
        get() = configurationId
}