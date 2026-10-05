package bosca.community.model

import bosca.db.annotation.ColumnName
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class PrayerPermission(
    @Contextual
    @ColumnName("prayer_id")
    val prayerId: UUID,
    @Contextual
    @ColumnName("group_id")
    override val groupId: UUID,
    override val action: PermissionAction
) : EntityPermission {

    @Transient
    override val entityId: UUID = prayerId
}
