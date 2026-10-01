package bosca.security.routes

import bosca.security.model.SignupToken
import bosca.security.model.SignupTokenType
import bosca.security.routes.oauth2.OAuth2State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Oauth2StateTest {

    @Test
    fun `OAuth2State stores all fields`() {
        val tokens = listOf(SignupToken(SignupTokenType.ORGANIZATION, "org-abc"))
        val state = OAuth2State(
            state = "random-state",
            provider = "google",
            tokens = tokens,
            redirect = "/dashboard",
            admin = true,
            codeVerifier = "test-verifier"
        )
        assertEquals("random-state", state.state)
        assertEquals("google", state.provider)
        assertEquals(tokens, state.tokens)
        assertEquals("/dashboard", state.redirect)
        assertTrue(state.admin)
        assertEquals("test-verifier", state.codeVerifier)
    }

    @Test
    fun `OAuth2State with empty tokens`() {
        val state = OAuth2State(
            state = "s",
            provider = "google",
            tokens = emptyList(),
            redirect = "/",
            admin = false,
            codeVerifier = "v"
        )
        assertTrue(state.tokens.isEmpty())
        assertFalse(state.admin)
    }

    @Test
    fun `OAuth2State equality`() {
        val a = OAuth2State("s", "google", emptyList(), "/", false, "v")
        val b = OAuth2State("s", "google", emptyList(), "/", false, "v")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `OAuth2State with multiple signup tokens`() {
        val tokens = listOf(
            SignupToken(SignupTokenType.ORGANIZATION, "org-1"),
            SignupToken(SignupTokenType.COMMUNITY_GROUP, "group-2")
        )
        val state = OAuth2State("state-123", "google", tokens, "/home", false, "verifier-123")
        assertEquals(2, state.tokens.size)
        assertEquals(SignupTokenType.ORGANIZATION, state.tokens[0].type)
        assertEquals(SignupTokenType.COMMUNITY_GROUP, state.tokens[1].type)
    }

    @Test
    fun `OAuth2State copy changes redirect`() {
        val original = OAuth2State("s", "google", emptyList(), "/old", false, "v")
        val copied = original.copy(redirect = "/new")
        assertEquals("/new", copied.redirect)
        assertEquals("s", copied.state)
    }
}
