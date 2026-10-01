package bosca.security.routes.passkey

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for WebAuthn sign-count regression detection, which guards against
 * credential cloning attacks. When an authenticator's reported sign count
 * does not advance beyond the previously stored value (and both are positive),
 * the credential is considered potentially compromised.
 *
 * The W3C WebAuthn spec recommends this check because a cloned credential
 * will typically replay the same or a lower sign count than the legitimate
 * authenticator has already advanced past.
 */
class SignCountRegressionTest {

    @Test
    fun `advancing sign count is not a regression`() {
        assertFalse(
            PasskeyAuthenticateComplete.isSignCountRegression(storedSignCount = 5, newSignCount = 6),
            "A sign count that advances past the stored value must be accepted"
        )
    }

    @Test
    fun `large sign count advancement is not a regression`() {
        assertFalse(
            PasskeyAuthenticateComplete.isSignCountRegression(storedSignCount = 100, newSignCount = 500),
            "A sign count that jumps well past the stored value must be accepted"
        )
    }

    @Test
    fun `equal positive sign counts are a regression`() {
        assertTrue(
            PasskeyAuthenticateComplete.isSignCountRegression(storedSignCount = 5, newSignCount = 5),
            "Equal positive sign counts indicate a potential clone and must be rejected"
        )
    }

    @Test
    fun `lower sign count is a regression`() {
        assertTrue(
            PasskeyAuthenticateComplete.isSignCountRegression(storedSignCount = 10, newSignCount = 3),
            "A sign count lower than stored indicates a potential clone and must be rejected"
        )
    }

    @Test
    fun `new sign count of 1 against higher stored count is a regression`() {
        assertTrue(
            PasskeyAuthenticateComplete.isSignCountRegression(storedSignCount = 100, newSignCount = 1),
            "A sign count that drops to 1 from a high stored value must be rejected"
        )
    }

    @Test
    fun `stored zero accepts any new sign count`() {
        assertFalse(
            PasskeyAuthenticateComplete.isSignCountRegression(storedSignCount = 0, newSignCount = 0),
            "When stored count is zero the authenticator does not support sign counts; accept any value"
        )
        assertFalse(
            PasskeyAuthenticateComplete.isSignCountRegression(storedSignCount = 0, newSignCount = 1),
            "When stored count is zero a non-zero reported count must still be accepted"
        )
        assertFalse(
            PasskeyAuthenticateComplete.isSignCountRegression(storedSignCount = 0, newSignCount = 100),
            "When stored count is zero any positive reported count must be accepted"
        )
    }

    @Test
    fun `new zero against positive stored count is not a regression`() {
        assertFalse(
            PasskeyAuthenticateComplete.isSignCountRegression(storedSignCount = 5, newSignCount = 0),
            "A zero new sign count means the authenticator stopped tracking; this is not a clone signal"
        )
    }

    @Test
    fun `both zero is not a regression`() {
        assertFalse(
            PasskeyAuthenticateComplete.isSignCountRegression(storedSignCount = 0, newSignCount = 0),
            "Both counts at zero means no sign-count support; must not be treated as regression"
        )
    }
}
