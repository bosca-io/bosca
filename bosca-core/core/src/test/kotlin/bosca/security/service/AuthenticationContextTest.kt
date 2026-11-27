package bosca.security.service

import bosca.security.model.AuthenticatedPrincipal
import bosca.server.auth.CallAuthenticationContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class AuthenticationContextTest {

    @Test
    fun principalReturnsNullWithNullContext() {
        val ctx = AuthenticationContext(null, null)
        assertNull(ctx.principal())
    }

    @Test
    fun authenticationProvidersHasProvidersArray() {
        val providers = AuthenticationProviders(arrayOf("test"))
        assertNotNull(providers.providers)
    }

    @Test
    @OptIn(bosca.core.annotations.Internal::class)
    fun `missing provider configuration cannot expose a principal stored on the call`() {
        val call = CallAuthenticationContext()
        call.principal("browser", mockk<AuthenticatedPrincipal>())

        assertNull(AuthenticationContext(call, null).principal())
    }

    @Test
    fun `configured providers are checked in order before falling back to another call principal`() {
        val preferred = mockk<AuthenticatedPrincipal>()
        val fallback = mockk<AuthenticatedPrincipal>()
        val call = mockk<CallAuthenticationContext>()
        every { call.principal("missing") } returns null
        every { call.principal("browser") } returns preferred
        every { call.anyPrincipal() } returns fallback

        assertSame(preferred, AuthenticationContext(call, AuthenticationProviders(arrayOf("missing", "browser"))).principal())
        verify(exactly = 0) { call.anyPrincipal() }

        assertSame(fallback, AuthenticationContext(call, AuthenticationProviders(arrayOf("missing"))).principal())
        assertSame(fallback, AuthenticationContext(call, AuthenticationProviders(emptyArray())).principal())
    }
}
