package bosca.community.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class CommunityGroupSignupEmail(
    val email: String,
    @Contextual
    @ColumnName("group_id")
    val groupId: UUID,
    @Contextual
    val created: OffsetDateTime,
    @Contextual
    val expires: OffsetDateTime
)