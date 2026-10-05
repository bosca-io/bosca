package bosca.core.security

import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Unit tests for [toAuthError], the GraphQL-error-code → typed-[BoscaAuthError] mapping that lets the UI act
 * on specific auth failures (the Kotlin port of the web client's `mapPasswordLoginError`).
 */
class AuthErrorMappingTest {

    @Test
    fun `PRINCIPAL_NOT_VERIFIED code maps to PrincipalNotVerifiedError`() {
        assertIs<PrincipalNotVerifiedError>(toAuthError("PRINCIPAL_NOT_VERIFIED", "any message"))
    }

    @Test
    fun `EMAIL_NOT_VERIFIED code maps to EmailNotVerifiedError`() {
        assertIs<EmailNotVerifiedError>(toAuthError("EMAIL_NOT_VERIFIED", "any message"))
    }

    @Test
    fun `INVALID_CREDENTIALS code maps to InvalidCredentialsError`() {
        assertIs<InvalidCredentialsError>(toAuthError("INVALID_CREDENTIALS", "any message"))
    }

    @Test
    fun `code takes precedence over a conflicting message`() {
        // A wrong-password-looking message but an explicit verified code: trust the code.
        assertIs<PrincipalNotVerifiedError>(toAuthError("PRINCIPAL_NOT_VERIFIED", "invalid password"))
    }

    @Test
    fun `codeless principal-not-verified message maps to PrincipalNotVerifiedError`() {
        assertIs<PrincipalNotVerifiedError>(
            toAuthError(null, "Exception while fetching data (/security/login/password) : principal not verified"),
        )
    }

    @Test
    fun `codeless email-not-verified message maps to EmailNotVerifiedError`() {
        assertIs<EmailNotVerifiedError>(
            toAuthError(null, "Exception while fetching data (/security/login/password) : email not verified"),
        )
    }

    @Test
    fun `bare not-verified message defaults to the principal gate`() {
        assertIs<PrincipalNotVerifiedError>(toAuthError(null, "the account is not verified"))
    }

    @Test
    fun `missing-credentials message maps to InvalidCredentialsError`() {
        assertIs<InvalidCredentialsError>(toAuthError(null, "Exception ... : missing credentials"))
    }

    @Test
    fun `invalid-password message maps to InvalidCredentialsError`() {
        assertIs<InvalidCredentialsError>(toAuthError(null, "Exception ... : invalid password"))
    }

    @Test
    fun `unrecognized error falls back to GraphQLAuthError preserving messages`() {
        val err = toAuthError(null, "something else entirely", listOf("something else entirely", "second"))
        assertIs<GraphQLAuthError>(err)
        kotlin.test.assertEquals(listOf("something else entirely", "second"), err.errors)
    }

    @Test
    fun `unknown code falls through to message matching`() {
        assertIs<InvalidCredentialsError>(toAuthError("SOME_OTHER_CODE", "invalid password"))
    }
}
