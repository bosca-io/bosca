package bosca.community.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class CommunityGroupSignupToken(
    @ColumnName("token")
    val token: String,
    @Contextual
    @ColumnName("group_id")
    val groupId: UUID,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val expires: OffsetDateTime = OffsetDateTime.now().plusDays(60)
)
