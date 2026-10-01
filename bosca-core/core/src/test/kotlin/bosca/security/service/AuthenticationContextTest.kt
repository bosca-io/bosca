package bosca.security.service

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
}
