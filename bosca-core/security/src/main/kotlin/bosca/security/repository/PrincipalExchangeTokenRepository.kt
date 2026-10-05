package bosca.security.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.serialization.OffsetDateTime

/**
 * Manages single-use exchange tokens that facilitate cross-domain OAuth2 authentication.
 *
 * When a user authenticates via OAuth2 on one domain and is redirected to a different domain,
 * an exchange token is generated and appended to the redirect URL. The target domain consumes
 * the token exactly once to obtain a JWT. Tokens expire after 5 minutes.
 */
@Repository
interface PrincipalExchangeTokenRepository {

    /**
     * Persists a new exchange token linked to a principal.
     *
     * @param token the exchange token string
     * @param principalId the principal the token authenticates
     * @param accountCreated whether the originating sign-in created the account (vs. authenticated an
     *        existing one); carried so the redeeming client can distinguish a new account
     * @param originator the caller-supplied login request originator; carried so the redeeming client can echo it
     * @param created the token creation timestamp
     * @param expires the token expiration timestamp (default 5 minutes from now)
     */
    @Query("insert into principal_exchange_tokens (token, principal_id, account_created, originator, created, expires) values (:token, :principalId, :accountCreated, :originator, :created, :expires)")
    suspend fun addExchangeToken(
        token: String,
        principalId: UUID,
        accountCreated: Boolean = false,
        originator: String? = null,
        created: OffsetDateTime = OffsetDateTime.now(),
        expires: OffsetDateTime = OffsetDateTime.now().plusMinutes(5)
    )

    /**
     * Atomically consumes a valid (non-expired) exchange token by deleting it and returning the
     * associated principal ID together with whether the originating sign-in created the account.
     * This prevents race conditions where concurrent requests could both read and use the same token.
     *
     * @param token the exchange token to consume
     * @return the principal ID and account-created flag, or `null` if the token does not exist or has expired
     */
    @Query("delete from principal_exchange_tokens where token = :token and expires > now() returning principal_id, account_created, originator")
    suspend fun consumeToken(token: String): ConsumedExchangeToken?

    /**
     * Removes all expired exchange tokens from the database.
     */
    @Query("delete from principal_exchange_tokens where expires < now()")
    suspend fun deleteExpired()
}
