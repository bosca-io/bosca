package bosca.server.auth

import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallAuthenticationContextTest {

    private fun createPrincipal(): AuthenticatedPrincipal {
        return AuthenticatedPrincipal(Principal(), emptyList())
    }

    @Test
    fun `setting and getting principal by provider`() {
        val context = CallAuthenticationContext()
        val principal = createPrincipal()
        context.principal("jwt", principal)
        assertEquals(principal, context.principal("jwt"))
    }

    @Test
    fun `getting principal by unknown provider returns null`() {
        val context = CallAuthenticationContext()
        val principal = createPrincipal()
        context.principal("jwt", principal)
        assertNull(context.principal("basic"))
    }

    @Test
    fun `principal with null provider falls back to any`() {
        val context = CallAuthenticationContext()
        val principal = createPrincipal()
        context.principal("jwt", principal)
        val result = context.principal(null)
        assertNotNull(result)
        assertEquals(principal, result)
    }

    @Test
    fun `principal with null provider falls back to anyPrincipal`() {
        val context = CallAuthenticationContext()
        val principal = createPrincipal()
        context.principal("default", principal)
        val result = context.principal(null)
        assertNotNull(result)
        assertEquals(principal, result)
    }

    @Test
    fun `principal default parameter is null`() {
        val context = CallAuthenticationContext()
        val principal = createPrincipal()
        context.principal("jwt", principal)
        // calling principal() with default null provider
        val result = context.principal()
        assertNotNull(result)
        assertEquals(principal, result)
    }

    @Test
    fun `anyPrincipal returns first available principal`() {
        val context = CallAuthenticationContext()
        val principal = createPrincipal()
        context.principal("jwt", principal)
        val result = context.anyPrincipal()
        assertNotNull(result)
        assertEquals(principal, result)
    }

    @Test
    fun `anyPrincipal returns null when empty`() {
        val context = CallAuthenticationContext()
        assertNull(context.anyPrincipal())
    }

    @Test
    fun `allPrincipals returns complete map`() {
        val context = CallAuthenticationContext()
        val jwtPrincipal = createPrincipal()
        val basicPrincipal = createPrincipal()
        context.principal("jwt", jwtPrincipal)
        context.principal("basic", basicPrincipal)
        val all = context.allPrincipals()
        assertEquals(2, all.size)
        assertEquals(jwtPrincipal, all["jwt"])
        assertEquals(basicPrincipal, all["basic"])
    }

    @Test
    fun `allPrincipals returns empty map when no principals`() {
        val context = CallAuthenticationContext()
        assertTrue(context.allPrincipals().isEmpty())
    }

    @Test
    fun `empty context returns null for all lookups`() {
        val context = CallAuthenticationContext()
        assertNull(context.principal("jwt"))
        assertNull(context.principal(null))
        assertNull(context.principal())
        assertNull(context.anyPrincipal())
    }

    @Test
    fun `overwriting principal for same provider replaces it`() {
        val context = CallAuthenticationContext()
        val first = createPrincipal()
        val second = createPrincipal()
        context.principal("jwt", first)
        context.principal("jwt", second)
        assertEquals(second, context.principal("jwt"))
        assertEquals(1, context.allPrincipals().size)
    }

    @Test
    fun `multiple providers stored independently`() {
        val context = CallAuthenticationContext()
        val jwtPrincipal = createPrincipal()
        val sessionPrincipal = createPrincipal()
        val oauthPrincipal = createPrincipal()
        context.principal("jwt", jwtPrincipal)
        context.principal("session", sessionPrincipal)
        context.principal("oauth", oauthPrincipal)
        assertEquals(jwtPrincipal, context.principal("jwt"))
        assertEquals(sessionPrincipal, context.principal("session"))
        assertEquals(oauthPrincipal, context.principal("oauth"))
        assertEquals(3, context.allPrincipals().size)
    }
}
