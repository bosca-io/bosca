package bosca.security.routes.oauth2

import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.LoginResponse
import bosca.security.model.OAuth2CredentialAttributes
import bosca.security.model.Token
import bosca.security.oauth2.ThirdPartyUser
import bosca.security.routes.security.SecurityRouteTestCall
import bosca.security.routes.security.SecurityRouteTestEnvironment
import bosca.security.routes.security.authRateLimitCacheManager
import bosca.security.service.AuthCookiePrefix
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.security.session.Session
import bosca.serialization.UUID
import bosca.server.Cookie
import bosca.server.routing.RoutingContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.test.runTest
import org.slf4j.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OAuth2CallbackHandlerTest {

    private val securityService = mockk<SecurityService>()
    private val securityConfiguration = mockk<SecurityConfiguration>(relaxed = true)
    private val log = mockk<Logger>(relaxed = true)
    private val tracer = mockk<Tracer>()
    private val span = mockk<Span>(relaxed = true)
    private val spanBuilder = mockk<SpanBuilder>()
    private lateinit var environment: SecurityRouteTestEnvironment

    private val handler = OAuth2CallbackHandler(securityService, securityConfiguration, tracer, log)

    private val testUser = object : ThirdPartyUser {
        override val id = "user-123"
        override val name = "Test User"
        override val givenName = "Test"
        override val familyName = "User"
        override val picture: String? = null
        override val email = "test@example.com"
        override val emailVerified = true
    }

    @BeforeTest
    fun setup() {
        environment = SecurityRouteTestEnvironment(authRateLimitCacheManager())
        every { tracer.spanBuilder(any()) } returns spanBuilder
        every { spanBuilder.startSpan() } returns span
        every { securityConfiguration.domain } returns "example.com"
        every { securityConfiguration.adminDomain } returns "admin.example.com"
        every { securityConfiguration.cookieSecure } returns true
    }

    @AfterTest
    fun tearDown() {
        environment.close()
    }

    @Test
    fun `handler can be constructed with required dependencies`() {
        assertNotNull(handler)
    }

    @Test
    fun `OAuth2State carries code verifier for PKCE`() {
        val state = OAuth2State(
            state = "test-state",
            provider = "google",
            tokens = emptyList(),
            redirect = "/dashboard",
            admin = false,
            codeVerifier = "test-verifier-abc123"
        )
        assertNotNull(state.codeVerifier)
        kotlin.test.assertEquals("test-verifier-abc123", state.codeVerifier)
    }

    @Test
    fun `cross domain callback creates encoded exchange token and preserves redirect query`() = runTest {
        val testCall = SecurityRouteTestCall()
        val context = RoutingContext(testCall.call, testCall.application)
        val loginResponse = response(accountCreated = true, originator = "studio")
        val state = state(
            redirect = "https://other.example/complete?source=oauth",
            originator = "studio",
        )
        val requestOrigin = slot<String?>()
        val originator = slot<String?>()
        coEvery {
            securityService.loginWithThirdParty(
                any(),
                testUser,
                any(),
                true,
                emptyList(),
                captureNullable(requestOrigin),
                captureNullable(originator),
            )
        } returns loginResponse
        coEvery {
            securityService.createExchangeToken(
                loginResponse.principalId,
                true,
                "studio",
            )
        } returns "token with spaces"

        with(handler) { context.handleCallback(state, "google", testUser) }

        assertEquals("https://other.example", requestOrigin.captured)
        assertEquals("studio", originator.captured)
        verify {
            testCall.response.respondRedirect(
                "https://other.example/complete?source=oauth&exchangeToken=token+with+spaces",
                false,
            )
        }
        coVerify {
            securityService.createExchangeToken(loginResponse.principalId, true, "studio")
        }
        verify { span.end() }
        verify(exactly = 0) { testCall.sessions.set(any()) }
    }

    @Test
    fun `same domain callback establishes ordinary session and companion cookies`() = runTest {
        val testCall = SecurityRouteTestCall(
            languages = listOf(bosca.server.LanguageItem("fr-CA", 1.0f)),
        )
        val context = RoutingContext(testCall.call, testCall.application)
        val loginResponse = response(
            refreshToken = "refresh",
            accountCreated = true,
            originator = "web",
        )
        coEvery {
            securityService.loginWithThirdParty(any(), testUser, any(), true, any(), any(), any())
        } returns loginResponse

        with(handler) {
            context.handleCallback(
                state(redirect = "https://studio.example/complete", originator = "web"),
                "google",
                testUser,
            )
        }

        verify { testCall.sessions.clear() }
        verify { testCall.sessions.set(Session(loginResponse, false)) }
        val cookies = mutableListOf<Cookie>()
        verify(exactly = 3) {
            testCall.response.cookies.append(capture(cookies))
        }
        assertEquals(listOf("_bat_rt", "_bat_meta", "_bat_signin"), cookies.map { it.name })
        assertEquals("example.com", cookies.first().domain)
        verify {
            testCall.response.respondRedirect(
                "https://studio.example/complete",
                false,
            )
        }
        coVerify(exactly = 0) { securityService.createExchangeToken(any(), any(), any()) }
    }

    @Test
    fun `same domain callback keeps every custom cookie host-only`() = runTest {
        val prefix = AuthCookiePrefix("_bat_preview", listOf("studio.example"))
        every { securityConfiguration.authCookiePrefixes } returns listOf(prefix)
        val testCall = SecurityRouteTestCall()
        val context = RoutingContext(testCall.call, testCall.application)
        val loginResponse = response(refreshToken = "preview-refresh")
        coEvery {
            securityService.loginWithThirdParty(any(), testUser, any(), true, any(), any(), any())
        } returns loginResponse

        with(handler) {
            context.handleCallback(
                state(redirect = "https://studio.example/complete"),
                "google",
                testUser,
            )
        }

        verify { testCall.sessions.set(Session(loginResponse, false)) }
        val cookies = mutableListOf<Cookie>()
        verify(exactly = 3) { testCall.response.cookies.append(capture(cookies)) }
        assertEquals(
            listOf("_bat_preview_rt", "_bat_preview_meta", "_bat_preview_signin"),
            cookies.map { it.name },
        )
        assertEquals(listOf(null, null, null), cookies.map { it.domain })
    }

    @Test
    fun `connect callback links the provider without replacing the current login`() = runTest {
        val principalId = UUID.random()
        val testCall = SecurityRouteTestCall()
        val context = RoutingContext(testCall.call, testCall.application)
        coEvery {
            securityService.connectThirdParty(
                principalId,
                any<OAuth2CredentialAttributes>(),
                testUser,
            )
        } returns mockk()

        with(handler) {
            context.handleCallback(
                state(
                    redirect = "https://studio.example/security?tab=accounts",
                    connectPrincipalId = principalId,
                ),
                "google",
                testUser,
            )
        }

        coVerify(exactly = 1) {
            securityService.connectThirdParty(
                principalId,
                match {
                    it is OAuth2CredentialAttributes &&
                        it.source == "google" &&
                        it.identifier == testUser.id
                },
                testUser,
            )
        }
        coVerify(exactly = 0) {
            securityService.loginWithThirdParty(any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) {
            testCall.sessions.clear()
            testCall.sessions.set(any())
        }
        verify {
            testCall.response.respondRedirect(
                "https://studio.example/security?tab=accounts&connected=google",
                false,
            )
            span.end()
        }
    }

    @Test
    fun `connect callback appends its result to a redirect without a query`() = runTest {
        val principalId = UUID.random()
        val testCall = SecurityRouteTestCall()
        val context = RoutingContext(testCall.call, testCall.application)
        coEvery {
            securityService.connectThirdParty(principalId, any<OAuth2CredentialAttributes>(), testUser)
        } returns mockk()

        with(handler) {
            context.handleCallback(
                state(
                    redirect = "https://studio.example/security",
                    connectPrincipalId = principalId,
                ),
                "google",
                testUser,
            )
        }

        verify {
            testCall.response.respondRedirect(
                "https://studio.example/security?connected=google",
                false,
            )
        }
    }

    @Test
    fun `same domain admin callback evaluates groups and omits absent refresh cookie`() = runTest {
        val testCall = SecurityRouteTestCall()
        val context = RoutingContext(testCall.call, testCall.application)
        val loginResponse = response(refreshToken = null)
        coEvery {
            securityService.loginWithThirdParty(any(), testUser, any(), true, any(), any(), any())
        } returns loginResponse
        coEvery {
            securityService.getPrincipalGroups(loginResponse.principalId)
        } returns listOf(
            Group(
                id = UUID.random(),
                name = "administrators",
                description = "Administrators",
                type = GroupType.SYSTEM,
            ),
        )

        with(handler) {
            context.handleCallback(
                state(redirect = "https://studio.example/admin", admin = true),
                "google",
                testUser,
            )
        }

        verify { testCall.sessions.set(Session(loginResponse, true)) }
        val cookies = mutableListOf<Cookie>()
        verify(exactly = 2) {
            testCall.response.cookies.append(capture(cookies))
        }
        assertEquals(listOf("_bat_meta", "_bat_signin"), cookies.map { it.name })
        assertEquals(listOf("admin.example.com", "admin.example.com"), cookies.map { it.domain })
    }

    @Test
    fun `admin callback without administrator membership stays non-admin`() = runTest {
        val testCall = SecurityRouteTestCall()
        val context = RoutingContext(testCall.call, testCall.application)
        val loginResponse = response(refreshToken = null)
        coEvery { securityService.loginWithThirdParty(any(), testUser, any(), true, any(), any(), any()) } returns loginResponse
        coEvery { securityService.getPrincipalGroups(loginResponse.principalId) } returns listOf(
            Group(
                id = UUID.random(),
                name = "users",
                description = "Users",
                type = GroupType.SYSTEM,
            )
        )

        with(handler) {
            context.handleCallback(
                state(redirect = "https://studio.example/admin", admin = true),
                "google",
                testUser,
            )
        }

        verify { testCall.sessions.set(Session(loginResponse, false)) }
    }

    @Test
    fun `explicit redirect ports are preserved and treated as cross-domain`() = runTest {
        val testCall = SecurityRouteTestCall()
        val context = RoutingContext(testCall.call, testCall.application)
        val requestOrigin = slot<String?>()
        coEvery {
            securityService.loginWithThirdParty(any(), testUser, any(), true, any(), captureNullable(requestOrigin), any())
        } returns response()
        coEvery { securityService.createExchangeToken(any(), any(), any()) } returns "exchange"

        with(handler) {
            context.handleCallback(
                state("https://studio.example:8443/complete"),
                "google",
                testUser,
            )
        }

        assertEquals("https://studio.example:8443", requestOrigin.captured)
        verify {
            testCall.response.respondRedirect(
                "https://studio.example:8443/complete?exchangeToken=exchange",
                false,
            )
        }
    }

    @Test
    fun `relative malformed and nonstandard redirects use safe origin and domain decisions`() = runTest {
        val relativeCall = SecurityRouteTestCall()
        val malformedCall = SecurityRouteTestCall()
        val nonstandardCall = SecurityRouteTestCall()
        val protocolRelativeCall = SecurityRouteTestCall()
        val httpCall = SecurityRouteTestCall()
        val origins = mutableListOf<String?>()
        coEvery {
            securityService.loginWithThirdParty(
                any(),
                testUser,
                any(),
                true,
                any(),
                captureNullable(origins),
                any(),
            )
        } returns response()
        coEvery { securityService.createExchangeToken(any(), any(), any()) } returns "exchange"

        with(handler) {
            RoutingContext(relativeCall.call, relativeCall.application)
                .handleCallback(state("/complete"), "google", testUser)
            RoutingContext(malformedCall.call, malformedCall.application)
                .handleCallback(state("https://["), "google", testUser)
            RoutingContext(nonstandardCall.call, nonstandardCall.application)
                .handleCallback(state("ftp://studio.example/file"), "google", testUser)
            RoutingContext(protocolRelativeCall.call, protocolRelativeCall.application)
                .handleCallback(state("//studio.example/complete"), "google", testUser)
            RoutingContext(httpCall.call, httpCall.application)
                .handleCallback(state("http://studio.example/complete"), "google", testUser)
        }

        assertEquals(5, origins.size)
        assertNull(origins[0])
        assertNull(origins[1])
        assertEquals("ftp://studio.example", origins[2])
        assertNull(origins[3])
        assertEquals("http://studio.example", origins[4])
        verify { relativeCall.response.respondRedirect("/complete", false) }
        verify { malformedCall.response.respondRedirect("https://[", false) }
        verify {
            nonstandardCall.response.respondRedirect(
                "ftp://studio.example/file?exchangeToken=exchange",
                false,
            )
        }
        verify { protocolRelativeCall.response.respondRedirect("//studio.example/complete", false) }
        verify {
            httpCall.response.respondRedirect(
                "http://studio.example/complete?exchangeToken=exchange",
                false,
            )
        }
    }

    private fun state(
        redirect: String,
        admin: Boolean = false,
        originator: String? = null,
        connectPrincipalId: UUID? = null,
    ) = OAuth2State(
        state = "state",
        provider = "google",
        tokens = emptyList(),
        redirect = redirect,
        admin = admin,
        codeVerifier = "verifier",
        originator = originator,
        connectPrincipalId = connectPrincipalId,
    )

    private fun response(
        refreshToken: String? = "refresh",
        accountCreated: Boolean = false,
        originator: String? = null,
    ) = LoginResponse(
        principalId = UUID.random(),
        refreshToken = refreshToken,
        token = Token(expiresAt = 3600, issuedAt = 1, token = "signed"),
        accountCreated = accountCreated,
        originator = originator,
    )
}
