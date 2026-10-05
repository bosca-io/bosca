package bosca.profile.attribute.verification

import bosca.serialization.UUID

/**
 * A profile-attribute TYPE whose value can be proven (verified) — e.g. an email address, or in future a
 * phone number. Each implementation plugs the generic verification framework
 * ([bosca.profile.attribute.verification.AttributeVerificationService]) into a concrete delivery channel
 * (an email link, an SMS one-time code, …) and an optional post-verification reaction.
 *
 * Implementations are DI-registered (e.g. via `@Provider` returning `VerifiableAttributeType`) and the
 * framework collects them with `ProviderRegistry.findAll(VerifiableAttributeType::class)`, so adding a new
 * verifiable attribute is a new registration, not a code fork — and there is exactly one place that knows
 * a type's id, its JSON value key, and how to challenge it.
 */
interface VerifiableAttributeType {

    /** The profile-attribute type id this handles, e.g. `"bosca.profiles.email"`. */
    val typeId: String

    /** The JSON key under which the verifiable value is stored in the attribute, e.g. `"email"`. */
    val valueKey: String

    /**
     * The `verification_source` recorded when a value of this type is proven via the framework's challenge —
     * the proof CHANNEL, e.g. `"email"` for an email link (parallel to an OAuth provider recording `"google"`).
     * Stored verbatim on the attribute so audits/admin tooling can tell HOW control was proven.
     */
    val verificationSource: String

    /**
     * Delivers the verification challenge for [value] (the current value of the [typeId] attribute on
     * [profileId]) carrying the one-time [token] the recipient redeems to prove control — an email link
     * today, an SMS code later. Implementations must only deliver to the [value] itself (the address/number
     * being proven), never to an unrelated channel.
     */
    suspend fun deliverChallenge(profileId: UUID, value: String, token: String)

    /**
     * Reacts after an attribute of this type is verified (its token redeemed). For email this reconciles
     * login identity — marks the principal verified, moves the login identifier onto the proven address if
     * it had drifted, and releases stale uniqueness-backstop entries. Default: no-op (a verified value that
     * carries no identity meaning, such as a secondary email or a phone number, needs no reaction).
     */
    suspend fun onVerified(principalId: UUID, profileId: UUID, value: String) {}

    /**
     * Reacts when a previously-VERIFIED value of this type is CHANGED to [newValue], BEFORE re-verification
     * (which the framework triggers next). May throw to REJECT the change — the caller runs in the same
     * transaction as the edit, so a throw rolls the edit back. For email this rate-limits the change and
     * rejects taking an address another principal has already verified. Default: allow, no reaction.
     */
    suspend fun onValueChanged(principalId: UUID, profileId: UUID, oldValue: String, newValue: String) {}
}
