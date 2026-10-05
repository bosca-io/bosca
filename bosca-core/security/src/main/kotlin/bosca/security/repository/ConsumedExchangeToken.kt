package bosca.security.repository

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * The row returned when an exchange token is consumed: the principal it authenticates, whether the
 * originating sign-in created that account (so the client can tell a brand-new account from a returning
 * one), and the caller-supplied login originator — all carried across the cross-domain OAuth redirect.
 */
@Serializable
data class ConsumedExchangeToken(
    @ColumnName("principal_id")
    @Contextual
    val principalId: UUID,
    @ColumnName("account_created")
    val accountCreated: Boolean,
    @ColumnName("originator")
    val originator: String?,
)
