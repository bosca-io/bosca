package bosca.profile.attribute.verification

import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises the email channel that plugs `bosca.profiles.email` into the generic verification framework. The
 * channel's single dependency — the [SecurityService] CONTRACT — is constructor-injected, so the test passes a
 * mock directly (no DI registry wiring) and asserts the channel's three responsibilities: deliver the link,
 * react to a proven email, and gate an email change. Routing through the contract keeps this domain type off
 * the security implementation's message types.
 */
class EmailVerifiableAttributeTest {

    private val securityService = mockk<SecurityService>(relaxed = true)

    private val attribute = EmailVerifiableAttribute(securityService)

    private val profileId = UUID.random()
    private val principalId = UUID.random()

    @Test
    fun `it identifies the email attribute type and value key`() {
        assertEquals("bosca.profiles.email", attribute.typeId)
        assertEquals("email", attribute.valueKey)
        assertEquals("email", attribute.verificationSource)
    }

    @Test
    fun `deliverChallenge sends the verification email without touching the principal token`() = runTest {
        attribute.deliverChallenge(profileId, "owner@example.com", "the-token")

        // The framework already recorded the token on the email ATTRIBUTE (the template renders from it), so the
        // channel only sends the message via the contract — it must NOT mirror onto principals.verification_token
        // (the reset token).
        coVerify(exactly = 1) { securityService.sendEmailVerificationMessage(profileId) }
        coVerify(exactly = 0) { securityService.editPrincipal(any()) }
    }

    @Test
    fun `onVerified hands the proven email to the identity reaction`() = runTest {
        attribute.onVerified(principalId, profileId, "owner@example.com")

        coVerify(exactly = 1) { securityService.onEmailVerified(principalId) }
    }

    @Test
    fun `onValueChanged gates the change through the security guard`() = runTest {
        attribute.onValueChanged(principalId, profileId, "old@example.com", "new@example.com")

        // The guard (rate-limit + take-over check) sees only the new value; a throw rolls the edit back.
        coVerify(exactly = 1) { securityService.assertEmailChangeAllowed(principalId, "new@example.com") }
    }
}
