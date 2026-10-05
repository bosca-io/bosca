package bosca.security.routes.security

import bosca.security.model.SignupTokenType
import bosca.server.ContentType
import bosca.server.Parameters
import bosca.server.ServerCall
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SecurityRouteSupportTest {

    @Test
    fun `redirect validation accepts matching origins and preserves path`() {
        val allowed = listOf("https://studio.example/oauth/callback")

        assertEquals(
            "https://studio.example/account?tab=security",
            validateRedirect("https://studio.example/account?tab=security", allowed),
        )
        assertEquals(
            "https://studio.example:8443/account",
            validateRedirect(
                "https://studio.example:8443/account",
                listOf("https://studio.example:8443/allowed"),
            ),
        )
        assertEquals(
            "//studio.example/account",
            validateRedirect("//studio.example/account", listOf("https://studio.example")),
        )
    }

    @Test
    fun `redirect validation rejects unsafe and malformed values`() {
        assertNull(validateRedirect(null, emptyList()))
        assertEquals("/", validateRedirect("/relative", listOf("https://studio.example")))
        assertEquals("/", validateRedirect("https://attacker.example", listOf("https://studio.example")))
        assertEquals("/", validateRedirect("https://studio.example", emptyList()))
        assertEquals("/", validateRedirect("https://[invalid", listOf("https://studio.example")))
        assertEquals(
            "/",
            validateRedirect("https://studio.example", listOf("https://[invalid")),
        )
    }

    @Test
    fun `form redirects use explicit value then query parameter`() {
        val call = call(
            contentType = "application/x-www-form-urlencoded; charset=UTF-8",
            parameters = mapOf("redirect" to "https://studio.example/from-query"),
        )
        val allowed = listOf("https://studio.example")

        assertEquals(
            "https://studio.example/explicit",
            getFormRedirect(call, "https://studio.example/explicit", "redirect", allowed),
        )
        assertEquals(
            "https://studio.example/from-query",
            getErrorRedirect(call, "redirect", allowed),
        )
    }

    @Test
    fun `json requests never redirect`() {
        val call = call(
            contentType = "application/json",
            parameters = mapOf("redirect" to "https://studio.example"),
        )

        assertNull(
            getFormRedirect(
                call,
                "https://studio.example",
                "redirect",
                listOf("https://studio.example"),
            ),
        )
        val noContentType = mockk<ServerCall> {
            every { request.contentType() } returns null
        }
        assertEquals(false, noContentType.isFormRequest())
    }

    @Test
    fun `email validation accepts plausible addresses and rejects invalid values`() {
        requireValidEmail("person+security@example.com")

        listOf(
            "missing-at.example.com",
            "missing-domain@",
            "@missing-local.example",
            "person@example",
            "${"a".repeat(245)}@example.com",
        ).forEach { email ->
            assertFailsWith<IllegalArgumentException> { requireValidEmail(email) }
        }
    }

    @Test
    fun `signup token extraction ignores blanks and preserves known token types`() {
        val populated = call(
            contentType = "application/json",
            parameters = mapOf(
                "organization" to "organization-id",
                "community" to "community-id",
            ),
        ).getSignUpTokens()

        assertEquals(2, populated.size)
        assertEquals(SignupTokenType.ORGANIZATION, populated[0].type)
        assertEquals("organization-id", populated[0].token)
        assertEquals(SignupTokenType.COMMUNITY_GROUP, populated[1].type)
        assertEquals("community-id", populated[1].token)
        assertEquals(
            emptyList(),
            call(
                contentType = "application/json",
                parameters = mapOf("organization" to "", "community" to " "),
            ).getSignUpTokens(),
        )
    }

    private fun call(contentType: String, parameters: Map<String, String>): ServerCall =
        mockk {
            every { request.contentType() } returns ContentType.parse(contentType)
            every { request.queryParameters } returns Parameters.fromSingleValueMap(parameters)
        }
}
