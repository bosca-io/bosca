package bosca.security.routes

import bosca.routes.configureSecurityRoutes
import bosca.security.routes.oauth2.OAuth2Module
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class SecurityRoutesModuleTest {

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.routes.SecurityRoutesKt")
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.routes.SecurityRoutesKt")
    }

    @Test
    fun `install registers authentication and oauth2 modules and configures routes`() = runTest {
        val app = mockk<BoscaApplication>(relaxed = true)
        val installedModules = mutableListOf<BoscaApplicationModule>()
        coEvery { app.install(capture(installedModules)) } returns Unit
        coEvery { app.configureSecurityRoutes() } returns Unit

        val module = SecurityRoutesModule()
        module.install(app)

        val types = installedModules.map { it::class }
        assertTrue(types.contains(AuthenticationModule::class), "Should install AuthenticationModule")
        assertTrue(types.contains(OAuth2Module::class), "Should install OAuth2Module")
        coVerify { app.configureSecurityRoutes() }
    }
}
