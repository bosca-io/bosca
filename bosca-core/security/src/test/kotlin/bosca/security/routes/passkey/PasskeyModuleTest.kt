package bosca.security.routes.passkey

import bosca.cache.Cache
import bosca.cache.CacheManager
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertSame

@OptIn(InternalDI::class)
class PasskeyModuleTest {

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `install creates challenge cache and registers singleton state manager`() = runTest {
        ProviderRegistry.clear()
        val config = mockk<ApplicationConfig>(relaxed = true) {
            every { propertyOrNull(any()) } returns null
        }
        val application = BoscaApplication(config)
        val cache = mockk<Cache<String>>()
        val cacheManager = mockk<CacheManager> {
            coEvery { maybeAddCache<String>(any(), any(), any()) } returns cache
        }
        provides<CacheManager> { cacheManager }

        application.install(PasskeyModule())

        val first = provide<WebAuthnStateManager>()
        val second = provide<WebAuthnStateManager>()
        assertSame(first, second)
    }
}
