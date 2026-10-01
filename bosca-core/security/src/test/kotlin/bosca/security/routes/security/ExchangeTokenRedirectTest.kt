package bosca.security.routes.security

import bosca.core.annotations.Internal
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(Internal::class)
class ExchangeTokenRedirectTest {

    private val securityService = mockk<SecurityService>()
    private val configuration = mockk<SecurityConfiguration> {
        every { allowedRedirects } returns listOf("https://warehouse.example")
    }
    private lateinit var environment: SecurityRouteTestEnvironment

    @BeforeTest
    fun setUp() {
        environment = SecurityRouteTestEnvironment(authRateLimitCacheManager())
    }

    @AfterTest
    fun tearDown() {
        environment.close()
    }

    @Test
    fun `authenticated request redirects with a single-use exchange token`() = runTest {
        val principalId = UUID.random()
        val redirect = "https://warehouse.example/auth/studio/callback?state=state-123"
        val testCall = SecurityRouteTestCall(query = mapOf("redirect" to redirect))
        testCall.authenticationContext.principal(
            "session",
            AuthenticatedPrincipal(Principal(id = principalId), emptyList()),
        )
        coEvery {
            securityService.createExchangeToken(
                principalId,
                accountCreated = false,
                originator = null,
            )
        } returns "one+time-token"

        ExchangeTokenRedirect(securityService, configuration).execute(testCall.call)

        coVerify {
            securityService.createExchangeToken(
                principalId,
                accountCreated = false,
                originator = null,
            )
        }
        verify {
            testCall.response.header("Cache-Control", "no-store")
            testCall.response.header("Referrer-Policy", "no-referrer")
            testCall.response.respondRedirect(
                "https://warehouse.example/auth/studio/callback?state=state-123&exchangeToken=one%2Btime-token",
                false,
            )
        }
    }

    @Test
    fun `API tokens cannot initiate the browser handoff regardless of scopes or groups`() = runTest {
        val principal = Principal(id = UUID.random(), anonymous = false, verified = true)
        val admin = Group(
            id = UUID.random(),
            name = "administrators",
            description = "Admins",
            type = GroupType.SYSTEM,
        )
        val route = ExchangeTokenRedirect(securityService, configuration)
        coEvery { securityService.createExchangeToken(any(), any(), any()) } returns "exchange-token"

        for (groups in listOf(emptyList(), listOf(admin))) {
            for (scopes in listOf(emptyList(), listOf("content:view"), listOf("security:manage"), null)) {
                val testCall = SecurityRouteTestCall(
                    query = mapOf("redirect" to "https://warehouse.example/auth/studio/callback"),
                )
                testCall.authenticationContext.principal(
                    "api_token",
                    ScopedAuthenticatedPrincipal(principal, groups, scopes, null, 1L),
                )

                route.execute(testCall.call)

                verify {
                    testCall.response.respondText(
                        "API tokens cannot create interactive sessions",
                        ContentType.Text.Plain,
                        HttpStatusCode.Forbidden,
                    )
                }
                verify(exactly = 0) { testCall.response.respondRedirect(any(), any()) }
            }
        }
        coVerify(exactly = 0) { securityService.createExchangeToken(any(), any(), any()) }
    }

    @Test
    fun `JWT and password authentication can initiate the browser handoff`() = runTest {
        val principal = Principal(id = UUID.random(), anonymous = false, verified = true)
        coEvery { securityService.createExchangeToken(principal.id, false, null) } returns "exchange-token"
        val route = ExchangeTokenRedirect(securityService, configuration)

        for (provider in listOf("bearer", "basic")) {
            val testCall = SecurityRouteTestCall(
                query = mapOf("redirect" to "https://warehouse.example/auth/studio/callback"),
            )
            testCall.authenticationContext.principal(provider, AuthenticatedPrincipal(principal, emptyList()))

            route.execute(testCall.call)

            verify {
                testCall.response.respondRedirect(
                    "https://warehouse.example/auth/studio/callback?exchangeToken=exchange-token",
                    false,
                )
            }
        }
        coVerify(exactly = 2) { securityService.createExchangeToken(principal.id, false, null) }
    }

    @Test
    fun `request outside OAuth redirect allow-list does not mint a token`() = runTest {
        val testCall = SecurityRouteTestCall(
            query = mapOf(
                "redirect" to "https://attacker.example/auth/studio/callback?state=state-123",
            ),
        )

        ExchangeTokenRedirect(securityService, configuration).execute(testCall.call)

        coVerify(exactly = 0) {
            securityService.createExchangeToken(any(), any(), any())
        }
        verify {
            testCall.response.respondText(
                "redirect not allowed",
                ContentType.Text.Plain,
                HttpStatusCode.BadRequest,
            )
        }
    }

    @Test
    fun `missing and blank redirects are rejected before authentication`() = runTest {
        val missing = SecurityRouteTestCall()
        val blank = SecurityRouteTestCall(query = mapOf("redirect" to "  "))
        val route = ExchangeTokenRedirect(securityService, configuration)

        route.execute(missing.call)
        route.execute(blank.call)

        verify {
            missing.response.respondText(
                "redirect not allowed",
                ContentType.Text.Plain,
                HttpStatusCode.BadRequest,
            )
            blank.response.respondText(
                "redirect not allowed",
                ContentType.Text.Plain,
                HttpStatusCode.BadRequest,
            )
        }
        coVerify(exactly = 0) { securityService.createExchangeToken(any(), any(), any()) }
    }

    @Test
    fun `allowed redirect without an authenticated principal does not mint a token`() = runTest {
        val testCall = SecurityRouteTestCall(
            query = mapOf("redirect" to "https://warehouse.example/auth/studio/callback"),
        )

        ExchangeTokenRedirect(securityService, configuration).execute(testCall.call)

        coVerify(exactly = 0) { securityService.createExchangeToken(any(), any(), any()) }
    }

    @Test
    fun `redirect without an existing query uses a question-mark separator`() = runTest {
        val principalId = UUID.random()
        val testCall = SecurityRouteTestCall(
            query = mapOf("redirect" to "https://warehouse.example/auth/studio/callback"),
        )
        testCall.authenticationContext.principal(
            "session",
            AuthenticatedPrincipal(Principal(id = principalId), emptyList()),
        )
        coEvery { securityService.createExchangeToken(principalId, false, null) } returns "token"

        ExchangeTokenRedirect(securityService, configuration).execute(testCall.call)

        verify {
            testCall.response.respondRedirect(
                "https://warehouse.example/auth/studio/callback?exchangeToken=token",
                false,
            )
        }
    }
}
