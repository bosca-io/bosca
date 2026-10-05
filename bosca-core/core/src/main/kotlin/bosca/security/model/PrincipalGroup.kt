package bosca.security.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class PrincipalGroup(
    @Contextual
    val principal: UUID,
    @Contextual
    @ColumnName("group_id")
    val groupId: UUID,
)