package bosca.security.routes.oauth2

import bosca.cache.Cache
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.StringCacheKey
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.observability.ErrorCapture
import bosca.security.model.LinkProofMethod
import bosca.security.model.LoginResponse
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.model.Token
import bosca.security.oauth2.DefaultOauth2User
import bosca.security.routes.security.SecurityRouteTestCall
import bosca.security.routes.security.SecurityRouteTestEnvironment
import bosca.security.service.AccountLinkRequired
import bosca.security.service.OAuth2Provider
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.HttpMethod
import bosca.server.HttpStatusCode
import bosca.server.config.ApplicationConfig
import bosca.server.routing.RoutingContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalDI::class, Internal::class)
class OAuth2ModuleTest {

    private val google = provider("google", pkceEnabled = true)
    private val facebook = provider("facebook", pkceEnabled = false)
    private val disabled = provider("disabled", enabled = false)
    private val securityConfiguration = mockk<SecurityConfiguration>(relaxed = true) {
        every { oauth2 } returns listOf(google, facebook, disabled)
        every { allowedRedirects } returns listOf("https://studio.example", "/")
        every { domain } returns "studio.example"
        every { adminDomain } returns "admin.example"
        every { cookieSecure } returns true
    }
    private val securityService = mockk<SecurityService>()
    private val cache = mockk<Cache<String>>(relaxed = true)
    private val cacheManager = mockk<CacheManager> {
        coEvery { maybeAddCache<String>(any(), any(), any()) } returns cache
    }
    private val tracer = mockk<Tracer>()
    private val span = mockk<Span>(relaxed = true)
    private val spanBuilder = mockk<SpanBuilder> {
        every { startSpan() } returns span
    }
    private val errorCapture = mockk<ErrorCapture>(relaxed = true)
    private lateinit var application: BoscaApplication
    private lateinit var json: Json
    private lateinit var environment: SecurityRouteTestEnvironment

    @BeforeTest
    fun setUp() = runTest {
        environment = SecurityRouteTestEnvironment(cacheManager)
        val config = mockk<ApplicationConfig>(relaxed = true) {
            every { propertyOrNull(any()) } returns null
        }
        application = BoscaApplication(config)
        json = application.json
        provides<SecurityConfiguration> { securityConfiguration }
        provides<SecurityService> { securityService }
        provides<Tracer> { tracer }
        provides<ErrorCapture> { errorCapture }
        every { tracer.spanBuilder(any()) } returns spanBuilder
        mockkConstructor(OAuth2TokenExchanger::class)
        mockkConstructor(OAuth2UserInfoFetcher::class)
        coEvery {
            anyConstructed<OAuth2TokenExchanger>().exchange(any(), any(), any(), isNull())
        } returns ("access-token" to null)
        coEvery {
            anyConstructed<OAuth2TokenExchanger>().exchange(any(), any(), any(), any())
        } returns ("access-token" to null)
        coEvery {
            anyConstructed<OAuth2UserInfoFetcher>().fetchUser(any(), any())
        } returns DefaultOauth2User(id = "subject", email = "person@example.com")
        application.install(OAuth2Module())
    }

    @AfterTest
    fun tearDown() {
        unmockkConstructor(OAuth2UserInfoFetcher::class)
        unmockkConstructor(OAuth2TokenExchanger::class)
        environment.close()
    }

    @Test
    fun `install registers enabled provider routes and the facebook legacy handler`() = runTest {
        assertNotNull(application.router.resolve(HttpMethod.Get, "/oauth2/google/login"))
        assertNotNull(application.router.resolve(HttpMethod.Get, "/oauth2/google/connect"))
        assertNotNull(application.router.resolve(HttpMethod.Get, "/oauth2/google/callback"))
        assertNotNull(application.router.resolve(HttpMethod.Get, "/oauth2/facebook/login"))
        assertNotNull(application.router.resolve(HttpMethod.Get, "/oauth2/facebook/connect"))
        assertNotNull(application.router.resolve(HttpMethod.Get, "/oauth2/facebook/callback"))
        assertNotNull(application.router.resolve(HttpMethod.Get, "/__/auth/handler"))
        assertNull(application.router.resolve(HttpMethod.Get, "/oauth2/disabled/login"))
    }

    @Test
    fun `install omits the legacy handler when no enabled facebook provider exists`() = runTest {
        every { securityConfiguration.oauth2 } returns listOf(
            google,
            disabled,
            provider("facebook", enabled = false),
        )
        val config = mockk<ApplicationConfig>(relaxed = true) {
            every { propertyOrNull(any()) } returns null
        }
        val withoutFacebook = BoscaApplication(config)

        withoutFacebook.install(OAuth2Module())

        assertNull(withoutFacebook.router.resolve(HttpMethod.Get, "/__/auth/handler"))
    }

    @Test
    fun `login builds provider authorization redirects with callback and PKCE settings`() = runTest {
        val googleCall = invoke(
            "/oauth2/google/login",
            mapOf("admin" to "true", "redirect" to "https://studio.example/complete"),
        )
        val facebookCall = invoke("/oauth2/facebook/login")

        verify {
            googleCall.response.respondRedirect(
                match {
                    it.startsWith("https://accounts.example/google?") &&
                        "client_id=client+google" in it &&
                        "redirect_uri=https%3A%2F%2Fadmin.example%2Fgoogle" in it &&
                        "scope=openid+email" in it &&
                        "code_challenge=" in it &&
                        "code_challenge_method=S256" in it
                },
                false,
            )
        }
        verify {
            facebookCall.response.respondRedirect(
                match {
                    it.startsWith("https://accounts.example/facebook?") &&
                        "redirect_uri=https%3A%2F%2Fapp.example%2Ffacebook" in it &&
                        "code_challenge" !in it
                },
                false,
            )
        }
        coVerify(exactly = 2) { cache.put(any(), any()) }
    }

    @Test
    fun `connect stores the authenticated principal in oauth state`() = runTest {
        val principal = Principal(id = UUID.random())
        val storedState = io.mockk.slot<String>()
        coEvery { cache.put(any(), capture(storedState)) } returns Unit
        val call = SecurityRouteTestCall(
            query = mapOf("redirect" to "https://studio.example/security"),
        )
        call.authenticationContext.principal(
            "test",
            AuthenticatedPrincipal(principal, emptyList()),
        )
        val route = assertNotNull(application.router.resolve(HttpMethod.Get, "/oauth2/google/connect"))

        route.handler(RoutingContext(call.call, application))

        val state = json.decodeFromString<OAuth2State>(storedState.captured)
        assertTrue(route.authConfig != null)
        kotlin.test.assertEquals(principal.id, state.connectPrincipalId)
        kotlin.test.assertEquals("https://studio.example/security", state.redirect)
        verify {
            call.response.respondRedirect(
                match { it.startsWith("https://accounts.example/google?") },
                false,
            )
        }
    }

    @Test
    fun `connect rejects API tokens before creating provider state`() = runTest {
        val call = SecurityRouteTestCall()
        every { call.call.respond(HttpStatusCode.Forbidden) } answers { call.response.respond(HttpStatusCode.Forbidden) }
        call.authenticationContext.principal("test", ScopedAuthenticatedPrincipal(
            Principal(id = UUID.random(), anonymous = false), emptyList(), listOf("content:view"), null, 1L,
        ))
        val route = assertNotNull(application.router.resolve(HttpMethod.Get, "/oauth2/google/connect"))
        route.handler(RoutingContext(call.call, application))
        verify { call.response.respond(HttpStatusCode.Forbidden) }
        coVerify(exactly = 0) { cache.put(any(), any()) }
    }

    @Test
    fun `connect refuses to create state without an authenticated principal`() = runTest {
        val call = SecurityRouteTestCall(
            query = mapOf("redirect" to "https://studio.example/security"),
        )
        val route = assertNotNull(application.router.resolve(HttpMethod.Get, "/oauth2/google/connect"))

        assertFailsWith<IllegalStateException> {
            route.handler(RoutingContext(call.call, application))
        }

        coVerify(exactly = 0) { cache.put(any(), any()) }
    }

    @Test
    fun `provider callback handles provider errors and missing callback parameters`() = runTest {
        val denied = invoke(
            "/oauth2/google/callback",
            mapOf("error" to "access_denied", "error_description" to "No thanks"),
        )
        val defaultDenied = invoke(
            "/oauth2/google/callback",
            mapOf("error" to "access_denied"),
        )
        val missingCode = invoke("/oauth2/google/callback")
        val missingState = invoke("/oauth2/google/callback", mapOf("code" to "code"))

        verify { denied.response.respondRedirect("/?error=No+thanks", false) }
        verify { defaultDenied.response.respondRedirect("/?error=Authorization+denied", false) }
        verify { missingCode.response.respondRedirect("/?error=missing_code", false) }
        verify { missingState.response.respondRedirect("/?error=oauth2.state.null", false) }
        coVerify {
            errorCapture.capture(
                any<IllegalStateException>(),
                missingState.call,
                match { it["oauth2.error"] == "state.null" },
            )
        }
    }

    @Test
    fun `provider callback rejects missing and mismatched cached state`() = runTest {
        coEvery { cache.remove(any()) } returnsMany listOf(
            null,
            stateValue(state(provider = "facebook")),
        )

        val missing = invoke(
            "/oauth2/google/callback",
            mapOf("code" to "code", "state" to "missing"),
        )
        val mismatch = invoke(
            "/oauth2/google/callback",
            mapOf("code" to "code", "state" to "mismatch"),
        )

        verify { missing.response.respondRedirect("/?error=oauth2.state.not.found", false) }
        verify { mismatch.response.respondRedirect("/?error=oauth2.state.provider.mismatch", false) }
        coVerify(exactly = 2) {
            errorCapture.capture(
                any<IllegalStateException>(),
                any(),
                match {
                    it["oauth2.error"] == "state.not.found" ||
                        it["oauth2.error"] == "state.provider.mismatch"
                },
            )
        }
    }

    @Test
    fun `provider callback exchanges code fetches user and completes login`() = runTest {
        val oauthState = state(
            redirect = "https://other.example/complete?source=oauth",
            admin = true,
            codeVerifier = "verifier",
        )
        coEvery { cache.remove(any()) } returns stateValue(oauthState)
        coEvery {
            anyConstructed<OAuth2TokenExchanger>().exchange(
                any(),
                any(),
                any(),
                any(),
            )
        } returns ("access-token" to null)
        val user = DefaultOauth2User(
            id = "subject",
            name = "Person",
            email = "person@example.com",
            emailVerifiedClaim = true,
        )
        coEvery {
            anyConstructed<OAuth2UserInfoFetcher>().fetchUser(any(), any())
        } returns user
        val login = loginResponse()
        coEvery {
            securityService.loginWithThirdParty(any(), user, any(), true, any(), any(), any())
        } returns login
        coEvery {
            securityService.createExchangeToken(login.principalId, false, "module-test")
        } returns "exchange token"

        val call = invoke(
            "/oauth2/google/callback",
            mapOf("code" to "authorization-code", "state" to "valid"),
        )

        verify {
            call.response.respondRedirect(
                "https://other.example/complete?source=oauth&exchangeToken=exchange+token",
                false,
            )
        }
        verify { span.end() }
    }

    @Test
    fun `provider callback routes account linking with no existing redirect query`() = runTest {
        coEvery { cache.remove(any()) } returns stateValue(
            state(redirect = "https://studio.example/finish")
        )
        val user = DefaultOauth2User(id = "subject", email = "person@example.com")
        coEvery { anyConstructed<OAuth2UserInfoFetcher>().fetchUser(any(), any()) } returns user
        coEvery {
            securityService.loginWithThirdParty(any(), user, any(), true, any(), any(), any())
        } throws AccountLinkRequired(
            email = "person@example.com",
            existingPrincipalId = UUID.random(),
            token = "link token",
            methods = listOf(LinkProofMethod.EMAIL),
        )

        val call = invoke(
            "/oauth2/google/callback",
            mapOf("code" to "authorization-code", "state" to "valid"),
        )

        verify {
            call.response.respondRedirect(
                "https://studio.example/finish?link=link+token&methods=EMAIL",
                false,
            )
        }
    }

    @Test
    fun `legacy facebook callback covers validation and account linking redirects`() = runTest {
        val denied = invoke(
            "/__/auth/handler",
            mapOf("error" to "denied", "error_description" to "User denied"),
        )
        val defaultDenied = invoke("/__/auth/handler", mapOf("error" to "denied"))
        val missingCode = invoke("/__/auth/handler")
        val missingState = invoke("/__/auth/handler", mapOf("code" to "code"))
        coEvery { cache.remove(any()) } returnsMany listOf(
            null,
            stateValue(state(provider = "google")),
            stateValue(
                state(
                    provider = "facebook",
                    redirect = "https://studio.example/finish?source=facebook",
                    codeVerifier = "unused",
                ),
            ),
        )
        val missing = invoke(
            "/__/auth/handler",
            mapOf("code" to "code", "state" to "missing"),
        )
        val mismatch = invoke(
            "/__/auth/handler",
            mapOf("code" to "code", "state" to "mismatch"),
        )
        val user = DefaultOauth2User(id = "facebook-user", email = "person@example.com")
        coEvery {
            anyConstructed<OAuth2TokenExchanger>().exchange(
                any(),
                any(),
                any(),
                isNull(),
            )
        } returns ("facebook-token" to "ignored-state")
        coEvery {
            anyConstructed<OAuth2UserInfoFetcher>().fetchUser(any(), any())
        } returns user
        coEvery {
            securityService.loginWithThirdParty(any(), user, any(), true, any(), any(), any())
        } throws AccountLinkRequired(
            email = "person@example.com",
            existingPrincipalId = UUID.random(),
            token = "link token",
            methods = listOf(LinkProofMethod.EMAIL, LinkProofMethod.PASSWORD),
        )
        val linked = invoke(
            "/__/auth/handler",
            mapOf("code" to "code", "state" to "valid"),
        )

        verify { denied.response.respondRedirect("/?error=User+denied", false) }
        verify { defaultDenied.response.respondRedirect("/?error=Authorization+denied", false) }
        verify { missingCode.response.respondRedirect("/?error=missing_code", false) }
        verify { missingState.response.respondRedirect("/?error=oauth2.state.null", false) }
        verify { missing.response.respondRedirect("/?error=oauth2.state.not.found", false) }
        verify { mismatch.response.respondRedirect("/?error=oauth2.state.provider.mismatch", false) }
        verify {
            linked.response.respondRedirect(
                "https://studio.example/finish?source=facebook&link=link+token&methods=EMAIL%2CPASSWORD",
                false,
            )
        }
    }

    private suspend fun invoke(
        path: String,
        query: Map<String, String> = emptyMap(),
    ): SecurityRouteTestCall {
        val call = SecurityRouteTestCall(query = query)
        val route = assertNotNull(application.router.resolve(HttpMethod.Get, path))
        route.handler(RoutingContext(call.call, application))
        return call
    }

    private fun state(
        provider: String = "google",
        redirect: String = "/",
        admin: Boolean = false,
        codeVerifier: String = "verifier",
        connectPrincipalId: UUID? = null,
    ) = OAuth2State(
        state = "state",
        provider = provider,
        tokens = emptyList(),
        redirect = redirect,
        admin = admin,
        codeVerifier = codeVerifier,
        originator = "module-test",
        connectPrincipalId = connectPrincipalId,
    )

    private fun stateValue(state: OAuth2State): CacheValue = object : CacheValue {
        override val value = json.encodeToString(OAuth2State.serializer(), state)
        override val exists = true
    }

    private fun loginResponse() = LoginResponse(
        principalId = UUID.random(),
        refreshToken = null,
        token = Token(expiresAt = 200, issuedAt = 100, token = "jwt"),
        accountCreated = false,
        originator = "module-test",
    )

    companion object {
        private fun provider(
            type: String,
            enabled: Boolean = true,
            pkceEnabled: Boolean = true,
        ) = OAuth2Provider(
            type = type,
            clientId = "client $type",
            clientSecret = "secret $type",
            enabled = enabled,
            callback = "https://app.example/$type",
            adminCallback = "https://admin.example/$type",
            scopes = listOf("openid", "email"),
            userInfoUrl = "https://userinfo.example/$type",
            authorizeUrl = "https://accounts.example/$type",
            accessTokenUrl = "https://tokens.example/$type",
            pkceEnabled = pkceEnabled,
        )
    }
}
