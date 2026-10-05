package bosca.security.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import java.time.OffsetDateTime

@Serializable
data class Principal(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    val verified: Boolean = false,
    val anonymous: Boolean = true,
    @Contextual
    val attributes: JsonElement? = null,
    @ColumnName("verification_token")
    val verificationToken: String? = null,
    /**
     * The raw web origin a password-reset was requested from (e.g. `https://app.example.com`), persisted with the
     * reset token so the email link routes back to that host in a multi-host deployment. Validated against
     * the redirect allow-list when the link is built; cleared when the token is redeemed.
     */
    @ColumnName("verification_origin")
    val verificationOrigin: String? = null,
    @ColumnName("primary_profile_id")
    @Contextual
    val primaryProfileId: UUID? = null,
    /**
     * Monotonic counter used to mass-invalidate outstanding JWTs for
     * this principal. Every JWT minted by `createJwtToken` embeds the
     * current value as a `tver` claim; the request verifier rejects
     * any token whose claim does not match. Flows that must terminate
     * existing sessions (password change, reset, identifier change,
     * admin lock, ...) increment this counter and delete the
     * principal's refresh tokens, rendering every previously-issued
     * access and refresh token unusable in a single step.
     */
    @ColumnName("token_version")
    val tokenVersion: Int = 0,
    /**
     * Non-null once an admin has *marked the principal deleted* — a reversible staging step ahead
     * of a full hard delete. While set, the principal's sessions are revoked and authentication is
     * refused (see `SecurityService.markPrincipalDeleted` and the `requireNotDeleted` login gate);
     * clearing it (restore) re-enables the account.
     */
    @ColumnName("deleted_at")
    @Contextual
    val deletedAt: OffsetDateTime? = null,
    /**
     * Fast-path hint for JWT authentication. False means no login can have an unexpired targeted
     * revocation, so request validation can skip the per-login cache hierarchy entirely.
     */
    @ColumnName("has_login_revocations")
    val hasLoginRevocations: Boolean = false,
) {

    override fun toString() = id.toString()
}

@Serializable
open class AuthenticatedPrincipal(
    private val principal: Principal,
    private val groups: List<Group>,
    /** Durable login record carried by this JWT, or null for non-login and legacy tokens. */
    val loginId: Long? = null,
) {

    @Transient
    private val groupIds = groups.mapTo(mutableSetOf()) { it.id }

    @Transient
    private val groupNames = groups.mapTo(mutableSetOf()) { it.name }

    val id get() = principal.id

    fun hasGroup(group: Group) = groups.contains(group)

    fun hasGroup(groupId: UUID) = groupIds.contains(groupId)

    fun hasGroup(name: String) = groupNames.contains(name)

    fun asPrincipal() = principal

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AuthenticatedPrincipal

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}
