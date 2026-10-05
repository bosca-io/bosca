package bosca.security.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * One successful interactive authentication for a principal.
 *
 * [method] identifies the proof that minted the login (for example `password`, `third_party`,
 * `passkey`, or `exchange_token`). Silent refreshes and request-level credential checks are
 * deliberately excluded so this history describes user sign-ins rather than background API use.
 */
@Serializable
data class PrincipalLogin(
    val id: Long = 0,
    @ColumnName("principal_id")
    @Contextual
    val principalId: UUID,
    val method: String,
    /** Explicit revocation time. Token expiry remains token state, not login-history state. */
    @ColumnName("revoked_at")
    @Contextual
    val revokedAt: OffsetDateTime? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
)
