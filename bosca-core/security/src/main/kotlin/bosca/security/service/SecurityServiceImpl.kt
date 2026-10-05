package bosca.security.service

import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.CacheManager
import bosca.cache.ServiceCache
import bosca.cache.annotations.Serializer
import bosca.cache.serializers.LongKeySerializer
import bosca.cache.serializers.StringKeySerializer
import bosca.cache.serializers.UUIDKeySerializer
import bosca.cache.serializers.buildCacheKey
import bosca.cache.serializers.separateForCacheKey
import bosca.community.service.CommunityService
import bosca.db.afterCommit
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.di.annotation.ProviderName
import bosca.di.provide
import bosca.graphql.Batch
import bosca.graphql.CodedError
import bosca.security.SecureTokens
import bosca.security.routes.security.AuthRateLimiter
import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.attribute.model.getAttributeString
import bosca.profile.attribute.verification.AttributeVerificationService
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.service.OrganizationService
import bosca.profile.organization.service.process
import bosca.profile.profile.model.ProfileInput
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.PubSubService
import bosca.security.encryption.ArgonPassword
import bosca.security.encryption.ScryptPassword
import bosca.security.events.CredentialDeleted
import bosca.security.events.CredentialLinked
import bosca.security.events.AccountLinkEmailRequested
import bosca.security.events.EmailVerificationRequested
import bosca.security.events.EmailVerified
import bosca.security.events.GroupCreated
import bosca.security.events.GroupDeleted
import bosca.security.events.GroupUpdated
import bosca.security.events.PasskeyAdded
import bosca.security.events.PasswordChanged
import bosca.security.events.PasswordResetEmailRequested
import bosca.security.events.PasswordReset
import bosca.security.events.PasswordResetRequested
import bosca.security.events.PrincipalAddedToGroup
import bosca.security.events.PrincipalCreated
import bosca.security.events.PrincipalDeleted
import bosca.security.events.PrincipalMarkedDeleted
import bosca.security.events.PrincipalRemovedFromGroup
import bosca.security.events.PrincipalRestored
import bosca.security.events.PrincipalSignedIn
import bosca.security.events.PrincipalLoginsRevoked
import bosca.security.events.PrincipalUpdated
import bosca.security.events.PrincipalsMerged
import bosca.security.events.SecurityAlertEmailRequested
import bosca.security.events.SecurityEmailDetail
import bosca.security.events.WelcomeEmailRequested
import bosca.security.events.dispatch
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.CredentialAttributes
import bosca.security.model.CredentialPasswordAttributes
import bosca.security.model.CredentialType
import bosca.security.model.DuplicatedAccountIds
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.LinkProofMethod
import bosca.security.model.LoginResponse
import bosca.security.model.OAuth2CredentialAttributes
import bosca.security.model.PasskeyCredentialAttributes
import bosca.security.model.Principal
import bosca.security.model.PrincipalLogin
import bosca.security.model.PrincipalCredential
import bosca.security.model.PrincipalGroup
import bosca.security.model.ScryptCredentialAttributes
import bosca.security.model.SignupToken
import bosca.security.model.SimplePasswordAttributes
import bosca.security.model.Token
import bosca.security.oauth2.ThirdPartyUser
import bosca.security.repository.GroupRepository
import bosca.security.repository.PrincipalCredentialsRepository
import bosca.security.repository.PrincipalExchangeTokenRepository
import bosca.security.repository.PrincipalGroupRepository
import bosca.security.repository.PrincipalRefreshTokenRepository
import bosca.security.repository.PrincipalRepository
import bosca.security.repository.PrincipalEmailRepository
import bosca.serialization.JsonConverter.toJsonElement
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import com.auth0.jwt.JWT
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.Payload
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

private const val CACHE_KEY_SEPARATOR = "::"

private fun requireCacheSafeGroupName(name: String) {
    require(CACHE_KEY_SEPARATOR !in name) {
        "group name must not contain '$CACHE_KEY_SEPARATOR'"
    }
}

data class GroupId(
    val name: String,
    val type: GroupType
) {
    init {
        requireCacheSafeGroupName(name)
    }
}

data class GroupCacheKey(
    override val cacheName: String,
    override val key: GroupId
) : CacheKey<GroupId> {

    override fun toRemoteKey(prefix: Boolean) = buildCacheKey(prefix) {
        appendKeyPrefix("gid", cacheName)
        appendKeyPart(key.name)
        appendKeyPart(key.type)
    }
}

@Serializer("gid")
object GroupCacheKeySerializer : CacheKeySerializer<GroupId> {

    override fun toLocalKey(cacheName: String, value: GroupId): CacheKey<GroupId> {
        return GroupCacheKey(cacheName, value)
    }

    override fun fromRemoteKey(key: String): CacheKey<GroupId> {
        val keyParts = key.separateForCacheKey()
        return GroupCacheKey(keyParts[0], GroupId(keyParts[1], GroupType.valueOf(keyParts[2])))
    }
}

class MissingCredentials : SecurityException("missing credentials"), CodedError {
    // Collapsed with invalid-password so the client can't distinguish a missing account from a wrong password.
    override val code = "INVALID_CREDENTIALS"
}

/** Thrown when password authentication fails — same code as [MissingCredentials] (enumeration-resistant). */
class InvalidPassword : SecurityException("invalid password"), CodedError {
    override val code = "INVALID_CREDENTIALS"
}

/** Thrown when login is attempted against a principal whose account-level `verified` flag is still false. */
class PrincipalNotVerified : SecurityException("principal not verified"), CodedError {
    override val code = "PRINCIPAL_NOT_VERIFIED"
}

/**
 * Thrown when an email address specifically has not been verified — distinct from the account-level
 * [PrincipalNotVerified] gate that guards login. Reserved for email-attribute verification checks.
 */
class EmailNotVerified : SecurityException("email not verified"), CodedError {
    override val code = "EMAIL_NOT_VERIFIED"
}

/**
 * Thrown when a credential cannot be attached to a principal because its identifier already
 * belongs to a principal (the same one or a different one). Extends [SecurityException] so it
 * surfaces as a clean GraphQL/API error rather than a 500.
 */
class CredentialConflict : SecurityException("credential already in use"), CodedError {
    override val code = "CREDENTIAL_CONFLICT"
}

/**
 * Thrown by [SecurityServiceImpl.verifyEmailAvailableForSignup] when a sign-up would duplicate an
 * existing **verified** account. Carries the matched [email] and the [existingPrincipalId] so the
 * caller can route the user into account-linking with proof of ownership (the pending-link
 * challenge) rather than minting a second principal. Extends [SecurityException] so it surfaces as a
 * controlled auth error rather than a 500.
 */
class AccountLinkRequired(
    val email: String,
    val existingPrincipalId: UUID,
    /** Single-use token identifying the pending link; the caller drives the proof challenge with it. */
    val token: String = "",
    /** The proof methods available for the existing account (password and/or email). */
    val methods: List<LinkProofMethod> = emptyList(),
) : SecurityException("an account already exists for this email; sign in to link this method"), CodedError {
    override val code = "ACCOUNT_LINK_REQUIRED"
}

/**
 * Thrown when a user proves control of an email (clicks a valid verification link) but that address is ALREADY
 * verified-owned by a DIFFERENT principal — two legitimate accounts on one mailbox, whoever verifies second.
 * The verification is rolled back (we never create a second verified owner of one email); this distinct code
 * lets the client tell the user the address is already in use on another account — sign in there or use
 * forgot-password — instead of a meaningless generic "credential conflict".
 */
class EmailAlreadyVerified(cause: Throwable? = null) : SecurityException("this email is already verified on another account"), CodedError {
    init { cause?.let { initCause(it) } }
    override val code = "EMAIL_ALREADY_VERIFIED"
}

/**
 * Walks an exception's cause chain looking for a PostgreSQL unique-constraint violation (SQLState
 * 23505). Used as the race backstop when a uniqueness pre-check passes but a concurrent insert wins.
 */
private fun isDuplicateKeyException(e: Throwable): Boolean {
    var cause: Throwable? = e
    while (cause != null) {
        if (cause is java.sql.SQLException && cause.sqlState == "23505") return true
        cause = cause.cause
    }
    return false
}

private fun rethrowCredentialFailure(e: Exception): Nothing {
    if (isDuplicateKeyException(e)) throw CredentialConflict()
    throw e
}

private fun CredentialType.isPasswordType(): Boolean =
    this == CredentialType.PASSWORD || this == CredentialType.PASSWORD_SCRYPT

private fun GroupType?.orSystem(): GroupType =
    this ?: GroupType.SYSTEM

private fun String.emittedIf(enabled: Boolean): String? =
    if (enabled) this else null

private fun String?.normalizedEmail(): String? {
    val value = this ?: return null
    val normalized = value.lowercase().trim()
    return if (normalized.isBlank()) null else normalized
}

private fun JsonElement?.withAuditAttribute(name: String, value: String): JsonObject =
    JsonObject((this as? JsonObject).orEmpty() + (name to JsonPrimitive(value)))

/**
 * JWT claim name for the per-principal token generation counter.
 *
 * The value is mirrored in `principals.token_version`; a mismatch at
 * request verification time means the token was minted before the
 * principal's last session-invalidating action (password change,
 * reset, identifier change, ...) and must be rejected.
 *
 * Tokens minted before this claim existed carry no value; the
 * verifier treats an absent claim as generation 0 so pre-migration
 * tokens stay valid until their owner performs an action that
 * advances the counter.
 */
private const val TOKEN_VERSION_CLAIM = "tver"
private const val LOGIN_ID_CLAIM = "lid"
private const val LOGIN_REVOCATION_CHANNEL = "bosca.security.login-revocation"

@ServiceImplementation
class SecurityServiceImpl(
    private val groupRepository: GroupRepository,
    private val principalRepository: PrincipalRepository,
    private val principalGroupsRepository: PrincipalGroupRepository,
    private val principalRefreshTokensRepository: PrincipalRefreshTokenRepository,
    private val principalExchangeTokenRepository: PrincipalExchangeTokenRepository,
    private val credentialsRepository: PrincipalCredentialsRepository,
    private val json: Json,
    @ProviderName("argon2PasswordEncoder")
    private val argonPasswordEncoder: PasswordEncoder<ArgonPassword>,
    @ProviderName("scryptPasswordEncoder")
    private val scryptPasswordEncoder: ObjectProvider<PasswordEncoder<ScryptPassword>>,
    private val securityConfiguration: ObjectProvider<SecurityConfiguration>,
    private val profileService: ObjectProvider<ProfileService>,
    private val organizationService: ObjectProvider<OrganizationService>,
    private val communityService: ObjectProvider<CommunityService>,
    // Injected as an ObjectProvider (lazy, like the other cross-module services above) to break the DI cycle:
    // AttributeVerificationService's VerifiableAttributeType registry includes EmailVerifiableAttribute, whose
    // onVerified reaction calls back into this SecurityService.
    private val attributeVerificationService: ObjectProvider<AttributeVerificationService>,
    private val principalEmailRepository: PrincipalEmailRepository,
    private val thirdPartyTokenVerifier: ThirdPartyTokenVerifier,
    private val pubSubService: PubSubService,
) : SecurityService {

    private val loginRevocationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** L1: bounded per-process cache. L2 is [loginRevocationCache]; PostgreSQL is its resolver. */
    private val loginRevocationL1 = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .build<Long, Boolean>()

    /** Pub/sub safety net while another process may still hold a cached pre-revocation Principal. */
    private val principalsWithLoginRevocationsL1 = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .build<UUID, Boolean>()

    private val loginRevocationCache = ServiceCache<Long, String>(
        "security:login:revocation",
        LongKeySerializer,
        expiration = 30.minutes,
    ) {
        if (principalRepository.isLoginRevoked(it)) "1" else "0"
    }

    init {
        loginRevocationScope.launch {
            pubSubService.subscribe(LOGIN_REVOCATION_CHANNEL, String.serializer()).collect { message ->
                runCatching {
                    val (principalId, loginId) = message.message.split(':', limit = 2)
                    UUID.parse(principalId) to loginId.toLong()
                }
                    .onSuccess { (principalId, loginId) ->
                        principalsWithLoginRevocationsL1.put(principalId, true)
                        loginRevocationL1.put(loginId, true)
                    }
                    .onFailure { log.warn("Ignoring malformed login revocation message", it) }
            }
        }
    }

    private val groupByNameCache = ServiceCache("security:groups:name:type", GroupCacheKeySerializer) {
        groupRepository.getGroupByName(it.name, it.type)
    }

    private val groupByIdCache = ServiceCache("security:groups:id", UUIDKeySerializer) {
        groupRepository.getById(it)
    }

    private val principalIdCache = ServiceCache("security:principal:id", UUIDKeySerializer, { keys, batch ->
        principalRepository.getPrincipalsById(keys).forEach {
            batch.setData(it.id, it)
        }
    }) {
        principalRepository.getPrincipalById(it)
    }

    private val principalGroupsCache = ServiceCache(
        "security:principal:groups",
        UUIDKeySerializer,
        { principalIds, batch ->
            val memberships = principalGroupsRepository.getPrincipalGroups(principalIds)
            val groupIds = memberships.map { it.groupId }.distinct()
            val groupsById = if (groupIds.isEmpty()) {
                emptyMap()
            } else {
                groupRepository.getByIds(groupIds).associateBy { it.id }
            }
            val membershipsByPrincipal = memberships.groupBy { it.principal }
            for (principalId in principalIds) {
                val groups = membershipsByPrincipal[principalId].orEmpty().map { membership ->
                    checkNotNull(groupsById[membership.groupId]) {
                        "security group ${membership.groupId} is missing for principal $principalId"
                    }
                }
                batch.setData(principalId, groups)
            }
        },
    ) {
        principalGroupsRepository.getPrincipalGroups(it)
    }

    private suspend fun removeFromCache(id: UUID) {
        principalIdCache.remove(id)
        principalGroupsCache.remove(id)
    }

    private suspend fun isLoginRevoked(loginId: Long): Boolean {
        loginRevocationL1.getIfPresent(loginId)?.let { return it }
        val revoked = loginRevocationCache.get(loginId) == "1"
        loginRevocationL1.put(loginId, revoked)
        return revoked
    }

    private suspend fun cacheLoginRevocation(principalId: UUID, loginId: Long) {
        principalsWithLoginRevocationsL1.put(principalId, true)
        loginRevocationL1.put(loginId, true)
        loginRevocationCache.put(loginId, "1")
        try {
            pubSubService.publish(LOGIN_REVOCATION_CHANNEL, String.serializer(), "$principalId:$loginId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to publish login revocation for {}; other instances will observe it after their L1 entry expires", loginId, e)
        }
    }

    /**
     * Invalidates every outstanding access and refresh token for the
     * given principal in a single step.
     *
     * Bumps `principals.token_version` so all previously-issued JWTs
     * fail the `tver` claim check on their next request, and deletes
     * every row in `principal_refresh_tokens` for the same principal
     * so a compromised refresh token cannot be used to mint a fresh
     * (correctly-versioned) JWT and slip past the bump. Finally
     * evicts the principal cache so the new version is visible to
     * the next in-flight verification without waiting for TTL.
     *
     * Must be called inside a `transaction { ... }` block — callers
     * that mutate credentials or identifiers should invoke this as
     * the last step of the same transaction so a rollback cleanly
     * reverts both the credential change and the invalidation.
     */
    private suspend fun bumpTokenVersion(principalId: UUID) {
        principalRepository.incrementTokenVersion(principalId)
        principalRefreshTokensRepository.deleteByPrincipalId(principalId)
        principalRepository.revokeSessions(principalId)
        removeFromCache(principalId)
    }

    private fun generateSecureToken(): String = SecureTokens.generate()

    override suspend fun getMaxTokenAgeInSeconds(): Long {
        return securityConfiguration.get().expirationTimeInSeconds
    }

    override suspend fun createJwtToken(
        principal: Principal,
        claims: Map<String, Any>
    ): DecodedJWT {
        val configuration = securityConfiguration.get()
        val string = JWT.create()
            .withAudience(configuration.audience)
            .withIssuer(configuration.issuer)
            .withSubject(principal.id.toString())
            .withExpiresAt(Instant.ofEpochMilli(System.currentTimeMillis() + (configuration.expirationTimeInSeconds * 1000)))
            .withIssuedAt(Instant.now())
            .withPayload(claims)
            // Embed the current per-principal token generation so the
            // request-side verifier can mass-invalidate this token (and
            // every other token minted at the same generation) by
            // bumping `principals.token_version`. See
            // `authenticateWithPayload` for the matching check and
            // `bumpTokenVersion` for the bump side of the contract.
            .withClaim(TOKEN_VERSION_CLAIM, principal.tokenVersion)
            .sign(configuration.algorithm)
        return JWT.decode(string)
    }

    override suspend fun getGroupByName(name: String, type: GroupType) = groupByNameCache.get(GroupId(name, type))

    override suspend fun getPrincipalsByGroup(group: Group): List<Principal> {
        val principalIds = principalGroupsRepository.getGroupsByPrincipalId(group.id)
        return principalIdCache.getAll(principalIds.map { it.principal }).mapNotNull { it }
    }

    override suspend fun getPrincipalGroups(principalId: UUID): List<Group> {
        return principalGroupsCache.get(principalId).orEmpty()
    }

    override suspend fun getPrincipalGroups(principalIds: List<UUID>): Map<UUID, List<Group>> {
        val distinctIds = principalIds.distinct()
        if (distinctIds.isEmpty()) return emptyMap()
        val groups = principalGroupsCache.getAll(distinctIds)
        return distinctIds.mapIndexed { index, principalId ->
            principalId to groups[index].orEmpty()
        }.toMap()
    }

    override suspend fun addPrincipalGroup(principalId: UUID, groupId: UUID): Unit = transaction {
        principalGroupsRepository.add(PrincipalGroup(principalId, groupId))
        removeFromCache(principalId)
        PrincipalAddedToGroup(principalId, groupId).dispatch()
    }

    override suspend fun removePrincipalGroup(principalId: UUID, groupId: UUID): Unit = transaction {
        principalGroupsRepository.deleteByPrincipalAndGroupId(principalId, groupId)
        removeFromCache(principalId)
        PrincipalRemovedFromGroup(principalId, groupId).dispatch()
    }

    override suspend fun getGroupById(id: UUID) = groupByIdCache.get(id) ?: error("Group not found")

    override suspend fun getGroups(type: GroupType?, offset: Long, limit: Int): List<Group> = groupRepository.getAll(type.orSystem(), offset, limit)

    override suspend fun findGroups(nameOrDescription: String, type: GroupType?, offset: Long, limit: Int): List<Group> {
        return groupRepository.findByNameOrDescription(nameOrDescription, type.orSystem(), offset, limit)
    }

    override suspend fun addGroup(group: Group): Group {
        requireCacheSafeGroupName(group.name)
        return transaction {
            val added = groupRepository.add(group)
            GroupCreated(added.id, added.name, added.type).dispatch()
            added
        }
    }

    override suspend fun editGroup(group: Group): Group {
        requireCacheSafeGroupName(group.name)
        return transaction {
            val updated = groupRepository.update(group)
            GroupUpdated(updated.id, updated.name, updated.type).dispatch()
            updated
        }
    }

    override suspend fun deleteGroup(id: UUID): Unit = transaction {
        groupRepository.deleteById(id)
        GroupDeleted(id).dispatch()
    }

    override suspend fun getByVerificationToken(token: String) = principalRepository.getByVerificationToken(token)

    override suspend fun verifyWithToken(token: String) {
        // Redeeming an email-verification link is just confirming a verifiable attribute: the generic
        // framework marks the attribute verified by its token and runs the email reaction
        // ([EmailVerifiableAttribute.onVerified] -> [onEmailVerified]), which opens the login gate and
        // reconciles identity. The attribute token is the source of truth now — no principal-token lookup.
        attributeVerificationService.get().confirmVerification(token)
    }

    override suspend fun getPrincipals(offset: Long, limit: Int) = principalRepository.getPrincipals(offset, limit)

    override suspend fun getPrincipals(offset: Long, limit: Int, includeDeleted: Boolean) =
        principalRepository.getPrincipals(offset, limit, includeDeleted)

    override suspend fun getPrincipalById(id: UUID) = principalIdCache.get(id)

    private suspend fun requirePrincipal(id: UUID): Principal =
        requirePrincipal(id, "principal not found")

    private suspend fun requirePrincipal(id: UUID, message: String): Principal =
        getPrincipalById(id) ?: error(message)

    private suspend fun getVerifiedPrincipalById(id: UUID): Principal? =
        getPrincipalById(id)?.takeIf { it.verified }

    override suspend fun getPrincipalsById(id: List<UUID>): List<Principal> {
        return principalIdCache.getAll(id).mapNotNull { it }
    }

    override suspend fun getPrincipalByIdentifier(identifier: String, type: CredentialType): Principal? {
        val id = principalRepository.getPrincipalIdByIdentifierAndType(identifier, type) ?: return null
        return getPrincipalById(id)
    }

    override suspend fun getPrincipalByIdentifier(identifier: String): Principal? {
        val id = principalRepository.getPrincipalIdByIdentifier(identifier) ?: return null
        return getPrincipalById(id)
    }

    override suspend fun getPrincipalByEmail(email: String): Principal? {
        val profiles = profileService.get().getProfilesByEmail(email)
        // distinctBy id: a single principal can own multiple profiles carrying the same email (e.g. after
        // a merge re-parents a duplicate's email-bearing profile onto the survivor) — that is ONE principal,
        // not a collision.
        val principals = profiles.mapNotNull {
            getVerifiedPrincipalById(it.principal ?: return@mapNotNull null)
        }.distinctBy { it.id }
        when {
            principals.size == 1 -> return principals.single()
            principals.size > 1 -> {
                // More than one VERIFIED principal claims this email via its profile attribute — a dirty/legacy
                // state predating the uniqueness backstop. The `principal_emails` PK is the authoritative single
                // owner, so use it to break the tie rather than guessing or refusing outright.
                val owner = resolveBackstopOwner(email)
                if (owner != null) return owner
                log.error("multiple verified principals claim email and no backstop owner; refusing to resolve: {}", principals.map { it.id })
                return null
            }
        }
        // No VERIFIED attribute matched, but the email may be proven at the PRINCIPAL level — an OAuth-verified
        // address, or a row from the V160 backfill — without the profile attribute marked verified. The backstop
        // row is itself proof of ownership, so resolve through it (keeps signup/change collision detection and
        // password-reset routing consistent with the uniqueness backstop).
        return resolveBackstopOwner(email)
    }

    /** Resolves the verified principal that owns [email] via the uniqueness backstop, or null if none/unverified. */
    private suspend fun resolveBackstopOwner(email: String): Principal? {
        val ownerId = principalEmailRepository.getByEmail(email.lowercase().trim()) ?: return null
        return getVerifiedPrincipalById(ownerId)
    }

    /**
     * Registers the normalized, non-blank [email] as verified-owned by [principalId] in the uniqueness backstop.
     * If the
     * email is already registered to a DIFFERENT principal, throws [CredentialConflict] — the
     * database-level backstop behind [verifyEmailAvailableForSignup]. Idempotent for the same
     * principal. Callers derive this value only from normalized provider-verified or verified profile
     * attributes. Must be called inside the principal's create/verify transaction so a conflict rolls the
     * whole operation back.
     */
    private suspend fun registerPrincipalEmail(email: String, principalId: UUID) {
        val normalized = email.lowercase().trim()
        principalEmailRepository.getByEmail(normalized)?.let { current ->
            if (current != principalId) throw CredentialConflict()
            return
        }
        try {
            principalEmailRepository.add(normalized, principalId)
        } catch (e: Exception) {
            // Lost a race: another principal registered the email between the pre-check and the insert.
            // Don't re-query to identify the winner — the failed INSERT has aborted this transaction, so any
            // further statement on the connection raises 25P02 ("current transaction is aborted"). A unique
            // violation here means the email is already claimed, so surface a clean conflict.
            rethrowCredentialFailure(e)
        }
    }

    /**
     * The principal's PROVEN email addresses — the values of its `bosca.profiles.email` attributes whose
     * `verified` flag is set. The flag is the source of truth: it is set server-side ONLY at proven-control
     * points (an email-link confirmation redeemed via [ProfileService.verifyByToken], or a provider-asserted
     * verified email at OAuth sign-up), never from client input — so reading verified attributes, rather than
     * the raw client-settable value, is what keeps a verified account from squatting a victim's address.
     */
    private suspend fun getVerifiedPrincipalEmails(principalId: UUID): List<String> =
        profileService.get().getByPrincipal(principalId)
            .flatMap { profileService.get().getAttributes(it.id) }
            .filter { it.typeId == "bosca.profiles.email" && it.verified }
            .mapNotNull { it.getAttributeString("email").normalizedEmail() }
            .filter { it.contains("@") }
            .distinct()

    /**
     * Registers every PROVEN email the principal owns into the uniqueness backstop (`principal_emails`). A
     * conflict means the email is already owned by another principal and rolls back the enclosing
     * transaction. Idempotent; callers must mark the attributes verified BEFORE calling this.
     */
    private suspend fun registerVerifiedPrincipalEmails(principalId: UUID) {
        getVerifiedPrincipalEmails(principalId).forEach { registerPrincipalEmail(it, principalId) }
    }

    override suspend fun onEmailVerified(principalId: UUID): Unit = transaction {
        val principal = getPrincipalById(principalId) ?: return@transaction
        // Open the first-login gate. Do NOT touch `verification_token`: the email-verify token now lives on the
        // attribute (already cleared by verifyByToken), and `principals.verification_token` is exclusively the
        // password-reset token — clearing it here would silently kill an in-flight reset. Only write if needed.
        if (!principal.verified) {
            editPrincipal(principal.copy(verified = true))
        }
        reconcilePrincipalEmails(principalId)
        EmailVerified(principalId).dispatch()
    }

    /**
     * Reconciles the principal's email IDENTITY to its currently-PROVEN emails. Runs only AFTER an email is
     * verified (from [onEmailVerified]), so login and the backstop never reflect an unproven address:
     *  - registers each verified email in the backstop (idempotent);
     *  - RELEASES every backstop row the principal owns that is no longer one of its proven emails — the user
     *    moved off it. This runs for ALL accounts regardless of login type, so a stale verified email is never
     *    orphaned (an orphaned row would make that address permanently unclaimable by anyone, including its
     *    real owner);
     *  - if the login identifier points at an email the principal no longer has verified — i.e. the user
     *    changed their login email and just proved the new one — moves login onto a verified address
     *    (re-checking uniqueness + invalidating sessions). The stale login email, if registered, was released
     *    by the step above.
     *
     * This is why [assertEmailChangeAllowed] does NOT touch the login identifier or backstop at change time:
     * a typo'd new address simply never gets proven, so login stays put and nothing is released until proof
     * arrives.
     */
    private suspend fun reconcilePrincipalEmails(principalId: UUID) {
        // Before claiming this principal's proven emails, check whether any is ALREADY verified-owned by a
        // DIFFERENT principal — two legitimate accounts on one mailbox, whoever verifies second. That is not a
        // dead-end: redeeming the token just proved control of the address, so route the user into the
        // proof-gated account-link flow (attach this principal's credential to the owner, then retire this
        // duplicate) rather than erroring. This is an explicit pre-check — not a catch on the backstop insert
        // below — because a failed INSERT aborts the transaction (25P02), leaving no healthy connection to read
        // the duplicate's credential or mint the pending link. The throw rolls back the verification, so we
        // never create a second verified owner of one email.
        getVerifiedPrincipalEmails(principalId).forEach { email ->
            val owner = principalEmailRepository.getByEmail(email) ?: return@forEach
            if (owner != principalId) captureVerifyTimeAccountLink(duplicateId = principalId, survivorId = owner, email = email)
        }
        try {
            registerVerifiedPrincipalEmails(principalId)
        } catch (e: CredentialConflict) {
            // Lost a race: the email was claimed by another principal between the pre-check above and the
            // insert. The transaction is now aborted, so we can't mint a pending link here — surface the
            // specific, actionable code (the throw still rolls back the verification). Chain the original
            // conflict so the cause/stack survives for diagnostics.
            throw EmailAlreadyVerified(e)
        }
        // Keep the ORDERED list (priority desc, created desc — getVerifiedPrincipalEmails preserves it) for the
        // login-identifier move, plus a set for O(1) membership. A principal with several verified emails (e.g.
        // after a merge) must move login DETERMINISTICALLY onto its primary address — picking from a HashSet
        // would choose an arbitrary one and silently invalidate sessions onto an unexpected email.
        val verifiedEmails = getVerifiedPrincipalEmails(principalId)
        val verified = verifiedEmails.toSet()
        // The backstop rows the principal no longer proves — addresses it just moved off of. Capture them
        // BEFORE releasing, because whether the login identifier should follow depends on it having BEEN one
        // of these (see below).
        val released = principalEmailRepository.getEmailsByPrincipal(principalId)
            .map { it.lowercase().trim() }
            .filter { it !in verified }
        released.forEach { principalEmailRepository.delete(it, principalId) }
        val principal = getPrincipalById(principalId) ?: return
        // Move the login identifier onto a proven address ONLY when a password credential's identifier is one of
        // the just-vacated verified emails (it's in `released`) — i.e. the user changed their LOGIN email and
        // proved a new one. Do NOT move it when:
        //   - the identifier is a username ("admin") — never an email, never in `released`; or
        //   - the identifier is an INDEPENDENT alternate login email that was never one of the principal's
        //     verified profile emails — changing some OTHER profile email must not silently rename the login.
        // `released` (the principal's just-vacated verified emails) is exactly that "was-the-login-email" history,
        // so it distinguishes both cases without guessing from the "@" shape. Resolve the EXACT matched credential
        // and move only that one — `principal_credentials` has no row order, so an account with multiple password
        // credentials must not have an arbitrary firstOrNull renamed.
        val changedLogin = getCredentials(principal).firstOrNull {
            it.type.isPasswordType() &&
                it.attributes.identifier.lowercase().trim() in released
        } ?: return
        verifiedEmails.firstOrNull()?.let { provenEmail -> updateCredentialIdentifier(principalId, changedLogin, provenEmail) }
    }

    /**
     * A verify-time collision: [duplicateId] just proved control of [email], but it is already verified-owned
     * by [survivorId]. Rather than dead-ending with [EmailAlreadyVerified], capture a pending account-link that
     * will attach the duplicate's sign-in credential to the survivor (AFTER the survivor's ownership is proven
     * via the existing link challenge) and retire the duplicate, then throw [AccountLinkRequired] so the caller
     * drives that proof. Requiring the survivor's proof is what makes this safe: it stops a pre-registered
     * duplicate (an attacker setting a password on a victim's address before the victim's account exists) from
     * binding its credential onto the victim's account merely because the victim later clicks the verify link.
     *
     * Falls back to [EmailAlreadyVerified] when there is nothing safe to link: the survivor can't be resolved
     * as verified, the duplicate has no password credential to move, or the survivor already holds a password
     * (two genuine password accounts on one address — a second password can't be attached; the user should
     * sign in to the survivor or reset). Mirrors [verifyEmailAvailableForSignup]'s "nothing to link" rule.
     */
    private suspend fun captureVerifyTimeAccountLink(duplicateId: UUID, survivorId: UUID, email: String): Nothing {
        val normalizedEmail = email.lowercase().trim()
        val survivor = getVerifiedPrincipalById(survivorId) ?: throw EmailAlreadyVerified()
        val duplicate = getPrincipalById(duplicateId) ?: throw EmailAlreadyVerified()
        // The duplicate's sign-in credential to move onto the survivor. Only a password can strand a duplicate
        // here (an OAuth identity already de-dupes at sign-in), so that's the one to link.
        val duplicateCredential = getCredentials(duplicate).firstOrNull {
            it.type.isPasswordType()
        } ?: throw EmailAlreadyVerified()
        val survivorCredentials = getCredentials(survivor)
        if (survivorCredentials.any { it.type.isPasswordType() }) {
            throw EmailAlreadyVerified()
        }
        val token = generateSecureToken()
        // Pin the email-proof recipient now, while we hold the matched survivor + address (see
        // resolveAccountLinkDeliveryProfileId), so the proof can't be re-routed by a later email change.
        val deliveryProfileId = resolveAccountLinkDeliveryProfileId(survivorId, normalizedEmail)
        pendingLinkStore().putPendingLink(
            token,
            PendingLink(
                targetPrincipalId = survivorId,
                email = normalizedEmail,
                credentialType = duplicateCredential.type,
                credentialAttributes = duplicateCredential.attributesJson,
                retirePrincipalId = duplicateId,
                deliveryProfileId = deliveryProfileId,
            ),
        )
        throw AccountLinkRequired(normalizedEmail, survivorId, token, computeProofMethods(survivorCredentials))
    }

    /**
     * Retires [duplicateId] into [survivorId] WITHOUT merging its profile data — contrast [mergePrincipals],
     * which re-parents the duplicate's profiles. Deletes the duplicate's credentials so their globally-unique
     * identifiers free up for the survivor, discards its (brand-new, content-less) profiles, drops its
     * uniqueness-backstop rows, and marks the principal retired + anonymous so it leaves verified-email lookups
     * and can no longer log in. Called by the verify-time account-link completion, inside the link's
     * transaction, so any later failure rolls the whole retirement back.
     */
    private suspend fun retireDuplicateDiscardingProfiles(duplicateId: UUID, survivorId: UUID) {
        val duplicate = getPrincipalById(duplicateId) ?: return
        getCredentials(duplicate).forEach { credentialsRepository.delete(duplicateId, it.type, it.attributes.identifier) }
        profileService.get().getByPrincipal(duplicateId).forEach { profileService.get().delete(it.id) }
        principalEmailRepository.deleteByPrincipal(duplicateId)
        // Invalidate any outstanding sessions for the retired account, then record the merge target for audit.
        bumpTokenVersion(duplicateId)
        val retiredAttributes = duplicate.attributes.withAuditAttribute("retiredInto", survivorId.toString())
        editPrincipal(
            duplicate.copy(
                verified = false,
                anonymous = true,
                primaryProfileId = null,
                attributes = retiredAttributes,
            )
        )
    }

    override suspend fun verifyEmailAvailableForSignup(email: String?, pendingCredential: CredentialAttributes) {
        val normalizedEmail = email.normalizedEmail() ?: return
        // getPrincipalByEmail only resolves *verified* accounts, so an unverified account that happens
        // to share this email does not block the sign-up — the verified identity legitimately wins, and
        // any leftover unverified duplicate is left for admin merge / the uniqueness backstop.
        val existing = getPrincipalByEmail(normalizedEmail) ?: return
        // Only offer a link when the sign-up would add a genuinely NEW kind of sign-in method to the
        // existing account (a password onto an OAuth-only account, or an OAuth identity onto a password
        // account). If the account already has this kind of credential there is nothing to link — a
        // password sign-up against an account that already has a password is just a duplicate attempt —
        // so fail outright and let the user sign in (or reset) instead of minting a pointless challenge.
        // A third-party identity is always new here: login already matched any existing provider id.
        // Load the existing account's credentials ONCE — reused for both the "nothing to link" check and
        // the proof methods below (avoids a second getByPrincipalId on the collision path).
        val existingCredentials = credentialsRepository.getByPrincipalId(existing.id)
        if (pendingCredential.type.isPasswordType()) {
            if (existingCredentials.any { it.type.isPasswordType() }) {
                throw CredentialConflict()
            }
        }
        // Capture the new sign-in method in its FINAL stored form (passwords encoded) so confirmation
        // can attach it verbatim — and so a raw password never lingers in the cache.
        val finalAttributes = encodeIfPassword(pendingCredential)
        val token = generateSecureToken()
        val storedCredential = PrincipalCredential(principal = existing.id, attributes = finalAttributes)
        // Pin the email-proof recipient now, while we hold the matched survivor + address (see
        // resolveAccountLinkDeliveryProfileId), so the proof can't be re-routed by a later email change.
        val deliveryProfileId = resolveAccountLinkDeliveryProfileId(existing.id, normalizedEmail)
        pendingLinkStore().putPendingLink(
            token,
            PendingLink(
                targetPrincipalId = existing.id,
                email = normalizedEmail,
                credentialType = storedCredential.type,
                credentialAttributes = storedCredential.attributesJson,
                deliveryProfileId = deliveryProfileId,
            ),
        )
        throw AccountLinkRequired(normalizedEmail, existing.id, token, computeProofMethods(existingCredentials))
    }

    override suspend fun getPrincipalLastLogin(principalId: UUID): OffsetDateTime? {
        return principalRepository.getLastLogin(principalId)
    }

    override suspend fun getPrincipalLogins(principalId: UUID, offset: Long, limit: Int): List<PrincipalLogin> {
        return principalRepository.getLogins(principalId, offset, limit)
    }

    override suspend fun revokePrincipalLogin(principalId: UUID, loginId: Long): PrincipalLogin? = transaction {
        val login = principalRepository.revokeLogin(principalId, loginId)
        if (login == null) {
            principalRefreshTokensRepository.deleteByLoginId(principalId, loginId)
        } else {
            principalRefreshTokensRepository.deleteByLoginId(principalId, login.id)
            val expiresAt = OffsetDateTime.now().plusSeconds(securityConfiguration.get().expirationTimeInSeconds)
            principalRepository.addLoginRevocation(principalId, login.id, expiresAt)
            principalRepository.markHasLoginRevocations(principalId)
            afterCommit {
                removeFromCache(principalId)
                cacheLoginRevocation(principalId, login.id)
            }
            PrincipalLoginsRevoked(principalId, login.id).dispatch()
        }
        login
    }

    override suspend fun addPrincipalsToBatch(batch: Batch<UUID, Principal>) {
        principalIdCache.addToBatch(batch)
    }

    override suspend fun getCredentials(principal: Principal): List<PrincipalCredential> {
        return credentialsRepository.getByPrincipalId(principal.id)
    }

    override suspend fun getCredentials(principal: Principal, type: CredentialType): List<PrincipalCredential> {
        return credentialsRepository.getByPrincipalId(principal.id).filter { it.type == type }
    }

    override suspend fun addPrincipal(
        principal: Principal,
        credential: CredentialAttributes,
        groups: List<Group>,
        addPrincipalGroup: Boolean,
        originator: String?
    ): AuthenticatedPrincipal = transaction {
        var principal = principal
        // Do NOT stamp principals.verification_token at sign-up. Email verification records its one-time token
        // on the email ATTRIBUTE (sendVerificationEmail -> requestVerification -> setVerificationToken), and the
        // link is rendered from that attribute token — the principal column is never read by verification.
        // It is now exclusively the password-reset token (minted on demand by forgotPassword). Stamping one
        // here would be vestigial and, since onEmailVerified no longer clears it, would linger after the user
        // verifies as a token resetPassword (which checks only `verified`) accepts — a sign-up-minted reset
        // credential. So leave it null.
        // Centralized duplicate-identifier guard: every sign-up entry point (GraphQL, REST, OAuth create)
        // gets a clean CredentialConflict here instead of a raw ix_principal_identifier 500. Pre-check on
        // the raw identifier (before the expensive password hash) plus a race backstop on the insert.
        if (getPrincipalByIdentifier(credential.identifier) != null) throw CredentialConflict()
        principal = principalRepository.add(principal)
        try {
            credentialsRepository.add(PrincipalCredential(principal = principal.id, attributes = encodeIfPassword(credential), originator = originator))
        } catch (e: Exception) {
            rethrowCredentialFailure(e)
        }
        if (addPrincipalGroup) {
            val group = Group(name = "${principal.id}.user", description = "${principal.id} User", type = GroupType.PRINCIPAL)
            val newGroup = addGroup(group)
            addPrincipalGroup(principal.id, newGroup.id)
        }
        groups.forEach {
            addPrincipalGroup(principal.id, it.id)
        }
        PrincipalCreated(principal.id, principal.verified).dispatch()
        AuthenticatedPrincipal(
            principal,
            groups,
        )
    }

    override suspend fun linkCredentialToPrincipal(
        principalId: UUID,
        credential: CredentialAttributes,
    ): PrincipalCredential = attachCredential(principalId, encodeIfPassword(credential))

    /** Encodes a password credential's password for storage; other credential types pass through unchanged. */
    private suspend fun encodeIfPassword(credential: CredentialAttributes): CredentialAttributes =
        if (credential is SimplePasswordAttributes) {
            credential.withPassword(argonPasswordEncoder.encode(credential.password))
        } else {
            credential
        }

    /**
     * Attaches an already-FINAL credential (passwords pre-encoded) to a principal: rejects an
     * identifier already in use and inserts the row. Attaching is additive, so it deliberately does
     * NOT invalidate the principal's existing sessions. Shared by [linkCredentialToPrincipal] and the
     * account-link confirmation flow.
     */
    private suspend fun attachCredential(principalId: UUID, finalAttributes: CredentialAttributes): PrincipalCredential = transaction {
        val principal = requirePrincipal(principalId)
        if (principal.anonymous) throw SecurityException("principal is anonymous")
        // ix_principal_identifier makes the identifier globally unique. Pre-check the current owner:
        val existingOwner = getPrincipalByIdentifier(finalAttributes.identifier, finalAttributes.type)
        if (existingOwner != null) {
            // Already owned by THIS principal → attaching is a no-op (e.g. a password sign-up that
            // collides with the same account's existing password, or re-connecting an already-linked
            // provider). Return the existing credential instead of failing after a valid proof.
            if (existingOwner.id == principalId) {
                // SQL matched on lower(identifier); fall back to a type-only match so a Postgres-lower()
                // vs Kotlin-ignoreCase divergence on a non-ASCII identifier can't NoSuchElementException
                // after a valid proof.
                val ownerCredentials = getCredentials(principal)
                return@transaction ownerCredentials.firstOrNull {
                    it.type == finalAttributes.type &&
                        it.attributes.identifier.trim().equals(finalAttributes.identifier.trim(), ignoreCase = true)
                } ?: ownerCredentials.firstOrNull { it.type == finalAttributes.type }
                    ?: error("credential lookup inconsistency for identifier ${finalAttributes.identifier}")
            }
            // Owned by a DIFFERENT principal → genuine conflict; never steal an identity.
            throw CredentialConflict()
        }
        // At most ONE password credential per principal: the password is the account's single login secret, and
        // login / updatePassword / updateIdentifier / reset all resolve "the" password credential — a second one
        // makes "the" ambiguous (and lets the identifier-follow rename the wrong row). verifyEmailAvailableForSignup
        // blocks password-onto-password up front; this is the backstop on the actual attach (also covers a race
        // where a password was added between that check and the proof). A partial unique index enforces it at the
        // schema too. Passkeys / OAuth / API tokens are unaffected — a principal may hold several of those.
        if (finalAttributes.type.isPasswordType()) {
            if (getCredentials(principal).any { it.type.isPasswordType() }) {
                throw CredentialConflict()
            }
        }
        val stored = try {
            credentialsRepository.add(PrincipalCredential(principal = principalId, attributes = finalAttributes))
        } catch (e: Exception) {
            rethrowCredentialFailure(e)
        }
        // Attaching a new sign-in method is purely additive: it neither invalidates an existing
        // credential proof nor changes who the principal is, so it must NOT end the caller's own
        // session (e.g. "Connect Google" from settings) or any other device's session. Session
        // invalidation is reserved for credential *changes/removals* (password, identifier, delete)
        // and explicit sign-out — see the other bumpTokenVersion callers.
        CredentialLinked(principalId, finalAttributes.type).dispatch()
        stored
    }

    override suspend fun connectThirdParty(principalId: UUID, type: ThirdPartyType, token: String): PrincipalCredential {
        val user = thirdPartyTokenVerifier.verify(type, token)
        return connectThirdParty(
            principalId,
            OAuth2CredentialAttributes(user.id, null, null, type.name.lowercase()),
            user,
        )
    }

    /**
     * Attaches an already-verified provider identity to [principalId]. Split out of the token-taking
     * overload (mirroring [loginWithThirdParty] vs [loginWithThirdPartyToken]) so the ownership/verified
     * guard is testable without a live provider round-trip.
     */
    override suspend fun connectThirdParty(
        principalId: UUID,
        credential: CredentialAttributes,
        user: ThirdPartyUser,
    ): PrincipalCredential {
        // The caller is already authenticated, so the active session is the proof of ownership. We
        // still refuse to attach an identity whose asserted email the provider hasn't verified.
        if (user.email?.isNotBlank() == true && !user.emailVerified) {
            throw SecurityException("third party email is not verified")
        }
        return linkCredentialToPrincipal(principalId, credential)
    }

    private var pendingLinkStoreRef: PendingLinkStore? = null

    private suspend fun pendingLinkStore(): PendingLinkStore {
        pendingLinkStoreRef?.let { return it }
        // maybeAddCache is idempotent, so a concurrent double-init just resolves to the same cache.
        val cache = provide<CacheManager>().maybeAddCache(PendingLinkStore.CACHE_NAME, StringKeySerializer, 15.minutes)
        return PendingLinkStore(cache, json).also { pendingLinkStoreRef = it }
    }

    private var linkEmailRateLimiterRef: AuthRateLimiter? = null

    /**
     * Caps account-link proof emails per TARGET account (reuses the login [AuthRateLimiter]). Keyed on
     * the target email rather than the pending-link token, so the cap holds even if an attacker collects
     * multiple tokens for the same victim.
     */
    private suspend fun linkEmailRateLimiter(): AuthRateLimiter {
        linkEmailRateLimiterRef?.let { return it }
        // Distinct cache name: AuthRateLimiter's default ("auth:rate-limit") is owned by the login limiter
        // with a 1-minute window, and maybeAddCache ignores a later caller's window — sharing it would
        // silently collapse this 10-minute window to login's.
        return AuthRateLimiter(provide<CacheManager>(), maxAttempts = 5, window = 10.minutes, cacheName = "auth:rate-limit:link-email")
            .also { linkEmailRateLimiterRef = it }
    }

    private var emailChangeRateLimiterRef: AuthRateLimiter? = null

    /**
     * Caps email CHANGES to one per 10 minutes per principal, so the change → re-verification send can't be
     * looped to flood inboxes. `maxAttempts = 1`: the first change passes, then [AuthRateLimiter.recordFailure]
     * (called only on a SUCCESSFUL change) writes the marker and re-puts its key, which resets the 10-minute
     * TTL — so a successful change restarts the cooldown while a rejected attempt (typo / conflict) consumes
     * nothing. Distinct cache name for the same maybeAddCache-window reason as [linkEmailRateLimiter].
     */
    private suspend fun emailChangeRateLimiter(): AuthRateLimiter {
        emailChangeRateLimiterRef?.let { return it }
        return AuthRateLimiter(provide<CacheManager>(), maxAttempts = 1, window = 10.minutes, cacheName = "auth:rate-limit:email-change")
            .also { emailChangeRateLimiterRef = it }
    }

    private var linkPasswordRateLimiterRef: AuthRateLimiter? = null

    /**
     * Caps account-link PASSWORD proof attempts per TARGET account. [confirmAccountLinkWithPassword] re-checks a
     * password but is reached through an UNAUTHENTICATED GraphQL mutation that never passes through the login
     * route's [AuthRateLimiter] — so without this guard a held pending-link token (obtainable by attempting a
     * sign-up with the victim's verified email) is an unbounded password-guessing oracle against the target.
     * Keyed on the target principal so the cap survives re-issued tokens for the same victim. Distinct cache
     * name for the same maybeAddCache-window reason as [linkEmailRateLimiter].
     */
    private suspend fun linkPasswordRateLimiter(): AuthRateLimiter {
        linkPasswordRateLimiterRef?.let { return it }
        return AuthRateLimiter(provide<CacheManager>(), maxAttempts = 5, window = 10.minutes, cacheName = "auth:rate-limit:link-password")
            .also { linkPasswordRateLimiterRef = it }
    }

    /** Proof methods available for an existing account: password only if it has one; email always. */
    private fun computeProofMethods(credentials: List<PrincipalCredential>): List<LinkProofMethod> {
        val methods = mutableListOf<LinkProofMethod>()
        if (credentials.any { it.type.isPasswordType() }) {
            methods.add(LinkProofMethod.PASSWORD)
        }
        methods.add(LinkProofMethod.EMAIL)
        return methods
    }

    override suspend fun confirmAccountLinkWithPassword(token: String, password: String): LoginResponse {
        val pending = pendingLinkStore().peekPendingLink(token) ?: throw SecurityException("link request not found or expired")
        val target = getPrincipalById(pending.targetPrincipalId) ?: throw SecurityException("link request not found or expired")
        // Re-auth proof against the target's ACTUAL password credential — its identifier need not equal
        // the matched profile email (it may be a username or an alternate email), so authenticate by the
        // real identifier rather than by pending.email.
        val passwordCredential = getCredentials(target).firstOrNull {
            it.type.isPasswordType()
        } ?: throw SecurityException("password proof is not available for this account")
        // This is an unauthenticated mutation that bypasses the login route's limiter, so cap guesses per
        // TARGET principal (mirrors the Login route: throw when limited, count failures, decrement on success).
        val limiter = linkPasswordRateLimiter()
        val rateLimitKey = "link:password:${pending.targetPrincipalId}"
        if (limiter.isRateLimited(rateLimitKey)) throw SecurityException("link.password.rate.limited")
        val authenticated = try {
            authenticateWithCredential(SimplePasswordAttributes(passwordCredential.attributes.identifier, password))
        } catch (e: SecurityException) {
            limiter.recordFailure(rateLimitKey)
            throw e
        }
        if (authenticated.asPrincipal().id != pending.targetPrincipalId) {
            limiter.recordFailure(rateLimitKey)
            throw SecurityException("invalid credentials")
        }
        limiter.recordSuccess(rateLimitKey)
        return completeAccountLink(token, pending)
    }

    override suspend fun requestAccountLinkEmailProof(token: String, requestOrigin: String?) {
        val pending = pendingLinkStore().peekPendingLink(token) ?: throw SecurityException("link request not found or expired")
        // Rate-limit proof emails per TARGET account so a held pending-link token can't be looped to
        // flood a victim's inbox (mirrors the login brute-force limiter; keyed on the target email so
        // the cap survives re-issued tokens).
        val limiter = linkEmailRateLimiter()
        val rateLimitKey = "link:email-proof:${pending.email}"
        if (limiter.isRateLimited(rateLimitKey)) throw SecurityException("link.email.rate.limited")
        limiter.recordFailure(rateLimitKey)
        // The proof token is delivered ONLY by email, so possessing it proves control of the address —
        // the pending-link token alone (held by the browser) is not sufficient.
        val emailToken = generateSecureToken()
        pendingLinkStore().putEmailProof(emailToken, token)
        // Route the magic-link back to the host the user is on (validated), so a multi-host sign-in
        // lands the confirmation on the same Studio host rather than a single global default.
        val config = securityConfiguration.get()
        val origin = AppOrigins.resolve(null, requestOrigin, config.allowedAppOrigins, config.appUrl)
        sendAccountLinkEmail(pending, emailToken, origin)
    }

    override suspend fun confirmAccountLinkWithEmail(emailToken: String): LoginResponse {
        val linkToken = pendingLinkStore().consumeEmailProof(emailToken) ?: throw SecurityException("link confirmation not found or expired")
        val pending = pendingLinkStore().peekPendingLink(linkToken) ?: throw SecurityException("link request not found or expired")
        return completeAccountLink(linkToken, pending)
    }

    private suspend fun completeAccountLink(token: String, pending: PendingLink): LoginResponse {
        val response = transaction {
            // Verify-time collision: the credential being linked still lives on a separate, just-proven
            // DUPLICATE principal. Retire it (discarding its profiles) FIRST, so its credential's globally
            // unique identifier frees up before we attach the same credential to the survivor below. Runs in
            // this transaction, so if the attach fails the retirement rolls back too.
            pending.retirePrincipalId?.let { retireDuplicateDiscardingProfiles(it, pending.targetPrincipalId) }
            val finalAttributes = PrincipalCredential(
                principal = pending.targetPrincipalId,
                type = pending.credentialType,
                attributesJson = pending.credentialAttributes,
            ).attributes
            attachCredential(pending.targetPrincipalId, finalAttributes)
            // Re-read the survivor: retiring a duplicate above may have touched its cache, and we need a
            // live Principal to mint the JWT from. Attaching is additive and intentionally leaves the
            // survivor's existing sessions intact (only the retired duplicate's tokens are invalidated).
            val principal = requirePrincipal(pending.targetPrincipalId)
            val method = if (pending.credentialType == CredentialType.OAUTH2) "third_party" else "password"
            principal.newLoginResponse(true, emptyList(), method)
        }
        // Consume the single-use token only AFTER the DB work has committed. If attach failed mid-way, the
        // token survives so the user can retry instead of being stranded.
        pendingLinkStore().consumePendingLink(token)
        return response
    }

    /**
     * Resolves the profile the account-link email proof should be delivered to: the [principalId]'s profile
     * that carries [normalizedEmail] (already lower/trimmed) as its `bosca.profiles.email` value. Prefers a
     * VERIFIED match (the common case), then falls back to an unverified one.
     *
     * The fallback is safe because [normalizedEmail] is already proven verified-owned by the principal at the
     * uniqueness-backstop / principal level — that is exactly how the collision that mints the pending link is
     * detected (see [getPrincipalByEmail], which resolves via the backstop even when the profile attribute is
     * not flagged verified, e.g. some OAuth-verified or V160-backfilled addresses). Without the fallback,
     * those accounts pass collision detection but cannot be sent the proof. We still match by the address VALUE
     * (never an arbitrary profile), so the proof can only ever go to the matched address. Returns null only
     * when no profile of the principal bears the address at all (nothing safe to deliver to).
     */
    private suspend fun resolveAccountLinkDeliveryProfileId(principalId: UUID, normalizedEmail: String): UUID? {
        profileService.get().getProfilesByEmail(normalizedEmail).firstOrNull { it.principal == principalId }?.let { return it.id }
        return profileService.get().getByPrincipal(principalId).firstOrNull { profile ->
            profileService.get().getAttributes(profile.id).any { attr ->
                attr.typeId == "bosca.profiles.email" &&
                    attr.getAttributeString("email").normalizedEmail() == normalizedEmail
            }
        }?.id
    }

    private suspend fun sendAccountLinkEmail(pending: PendingLink, emailToken: String, origin: String) {
        // Deliver to the recipient pinned when the collision was detected (resolveAccountLinkDeliveryProfileId),
        // never re-resolved here. That target is the profile carrying the MATCHED address, so the proof can only
        // ever go to the address whose ownership is being tested — and a later email change can't redirect it. A
        // null target means no profile of the account bears the matched address, so there is nothing safe to send.
        val deliveryProfileId = pending.deliveryProfileId
            ?: throw SecurityException("the matched email is no longer verified for this account")
        val link = AuthWebLinks.accountLink(origin, emailToken)
        AccountLinkEmailRequested(setOf(deliveryProfileId), link).dispatch()
    }

    override suspend fun findDuplicateAccounts(): List<DuplicatedAccountIds> {
        return principalRepository.findDuplicateVerifiedEmailAccounts()
            .groupBy { it.email }
            .map { (email, rows) -> DuplicatedAccountIds(email, rows.map { it.principalId }.distinct()) }
    }

    override suspend fun mergePrincipals(survivorId: UUID, duplicateId: UUID): Principal = transaction {
        if (survivorId == duplicateId) throw SecurityException("cannot merge a principal into itself")
        val survivor = requirePrincipal(survivorId, "survivor principal not found")
        val duplicate = requirePrincipal(duplicateId, "duplicate principal not found")

        // 1. Move the duplicate's credentials. Identifiers are globally unique (ix_principal_identifier),
        //    so a collision is not expected; PRE-CHECK ownership and skip rather than catching the unique
        //    violation — a failed UPDATE would abort the whole merge transaction (25P02 on every later step),
        //    so "skip and continue" can't work from inside a catch.
        credentialsRepository.getByPrincipalId(duplicateId).forEach { credential ->
            val owner = getPrincipalByIdentifier(credential.attributes.identifier, credential.type)
            if (owner != null && owner.id == survivorId) {
                log.warn("mergePrincipals: skipping credential {} — identifier already belongs to the survivor", credential.id)
            } else {
                credentialsRepository.update(credential.copy(principal = survivorId))
            }
        }

        // 2. Re-parent the duplicate's profiles (ownership only — content is keyed on profile id, never fused).
        profileService.get().getByPrincipal(duplicateId).forEach { profile ->
            profileService.get().setPrincipal(profile.id, survivorId)
        }

        // 3. Move group memberships, skipping the duplicate's own personal group and de-duping against the
        //    survivor's existing memberships.
        val survivorGroupIds = principalGroupsRepository.getPrincipalGroups(survivorId).map { it.id }.toSet()
        val duplicatePersonalGroup = "$duplicateId.user"
        principalGroupsRepository.getPrincipalGroups(duplicateId).forEach { group ->
            if (group.name != duplicatePersonalGroup && group.id !in survivorGroupIds) {
                addPrincipalGroup(survivorId, group.id)
            }
            removePrincipalGroup(duplicateId, group.id)
        }

        // 4. Give the survivor a primary profile if it lacks one (a just-re-parented profile is fine).
        if (survivor.primaryProfileId == null) {
            profileService.get().getByPrincipal(survivorId).firstOrNull()?.let {
                setPrimaryProfile(survivorId, it.id)
            }
        }

        // 5. Retire the duplicate: it now owns nothing and cannot log in (no credentials). Mark it
        //    unverified + anonymous so it drops out of verified-email lookups and the unique index,
        //    record the merge target for audit, and invalidate any outstanding sessions.
        bumpTokenVersion(duplicateId)
        // Preserve any existing attributes the duplicate carried (flags, external ids) and just add the
        // merge pointer, rather than overwriting the whole object.
        val retiredAttributes = duplicate.attributes.withAuditAttribute("mergedInto", survivorId.toString())
        editPrincipal(
            duplicate.copy(
                verified = false,
                anonymous = true,
                primaryProfileId = null,
                attributes = retiredAttributes,
            )
        )

        // reconcile the verified-email backstop — drop the (now-retired) duplicate's registry
        // entries, then re-assert the survivor's emails (which now include the re-parented profiles), so
        // the previously-shared email ends up owned by the survivor.
        principalEmailRepository.deleteByPrincipal(duplicateId)
        registerVerifiedPrincipalEmails(survivorId)

        PrincipalsMerged(survivorId, duplicateId).dispatch()
        requirePrincipal(survivorId, "survivor principal not found")
    }

    override suspend fun editPrincipal(principal: Principal): Principal = transaction {
        val updated = principalRepository.edit(principal.copy(modified = OffsetDateTime.now()))
        removeFromCache(principal.id)
        PrincipalUpdated(principal.id).dispatch()
        updated
    }

    override suspend fun markPrincipalDeleted(id: UUID): Principal = transaction {
        val principal = principalRepository.markDeleted(id)
        // A marked-deleted account must not stay logged in: bump the token version and drop refresh
        // tokens (the same revocation path as signOut). Combined with the `requireNotDeleted` login
        // gate, this both kills live sessions and blocks fresh logins until the principal is restored.
        // bumpTokenVersion also evicts the principal cache, so the next read sees `deleted_at`.
        bumpTokenVersion(id)
        PrincipalMarkedDeleted(id).dispatch()
        principal
    }

    override suspend fun restorePrincipal(id: UUID): Principal = transaction {
        val principal = principalRepository.restore(id)
        removeFromCache(id)
        PrincipalRestored(id).dispatch()
        principal
    }

    override suspend fun deletePrincipal(id: UUID) {
        transaction {
            // Tear the principal's profiles down through the profile service first, so their caches are
            // invalidated and ProfileDeletedEvent fires (search-index cleanup) — the raw DB cascade would
            // remove the rows but bypass both. `primary_profile_id` is ON DELETE SET NULL, so clearing a
            // profile that is the principal's primary is harmless. The remaining footprint (credentials,
            // group memberships, emails, refresh/exchange tokens, org memberships) is removed by the
            // ON DELETE CASCADE constraints when the principal row itself is deleted.
            profileService.get().getByPrincipal(id).forEach { profile ->
                profileService.get().delete(profile.id)
            }
            principalRepository.deleteById(id)
            removeFromCache(id)
            PrincipalDeleted(id).dispatch()
        }
    }

    override suspend fun setPrimaryProfile(principalId: UUID, profileId: UUID) {
        transaction {
            val principal = requirePrincipal(principalId)
            val profile = profileService.get().getById(profileId)
            require(profile.principal == principalId && !profile.isDeleted) {
                "primary profile must be active and owned by the principal"
            }
            editPrincipal(principal.copy(primaryProfileId = profileId))
        }
    }

    override suspend fun clearPrimaryProfile(principalId: UUID) {
        transaction {
            val principal = requirePrincipal(principalId)
            editPrincipal(principal.copy(primaryProfileId = null))
        }
    }

    override suspend fun updatePassword(principalId: UUID, password: String, identifier: String?) {
        val newPassword = argonPasswordEncoder.encode(password)
        transaction {
            val principal = requirePrincipal(principalId)
            val credential = getCredentials(principal).firstOrNull { it.type.isPasswordType() }
            val newIdentifier = resolvePasswordIdentifier(principal, credential, identifier)
            val updatedCredential = if (credential == null) {
                PrincipalCredential(
                    principal = principal.id,
                    attributes = CredentialPasswordAttributes(newIdentifier, newPassword),
                )
            } else {
                credential.withPasswordAndIdentifier(newPassword, newIdentifier)
            }
            getPrincipalByIdentifier(newIdentifier, CredentialType.PASSWORD)?.let {
                if (it.id != principal.id) throw SecurityException("identifier already in use")
            }
            if (updatedCredential.id == 0L) {
                credentialsRepository.add(updatedCredential)
            } else {
                credentialsRepository.update(updatedCredential)
            }
            // Every previously-issued session for this principal —
            // access token, refresh token — must die here. The
            // password just changed; anyone still holding a token
            // minted under the old credential has no business
            // minting new JWTs or making authenticated requests.
            bumpTokenVersion(principalId)
            PasswordChanged(principalId).dispatch()
            dispatchSecurityAlert(
                principal,
                event = "Your password was changed",
            )
        }
    }

    private suspend fun resolvePasswordIdentifier(
        principal: Principal,
        credential: PrincipalCredential?,
        requestedIdentifier: String?,
    ): String {
        if (requestedIdentifier != null) return requestedIdentifier.lowercase().trim()
        if (credential != null) return credential.attributes.identifier
        val profile = profileService.get().getByPrincipal(principal.id).firstOrNull()
            ?: error("profile not found")
        val email = profileService.get().getAttributes(profile.id)
            .firstOrNull { it.typeId == "bosca.profiles.email" }
            ?.getAttributeString("email")
        return email ?: error("email attribute not found")
    }

    override suspend fun updateIdentifier(principalId: UUID, identifier: String) = transaction {
        val principal = requirePrincipal(principalId)
        val credential = getCredentials(principal)
            .firstOrNull { it.type.isPasswordType() }
            ?: error("credential not found")
        updateCredentialIdentifier(principalId, credential, identifier)
    }

    /**
     * Renames ONE specific password credential's login identifier (re-checking global uniqueness and
     * invalidating sessions). Callers pass the exact credential to move rather than letting this re-resolve it
     * via firstOrNull — `principal_credentials` has no row ordering, so re-resolving could rename a DIFFERENT
     * credential than the caller intended on an account with more than one password credential.
     */
    private suspend fun updateCredentialIdentifier(principalId: UUID, credential: PrincipalCredential, identifier: String) {
        val newIdentifier = identifier.lowercase().trim()
        if (newIdentifier == credential.attributes.identifier) return
        getPrincipalByIdentifier(newIdentifier, credential.type)?.let {
            if (it.id != principalId) throw SecurityException("identifier already in use")
        }
        credentialsRepository.update(credential.withIdentifier(newIdentifier))
        // Identifier change is a session-ending event: the "who" of the credential just moved. Force every
        // outstanding token to re-authenticate against the new identifier rather than coast on the old one.
        bumpTokenVersion(principalId)
    }

    private suspend fun getStoredCredentialAttributes(credential: CredentialAttributes): Pair<Principal, PrincipalCredential> {
        var principalCredential = credentialsRepository.getByIdentifier(credential.identifier, credential.type).firstOrNull()
        if (principalCredential == null && credential.type == CredentialType.PASSWORD) {
            principalCredential = credentialsRepository.getByIdentifier(credential.identifier, CredentialType.PASSWORD_SCRYPT).firstOrNull()
        }
        if (principalCredential == null) {
            throw MissingCredentials()
        }
        val principal = requirePrincipal(principalCredential.principal)
        if (principal.anonymous) throw SecurityException("principal is anonymous")
        return Pair(principal, principalCredential)
    }

    private suspend fun LoginResponse.processSignupTokens(principal: Principal, signupTokens: List<SignupToken>) {
        profileService.get().getByPrincipal(principalId).forEach {
            try {
                transaction {
                    signupTokens.process(it, principal, profileService.get(), organizationService.get(), communityService)
                }
            } catch (e: Exception) {
                log.info("failed to process signup token: $e")
            }
        }
    }

    override suspend fun forgotPassword(identifier: String, requestOrigin: String?) {
        val principal = getPrincipalByIdentifier(identifier) ?: getPrincipalByEmail(identifier)
        if (principal == null || !principal.verified) {
            log.debug("Forgot password request for unknown or unverified identifier: {}", identifier)
            return
        }
        // Persist the originating host with the reset token so the email link routes back to it (multi-host);
        // validated against the allow-list when the link is built.
        val verificationToken = generateSecureToken()
        val update = principal.copy(verificationToken = verificationToken, verificationOrigin = requestOrigin)
        editPrincipal(update)
        val profile = profileService.get().getByPrincipal(principal.id).firstOrNull() ?: return
        val configuration = securityConfiguration.get()
        val origin = AppOrigins.resolve(null, update.verificationOrigin, configuration.allowedAppOrigins, configuration.appUrl)
        PasswordResetEmailRequested(
            recipientIds = setOf(profile.id),
            resetUrl = AuthWebLinks.resetPassword(origin, verificationToken),
        ).dispatch()
        PasswordResetRequested(principal.id).dispatch()
    }

    override suspend fun resetPassword(token: String, password: String) {
        transaction {
            val principal = getByVerificationToken(token) ?: throw SecurityException("token not found")
            if (!principal.verified) throw PrincipalNotVerified()
            val update = principal.copy(verificationToken = null, verificationOrigin = null)
            editPrincipal(update)
            updatePassword(principal.id, password)
            PasswordReset(principal.id).dispatch()
        }
    }

    override suspend fun authenticateWithPayload(payload: Payload): AuthenticatedPrincipal {
        val configuration = securityConfiguration.get()
        if (payload.audience.contains(configuration.audience) && payload.issuer == configuration.issuer) {
            val principalId = UUID.parse(payload.subject)
            val principal = requirePrincipal(principalId)
            if (principal.anonymous) throw SecurityException("principal is anonymous")
            // Reject every request made as a soft-deleted principal. Marking deleted bumps the token
            // version (so this is usually caught by the `tver` check below too), but enforcing it here
            // is the authoritative request-time gate that survives any token that slips the bump.
            requireNotDeleted(principal)
            // Per-principal generation check. Pre-migration tokens
            // have no `tver` claim — `asInt()` returns null on a
            // missing claim and we treat it as generation 0, which
            // matches the DB default so existing sessions survive
            // the rollout. Any bump advances the principal past 0
            // and legacy tokens naturally lose at that point.
            val versionClaim = payload.getClaim(TOKEN_VERSION_CLAIM)?.asInt() ?: 0
            if (versionClaim != principal.tokenVersion) {
                throw SecurityException("token has been invalidated")
            }
            val loginId = payload.getClaim(LOGIN_ID_CLAIM)?.asLong()
            val mayHaveLoginRevocations = principal.hasLoginRevocations ||
                principalsWithLoginRevocationsL1.getIfPresent(principalId) == true
            if (loginId != null && mayHaveLoginRevocations && isLoginRevoked(loginId)) {
                throw SecurityException("login has been revoked")
            }
            return AuthenticatedPrincipal(
                principal,
                getPrincipalGroups(principalId),
                loginId,
            )
        } else {
            throw SecurityException("invalid token")
        }
    }

    override suspend fun authenticateWithCredential(credential: CredentialAttributes): AuthenticatedPrincipal {
        val result = loginWithCredential(credential, false, emptyList(), emitSignInEvent = false, originator = null)
        val principal = requirePrincipal(result.principalId)
        if (principal.anonymous) throw SecurityException("principal is anonymous")
        return AuthenticatedPrincipal(
            principal,
            getPrincipalGroups(result.principalId),
        )
    }

    /**
     * Account-state gate applied ONLY after a credential proof has already succeeded. Keeping it strictly
     * post-authentication is what preserves enumeration-resistance: a wrong password throws [InvalidPassword]
     * for verified and unverified accounts alike (and a missing account throws [MissingCredentials] — same
     * `INVALID_CREDENTIALS` code), so an unauthenticated caller submitting an arbitrary password can never
     * learn whether the account exists or whether it is verified. Only a proven-correct password reaches here
     * and may surface the distinct [PrincipalNotVerified]. Never call this before validating the credential.
     */
    private fun requireVerifiedForLogin(principal: Principal) {
        if (!principal.verified) throw PrincipalNotVerified()
    }

    /**
     * Account-state gate that blocks a *soft-deleted* (marked-for-deletion) principal from
     * authenticating. Like [requireVerifiedForLogin], it is applied ONLY after a credential proof has
     * already succeeded in the password flows, so a wrong password stays indistinguishable regardless
     * of delete status (enumeration-resistance). Marking a principal deleted also bumps its token
     * version, so live sessions die at the request-time check in [authenticateWithPayload]; this gate
     * is what additionally prevents minting a fresh session via any login path.
     */
    private fun requireNotDeleted(principal: Principal) {
        if (principal.deletedAt != null) throw SecurityException("principal is deleted")
    }

    override suspend fun loginWithCredential(credential: CredentialAttributes, generateRefreshToken: Boolean, signupTokens: List<SignupToken>, originator: String?): LoginResponse =
        loginWithCredential(credential, generateRefreshToken, signupTokens, emitSignInEvent = true, originator = originator)

    /**
     * Credential login with control over the [PrincipalSignedIn] event. Interactive logins emit; the
     * per-request basic-auth path ([authenticateWithCredential]) passes false so middleware
     * authentication on every request does not masquerade as a stream of sign-ins.
     */
    private suspend fun loginWithCredential(credential: CredentialAttributes, generateRefreshToken: Boolean, signupTokens: List<SignupToken>, emitSignInEvent: Boolean, originator: String?): LoginResponse {
        val (principal, storedCredential) = getStoredCredentialAttributes(credential)
        val storedAttributes = storedCredential.attributes
        val response = when (credential.type) {
            CredentialType.PASSWORD -> {
                val requestAttributes = credential as SimplePasswordAttributes
                val password = if (storedAttributes is ScryptCredentialAttributes) {
                    scryptPasswordEncoder.get().matches(requestAttributes.password, ScryptPassword(storedAttributes))
                } else {
                    val storedPassword = (storedAttributes as CredentialPasswordAttributes).password
                    argonPasswordEncoder.matches(requestAttributes.password, ArgonPassword(storedPassword))
                }
                if (!password) throw InvalidPassword()
                // Verification (and the soft-delete gate) are enforced AFTER the password proof so a wrong
                // password is indistinguishable regardless of account state (see [requireVerifiedForLogin]
                // / [requireNotDeleted]). OAuth2 logins skip the verification gate — the provider may not
                // assert a verified email yet — but a soft-deleted account is still refused below.
                requireNotDeleted(principal)
                requireVerifiedForLogin(principal)
                principal.newLoginResponse(generateRefreshToken, signupTokens, "password".emittedIf(emitSignInEvent))
            }

            CredentialType.PASSWORD_SCRYPT -> {
                // PASSWORD_SCRYPT is a storage format, not a wire-level login request. Interactive callers
                // submit SimplePasswordAttributes (PASSWORD); getStoredCredentialAttributes falls back to the
                // legacy row and the PASSWORD branch above verifies it with the scrypt encoder.
                throw SecurityException("legacy scrypt credentials must be presented as a password login")
            }

            CredentialType.OAUTH2 -> {
                requireNotDeleted(principal)
                principal.newLoginResponse(generateRefreshToken, signupTokens, "third_party".emittedIf(emitSignInEvent))
            }

            CredentialType.API_TOKEN -> {
                throw SecurityException("API tokens cannot be used for interactive login")
            }

            CredentialType.PASSKEY -> {
                throw SecurityException("Passkeys use the WebAuthn ceremony, not credential-based login")
            }
        }
        // Record where this login came from on the credential as its "last originator" — only for interactive
        // logins that actually supply one, so the per-request basic-auth path and originator-less logins never
        // overwrite a previously-recorded value with null.
        if (emitSignInEvent && originator != null) {
            credentialsRepository.updateLastOriginator(storedCredential.id, originator)
        }
        // Echo the request originator straight back on the response.
        return response.copy(originator = originator)
    }

    override suspend fun deleteCredential(principalId: UUID, type: CredentialType, identifier: String) {
        transaction {
            val principal = requirePrincipal(principalId)
            val credentials = getCredentials(principal)
            val matching = credentials.firstOrNull {
                it.type == type &&
                    it.attributes.identifier.equals(identifier, ignoreCase = type.isPasswordType())
            } ?: error("credential not found")
            if (credentials.size <= 1) error("cannot remove the last credential from a principal")
            credentialsRepository.delete(principalId, matching.type, matching.attributes.identifier)
            bumpTokenVersion(principalId)
            CredentialDeleted(principalId, matching.type).dispatch()
        }
    }

    override suspend fun sendVerificationEmail(id: UUID, requestOrigin: String?) {
        val principal = requirePrincipal(id)
        if (principal.verified) return
        val profileId = profileService.get().getByPrincipal(id).firstOrNull()?.id ?: error("profile not found")
        // Begin verification of the principal's email attribute through the generic framework: the email
        // channel ([EmailVerifiableAttribute]) records the one-time token (and the originating host, for
        // multi-host email routing) on the email attribute and sends the link, which the template renders
        // from that attribute token.
        attributeVerificationService.get().requestVerification(profileId, "bosca.profiles.email", requestOrigin)
    }

    override suspend fun sendEmailVerificationMessage(profileId: UUID) {
        val email = profileService.get().getAttributes(profileId)
            .firstOrNull { it.typeId == "bosca.profiles.email" }
            ?: error("email attribute not found")
        val token = email.verificationToken ?: error("email verification token not found")
        val configuration = securityConfiguration.get()
        val origin = AppOrigins.resolve(null, email.verificationOrigin, configuration.allowedAppOrigins, configuration.appUrl)
        EmailVerificationRequested(
            recipientIds = setOf(profileId),
            verifyUrl = AuthWebLinks.verify(origin, token),
        ).dispatch()
    }

    override suspend fun sendWelcomeMessage(profileId: UUID) {
        WelcomeEmailRequested(
            recipientIds = setOf(profileId),
            getStartedUrl = securityConfiguration.get().welcomeUrl,
        ).dispatch()
    }

    override suspend fun assertEmailChangeAllowed(principalId: UUID, newEmail: String) {
        val normalizedNew = newEmail.lowercase().trim()
        if (normalizedNew.isBlank()) return
        // Cap email changes to one per 10 minutes per principal so the change -> re-verification can't be
        // looped to flood inboxes. Throwing rolls back the attribute edit (the framework calls this inside
        // the edit's transaction).
        val rateLimiter = emailChangeRateLimiter()
        if (rateLimiter.isRateLimited(principalId.toString())) throw SecurityException("email.change.rate.limited")
        // Guard: never let a change take an address another principal has already PROVEN (verified).
        getPrincipalByEmail(normalizedNew)?.let { if (it.id != principalId) throw CredentialConflict() }
        // Consume the cooldown NOW (synchronously), at allow-time — not deferred to commit. This is an
        // anti-spam ATTEMPT limiter: deferring the consume to commit widens the window in which rapid/near-
        // concurrent changes all pass isRateLimited before any has recorded, so consuming here narrows it.
        // Trade-off: this runs inside the edit transaction, so if a later step rolls the edit back — most
        // reachably the very next deliverChallengeFor in onAttributesChanged (a setVerificationToken write or
        // a render error) — the change does not persist yet the (non-transactional) cooldown is still consumed,
        // locking the user out of changing their email for the window. That window is FIXED, not self-renewing:
        // once consumed, isRateLimited throws on subsequent attempts BEFORE reaching recordFailure, so the TTL
        // is never re-extended. A rejected attempt (conflict/rate-limit) threw above and consumed nothing.
        // (The limiter is best-effort: truly-simultaneous requests can still race either way; a hard guarantee
        // would need an atomic increment-and-check.)
        rateLimiter.recordFailure(principalId.toString())
    }

    override suspend fun loginWithThirdParty(credential: CredentialAttributes, user: ThirdPartyUser, locale: Locale, generateRefreshToken: Boolean, signupTokens: List<SignupToken>, requestOrigin: String?, originator: String?): LoginResponse {
        val response = try {
            loginWithCredential(credential, generateRefreshToken, signupTokens, originator = originator)
        } catch (_: MissingCredentials) {
            null
        }
        if (response != null) {
            // Existing third-party account: no credential created. loginWithCredential already echoed the
            // request originator and updated the credential's last originator.
            return response
        }
        // no exact-credential match, so we're about to create. If the provider asserts a
        // *verified* email that a verified account already owns, do not fork a second principal —
        // route the user into account-linking (with proof) instead. Only a provider-verified email is
        // trustworthy enough to drive this decision. The OAuth credential is the method to attach once
        // ownership is proven.
        val verifiedEmail = user.email.takeIf { user.emailVerified }.normalizedEmail()
        verifyEmailAvailableForSignup(verifiedEmail, credential)
        val (principal, createdProfile) = transaction {
            val principal = addPrincipal(
                Principal(
                    anonymous = false,
                    // Only trust the email as verified when the provider actually asserts it
                    // (email_verified / verified_email). A merely-present, unverified email is
                    // attacker-controllable and must not confer verified status.
                    verified = verifiedEmail != null,
                ),
                credential,
                emptyList(),
                // Stamp the OAuth credential with where this sign-in came from.
                originator = originator
            )
            val email = user.email
            val profile = ProfileInput(
                name = user.name ?: user.email ?: user.id,
                visibility = ProfileVisibility.USER,
                attributes = listOf(
                    ProfileAttributeInput(
                        typeId = "bosca.profiles.name",
                        attributes = mapOf("name" to (user.name ?: email)).toJsonElement(),
                        priority = 1,
                        source = "oauth2",
                        confidence = 100,
                        visibility = ProfileVisibility.USER,
                    ),
                    ProfileAttributeInput(
                        typeId = "bosca.profiles.locale",
                        attributes = mapOf("locale" to locale.toLanguageTag()).toJsonElement(),
                        priority = 1,
                        source = "signup",
                        confidence = 100,
                        visibility = ProfileVisibility.USER,
                    ),
                    ProfileAttributeInput(
                        typeId = "bosca.profiles.name.given",
                        attributes = mapOf("name" to (user.givenName)).toJsonElement(),
                        priority = 1,
                        source = "oauth2",
                        confidence = 100,
                        visibility = ProfileVisibility.USER,
                    ),
                    ProfileAttributeInput(
                        typeId = "bosca.profiles.name.family",
                        attributes = mapOf("name" to (user.familyName)).toJsonElement(),
                        priority = 1,
                        source = "oauth2",
                        confidence = 100,
                        visibility = ProfileVisibility.USER,
                    ),
                    ProfileAttributeInput(
                        typeId = "bosca.profiles.email",
                        attributes = mapOf("email" to email).toJsonElement(),
                        priority = 1,
                        source = "oauth2",
                        confidence = 100,
                        visibility = ProfileVisibility.USER,
                    ),
                    ProfileAttributeInput(
                        typeId = "bosca.profiles.avatar",
                        attributes = mapOf("picture" to user.picture).toJsonElement(),
                        priority = 1,
                        source = "oauth2",
                        confidence = 100,
                        visibility = ProfileVisibility.USER,
                    )
                )
            )
            val createdProfile = profileService.get().add(profile, ProfileType.GENERIC, principal.id)
            if (verifiedEmail != null) {
                // The provider asserts it verified this email, so it is proven control. Stamp the email
                // attribute we just created as verified (source = the provider, e.g. "google")...
                val provider = (credential as OAuth2CredentialAttributes).source ?: "oauth2"
                profileService.get().markVerified("bosca.profiles.email", listOf(createdProfile.id), "email", verifiedEmail, provider)
                // ...and claim it in the uniqueness backstop, inside the create transaction so a
                // conflict rolls back the whole sign-up.
                registerPrincipalEmail(verifiedEmail, principal.id)
            }
            principal to createdProfile
        }
        sendWelcomeMessage(createdProfile.id)
        if (!principal.asPrincipal().verified) {
            // Route the verification email back to the host the user started on (e.g. the Studio host carried
            // by the OAuth redirect), not the default app origin — validated against the allow-list downstream.
            sendVerificationEmail(principal.id, requestOrigin)
        }
        // We just minted the principal above, so flag this sign-in as an account creation. The early return
        // at the top of this method is the existing-account branch and keeps the default (false). The
        // originator was already stamped on the credential at creation (original + last), so don't re-update
        // it on this login — just echo it back on the response.
        return loginWithCredential(credential, generateRefreshToken, signupTokens, originator = null).copy(accountCreated = true, originator = originator)
    }

    override suspend fun loginWithThirdPartyToken(
        type: ThirdPartyType,
        token: String,
        locale: Locale,
        generateRefreshToken: Boolean,
        signupTokens: List<SignupToken>,
        requestOrigin: String?,
        originator: String?
    ): LoginResponse {
        val user = thirdPartyTokenVerifier.verify(type, token)
        return loginWithThirdParty(OAuth2CredentialAttributes(user.id, null, null, type.name.lowercase()), user, locale, generateRefreshToken, signupTokens, requestOrigin, originator)
    }

    override suspend fun signOut(principalId: UUID, loginId: Long?) = transaction {
        if (loginId == null) {
            bumpTokenVersion(principalId)
            PrincipalLoginsRevoked(principalId, null).dispatch()
            return@transaction
        }
        val login = principalRepository.revokeLogin(principalId, loginId)
        if (login == null) {
            principalRefreshTokensRepository.deleteByLoginId(principalId, loginId)
        } else {
            principalRefreshTokensRepository.deleteByLoginId(principalId, login.id)
            val expiresAt = OffsetDateTime.now().plusSeconds(securityConfiguration.get().expirationTimeInSeconds)
            principalRepository.addLoginRevocation(principalId, login.id, expiresAt)
            principalRepository.markHasLoginRevocations(principalId)
            afterCommit {
                removeFromCache(principalId)
                cacheLoginRevocation(principalId, login.id)
            }
            PrincipalLoginsRevoked(principalId, login.id).dispatch()
        }
    }

    override suspend fun loginWithRefreshToken(refreshToken: String): LoginResponse = transaction {
        // Atomically consume the refresh token (DELETE ... RETURNING) to prevent reuse races.
        val consumed = principalRefreshTokensRepository.consumeToken(refreshToken)
            ?: throw SecurityException("refresh token not found")
        val principalId = consumed.principalId
        // The refresh-token row is the session authority. Revoking a tracked login deletes every
        // refresh token for that login in the same transaction, so no separate login lookup is needed.
        val principal = getPrincipalById(principalId)
            ?: throw SecurityException("principal not found")
        if (principal.anonymous) throw SecurityException("principal is anonymous")
        requireNotDeleted(principal)
        principal.newLoginResponse(true, emptyList(), "refresh_token", consumed.loginId)
    }

    override suspend fun loginWithJwtToken(jwtToken: String, generateRefreshToken: Boolean, signupTokens: List<SignupToken>): LoginResponse {
        val verifiedToken = securityConfiguration.get().verifier.verify(jwtToken)
        val principalId = UUID.parse(verifiedToken.subject)
        val principal = requirePrincipal(principalId)
        if (principal.anonymous) throw SecurityException("principal is anonymous")
        requireNotDeleted(principal)
        return principal.newLoginResponse(generateRefreshToken, signupTokens, "jwt")
    }

    /**
     * Builds the [LoginResponse] for an already-proven sign-in. [signInMethod] names the proof that
     * succeeded ("password", "third_party", "refresh_token", "jwt", "exchange_token", "passkey") and
     * drives the [PrincipalSignedIn] event; pass null to suppress the event — used by the per-request
     * basic-auth path ([authenticateWithCredential]), which would otherwise fire on every request.
     */
    private suspend fun Principal.newLoginResponse(
        generateRefreshToken: Boolean,
        signupTokens: List<SignupToken>,
        signInMethod: String?,
        existingLoginId: Long? = null,
    ): LoginResponse {
        val loginId = existingLoginId ?: signInMethod?.let { principalRepository.addLogin(id, it).id }
        val token = createJwtToken(
            this,
            loginId?.let { mapOf(LOGIN_ID_CLAIM to it) }.orEmpty(),
        )
        val expiresAtClaim = token.expiresAt
            ?: error("JWT is missing required `exp` claim; refusing to build LoginResponse")
        val issuedAtClaim = token.issuedAt
            ?: error("JWT is missing required `iat` claim; refusing to build LoginResponse")
        val refreshExpiresAt = if (generateRefreshToken) OffsetDateTime.now().plusDays(30) else null

        val refreshToken = if (generateRefreshToken) {
            val refreshToken = generateSecureToken()
            principalRefreshTokensRepository.addPrincipalRefreshToken(
                token = refreshToken,
                principalId = id,
                loginId = loginId,
                expires = refreshExpiresAt ?: error("refresh token expiry was not created"),
            )
            refreshToken
        } else {
            null
        }
        // Every JWT reaching this point must carry both `exp` and `iat` claims.
        // `createJwtToken` always sets them, and externally-supplied JWTs (via
        // `loginWithJwtToken`) are verified upstream with a verifier that
        // requires the standard claims. A missing claim here means a bug in
        // token creation or a misconfigured verifier — silently defaulting to
        // epoch 0 or "now" hides the defect and produces tokens that clients
        // interpret as already expired. Fail loudly instead.
        val response = LoginResponse(
            principalId = id,
            refreshToken = refreshToken,
            token = Token(
                expiresAt = expiresAtClaim.toInstant().epochSecond.toInt(),
                issuedAt = issuedAtClaim.toInstant().epochSecond.toInt(),
                token = token.token
            )
        )
        response.processSignupTokens(this, signupTokens)
        signInMethod?.let {
            principalRepository.touchLastLogin(id)
            PrincipalSignedIn(id, it).dispatch()
            dispatchSecurityAlert(
                this,
                event = "New sign-in to your account",
                details = listOf(SecurityEmailDetail("Method", it.replace('_', ' '))),
            )
        }
        return response
    }

    /** Emits a profile-addressed security alert while keeping delivery and rendering in pipelines. */
    private suspend fun dispatchSecurityAlert(
        principal: Principal,
        event: String,
        details: List<SecurityEmailDetail> = emptyList(),
    ) {
        val profileId = principal.primaryProfileId
            ?: profileService.get().getPrimaryProfile(principal)?.id
            ?: return
        val securityAlertUrl = securityConfiguration.get().securityAlertUrl
        SecurityAlertEmailRequested(
            recipientIds = setOf(profileId),
            event = event,
            time = OffsetDateTime.now().toString(),
            details = details,
            reviewUrl = securityAlertUrl,
        ).dispatch()
    }

    override suspend fun deleteExpiredRefreshToken() {
        principalRefreshTokensRepository.deleteExpired()
        principalExchangeTokenRepository.deleteExpired()
        principalRepository.deleteExpiredLoginRevocations()
    }

    override suspend fun createExchangeToken(principalId: UUID, accountCreated: Boolean, originator: String?): String {
        val token = generateSecureToken()
        principalExchangeTokenRepository.addExchangeToken(
            token = token,
            principalId = principalId,
            accountCreated = accountCreated,
            originator = originator
        )
        return token
    }

    override suspend fun loginWithExchangeToken(exchangeToken: String): LoginResponse {
        return transaction {
            val consumed = principalExchangeTokenRepository.consumeToken(exchangeToken)
                ?: throw SecurityException("exchange token not found or expired")
            val principal = requirePrincipal(consumed.principalId)
            if (principal.anonymous) throw SecurityException("principal is anonymous")
            requireNotDeleted(principal)
            // Replay the account-created fact and login originator captured at sign-in time across the
            // exchange-token hop.
            principal.newLoginResponse(true, emptyList(), "exchange_token")
                .copy(accountCreated = consumed.accountCreated, originator = consumed.originator)
        }
    }

    override suspend fun loginWithPasskey(principalId: UUID, credentialId: String, generateRefreshToken: Boolean): LoginResponse {
        return transaction {
            val principal = getPrincipalById(principalId) ?: throw SecurityException("principal not found")
            if (principal.anonymous) throw SecurityException("principal is anonymous")
            requireNotDeleted(principal)
            if (!principal.verified) throw PrincipalNotVerified()
            val credentials = credentialsRepository.getByPrincipalId(principalId, CredentialType.PASSKEY)
            val matching = credentials.firstOrNull { it.attributes.identifier == credentialId }
                ?: throw SecurityException("passkey credential not found")
            val attrs = matching.attributes as PasskeyCredentialAttributes
            val now = Instant.now().toString()
            val updated = PrincipalCredential(
                id = matching.id,
                principal = principalId,
                type = CredentialType.PASSKEY,
                attributesJson = Json.encodeToJsonElement(PasskeyCredentialAttributes.serializer(), attrs.copy(lastUsedAt = now))
            )
            credentialsRepository.update(updated)
            principal.newLoginResponse(generateRefreshToken, emptyList(), "passkey")
        }
    }

    override suspend fun addPasskeyCredential(principalId: UUID, attributes: PasskeyCredentialAttributes): PrincipalCredential {
        return transaction {
            val principal = requirePrincipal(principalId)
            if (principal.anonymous) error("anonymous principals cannot register passkeys")
            if (!principal.verified) error("unverified principals cannot register passkeys")
            val stored = credentialsRepository.add(PrincipalCredential(principal = principalId, attributes = attributes))
            PasskeyAdded(principalId).dispatch()
            stored
        }
    }

    override suspend fun updatePasskeySignCount(credentialId: Long, newSignCount: Long) {
        transaction {
            val credential = credentialsRepository.getById(credentialId) ?: error("credential not found")
            val attrs = credential.attributes as PasskeyCredentialAttributes
            val updated = PrincipalCredential(
                id = credential.id,
                principal = credential.principal,
                type = CredentialType.PASSKEY,
                attributesJson = Json.encodeToJsonElement(PasskeyCredentialAttributes.serializer(), attrs.copy(signCount = newSignCount))
            )
            credentialsRepository.update(updated)
        }
    }

    companion object {

        private val log = LoggerFactory.getLogger(SecurityServiceImpl::class.java)
    }
}
