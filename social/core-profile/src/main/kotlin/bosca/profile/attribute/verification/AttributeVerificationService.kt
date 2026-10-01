package bosca.profile.attribute.verification

import bosca.profile.attribute.model.ProfileAttribute
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Generic, attribute-type-agnostic verification framework. Drives proving control of a profile attribute's
 * value (an email address today, a phone number tomorrow) through a one-time token and a per-type delivery
 * channel ([VerifiableAttributeType]).
 *
 * Knows nothing email-specific: it looks the type up in the DI-collected registry, mints + records the
 * token, and delegates delivery and the post-verification reaction to the type.
 */
interface AttributeVerificationService : Service {

    /**
     * Begins verification of the [typeId] attribute on [profileId]: mints a one-time token, records it on
     * the attribute, and delivers the type's challenge (e.g. an email link) to the attribute's value.
     * A no-op when the attribute is missing, carries no value, or is already verified.
     */
    suspend fun requestVerification(profileId: UUID, typeId: String, origin: String? = null)

    /**
     * The trigger entry point: given a profile's COMPLETE attribute set [before] and [after] an edit, detects
     * every registered verifiable attribute whose previously-VERIFIED value CHANGED and reacts to each —
     * running the type's change reaction (e.g. email's rate-limit + take-over guard, which may throw to reject
     * the edit) and re-verifying the new value. Both lists are full snapshots (not just the edited rows), so a
     * future reaction to a verifiable attribute being REMOVED can be added by diffing the two. Generic over
     * type: the value key per type comes from the registry, so the profile layer needs to know nothing
     * attribute-specific. A no-op when nothing verified changed. Must be called in the same transaction as the
     * edit so a rejection rolls it back.
     */
    suspend fun onAttributesChanged(profileId: UUID, before: List<ProfileAttribute>, after: List<ProfileAttribute>, origin: String? = null)

    /**
     * Redeems a verification [token]: marks the attribute carrying it verified and runs that type's
     * post-verification reaction ([VerifiableAttributeType.onVerified]). Throws when the token matches nothing
     * (bad, expired, or already redeemed) so the caller can surface the failure — a single-use link clicked
     * twice fails on the second click.
     */
    suspend fun confirmVerification(token: String)
}
