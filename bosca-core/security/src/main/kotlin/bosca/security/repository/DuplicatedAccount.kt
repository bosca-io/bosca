package bosca.security.repository

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** One (email, principal) row from the duplicate-account finder, grouped into [bosca.security.model.DuplicatedAccountIds] by the service. */
@Serializable
data class DuplicatedAccount(
    val email: String,
    @ColumnName("principal_id")
    @Contextual
    val principalId: UUID,
)
