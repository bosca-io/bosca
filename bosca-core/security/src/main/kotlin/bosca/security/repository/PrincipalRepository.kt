package bosca.security.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.CredentialType
import bosca.security.model.Principal
import bosca.security.model.PrincipalLogin
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@Repository
interface PrincipalRepository {

    @Query("select * from principals where id = :id")
    suspend fun getPrincipalById(id: UUID): Principal?

    @Query("select principal from principal_credentials where lower(attributes->>'identifier') = lower(:identifier)")
    suspend fun getPrincipalIdByIdentifier(identifier: String): UUID?

    @Query("select principal from principal_credentials where lower(attributes->>'identifier') = lower(:identifier) and type = (:type)::principal_credential_type")
    suspend fun getPrincipalIdByIdentifierAndType(identifier: String, type: CredentialType): UUID?

    @Query("insert into principals (verified, anonymous, attributes, verification_token, verification_origin, primary_profile_id) values (:verified, :anonymous, :attributes, :verificationToken, :verificationOrigin, :primaryProfileId) returning *")
    suspend fun add(principal: Principal): Principal

    @Query("update principals set modified = now(), verified = :verified, anonymous = :anonymous, attributes = :attributes, verification_token = :verificationToken, verification_origin = :verificationOrigin, primary_profile_id = :primaryProfileId where id = :id returning *")
    suspend fun edit(principal: Principal): Principal

    @Query("select * from principals order by id limit :limit offset :offset")
    suspend fun getPrincipals(offset: Long, limit: Int): List<Principal>

    @Query("select * from principals where (:includeDeleted or deleted_at is null) order by id limit :limit offset :offset")
    suspend fun getPrincipals(offset: Long, limit: Int, includeDeleted: Boolean): List<Principal>

    @Query("select * from principals where id = any (:id)")
    suspend fun getPrincipalsById(id: List<UUID>): List<Principal>

    @Query("select * from principals where verification_token = :token")
    suspend fun getByVerificationToken(token: String): Principal?

    /**
     * Records a successful sign-in. Deliberately leaves `modified` untouched — a login
     * is not an edit of the principal, and bumping `modified` here would make every
     * sign-in look like an account change to history/auditing consumers.
     */
    @Query("update principals set last_login = now() where id = :id")
    suspend fun touchLastLogin(id: UUID)

    @Query("select last_login from principals where id = :id")
    suspend fun getLastLogin(id: UUID): OffsetDateTime?

    @Query("insert into principal_logins (principal_id, method) values (:principalId, :method) returning *")
    suspend fun addLogin(
        principalId: UUID,
        method: String,
    ): PrincipalLogin

    @Query("select * from principal_logins where principal_id = :principalId order by created desc, id desc limit :limit offset :offset")
    suspend fun getLogins(principalId: UUID, offset: Long, limit: Int): List<PrincipalLogin>

    /** Revokes a login selected by its user-visible history ID. */
    @Query("update principal_logins set revoked_at = coalesce(revoked_at, now()) where principal_id = :principalId and id = :loginId returning *")
    suspend fun revokeLogin(principalId: UUID, loginId: Long): PrincipalLogin?

    /** Marks every tracked login revoked during principal-wide security invalidation. */
    @Query("update principal_logins set revoked_at = coalesce(revoked_at, now()) where principal_id = :principalId and revoked_at is null")
    suspend fun revokeSessions(principalId: UUID)

    /** Persists a revocation until all JWTs previously minted for the login have expired. */
    @Query("insert into principal_login_revocations (login_id, principal_id, expires_at) values (:loginId, :principalId, :expiresAt) on conflict (login_id) do update set expires_at = greatest(principal_login_revocations.expires_at, excluded.expires_at)")
    suspend fun addLoginRevocation(principalId: UUID, loginId: Long, expiresAt: OffsetDateTime)

    /** Enables the principal-level fast-path guard before a targeted revocation becomes visible. */
    @Query("update principals set has_login_revocations = true where id = :principalId")
    suspend fun markHasLoginRevocations(principalId: UUID)

    /** Checks the durable L3 revocation index for an unexpired login revocation. */
    @Query("select exists(select 1 from principal_login_revocations where login_id = :loginId and expires_at > now())")
    suspend fun isLoginRevoked(loginId: Long): Boolean

    /**
     * Removes revocations that can no longer match a valid JWT and clears the fast-path
     * flag only for principals whose revocations were removed and have no live revocations left.
     */
    @Query(
        """
        with expired as (
            delete from principal_login_revocations
            where expires_at <= now()
            returning principal_id
        ), affected as (
            select distinct principal_id
            from expired
        )
        update principals p
        set has_login_revocations = false
        from affected a
        where p.id = a.principal_id
          and p.has_login_revocations
          and not exists (
              select 1
              from principal_login_revocations r
              where r.principal_id = p.id
                and r.expires_at > now()
          )
        """,
    )
    suspend fun deleteExpiredLoginRevocations()

    /**
     * Atomically increments the principal's `token_version` counter
     * and returns the new value. Paired with an unconditional delete
     * of the principal's refresh tokens, this is the single source of
     * truth for mass-invalidating outstanding access and refresh
     * tokens — request-time JWT validation rejects any token whose
     * `tver` claim does not match the freshly-bumped value.
     */
    @Query("update principals set token_version = token_version + 1, modified = now() where id = :id returning token_version")
    suspend fun incrementTokenVersion(id: UUID): Int?

    /** Stages the principal for deletion (reversible). Pairs with a token-version bump to revoke sessions. */
    @Query("update principals set deleted_at = now(), modified = now() where id = :id returning *")
    suspend fun markDeleted(id: UUID): Principal

    /** Clears the soft-delete marker, re-enabling the account. */
    @Query("update principals set deleted_at = null, modified = now() where id = :id returning *")
    suspend fun restore(id: UUID): Principal

    /**
     * Irreversibly removes the principal. Every FK into `principals` is `ON DELETE CASCADE`
     * (credentials, group memberships, emails, refresh/exchange tokens, org members, and the
     * principal's profiles), so the row's full footprint goes with it; `primary_profile_id` is the
     * only self-reference and it lives on the row being deleted.
     */
    @Query("delete from principals where id = :id")
    suspend fun deleteById(id: UUID)

    /**
     * Finds verified principals that share a verified email address — duplicate accounts created before
     * sign-up collision prevention. Returns one (email, principal_id) row per principal in each colliding
     * group, ordered by email; the service folds these into [bosca.security.model.DuplicatedAccountIds].
     * Admin-only / cleanup use — this is a heavy aggregate scan, not a hot path.
     */
    @Query(
        """
        select distinct lower(trim(pa.attributes->>'email')) as email, p.id as principal_id
        from profile_attributes pa
        join profiles pr on pr.id = pa.profile
        join principals p on p.id = pr.principal
        where pa.type_id = 'bosca.profiles.email'
          and pa.verified = true
          and p.verified = true
          and lower(trim(pa.attributes->>'email')) in (
            select lower(trim(pa2.attributes->>'email'))
            from profile_attributes pa2
            join profiles pr2 on pr2.id = pa2.profile
            join principals p2 on p2.id = pr2.principal
            where pa2.type_id = 'bosca.profiles.email' and pa2.verified = true and p2.verified = true
            group by lower(trim(pa2.attributes->>'email'))
            having count(distinct p2.id) > 1
          )
        order by email
        """
    )
    suspend fun findDuplicateVerifiedEmailAccounts(): List<DuplicatedAccount>
}
