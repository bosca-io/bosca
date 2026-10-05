package bosca.profile.attribute.verification

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.security.service.SecurityService

/**
 * Registers the verifiable attribute types this module contributes. The generic [AttributeVerificationService]
 * collects every registered [VerifiableAttributeType] via `ProviderRegistry.findAll`, so a new verifiable
 * attribute (e.g. phone) is a new `@Provider` entry here (or in any module), not a change to the framework.
 *
 * Lives in the profile domain so the registration is emitted into the `Profile` provider registrar — binaries
 * that don't compose the profile domain (e.g. the kubernetes controller) never load it and so never link
 * [VerifiableAttributeType]. The `@Provider` factory injects the email channel's dependency so it reaches
 * [EmailVerifiableAttribute]'s constructor rather than being looked up inside the channel.
 */
@Providers
class VerifiableAttributeRegistrar {

    @Provider(name = "bosca.profiles.email")
    fun emailVerifiableAttribute(securityService: SecurityService): VerifiableAttributeType =
        EmailVerifiableAttribute(securityService)
}
