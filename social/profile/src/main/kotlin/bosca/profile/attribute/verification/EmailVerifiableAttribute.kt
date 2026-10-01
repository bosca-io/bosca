package bosca.profile.attribute.verification

import bosca.security.service.SecurityService
import bosca.serialization.UUID

/**
 * The email channel + identity reaction of the generic attribute-verification framework: it plugs
 * `bosca.profiles.email` into [VerifiableAttributeType]. It lives in the profile domain (alongside the
 * framework it plugs into) rather than in the foundation security module, so the universally-loaded security
 * provider registrar never references this domain type — only servers that compose the profile domain pay for
 * it. Its single dependency, the [SecurityService] CONTRACT, is constructor-injected (threaded through the
 * `@Provider` factory in [VerifiableAttributeRegistrar]); routing through the contract keeps this off the
 * security IMPLEMENTATION's message types. The framework builds the instance lazily at challenge time, by
 * which point the [SecurityService] singleton already exists, so injecting it here creates no cycle.
 */
class EmailVerifiableAttribute(
    private val securityService: SecurityService,
) : VerifiableAttributeType {

    override val typeId = "bosca.profiles.email"
    override val valueKey = "email"
    override val verificationSource = "email"

    override suspend fun deliverChallenge(profileId: UUID, value: String, token: String) {
        // The framework already recorded [token] on the email ATTRIBUTE (which confirmation redeems), and the
        // email template renders the verify link straight from that attribute token. So there is no principal
        // token to mirror — `principals.verification_token` is left exclusively to the password-reset flow,
        // which avoids the two clobbering each other. Just send the verification email to this profile.
        securityService.sendEmailVerificationMessage(profileId)
    }

    override suspend fun onVerified(principalId: UUID, profileId: UUID, value: String) {
        // A proven email carries login identity: mark the principal verified + reconcile login/backstop.
        securityService.onEmailVerified(principalId)
    }

    override suspend fun onValueChanged(principalId: UUID, profileId: UUID, oldValue: String, newValue: String) {
        // Email is the login identity, so changing it is gated: rate-limited + can't take another principal's
        // verified address. Throwing rolls back the edit; the framework re-verifies the new value next.
        securityService.assertEmailChangeAllowed(principalId, newValue)
    }
}
