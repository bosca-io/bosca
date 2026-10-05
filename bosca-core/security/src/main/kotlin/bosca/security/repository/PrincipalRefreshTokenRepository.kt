package bosca.security.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.RefreshToken
import bosca.serialization.UUID
import bosca.serialization.OffsetDateTime


@Repository
interface PrincipalRefreshTokenRepository {

    @Query("insert into principal_refresh_tokens (token, principal_id, login_id, created, expires) values (:token, :principalId, :loginId, :created, :expires)")
    suspend fun addPrincipalRefreshToken(
        token: String,
        principalId: UUID,
        created: OffsetDateTime = OffsetDateTime.now(),
        expires: OffsetDateTime = OffsetDateTime.now().plusDays(30),
        loginId: Long? = null,
    )

    @Query("select principal_id from principal_refresh_tokens where token = :token and expires > now()")
    suspend fun getPrincipalId(token: String): UUID?

    @Query("delete from principal_refresh_tokens where token = :token")
    suspend fun deleteByToken(token: String)

    /** Atomically consumes a refresh token while preserving its login identity for rotation. */
    @Query("delete from principal_refresh_tokens where token = :token and expires > now() returning *")
    suspend fun consumeToken(token: String): RefreshToken?

    @Query("delete from principal_refresh_tokens where expires < now()")
    suspend fun deleteExpired()

    /**
     * Deletes every outstanding refresh token for the given principal.
     * Called alongside `PrincipalRepository.incrementTokenVersion` on
     * flows that must terminate existing sessions — a stale refresh
     * token still on disk would otherwise let a compromised client
     * mint a fresh (correctly-versioned) JWT and slip past the
     * invalidation.
     */
    @Query("delete from principal_refresh_tokens where principal_id = :principalId")
    suspend fun deleteByPrincipalId(principalId: UUID)

    /** Deletes the current refresh token for one durable login. */
    @Query("delete from principal_refresh_tokens where principal_id = :principalId and login_id = :loginId")
    suspend fun deleteByLoginId(principalId: UUID, loginId: Long)
}
