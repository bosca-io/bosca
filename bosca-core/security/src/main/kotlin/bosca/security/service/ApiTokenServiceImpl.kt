package bosca.security.service

import bosca.db.transaction
import bosca.pubsub.PubSubService
import bosca.security.model.ApiTokenCredentialAttributes
import bosca.security.model.CredentialType
import bosca.security.model.Group
import bosca.security.model.Principal
import bosca.security.model.PrincipalCredential
import bosca.security.repository.PrincipalCredentialsRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Maximum number of API token credentials a single principal may hold.
 * Prevents unbounded credential sprawl.
 */
private const val MAX_TOKENS_PER_PRINCIPAL = 50

/** Characters used for base62 encoding of the random token secret. */
private const val BASE62_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

/** Number of random bytes in the token secret (256 bits of entropy). */
private const val TOKEN_SECRET_BYTES = 32

/** Length of the visible prefix stored for UI display (e.g., "bsk_a1b2c3d4"). */
private const val TOKEN_PREFIX_LENGTH = 12

/** Maximum length for a token name to prevent storage abuse. */
private const val MAX_TOKEN_NAME_LENGTH = 255

/** Maximum length for a token description to prevent storage abuse. */
private const val MAX_TOKEN_DESCRIPTION_LENGTH = 1000

/** Regex for identifying IPv4-shaped addresses (validated properly via InetAddress). */
private val IPV4_PATTERN = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")

/**
 * Production implementation of [ApiTokenService] backed by the existing
 * [PrincipalCredentialsRepository] and [SecurityService].
 *
 * Tokens are stored as `principal_credentials` rows with type `API_TOKEN`.
 * The raw `bsk_...` token is never persisted — only its SHA-256 hash is
 * stored as the credential identifier.
 */
/** PubSub channel for distributing token cache invalidation events across instances. */
private const val TOKEN_INVALIDATION_CHANNEL = "bosca.security.api-token-invalidation"

private fun List<Group>.hasAdministrativeGroup(): Boolean =
    any { it.name == "administrators" || it.name == "sa" }

private fun Set<String>.coversScope(scope: String): Boolean =
    any { allowed -> scope == allowed || scope.startsWith("$allowed:") }

@ServiceImplementation
class ApiTokenServiceImpl(
    private val credentialsRepository: PrincipalCredentialsRepository,
    private val securityService: SecurityService,
    private val groupEvaluator: GroupEvaluator,
    private val pubSubService: PubSubService,
) : ApiTokenService {

    private val secureRandom = SecureRandom()
    private val invalidationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * L1 cache for resolved token authentication results. Keyed by SHA-256 identifier string.
     * TTL of 60 seconds balances performance with timely revocation propagation.
     */
    private val authCache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(60, TimeUnit.SECONDS)
        .build<String, CachedTokenAuth>()

    init {
        // Subscribe to invalidation events from other instances to evict
        // revoked/edited tokens from the local auth cache promptly.
        invalidationScope.launch {
            pubSubService.subscribe(TOKEN_INVALIDATION_CHANNEL, String.serializer()).collect { msg ->
                authCache.invalidate(msg.message)
            }
        }
    }

    /**
     * Negative cache for token identifiers that were not found, preventing repeated
     * database lookups for invalid or brute-forced tokens.
     */
    private val negativeCache = Caffeine.newBuilder()
        .maximumSize(50_000)
        .expireAfterWrite(60, TimeUnit.SECONDS)
        .build<String, Boolean>()

    /**
     * Tracks when each token was last written to the database, keyed by credential ID.
     * Decoupled from [authCache] so the debounce interval remains correct regardless
     * of the auth cache TTL.
     */
    private val lastUsedWriteCache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .build<Long, Boolean>()

    override suspend fun createToken(
        principalId: UUID,
        input: ApiTokenInput,
        createdBy: UUID,
    ): ApiTokenCreationResult = createToken(
        principalId = principalId,
        input = input,
        createdBy = createdBy,
        enforcePrincipalScopeGrant = true,
        enforcePersonalTokenLimit = true,
    )

    override suspend fun createEphemeralToken(
        principalId: UUID,
        input: ApiTokenInput,
        createdBy: UUID,
    ): ApiTokenCreationResult {
        require(input.expiresAt != null) { "ephemeral token expiration is required" }
        return createToken(
            principalId = principalId,
            input = input,
            createdBy = createdBy,
            enforcePrincipalScopeGrant = false,
            enforcePersonalTokenLimit = false,
        )
    }

    private suspend fun createToken(
        principalId: UUID,
        input: ApiTokenInput,
        createdBy: UUID,
        enforcePrincipalScopeGrant: Boolean,
        enforcePersonalTokenLimit: Boolean,
    ): ApiTokenCreationResult {
        val description = input.description
        require(input.name.isNotBlank()) { "token name must not be blank" }
        require(input.name.length <= MAX_TOKEN_NAME_LENGTH) { "token name must not exceed $MAX_TOKEN_NAME_LENGTH characters" }
        if (description != null) {
            require(description.length <= MAX_TOKEN_DESCRIPTION_LENGTH) { "token description must not exceed $MAX_TOKEN_DESCRIPTION_LENGTH characters" }
        }
        validateScopes(input.scopes)
        validateExpiration(input.expiresAt)

        // Prevent scope escalation: requested scopes must be covered by the
        // token owner's group memberships, matching the check in editToken.
        // Administrators and super-admins bypass this check — they have full system access.
        if (enforcePrincipalScopeGrant) {
            requireGrantedScopes(principalId, input.scopes)
        }

        if (enforcePersonalTokenLimit) {
            val existing = credentialsRepository.getByPrincipalId(principalId, CredentialType.API_TOKEN)
            if (existing.size >= MAX_TOKENS_PER_PRINCIPAL) {
                throw SecurityException("principal has reached the maximum of $MAX_TOKENS_PER_PRINCIPAL API tokens")
            }
        }

        val allowedGroups = input.allowedGroups
        if (allowedGroups != null) {
            val principalGroups = securityService.getPrincipalGroups(principalId).map { it.id }.toSet()
            val invalid = allowedGroups.filter { it !in principalGroups }
            if (invalid.isNotEmpty()) {
                throw IllegalArgumentException("groups not assigned to principal: $invalid")
            }
        }

        val rawToken = generateRawToken(principalId)
        val tokenHash = sha256Hex(rawToken)
        val identifier = "sha256:$tokenHash"
        val tokenPrefix = rawToken.take(TOKEN_PREFIX_LENGTH)

        val attributes = ApiTokenCredentialAttributes(
            identifier = identifier,
            name = input.name,
            description = input.description,
            tokenPrefix = tokenPrefix,
            scopes = input.scopes,
            allowedGroups = input.allowedGroups?.map { it.toString() },
            expiresAt = input.expiresAt,
            createdBy = createdBy.toString(),
        )

        val credential = PrincipalCredential(principal = principalId, attributes = attributes)
        val persisted = credentialsRepository.add(credential)

        return ApiTokenCreationResult(credential = persisted, rawToken = rawToken)
    }

    override suspend fun authenticate(rawToken: String, remoteIp: String?): ScopedAuthenticatedPrincipal {
        if (!rawToken.startsWith("bsk_")) {
            throw SecurityException("invalid API token format")
        }

        val tokenHash = sha256Hex(rawToken)
        val identifier = "sha256:$tokenHash"

        // Fast-reject tokens that were recently not found
        if (negativeCache.getIfPresent(identifier) != null) {
            throw SecurityException("invalid API token")
        }

        // Check L1 cache for a previously resolved authentication
        val cached = authCache.getIfPresent(identifier)
        if (cached != null) {
            validateCachedToken(cached)
            val principal = requireActivePrincipal(cached.credential.principal)
            try {
                updateLastUsed(cached.credential, cached.attrs, remoteIp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Failed to update last-used metadata for API token credential={}", cached.credential.id, e)
            }
            return ScopedAuthenticatedPrincipal(
                principal = principal,
                allGroups = cached.groups,
                scopes = cached.attrs.scopes,
                allowedGroupIds = cached.allowedGroupIds,
                credentialId = cached.credential.id,
            )
        }

        // Cache miss — resolve from database
        val credentials = credentialsRepository.getByIdentifier(identifier, CredentialType.API_TOKEN)
        val credential = credentials.firstOrNull()
        if (credential == null) {
            negativeCache.put(identifier, true)
            throw SecurityException("invalid API token")
        }

        val attrs = credential.attributes as ApiTokenCredentialAttributes

        validateActiveToken(attrs)

        val principal = requireActivePrincipal(credential.principal)

        val allGroups = securityService.getPrincipalGroups(credential.principal)
        val allowedGroupIds = attrs.allowedGroups?.map { UUID.parse(it) }?.toSet()

        // Populate cache for subsequent requests
        authCache.put(identifier, CachedTokenAuth(credential, attrs, allGroups, allowedGroupIds))

        try {
            updateLastUsed(credential, attrs, remoteIp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to update last-used metadata for API token credential={}", credential.id, e)
        }

        return ScopedAuthenticatedPrincipal(
            principal = principal,
            allGroups = allGroups,
            scopes = attrs.scopes,
            allowedGroupIds = allowedGroupIds,
            credentialId = credential.id,
        )
    }

    /**
     * Re-validates a cached token's expiration status on every request since
     * a token may expire within the 60-second cache window.
     *
     * Note on revocation: the cached `revokedAt` check below is a safety net,
     * but actual revocation propagation relies on [revokeToken] calling
     * [invalidateToken], which evicts the local cache and publishes an
     * invalidation event to other instances via PubSub.
     */
    private fun validateCachedToken(cached: CachedTokenAuth) {
        validateActiveToken(cached.attrs)
    }

    private suspend fun requireActivePrincipal(principalId: UUID): Principal {
        // Account state uses SecurityService's shared cache, which account mutations invalidate.
        // It must be checked even when the token credential is already in the process cache.
        val principal = securityService.getPrincipalById(principalId)
            ?: throw SecurityException("principal not found")
        if (principal.deletedAt != null) throw SecurityException("principal is deleted")
        if (principal.anonymous) throw SecurityException("API tokens cannot authenticate anonymous principals")
        return principal
    }

    private fun validateActiveToken(attrs: ApiTokenCredentialAttributes) {
        if (attrs.revokedAt != null) {
            throw SecurityException("API token has been revoked")
        }
        if (attrs.expiresAt != null) {
            try {
                val expiry = OffsetDateTime.parse(attrs.expiresAt)
                if (expiry.isBefore(OffsetDateTime.now())) {
                    throw SecurityException("API token has expired")
                }
            } catch (_: DateTimeParseException) {
                throw SecurityException("API token has invalid expiration")
            }
        }
    }

    override suspend fun revokeToken(credentialId: Long, requestingPrincipalId: UUID) {
        val credential = getAndAuthorize(credentialId, requestingPrincipalId)
        val attrs = credential.attributes as ApiTokenCredentialAttributes

        if (attrs.revokedAt != null) return

        val updated = attrs.copy(revokedAt = OffsetDateTime.now().toString())
        credentialsRepository.update(credential.withApiTokenAttributes(updated))
        invalidateToken(attrs.identifier)
    }

    override suspend fun revokeAllTokens(principalId: UUID): Int = transaction {
        val tokens = credentialsRepository.getByPrincipalId(principalId, CredentialType.API_TOKEN)
        var revoked = 0
        val now = OffsetDateTime.now().toString()
        for (credential in tokens) {
            val attrs = credential.attributes as ApiTokenCredentialAttributes
            if (attrs.revokedAt == null) {
                val updated = attrs.copy(revokedAt = now)
                credentialsRepository.update(credential.withApiTokenAttributes(updated))
                invalidateToken(attrs.identifier)
                revoked++
            }
        }
        revoked
    }

    override suspend fun getTokensForPrincipal(principalId: UUID): List<PrincipalCredential> {
        return credentialsRepository.getByPrincipalId(principalId, CredentialType.API_TOKEN)
    }

    override suspend fun getTokenById(credentialId: Long): PrincipalCredential? {
        val credential = credentialsRepository.getById(credentialId) ?: return null
        if (credential.type != CredentialType.API_TOKEN) return null
        return credential
    }

    override suspend fun editToken(
        credentialId: Long,
        name: String?,
        description: String?,
        scopes: List<String>?,
        requestingPrincipalId: UUID
    ): PrincipalCredential {
        if (name != null) {
            require(name.isNotBlank()) { "token name must not be blank" }
            require(name.length <= MAX_TOKEN_NAME_LENGTH) { "token name must not exceed $MAX_TOKEN_NAME_LENGTH characters" }
        }
        if (description != null) {
            require(description.length <= MAX_TOKEN_DESCRIPTION_LENGTH) { "token description must not exceed $MAX_TOKEN_DESCRIPTION_LENGTH characters" }
        }
        validateScopes(scopes)
        val credential = getAndAuthorize(credentialId, requestingPrincipalId)
        val attrs = credential.attributes as ApiTokenCredentialAttributes

        if (attrs.revokedAt != null) {
            throw SecurityException("cannot edit a revoked API token")
        }

        // Prevent scope escalation: requested scopes must be covered by what
        // the token owner's groups allow. Administrators and super-admins bypass
        // this check — they have full system access.
        requireGrantedScopes(credential.principal, scopes)

        val updated = attrs.copy(
            name = name ?: attrs.name,
            description = description ?: attrs.description,
            scopes = scopes ?: attrs.scopes,
        )
        val result = credentialsRepository.update(credential.withApiTokenAttributes(updated))
        invalidateToken(attrs.identifier)
        return result
    }

    private suspend fun requireGrantedScopes(principalId: UUID, scopes: List<String>?) {
        if (scopes == null) return
        val principalGroups = securityService.getPrincipalGroups(principalId)
        if (principalGroups.hasAdministrativeGroup()) return
        val allowedScopes = principalGroups.map { GroupEvaluator.groupToScope(it.name) }.toSet()
        val disallowed = scopes.filterNot(allowedScopes::coversScope)
        if (disallowed.isNotEmpty()) {
            throw SecurityException("cannot add scopes not covered by the token owner's group permissions: $disallowed")
        }
    }

    override suspend fun deleteToken(credentialId: Long, requestingPrincipalId: UUID) {
        val credential = getAndAuthorize(credentialId, requestingPrincipalId)
        val attrs = credential.attributes as ApiTokenCredentialAttributes

        if (attrs.revokedAt == null) {
            throw SecurityException("only revoked API tokens can be deleted")
        }

        credentialsRepository.delete(credential.principal, CredentialType.API_TOKEN, attrs.identifier)
    }

    /**
     * Invalidates a token in the local auth cache and publishes the invalidation
     * event to all other instances via PubSub so they evict their cached copy too.
     */
    private suspend fun invalidateToken(identifier: String) {
        authCache.invalidate(identifier)
        try {
            pubSubService.publish(TOKEN_INVALIDATION_CHANNEL, String.serializer(), identifier)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to publish token invalidation event for {} — revoked token may remain cached on other instances for up to 60s", identifier, e)
        }
    }

    /**
     * Updates only the `last_used_at` and `last_used_ip` fields on the credential
     * using a targeted SQL update. This avoids the stale-cache overwrite problem
     * where a full credential replacement could overwrite a concurrent revocation.
     *
     * Writes are debounced to at most once per minute per token using an independent
     * cache, decoupled from the auth cache TTL to prevent accidental write amplification
     * if the auth cache TTL is changed.
     */
    private suspend fun updateLastUsed(
        credential: PrincipalCredential,
        @Suppress("UNUSED_PARAMETER") attrs: ApiTokenCredentialAttributes,
        remoteIp: String?
    ) {
        // The lastUsedWriteCache entry expires after 1 minute — if present,
        // we wrote recently and can skip this update
        if (lastUsedWriteCache.getIfPresent(credential.id) != null) {
            return
        }
        credentialsRepository.updateLastUsed(
            id = credential.id,
            lastUsedAt = OffsetDateTime.now().toString(),
            lastUsedIp = sanitizeIp(remoteIp),
        )
        lastUsedWriteCache.put(credential.id, true)
    }

    /**
     * Loads a credential by ID and verifies the requesting principal is authorized
     * to manage it (must own the token or be an administrator).
     */
    private suspend fun getAndAuthorize(credentialId: Long, requestingPrincipalId: UUID): PrincipalCredential {
        val credential = credentialsRepository.getById(credentialId)
            ?: throw SecurityException("API token not found")

        if (credential.type != CredentialType.API_TOKEN) {
            throw SecurityException("credential is not an API token")
        }

        if (credential.principal == requestingPrincipalId) return credential

        val requester = securityService.getPrincipalById(requestingPrincipalId)
            ?: throw SecurityException("requesting principal not found")
        val requesterGroups = securityService.getPrincipalGroups(requestingPrincipalId)
        val requesterContext = ImpersonatedAuthenticationContext(requester, requesterGroups)
        if (!groupEvaluator.hasAdminGroup(requesterContext)) {
            throw SecurityException("not authorized to manage this API token")
        }

        return credential
    }

    /**
     * Generates a raw API token string in the format `bsk_<principal_short>_<secret>`.
     */
    private fun generateRawToken(principalId: UUID): String {
        val principalShort = principalId.toString().replace("-", "").take(8)
        val secretBytes = ByteArray(TOKEN_SECRET_BYTES)
        secureRandom.nextBytes(secretBytes)
        val secret = base62Encode(secretBytes)
        return "bsk_${principalShort}_$secret"
    }

    companion object {

        private val log = LoggerFactory.getLogger(ApiTokenServiceImpl::class.java)

        /**
         * Computes the SHA-256 hex digest of the given input string.
         */
        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Encodes a byte array into a base62 string using repeated division.
         */
        fun base62Encode(bytes: ByteArray): String {
            var value = java.math.BigInteger(1, bytes)
            val base = java.math.BigInteger.valueOf(62)
            val sb = StringBuilder()
            while (value > java.math.BigInteger.ZERO) {
                val (quotient, remainder) = value.divideAndRemainder(base)
                sb.append(BASE62_ALPHABET[remainder.toInt()])
                value = quotient
            }
            return sb.reverse().toString()
        }

        /**
         * Validates and sanitizes an IP address string. Returns `null` for invalid
         * or missing values to prevent storing arbitrary strings from spoofed
         * `X-Forwarded-For` headers.
         *
         * Uses [java.net.InetAddress] for IPv6 validation to correctly handle all
         * compressed forms (e.g., `::1`, `::ffff:127.0.0.1`).
         */
        private fun sanitizeIp(ip: String?): String? {
            if (ip == null) return null
            val trimmed = ip.trim()
            if (trimmed.length > 45) return null
            // Use InetAddress for both IPv4 and IPv6 validation to correctly
            // reject invalid addresses like 999.999.999.999
            if (IPV4_PATTERN.matches(trimmed)) {
                return try {
                    java.net.InetAddress.getByName(trimmed).hostAddress
                } catch (_: java.net.UnknownHostException) {
                    null
                }
            }
            if (trimmed.contains(':')) {
                return try {
                    val addr = java.net.InetAddress.getByName(trimmed)
                    if (addr is java.net.Inet6Address) addr.hostAddress else null
                } catch (_: java.net.UnknownHostException) {
                    null
                }
            }
            return null
        }

        private fun validateScopes(scopes: List<String>?) {
            if (scopes == null) return
            val invalid = scopes.filter { !ApiTokenScopes.isValid(it) }
            if (invalid.isNotEmpty()) {
                throw IllegalArgumentException("unrecognized scopes: $invalid")
            }
        }

        private fun validateExpiration(expiresAt: String?) {
            if (expiresAt == null) return
            try {
                val expiry = OffsetDateTime.parse(expiresAt)
                if (expiry.isBefore(OffsetDateTime.now())) {
                    throw IllegalArgumentException("expiration timestamp must be in the future: $expiresAt")
                }
            } catch (_: DateTimeParseException) {
                throw IllegalArgumentException("invalid expiration timestamp: $expiresAt")
            }
        }
    }
}

/**
 * Holds the resolved authentication state for a cached API token lookup,
 * avoiding repeated DB queries for the credential, principal, and groups.
 */
private data class CachedTokenAuth(
    val credential: PrincipalCredential,
    val attrs: ApiTokenCredentialAttributes,
    val groups: List<Group>,
    val allowedGroupIds: Set<UUID>?,
)

/**
 * Creates a copy of this [PrincipalCredential] with updated [ApiTokenCredentialAttributes],
 * preserving the credential's ID and principal reference.
 */
private fun PrincipalCredential.withApiTokenAttributes(attrs: ApiTokenCredentialAttributes): PrincipalCredential {
    return PrincipalCredential(principal = principal, attributes = attrs).copy(id = id)
}
