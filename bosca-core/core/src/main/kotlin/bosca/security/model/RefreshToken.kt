package bosca.security.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import java.time.OffsetDateTime

data class RefreshToken(
    @ColumnName("principal_id")
    val principalId: UUID,
    val token: String,
    @ColumnName("login_id")
    val loginId: Long? = null,
    val created: OffsetDateTime = OffsetDateTime.now(),
    val expires: OffsetDateTime = OffsetDateTime.now().plusDays(30)
)
