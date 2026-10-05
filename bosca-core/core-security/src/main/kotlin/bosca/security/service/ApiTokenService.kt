package bosca.security.service

import bosca.security.model.PrincipalCredential
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Holds the result of creating a new API token: the persisted credential and
 * the raw token string that must be shown to the user exactly once.
 *
 * @param credential the persisted [PrincipalCredential] containing the token's hashed identifier and metadata
 * @param rawToken the plaintext `bsk_...` token — never stored, only returned at creation time
 */
data class ApiTokenCreationResult(
    val credential: PrincipalCredential,
    val rawToken: String,
)

/**
 * Input parameters for creating a new API token credential.
 *
 * @param name a human-readable label for the token (e.g., "CI Deploy Token")
 * @param description an optional longer description of the token's intended purpose
 * @param scopes permission scopes to restrict the token's capabilities, or `null` for unrestricted access
 * @param allowedGroups group IDs to restrict the token to (must be a subset of the principal's groups), or `null` for all groups
 * @param expiresAt ISO-8601 timestamp when the token should expire, or `null` for no expiration
 */
@Serializable
data class ApiTokenInput(
    val name: String,
    val description: String? = null,
    val scopes: List<String>? = null,
    val allowedGroups: List<@Contextual UUID>? = null,
    val expiresAt: String? = null,
)

/**
 * Service responsible for the lifecycle and authentication of long-lived API tokens.
 *
 * API tokens are stored as credentials in the `principal_credentials` table with
 * type `API_TOKEN`. The raw token is a `bsk_`-prefixed string containing 256 bits
 * of cryptographic randomness; only its SHA-256 hash is persisted.
 */
interface ApiTokenService : Service {

    /**
     * Creates a new API token credential for the given principal.
     *
     * Generates a cryptographically random `bsk_...` token, computes its SHA-256
     * hash, and stores it as a [PrincipalCredential] with type `API_TOKEN`.
     *
     * @param principalId the principal the token authenticates as
     * @param input creation parameters (name, scopes, expiration, etc.)
     * @param createdBy the principal performing the creation (may differ from [principalId] for admin-created tokens)
     * @return the persisted credential and the raw token (shown once, never stored)
     * @throws SecurityException if the principal has exceeded the maximum token limit
     * @throws IllegalArgumentException if any scope values are unrecognized
     */
    suspend fun createToken(principalId: UUID, input: ApiTokenInput, createdBy: UUID): ApiTokenCreationResult

    /**
     * Creates a short-lived token for an internally managed ephemeral workload.
     *
     * Unlike user-managed tokens, ephemeral workload tokens do not consume the principal's
     * personal token quota and their requested scopes are not treated as a user grant. The token's
     * scopes still constrain runtime authorization; workload APIs must additionally bind the
     * credential to their own durable workload identity. [ApiTokenInput.expiresAt] is required,
     * and the caller must delete the token when the workload reaches a terminal state.
     *
     * @param principalId the principal whose authorization the workload executes under
     * @param input creation parameters, including a mandatory expiration
     * @param createdBy the principal recorded as creating the token
     * @return the persisted credential and one-time raw token
     */
    suspend fun createEphemeralToken(
        principalId: UUID,
        input: ApiTokenInput,
        createdBy: UUID,
    ): ApiTokenCreationResult

    /**
     * Authenticates an incoming `bsk_...` token by hashing it and looking up the
     * corresponding credential. Returns a [ScopedAuthenticatedPrincipal] that
     * carries the token's scope and group restrictions.
     *
     * @param rawToken the full `bsk_...` token from the Authorization header
     * @param remoteIp the caller's IP address for `last_used_ip` tracking, or `null` if unavailable
     * @return the authenticated and scope-restricted principal
     * @throws SecurityException if the token is invalid, revoked, or expired
     */
    suspend fun authenticate(rawToken: String, remoteIp: String?): ScopedAuthenticatedPrincipal

    /**
     * Revokes a single API token by its credential ID, rendering it permanently unusable.
     *
     * @param credentialId the `principal_credentials.id` of the token to revoke
     * @param requestingPrincipalId the principal performing the revocation (must own the token or be an admin)
     * @throws SecurityException if the requesting principal is not authorized to revoke this token
     */
    suspend fun revokeToken(credentialId: Long, requestingPrincipalId: UUID)

    /**
     * Revokes all active API tokens for the given principal.
     *
     * @param principalId the principal whose tokens should be revoked
     * @return the number of tokens that were revoked
     */
    suspend fun revokeAllTokens(principalId: UUID): Int

    /**
     * Retrieves all API token credentials for a principal, ordered by creation time descending.
     *
     * @param principalId the principal whose tokens should be listed
     * @return the list of API token credentials (both active and revoked)
     */
    suspend fun getTokensForPrincipal(principalId: UUID): List<PrincipalCredential>

    /**
     * Retrieves a single API token credential by its ID.
     *
     * @param credentialId the `principal_credentials.id` to look up
     * @return the credential, or `null` if not found or not an API token
     */
    suspend fun getTokenById(credentialId: Long): PrincipalCredential?

    /**
     * Updates the name, description, and/or scopes of an existing API token.
     *
     * @param credentialId the `principal_credentials.id` of the token to update
     * @param name the new name, or `null` to keep the current name
     * @param description the new description, or `null` to keep the current description
     * @param scopes the new scopes list, or `null` to keep the current scopes
     * @param requestingPrincipalId the principal performing the update
     * @return the updated credential
     * @throws SecurityException if the requesting principal is not authorized
     * @throws IllegalArgumentException if any scope values are unrecognized
     */
    suspend fun editToken(credentialId: Long, name: String?, description: String?, scopes: List<String>?, requestingPrincipalId: UUID): PrincipalCredential

    /**
     * Permanently deletes a revoked API token credential.
     *
     * @param credentialId the `principal_credentials.id` of the token to delete
     * @param requestingPrincipalId the principal performing the deletion
     * @throws SecurityException if the token is not revoked or the principal is not authorized
     */
    suspend fun deleteToken(credentialId: Long, requestingPrincipalId: UUID)

    /**
     * Returns all recognized API token scopes.
     */
    fun availableScopes(): List<ApiTokenScope> = ApiTokenScopes.all
}
