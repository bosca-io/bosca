package bosca.security.service

import bosca.graphql.CodedError
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SecurityErrorsTest {

    @Test
    fun `coded security errors expose stable client codes`() {
        val required = AccountLinkRequired("person@example.com", UUID.random())
        val errors: List<Pair<CodedError, String>> = listOf(
            MissingCredentials() to "INVALID_CREDENTIALS",
            InvalidPassword() to "INVALID_CREDENTIALS",
            PrincipalNotVerified() to "PRINCIPAL_NOT_VERIFIED",
            EmailNotVerified() to "EMAIL_NOT_VERIFIED",
            CredentialConflict() to "CREDENTIAL_CONFLICT",
            required to "ACCOUNT_LINK_REQUIRED",
        )

        errors.forEach { (error, code) ->
            assertEquals(code, error.code)
        }
        assertEquals("", required.token)
        assertEquals(emptyList(), required.methods)
    }

    @Test
    fun `email already verified retains an optional cause`() {
        val cause = IllegalStateException("duplicate")

        assertSame(cause, EmailAlreadyVerified(cause).cause)
        assertEquals(null, EmailAlreadyVerified(null).cause)
        assertEquals("EMAIL_ALREADY_VERIFIED", EmailAlreadyVerified().code)
    }
}
