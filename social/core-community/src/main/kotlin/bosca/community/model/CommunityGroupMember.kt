package bosca.community.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class CommunityGroupMember(
    @ColumnName("group_id")
    @Contextual
    val groupId: UUID,
    @ColumnName("profile_id")
    @Contextual
    val profileId: UUID,
)
