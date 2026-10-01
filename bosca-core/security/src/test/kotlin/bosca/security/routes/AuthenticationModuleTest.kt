package bosca.security.routes

import bosca.db.ConnectionPool
import bosca.di.MissingProviderException
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.graphql.GraphQLConnectionInitAuthenticator
import bosca.observability.ErrorCapture
import bosca.security.service.ApiTokenService
import bosca.security.service.AuthenticationProviders
import bosca.security.service.OAuth2Provider
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.middleware.SessionMiddleware
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class AuthenticationModuleTest {

    private val securityConfiguration = mockk<SecurityConfiguration>()
    private val connectionPool = mockk<ConnectionPool>()
    private val securityService = mockk<SecurityService>()
    private val apiTokenService = mockk<ApiTokenService>()
    private val tracer = mockk<Tracer>()
    private val errorCapture = mockk<ErrorCapture>()
    private lateinit var application: BoscaApplication

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        val config = mockk<ApplicationConfig>(relaxed = true) {
            every { propertyOrNull(any()) } returns null
        }
        application = BoscaApplication(config)
        provides<SecurityConfiguration> { securityConfiguration }
        provides<ConnectionPool> { connectionPool }
        provides<SecurityService> { securityService }
        provides<ApiTokenService> { apiTokenService }
        provides<Tracer> { tracer }
        provides<ErrorCapture> { errorCapture }
        coEvery { securityService.getMaxTokenAgeInSeconds() } returns 3_600
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `default install registers middleware authenticators and enabled providers`() = runTest {
        every { securityConfiguration.oauth2 } returns listOf(
            provider("google", enabled = true),
            provider("disabled", enabled = false),
        )

        application.install(AuthenticationModule())

        val authMiddleware = assertIs<BoscaAuthMiddleware>(application.authMiddleware.single())
        assertSame(authMiddleware, provide<BoscaAuthMiddleware>())
        assertIs<ConnectionInitAuthenticator>(provide<GraphQLConnectionInitAuthenticator>())
        assertIs<SessionMiddleware>(application.middleware.single())
        assertContentEquals(
            arrayOf(null, "session", "basic", "api_token", "google"),
            provide<AuthenticationProviders>().providers,
        )
    }

    @Test
    fun `install can omit provider registration and session middleware`() = runTest {
        every { securityConfiguration.oauth2 } returns emptyList()

        application.install(
            AuthenticationModule(
                includeProviders = false,
                includeSession = false,
            ),
        )

        assertTrue(application.authMiddleware.single() is BoscaAuthMiddleware)
        assertTrue(application.middleware.isEmpty())
        assertFailsWith<MissingProviderException> {
            provide<AuthenticationProviders>()
        }
    }

    private fun provider(type: String, enabled: Boolean) = OAuth2Provider(
        type = type,
        clientId = "client",
        clientSecret = "secret",
        enabled = enabled,
        callback = "https://app.example/callback",
        adminCallback = "https://admin.example/callback",
        scopes = emptyList(),
        userInfoUrl = "https://provider.example/user",
        authorizeUrl = "https://provider.example/authorize",
        accessTokenUrl = "https://provider.example/token",
    )
}
