package bosca.security.service

import bosca.graphql.Batch
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.CredentialAttributes
import bosca.security.model.CredentialType
import bosca.security.model.EncodedPassword
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.LoginResponse
import bosca.security.model.Principal
import bosca.security.model.PrincipalLogin
import bosca.security.model.PrincipalCredential
import bosca.security.model.SignupToken
import bosca.security.oauth2.ThirdPartyUser
import bosca.serialization.UUID
import bosca.serialization.OffsetDateTime
import bosca.service.Service
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.Payload
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
enum class ThirdPartyType {
    GOOGLE,
    FACEBOOK,
    APPLE
}

/**
 * Encodes and verifies passwords using a specific hashing algorithm.
 *
 * Implementations handle the one-way hashing of raw passwords and the comparison
 * of a raw password against a previously encoded value.
 *
 * @param T the type of [EncodedPassword] produced by this encoder
 */
interface PasswordEncoder<T : EncodedPassword> {

    /**
     * Encodes a raw password into its hashed representation.
     *
     * @param rawPassword the plaintext password to encode
     * @return the encoded password of type [T]
     */
    suspend fun encode(rawPassword: String): T

    /**
     * Checks whether a raw password matches a previously encoded password.
     *
     * @param rawPassword the plaintext password to verify
     * @param encodedPassword the previously encoded password to compare against
     * @return `true` if the raw password matches the encoded one, `false` otherwise
     */
    suspend fun matches(rawPassword: String, encodedPassword: T): Boolean
}

/**
 * Core security service providing authentication, authorization, principal management,
 * and group-based access control.
 *
 * This service is the central entry point for all security-related operations in the platform,
 * including JWT token management, credential-based and third-party login flows,
 * password reset workflows, and user verification.
 */
interface SecurityService : Service {

    /**
     * Returns the maximum age (in seconds) that an issued authentication token remains valid.
     *
     * @return the token lifetime in seconds
     */
    suspend fun getMaxTokenAgeInSeconds(): Long

    /**
     * Creates and signs a new JWT token for the given principal with the specified claims.
     *
     * @param principal the principal to issue the token for
     * @param claims additional claims to embed in the JWT payload
     * @return the decoded JWT containing the token value and claims
     */
    suspend fun createJwtToken(principal: Principal, claims: Map<String, Any>): DecodedJWT

    /**
     * Looks up a group by its name and type.
     *
     * @param name the exact group name to search for
     * @param type the group type to match
     * @return the matching [Group], or `null` if no group exists with that name and type
     */
    suspend fun getGroupByName(name: String, type: GroupType): Group?

    /**
     * Retrieves all principals that belong to the specified group.
     *
     * @param group the group whose members should be retrieved
     * @return a list of principals in the group
     */
    suspend fun getPrincipalsByGroup(group: Group): List<Principal>

    /**
     * Retrieves all groups that the specified principal belongs to.
     *
     * @param principalId the unique identifier of the principal
     * @return a list of groups the principal is a member of
     */
    suspend fun getPrincipalGroups(principalId: UUID): List<Group>

    /**
     * Retrieves group memberships for multiple principals using a batched lookup.
     *
     * @param principalIds principal identifiers to resolve
     * @return every requested principal mapped to its groups, including empty lists for non-members
     */
    suspend fun getPrincipalGroups(principalIds: List<UUID>): Map<UUID, List<Group>>

    /**
     * Adds a principal to a group, granting the principal the group's permissions.
     *
     * @param principalId the unique identifier of the principal to add
     * @param groupId the unique identifier of the group to add the principal to
     */
    suspend fun addPrincipalGroup(principalId: UUID, groupId: UUID)

    /**
     * Removes a principal from a group, revoking the group's permissions from the principal.
     *
     * @param principalId the unique identifier of the principal to remove
     * @param groupId the unique identifier of the group to remove the principal from
     */
    suspend fun removePrincipalGroup(principalId: UUID, groupId: UUID)

    /**
     * Retrieves a group by its unique identifier.
     *
     * @param id the unique identifier of the group
     * @return the [Group] with the specified ID
     * @throws Exception if no group is found with the given ID
     */
    suspend fun getGroupById(id: UUID): Group

    /**
     * Retrieves a paginated list of groups, optionally filtered by type.
     *
     * @param type an optional [GroupType] filter; `null` returns groups of all types
     * @param offset the number of groups to skip for pagination
     * @param limit the maximum number of groups to return
     * @return a list of groups matching the criteria
     */
    suspend fun getGroups(type: GroupType?, offset: Long, limit: Int): List<Group>

    /**
     * Searches for groups whose name or description matches the given query string,
     * optionally filtered by type.
     *
     * @param nameOrDescription the search term to match against group names and descriptions
     * @param type an optional [GroupType] filter; `null` searches across all types
     * @param offset the number of results to skip for pagination
     * @param limit the maximum number of results to return
     * @return a list of groups matching the search criteria
     */
    suspend fun findGroups(nameOrDescription: String, type: GroupType?, offset: Long, limit: Int): List<Group>

    /**
     * Creates a new group and persists it.
     *
     * @param group the group to create (the ID field will be assigned by the system)
     * @return the newly created [Group] with its assigned ID
     */
    suspend fun addGroup(group: Group): Group

    /**
     * Updates an existing group's name, description, or type.
     *
     * @param group the group with updated fields; the group's ID identifies which record to update
     * @return the updated [Group]
     */
    suspend fun editGroup(group: Group): Group

    /**
     * Deletes a group by its unique identifier.
     *
     * @param id the unique identifier of the group to delete
     */
    suspend fun deleteGroup(id: UUID)

    /**
     * Retrieves a principal by their email verification token.
     *
     * @param token the verification token to look up
     * @return the [Principal] associated with the token, or `null` if the token is invalid or expired
     */
    suspend fun getByVerificationToken(token: String): Principal?

    /**
     * Marks a principal's account as verified using the provided verification token.
     *
     * @param token the verification token previously sent to the principal
     */
    suspend fun verifyWithToken(token: String)

    /**
     * Retrieves a paginated list of all principals.
     *
     * @param offset the number of principals to skip for pagination
     * @param limit the maximum number of principals to return
     * @return a list of principals
     */
    suspend fun getPrincipals(offset: Long, limit: Int): List<Principal>

    /**
     * Retrieves a paginated list of principals, optionally including those that have been
     * soft-deleted (marked for deletion). When [includeDeleted] is `false` only live principals
     * are returned.
     *
     * @param offset the number of principals to skip for pagination
     * @param limit the maximum number of principals to return
     * @param includeDeleted whether to include soft-deleted principals
     * @return a list of principals
     */
    suspend fun getPrincipals(offset: Long, limit: Int, includeDeleted: Boolean): List<Principal>

    /**
     * Retrieves a principal by their unique identifier.
     *
     * @param id the unique identifier of the principal
     * @return the [Principal], or `null` if not found
     */
    suspend fun getPrincipalById(id: UUID): Principal?

    /**
     * Retrieves multiple principals by their unique identifiers in a single operation.
     *
     * @param id the list of principal identifiers to look up
     * @return a list of found principals (may be smaller than the input list if some IDs are not found)
     */
    suspend fun getPrincipalsById(id: List<UUID>): List<Principal>

    /**
     * Retrieves a principal by their login identifier (e.g., username).
     *
     * @param identifier the login identifier to search for
     * @param type the credential type to match (e.g., PASSWORD, OAUTH2)
     * @return the matching [Principal], or `null` if not found
     */
    suspend fun getPrincipalByIdentifier(identifier: String, type: CredentialType): Principal?

    /**
     * Retrieves a principal by their login identifier (e.g., username).
     *
     * @param identifier the login identifier to search for
     * @return the matching [Principal], or `null` if not found
     */
    suspend fun getPrincipalByIdentifier(identifier: String): Principal?

    /**
     * Retrieves a principal by their email address.
     *
     * @param email the email address to search for
     * @return the matching [Principal], or `null` if not found
     */
    suspend fun getPrincipalByEmail(email: String): Principal?

    /**
     * Retrieves the timestamp of a principal's most recent login.
     *
     * @param principalId the unique identifier of the principal
     * @return the date and time of the last login, or `null` if the principal has never logged in
     */
    suspend fun getPrincipalLastLogin(principalId: UUID): OffsetDateTime?

    /**
     * Returns successful interactive authentications for [principalId], newest first.
     *
     * Authorization belongs at the API boundary; callers inside the domain must already have
     * established that the viewer owns the principal or may administer it.
     */
    suspend fun getPrincipalLogins(principalId: UUID, offset: Long, limit: Int): List<PrincipalLogin>

    /**
     * Revokes one recorded sign-in owned by [principalId]. The durable login ID remains on
     * the [PrincipalLogin] for history, while all JWTs carrying that ID and its refresh token
     * become unusable.
     *
     * @param principalId owner of the sign-in
     * @param loginId stable [PrincipalLogin.id] selected by the caller
     * @return the revoked login, or `null` when the login does not belong to the principal
     */
    suspend fun revokePrincipalLogin(principalId: UUID, loginId: Long): PrincipalLogin?

    /**
     * Loads principals into a [Batch] for efficient bulk resolution, typically used
     * by GraphQL data loaders to avoid N+1 query issues.
     *
     * @param batch the batch to populate with principal data keyed by their UUIDs
     */
    suspend fun addPrincipalsToBatch(batch: Batch<UUID, Principal>)

    /**
     * Retrieves all credentials associated with a principal, regardless of credential type.
     *
     * @param principal the principal whose credentials should be retrieved
     * @return a list of the principal's credentials
     */
    suspend fun getCredentials(principal: Principal): List<PrincipalCredential>

    /**
     * Retrieves credentials of a specific type associated with a principal.
     *
     * @param principal the principal whose credentials should be retrieved
     * @param type the credential type to filter by (e.g., PASSWORD, OAUTH2)
     * @return a list of matching credentials
     */
    suspend fun getCredentials(principal: Principal, type: CredentialType): List<PrincipalCredential>

    /**
     * Registers a new principal with the given credentials and optional group memberships.
     *
     * @param principal the principal to create
     * @param credential the initial credential (e.g., password or OAuth2) for the principal
     * @param groups optional list of groups to assign the new principal to
     * @param addPrincipalGroup whether to automatically add the principal to a default principal group
     * @return the newly created [AuthenticatedPrincipal] with resolved group memberships
     */
    suspend fun addPrincipal(principal: Principal, credential: CredentialAttributes, groups: List<Group> = emptyList(), addPrincipalGroup: Boolean = true, originator: String? = null): AuthenticatedPrincipal

    /**
     * Attaches an additional [credential] to an existing principal so that principal can authenticate
     * through more than one method (e.g. password + OAuth2 + passkey).
     *
     * The credential's identifier must not already belong to ANY principal — if it does, a
     * [CredentialConflict] is thrown rather than silently stealing or duplicating an identity. The
     * uniqueness is enforced both by a pre-check and by the `ix_principal_identifier` unique index
     * (the race backstop). On success every outstanding session for the principal is invalidated, since
     * the set of ways to authenticate as them just changed.
     *
     * This is the shared primitive behind interactive account-linking (after proof of ownership) and the
     * logged-in "connect a provider" flow.
     *
     * @param principalId the principal that will own the new credential
     * @param credential the credential to attach (password credentials are encoded before storage)
     * @return the stored [PrincipalCredential]
     * @throws CredentialConflict if the credential identifier already belongs to a principal
     */
    suspend fun linkCredentialToPrincipal(principalId: UUID, credential: CredentialAttributes): PrincipalCredential

    /**
     * Connects an additional third-party (OAuth2/OIDC) login to an ALREADY-AUTHENTICATED principal.
     *
     * Proof of ownership is implicit — the caller is already signed in as [principalId]. The provider
     * [token] is verified with the provider; if it asserts an email, that email must be provider-verified.
     * The resulting OAuth2 credential is attached via [linkCredentialToPrincipal], so an identity already
     * bound to a different principal is rejected with [CredentialConflict] rather than stolen.
     *
     * @param principalId the authenticated principal to connect the provider to
     * @param type the third-party provider type (GOOGLE, FACEBOOK, APPLE)
     * @param token the provider token to verify
     * @return the stored OAuth2 [PrincipalCredential]
     */
    suspend fun connectThirdParty(principalId: UUID, type: ThirdPartyType, token: String): PrincipalCredential

    /**
     * Guards a user-facing sign-up against silently forking a second principal when a **verified**
     * account already owns [email]. If one does, throws `AccountLinkRequired` so the caller routes the
     * user into account-linking with proof of ownership instead of creating a duplicate. No-op when
     * [email] is null/blank or unowned by any verified account.
     *
     * Used symmetrically by every user-facing sign-up entry point (OAuth and password). Admin/internal
     * principal creation deliberately bypasses this guard.
     *
     * On collision a single-use pending-link token is minted (capturing [pendingCredential] for the
     * existing account) and `AccountLinkRequired` is thrown carrying that token and the available proof
     * methods, so the caller can drive the proof/linking challenge.
     *
     * @param email the email the sign-up is claiming (the OAuth provider's verified email, or the
     *   password sign-up identifier); pass null when the email is not trustworthy enough to match on.
     * @param pendingCredential the new sign-in method to attach to the existing account once ownership
     *   is proven (an OAuth2 credential, or the new password credential).
     */
    suspend fun verifyEmailAvailableForSignup(email: String?, pendingCredential: CredentialAttributes)

    /**
     * Completes an account link by re-authenticating with the existing account's password.
     *
     * @param token the pending-link token issued when the collision was detected
     * @param password the existing account's password (the proof of ownership)
     * @return a [LoginResponse] for the existing account, now carrying the newly-linked credential
     */
    suspend fun confirmAccountLinkWithPassword(token: String, password: String): LoginResponse

    /**
     * Sends a one-time magic-link to the existing account's verified email so the user can prove
     * ownership without a password (the only option for OAuth-only accounts).
     *
     * @param token the pending-link token issued when the collision was detected
     * @param requestOrigin the public web origin the requesting user is on (e.g. `https://app.example.com`),
     *   so the magic-link routes back to the right host in a multi-host deployment. Validated against the
     *   allow-list; falls back to the configured default app origin when null or not allow-listed.
     */
    suspend fun requestAccountLinkEmailProof(token: String, requestOrigin: String? = null)

    /**
     * Completes an account link using the one-time token delivered by [requestAccountLinkEmailProof];
     * possession of [emailToken] proves control of the account's verified email.
     *
     * @param emailToken the one-time token from the emailed magic-link
     * @return a [LoginResponse] for the existing account, now carrying the newly-linked credential
     */
    suspend fun confirmAccountLinkWithEmail(emailToken: String): LoginResponse

    /**
     * Finds groups of verified principals that share the same verified email — duplicate accounts
     * created before sign-up collision prevention existed. Read-only; intended for admin/cleanup use.
     */
    suspend fun findDuplicateAccounts(): List<bosca.security.model.DuplicatedAccountIds>

    /**
     * Merges the [duplicateId] principal into [survivorId]: moves the duplicate's credentials and
     * profiles (re-parenting ownership — content is keyed on profile id, never fused) and group
     * memberships onto the survivor, gives the survivor a primary profile if it lacks one, then retires
     * the duplicate (marked unverified + anonymous, sessions invalidated) so it drops out of verified
     * lookups and the uniqueness backstop. Caller MUST enforce admin authorization. Returns the survivor.
     *
     * @param survivorId the principal that keeps everything
     * @param duplicateId the principal to retire
     * @return the surviving [Principal]
     */
    suspend fun mergePrincipals(survivorId: UUID, duplicateId: UUID): Principal

    /**
     * Updates an existing principal's mutable attributes (e.g., verified status, attributes JSON).
     *
     * @param principal the principal with updated fields; the principal's ID identifies which record to update
     * @return the updated [Principal]
     */
    suspend fun editPrincipal(principal: Principal): Principal

    /**
     * Marks a principal as deleted — a reversible staging step ahead of a full [deletePrincipal].
     * Sets `deleted_at`, revokes every outstanding session (bumps the token version and drops refresh
     * tokens, the same path as [signOut]), and — together with the request/login `deleted` gate —
     * blocks the principal from authenticating until [restorePrincipal] is called. Caller MUST enforce
     * admin authorization.
     *
     * @param id the unique identifier of the principal to mark deleted
     * @return the updated [Principal] carrying its `deletedAt` timestamp
     */
    suspend fun markPrincipalDeleted(id: UUID): Principal

    /**
     * Reverses [markPrincipalDeleted] by clearing `deleted_at`, re-enabling authentication. Existing
     * sessions are NOT restored (they were already revoked); the user simply logs in again. Caller
     * MUST enforce admin authorization.
     *
     * @param id the unique identifier of the principal to restore
     * @return the updated [Principal] with no `deletedAt`
     */
    suspend fun restorePrincipal(id: UUID): Principal

    /**
     * Irreversibly deletes a principal and everything the database cascades from it — credentials,
     * group memberships, emails, refresh/exchange tokens, organization memberships, and the
     * principal's **linked profiles**. Intended to follow [markPrincipalDeleted]; the caller MUST
     * enforce both admin authorization and the "soft-deleted first" precondition.
     *
     * @param id the unique identifier of the principal to permanently delete
     */
    suspend fun deletePrincipal(id: UUID)

    /**
     * Sets the primary profile for a principal, determining which profile is used by default.
     * The profile must be active and owned by that principal.
     *
     * @param principalId the unique identifier of the principal
     * @param profileId the unique identifier of the profile to set as primary
     */
    suspend fun setPrimaryProfile(principalId: UUID, profileId: UUID)

    /**
     * Removes the primary profile association from a principal. After this call the principal
     * will have no designated primary profile until one is explicitly set again.
     *
     * @param principalId the unique identifier of the principal whose primary profile should be cleared
     */
    suspend fun clearPrimaryProfile(principalId: UUID)

    /**
     * Updates a principal's password credential. Optionally updates their login identifier as well.
     *
     * @param principalId the unique identifier of the principal
     * @param password the new plaintext password (will be encoded by the service)
     * @param identifier an optional new login identifier to set alongside the password
     */
    suspend fun updatePassword(principalId: UUID, password: String, identifier: String? = null)

    /**
     * Updates a principal's login identifier (e.g., username or email used for login).
     *
     * @param principalId the unique identifier of the principal
     * @param identifier the new login identifier
     */
    suspend fun updateIdentifier(principalId: UUID, identifier: String)

    /**
     * Authenticates a principal using a decoded JWT payload (e.g., from an incoming request).
     *
     * @param payload the decoded JWT payload containing the principal's identity claims
     * @return the [AuthenticatedPrincipal] with resolved group memberships
     */
    suspend fun authenticateWithPayload(payload: Payload): AuthenticatedPrincipal

    /**
     * Authenticates a principal using credential attributes (e.g., username/password).
     *
     * @param credential the credential attributes to authenticate with
     * @return the [AuthenticatedPrincipal] with resolved group memberships
     */
    suspend fun authenticateWithCredential(credential: CredentialAttributes): AuthenticatedPrincipal

    /**
     * Initiates the forgot-password flow by generating a reset token and sending it
     * to the principal identified by the given login identifier.
     *
     * @param identifier the login identifier (e.g., email or username) of the principal requesting a reset
     * @param requestOrigin the raw web origin the request came from (e.g. `https://app.example.com`), persisted
     *   with the reset token so the email link routes back to that host in a multi-host deployment. Validated
     *   against the allow-list when the link is built; null falls back to the default app origin.
     */
    suspend fun forgotPassword(identifier: String, requestOrigin: String? = null)

    /**
     * Resets a principal's password using a previously issued reset token.
     *
     * @param token the password reset token
     * @param password the new plaintext password to set
     */
    suspend fun resetPassword(token: String, password: String)

    /**
     * Authenticates a principal with credentials and returns a login response containing
     * access and optional refresh tokens.
     *
     * @param credential the credential attributes to authenticate with
     * @param generateRefreshToken whether to issue a refresh token alongside the access token
     * @param signupTokens optional signup tokens to process during login (e.g., invitation codes)
     * @return a [LoginResponse] containing the issued tokens
     */
    suspend fun loginWithCredential(credential: CredentialAttributes, generateRefreshToken: Boolean = false, signupTokens: List<SignupToken> = emptyList(), originator: String? = null): LoginResponse

    /**
     * Authenticates or registers a principal using third-party OAuth2 credentials and user profile.
     * If the user does not yet exist, a new principal is created from the third-party profile.
     *
     * @param credential the OAuth2 credential attributes
     * @param user the user profile from the third-party provider
     * @param locale the user's locale for localization of any generated content (e.g., verification emails)
     * @param generateRefreshToken whether to issue a refresh token
     * @param signupTokens optional signup tokens to process during login
     * @param requestOrigin the web origin the user is on (e.g. the Studio host the OAuth flow started from),
     *   used to route the verification email — sent when the provider does not assert a verified email — back
     *   to that host; validated against the app-origin allow-list, falling back to the default app origin
     * @return a [LoginResponse] containing the issued tokens
     */
    suspend fun loginWithThirdParty(credential: CredentialAttributes, user: ThirdPartyUser, locale: Locale, generateRefreshToken: Boolean = false, signupTokens: List<SignupToken> = emptyList(), requestOrigin: String? = null, originator: String? = null): LoginResponse

    /**
     * Authenticates or registers a principal by exchanging a third-party provider's access token
     * for Bosca credentials. The service validates the token with the provider and retrieves the user profile.
     *
     * @param type the third-party provider type (e.g., GOOGLE, FACEBOOK, APPLE)
     * @param token the access token from the third-party provider
     * @param locale the user's locale for localization
     * @param generateRefreshToken whether to issue a refresh token
     * @param signupTokens optional signup tokens to process during login
     * @param requestOrigin the web origin the user is on, used to route the verification email — sent when the
     *   provider does not assert a verified email — back to that host; validated against the app-origin
     *   allow-list, falling back to the default app origin
     * @return a [LoginResponse] containing the issued tokens
     */
    suspend fun loginWithThirdPartyToken(type: ThirdPartyType, token: String, locale: Locale, generateRefreshToken: Boolean = false, signupTokens: List<SignupToken> = emptyList(), requestOrigin: String? = null, originator: String? = null): LoginResponse

    /**
     * Links provider identity [user] to an already authenticated principal after an OAuth redirect flow
     * has fetched and verified the provider profile.
     *
     * @param principalId authenticated account receiving the new credential
     * @param credential provider credential derived from the verified profile
     * @param user verified profile returned by the provider
     * @return the linked credential (or the existing equivalent credential for an idempotent reconnect)
     */
    suspend fun connectThirdParty(
        principalId: UUID,
        credential: CredentialAttributes,
        user: ThirdPartyUser,
    ): PrincipalCredential

    /**
     * Issues a new access token by validating and exchanging a previously issued refresh token.
     *
     * @param refreshToken the refresh token to exchange
     * @return a [LoginResponse] containing the newly issued tokens
     */
    suspend fun loginWithRefreshToken(refreshToken: String): LoginResponse

    /**
     * Terminates only the sign-in identified by [loginId]. The [PrincipalLogin] record is marked
     * revoked and its refresh token is deleted. Other logins for [principalId] are not changed.
     *
     * A null login ID represents a legacy token and revokes all access and refresh tokens for the
     * principal because the individual login cannot be identified safely.
     *
     * @param principalId owner of the current authenticated login
     * @param loginId durable [PrincipalLogin.id] carried by the current JWT, when present
     */
    suspend fun signOut(principalId: UUID, loginId: Long?)

    /**
     * Authenticates a principal using an existing JWT token (e.g., for token-based SSO or re-authentication).
     *
     * @param jwtToken the JWT token to authenticate with
     * @param generateRefreshToken whether to issue a refresh token
     * @param signupTokens optional signup tokens to process during login
     * @return a [LoginResponse] containing the issued tokens
     */
    suspend fun loginWithJwtToken(jwtToken: String, generateRefreshToken: Boolean = false, signupTokens: List<SignupToken> = emptyList()): LoginResponse

    /**
     * Removes a specific credential from a principal identified by the credential's type and
     * login identifier. Used by administrators to disconnect OAuth providers or remove
     * password credentials from a principal's account.
     *
     * After deletion, bumps the principal's token version to invalidate all outstanding
     * sessions, since the credential set that authorized those sessions has changed.
     *
     * @param principalId the unique identifier of the principal whose credential should be removed
     * @param type the type of credential to remove (e.g., OAUTH2, PASSWORD)
     * @param identifier the login identifier of the credential to remove
     */
    suspend fun deleteCredential(principalId: UUID, type: CredentialType, identifier: String)

    /**
     * Sends a verification email to the principal with the given ID, containing a
     * verification token link to confirm their email address.
     *
     * @param id the unique identifier of the principal to send the verification email to
     * @param requestOrigin the raw web origin the request came from (e.g. `https://app.example.com`), persisted
     *   with the verification token so the email link routes back to that host in a multi-host deployment.
     *   Validated against the allow-list when the link is built; null falls back to the default app origin.
     */
    suspend fun sendVerificationEmail(id: UUID, requestOrigin: String? = null)

    /**
     * Delivers the email-verification challenge MESSAGE for [profileId] — the link the recipient redeems to
     * prove control of the address. This is the email channel's send, exposed on the security contract so the
     * profile domain's email verifiable-attribute type can trigger it WITHOUT depending on the security
     * implementation's message types. Called by the generic verification framework when issuing a challenge;
     * the one-time token is already recorded on the email attribute (the template renders the link from it).
     */
    suspend fun sendEmailVerificationMessage(profileId: UUID)

    /** Emits the pipeline-backed welcome email after a newly-created account has a delivery profile. */
    suspend fun sendWelcomeMessage(profileId: UUID)

    /**
     * The email type's change reaction in the generic verification framework: asserts a principal may change
     * its verified login email to [newEmail], else throws. Rate-limits changes (one per window) and rejects
     * taking an address another principal has already verified. Does NOT touch the login identifier or the
     * uniqueness backstop — those are reconciled by [onEmailVerified] once [newEmail] is actually proven, so
     * a typo never strands the user. Called BEFORE the framework re-verifies the new address.
     */
    suspend fun assertEmailChangeAllowed(principalId: UUID, newEmail: String)

    /**
     * Runs the login-identity consequences after one of the principal's email attributes is VERIFIED (the
     * post-verification reaction the email channel hands to the generic verification framework): marks the
     * principal verified (opening the first-login gate) and reconciles email identity — registering proven
     * emails in the uniqueness backstop, and, if the login identifier had drifted to an address no longer
     * verified (a completed email change), moving login onto a proven address and releasing the stale one.
     */
    suspend fun onEmailVerified(principalId: UUID)

    /**
     * Authenticates a principal using a previously registered passkey credential.
     *
     * @param principalId the unique identifier of the principal owning the passkey
     * @param credentialId the base64url-encoded credential ID identifying which passkey was used
     * @param generateRefreshToken whether to issue a refresh token alongside the access token
     * @return a [LoginResponse] containing the issued tokens
     */
    suspend fun loginWithPasskey(principalId: UUID, credentialId: String, generateRefreshToken: Boolean = false): LoginResponse

    /**
     * Persists a new passkey credential for the given principal after successful WebAuthn registration.
     *
     * @param principalId the unique identifier of the principal registering the passkey
     * @param attributes the passkey credential attributes containing the public key and metadata
     * @return the persisted [PrincipalCredential]
     */
    suspend fun addPasskeyCredential(principalId: UUID, attributes: bosca.security.model.PasskeyCredentialAttributes): PrincipalCredential

    /**
     * Updates the sign counter on a passkey credential after successful authentication,
     * detecting potential credential cloning if the new counter is not strictly greater
     * than the stored value.
     *
     * @param credentialId the database ID of the credential record to update
     * @param newSignCount the sign counter value reported by the authenticator
     */
    suspend fun updatePasskeySignCount(credentialId: Long, newSignCount: Long)

    /**
     * Removes expired refresh tokens, exchange tokens, and login revocations, then clears
     * principal revocation flags that no longer have active revocation records.
     */
    suspend fun deleteExpiredRefreshToken()

    /**
     * Generates a single-use, short-lived exchange token for the given principal.
     *
     * Used during cross-domain OAuth2 redirects: when the OAuth2 callback domain differs
     * from the redirect target domain, this token is appended to the redirect URL so the
     * target domain can exchange it for a JWT without needing the originating domain's cookie.
     *
     * @param principalId the unique identifier of the authenticated principal
     * @param accountCreated whether the originating sign-in created the account (vs. authenticated an
     *        existing one); persisted with the token so the redeeming domain can surface it
     * @param originator the caller-supplied login request originator; persisted with the token so the
     *        redeeming domain can echo it back on the [LoginResponse]
     * @return the generated exchange token string
     */
    suspend fun createExchangeToken(principalId: UUID, accountCreated: Boolean = false, originator: String? = null): String

    /**
     * Validates and consumes a single-use exchange token, returning a login response
     * containing a JWT and refresh token.
     *
     * The exchange token is deleted after successful use to prevent replay attacks.
     * Tokens that are expired or already consumed will cause an error.
     *
     * @param exchangeToken the single-use token to exchange
     * @return a [LoginResponse] containing the issued tokens
     * @throws SecurityException if the token is invalid, expired, or already consumed
     */
    suspend fun loginWithExchangeToken(exchangeToken: String): LoginResponse
}

suspend fun SecurityService.impersonate(identifier: String): ImpersonatedAuthenticationContext {
    val principal = getPrincipalByIdentifier(identifier) ?: error("Principal with identifier $identifier not found")
    val groups = getPrincipalGroups(principal.id)
    return ImpersonatedAuthenticationContext(principal, groups)
}

/**
 * Creates an impersonated authentication context for the principal with the given ID.
 *
 * This is used by async job executors that need to perform actions on behalf of
 * the original caller whose principal ID was captured in the job payload.
 *
 * @param principalId the unique identifier of the principal to impersonate
 * @return an authentication context representing the impersonated principal
 * @throws IllegalStateException if no principal exists with the given ID
 */
suspend fun SecurityService.impersonate(principalId: UUID): ImpersonatedAuthenticationContext {
    val principal = getPrincipalById(principalId) ?: error("Principal with id $principalId not found")
    val groups = getPrincipalGroups(principal.id)
    return ImpersonatedAuthenticationContext(principal, groups)
}
