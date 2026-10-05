package bosca.profile.attribute.verification

import bosca.cache.CacheManager
import bosca.db.afterCommit
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.attribute.model.getAttributeString
import bosca.profile.profile.service.ProfileService
import bosca.ratelimit.RateLimiter
import bosca.security.SecureTokens
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class AttributeVerificationServiceImpl(
    private val profileService: ProfileService,
    cacheManager: CacheManager,
) : AttributeVerificationService {

    /**
     * Throttles challenge delivery per (typeId, value), across every profile and entry point. The value is
     * client-settable and unverified until proven, so without this a user could point an attribute at a
     * victim's address and spam it with verification messages — this caps the volume to any single target.
     * Distinct cache name keeps its window independent of the auth limiters.
     */
    private val deliveryRateLimiter = RateLimiter(
        cacheManager,
        maxAttempts = MAX_CHALLENGES_PER_WINDOW,
        cacheName = "verification:rate-limit",
    )

    /**
     * The verifiable types registered across all modules, indexed by attribute type id. Collected from DI so
     * a new type (e.g. phone) is a new `@Provider`, never a change here.
     */
    @OptIn(InternalDI::class)
    private suspend fun registeredTypes(): Map<String, VerifiableAttributeType> =
        ProviderRegistry.findAll(VerifiableAttributeType::class).map { it.get() }.associateBy { it.typeId }

    override suspend fun requestVerification(profileId: UUID, typeId: String, origin: String?): Unit = transaction {
        val type = registeredTypes()[typeId] ?: error("no verifiable attribute type registered for '$typeId'")
        val attribute = profileService.getAttributes(profileId).firstOrNull { it.typeId == typeId } ?: return@transaction
        deliverChallengeFor(type, profileId, attribute, origin)
    }

    /**
     * Mints a one-time token for [attribute]'s value and delivers [type]'s challenge to it. A no-op when the
     * attribute is already verified, carries no value, has been challenged too often in the window (the prior
     * token stays valid — suppressing the redundant send, not erroring), or no matching unverified row exists.
     * Takes the already-resolved [type] and [attribute] so callers in a loop don't re-scan the DI registry or
     * re-read the profile's attributes.
     */
    private suspend fun deliverChallengeFor(type: VerifiableAttributeType, profileId: UUID, attribute: ProfileAttribute, origin: String?) {
        if (attribute.verified) return
        val value = attribute.getAttributeString(type.valueKey)?.lowercase()?.trim() ?: return
        // Throttle per (typeId, value) so the client-settable, still-unverified value can't be used to flood a
        // target address with challenges.
        val rateKey = "${type.typeId}:$value"
        if (deliveryRateLimiter.isRateLimited(rateKey)) return
        val token = SecureTokens.generate()
        // Record the one-time token (and the originating host, for multi-host email routing) on the
        // (unverified) attribute, then deliver the challenge. If nothing was stamped (the value is already
        // verified or vanished), don't send.
        if (profileService.setVerificationToken(type.typeId, profileId, type.valueKey, value, token, origin).isEmpty()) return
        type.deliverChallenge(profileId, value, token)
        // Count the send only once the edit COMMITS — the challenge delivery (message enqueue) is itself
        // deferred to commit, so a rolled-back edit increments neither, keeping the throttle in step with sends.
        afterCommit { deliveryRateLimiter.recordFailure(rateKey) }
    }

    override suspend fun onAttributesChanged(profileId: UUID, before: List<ProfileAttribute>, after: List<ProfileAttribute>, origin: String?): Unit = transaction {
        val types = registeredTypes()
        // Index the previously-VERIFIED values of registered types by attribute id.
        val verifiedBefore = before
            .filter { it.verified && it.typeId in types }
            .mapNotNull { attr -> types[attr.typeId]?.let { attr.getAttributeString(it.valueKey)?.lowercase()?.trim() }?.let { attr.id to it } }
            .toMap()
        if (verifiedBefore.isEmpty()) return@transaction
        // Resolve the principal lazily (only once an attribute actually changed) and memoize it. A profile
        // with no principal simply gets no reaction for THIS attribute — skipped, never an aborted loop.
        var principalId: UUID? = null
        after.forEach { updated ->
            val type = types[updated.typeId] ?: return@forEach
            val oldValue = verifiedBefore[updated.id] ?: return@forEach
            val newValue = updated.getAttributeString(type.valueKey)?.lowercase()?.trim() ?: return@forEach
            if (newValue == oldValue) return@forEach
            val pid = principalId ?: profileService.getById(profileId).principal?.also { principalId = it } ?: return@forEach
            // The type's change reaction may throw (rate-limit, take-over guard) → rolls the edit back. Then
            // re-verify the now-unverified value via its channel, using the just-edited row directly.
            type.onValueChanged(pid, profileId, oldValue, newValue)
            deliverChallengeFor(type, profileId, updated, origin)
        }
    }

    override suspend fun confirmVerification(token: String): Unit = transaction {
        val types = registeredTypes()
        // Record HOW control was proven: resolve the channel that delivered this token (e.g. "email") so the
        // attribute's verification_source is descriptive rather than a generic marker, then redeem with it.
        // A token that matches nothing — bad, expired, or already redeemed — is a failure, not a silent
        // success: callers (the email-verify link route and the passwordVerify mutation) rely on this throw.
        val source = profileService.getByVerificationToken(token)?.typeId?.let { types[it]?.verificationSource } ?: "verified"
        val verified = profileService.verifyByToken(token, source)
        if (verified.isEmpty()) error("verification token not found or already redeemed")
        verified.forEach { attribute ->
            val type = types[attribute.typeId] ?: return@forEach
            // Normalize the proven value exactly as the challenge was issued, so a type's reaction keys off the
            // same string requestVerification/onAttributesChanged used.
            val value = attribute.getAttributeString(type.valueKey)?.lowercase()?.trim() ?: return@forEach
            val principalId = profileService.getById(attribute.profile).principal ?: return@forEach
            type.onVerified(principalId, attribute.profile, value)
        }
    }

    companion object {
        /**
         * Challenges allowed per (typeId, value) within the limiter's window. Matches the platform's existing
         * verification-resend policy (see `resendPasswordVerification`) so the cap is consistent across flows.
         */
        private const val MAX_CHALLENGES_PER_WINDOW = 5
    }
}
