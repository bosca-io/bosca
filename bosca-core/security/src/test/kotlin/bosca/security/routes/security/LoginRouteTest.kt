package bosca.security.routes.security

import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.LoginResponse
import bosca.security.model.SignupToken
import bosca.security.model.SignupTokenType
import bosca.security.model.Token
import bosca.cache.CacheManager
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.security.session.Session
import bosca.serialization.UUID
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginRouteTest {

    private val securityService = mockk<SecurityService>()
    private val securityConfiguration = mockk<SecurityConfiguration> {
        every { allowedRedirects } returns listOf("https://studio.example")
        every { authCookiePrefixes } returns emptyList()
    }
    private val json = Json
    private lateinit var cacheManager: CacheManager
    private lateinit var environment: SecurityRouteTestEnvironment

    @BeforeTest
    fun setUp() {
        cacheManager = authRateLimitCacheManager()
        environment = SecurityRouteTestEnvironment(cacheManager)
    }

    @AfterTest
    fun tearDown() {
        environment.close()
    }

    @Test
    fun `json login authenticates records session and returns response`() = runTest {
        val loginResponse = response()
        val testCall = jsonCall()
        coEvery {
            securityService.loginWithCredential(any(), true, emptyList())
        } returns loginResponse

        Login(securityService, securityConfiguration, cacheManager)
            .execute(testCall.call)

        val session = slot<Any>()
        verify { testCall.sessions.clear() }
        verify { testCall.sessions.set(capture(session)) }
        assertEquals(Session(loginResponse, false), session.captured)
        verify {
            testCall.response.respondText(
                any(),
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        }
    }

    @Test
    fun `form login uses redirects signup tokens and administrator session`() = runTest {
        val loginResponse = response()
        val testCall = SecurityRouteTestCall(
            contentType = "application/x-www-form-urlencoded",
            query = mapOf(
                "admin" to "true",
                "organization" to "organization-token",
                "community" to "community-token",
            ),
            form = mapOf(
                "identifier" to "person@example.com",
                "password" to "password",
                "redirect" to "https://studio.example/dashboard",
            ),
        )
        val signupTokens = slot<List<SignupToken>>()
        coEvery {
            securityService.loginWithCredential(
                any(),
                true,
                capture(signupTokens),
            )
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

        Login(securityService, securityConfiguration, cacheManager)
            .execute(testCall.call)

        val session = slot<Any>()
        verify { testCall.sessions.set(capture(session)) }
        assertEquals(Session(loginResponse, true), session.captured)
        assertEquals(
            listOf(
                SignupToken(SignupTokenType.ORGANIZATION, "organization-token"),
                SignupToken(SignupTokenType.COMMUNITY_GROUP, "community-token"),
            ),
            signupTokens.captured,
        )
        verify {
            testCall.response.respondRedirect(
                "https://studio.example/dashboard",
                false,
            )
        }
    }

    @Test
    fun `form login without redirect commits an empty success`() = runTest {
        val testCall = SecurityRouteTestCall(
            contentType = "application/x-www-form-urlencoded",
            form = mapOf(
                "identifier" to "person@example.com",
                "password" to "password",
            ),
        )
        coEvery {
            securityService.loginWithCredential(any(), true, emptyList())
        } returns response()

        Login(securityService, securityConfiguration, cacheManager)
            .execute(testCall.call)

        verify { testCall.response.status(HttpStatusCode.OK) }
        verify { testCall.response.commit(any()) }
    }

    @Test
    fun `admin session request remains non-admin without the administrators group`() = runTest {
        val loginResponse = response()
        val testCall = SecurityRouteTestCall(
            contentType = "application/x-www-form-urlencoded",
            query = mapOf("admin" to "true"),
            form = mapOf(
                "identifier" to "person@example.com",
                "password" to "password",
            ),
        )
        coEvery { securityService.loginWithCredential(any(), true, emptyList()) } returns loginResponse
        coEvery { securityService.getPrincipalGroups(loginResponse.principalId) } returns listOf(
            Group(
                id = UUID.random(),
                name = "users",
                description = "Users",
                type = GroupType.SYSTEM,
            )
        )

        Login(securityService, securityConfiguration, cacheManager).execute(testCall.call)

        val session = slot<Any>()
        verify { testCall.sessions.set(capture(session)) }
        assertEquals(Session(loginResponse, false), session.captured)
    }

    @Test
    fun `security failures return generic JSON unauthorized response`() = runTest {
        val testCall = jsonCall()
        coEvery {
            securityService.loginWithCredential(any(), true, emptyList())
        } throws SecurityException("bad credentials")

        Login(securityService, securityConfiguration, cacheManager)
            .execute(testCall.call)

        verify {
            testCall.response.respondText(
                match { it.contains("authentication.failed") },
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
        }
    }

    @Test
    fun `form security failures distinguish unverified and invalid credentials`() = runTest {
        val route = Login(securityService, securityConfiguration, cacheManager)
        val unverified = formErrorCall()
        val invalid = formErrorCall()
        coEvery {
            securityService.loginWithCredential(any(), true, emptyList())
        } throws SecurityException("email not verified") andThenThrows
            SecurityException("bad credentials")

        route.execute(unverified.call)
        route.execute(invalid.call)

        verify {
            unverified.response.respondRedirect(
                "https://studio.example/login?error=not.verified",
                false,
            )
        }
        verify {
            invalid.response.respondRedirect(
                "https://studio.example/login?error=authentication.failed",
                false,
            )
        }
    }

    @Test
    fun `platform security exception with null message uses authentication failure redirect`() = runTest {
        val testCall = formErrorCall()
        coEvery {
            securityService.loginWithCredential(any(), true, emptyList())
        } throws java.lang.SecurityException()

        Login(securityService, securityConfiguration, cacheManager)
            .execute(testCall.call)

        verify {
            testCall.response.respondRedirect(
                "https://studio.example/login?error=authentication.failed",
                false,
            )
        }
    }

    @Test
    fun `platform security failures support JSON and unverified redirect responses`() = runTest {
        val route = Login(securityService, securityConfiguration, cacheManager)
        val json = jsonCall()
        val unverified = formErrorCall()
        coEvery {
            securityService.loginWithCredential(any(), true, emptyList())
        } throws java.lang.SecurityException("bad credentials") andThenThrows
            java.lang.SecurityException("account not verified")

        route.execute(json.call)
        route.execute(unverified.call)

        verify {
            json.response.respondText(
                match { it.contains("authentication.failed") },
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
        }
        verify {
            unverified.response.respondRedirect(
                "https://studio.example/login?error=not.verified",
                false,
            )
        }
    }

    @Test
    fun `unexpected parse failure returns JSON unauthorized response`() = runTest {
        val testCall = SecurityRouteTestCall(body = "{invalid")

        Login(securityService, securityConfiguration, cacheManager)
            .execute(testCall.call)

        verify {
            testCall.response.respondText(
                match { it.contains("authentication.failed") },
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
        }
    }

    @Test
    fun `unexpected form failure uses configured error redirect`() = runTest {
        val testCall = SecurityRouteTestCall(
            contentType = "application/x-www-form-urlencoded",
            query = mapOf(
                "redirect.error" to "https://studio.example/login",
            ),
            form = mapOf(
                "identifier" to "person@example.com",
                "password" to "password",
            ),
        )
        coEvery {
            securityService.loginWithCredential(any(), true, emptyList())
        } throws IllegalStateException("database unavailable")

        Login(securityService, securityConfiguration, cacheManager)
            .execute(testCall.call)

        verify {
            testCall.response.respondRedirect(
                "https://studio.example/login?error=authentication.failed",
                false,
            )
        }
    }

    @Test
    fun `missing form identifier uses the generic configured error redirect`() = runTest {
        val testCall = SecurityRouteTestCall(
            contentType = "application/x-www-form-urlencoded",
            query = mapOf("redirect.error" to "https://studio.example/login"),
            form = mapOf("password" to "password"),
        )

        Login(securityService, securityConfiguration, cacheManager)
            .execute(testCall.call)

        verify {
            testCall.response.respondRedirect(
                "https://studio.example/login?error=authentication.failed",
                false,
            )
        }
    }

    @Test
    fun `repeated failures rate limit the identifier`() = runTest {
        val route = Login(securityService, securityConfiguration, cacheManager)
        coEvery {
            securityService.loginWithCredential(any(), true, emptyList())
        } throws SecurityException("bad credentials")

        repeat(10) {
            route.execute(jsonCall().call)
        }
        val limited = jsonCall()
        route.execute(limited.call)

        verify {
            limited.response.respondText(
                match { it.contains("authentication.rate.limited") },
                ContentType.Application.Json,
                HttpStatusCode.TooManyRequests,
            )
        }
        coVerify(exactly = 10) {
            securityService.loginWithCredential(any(), true, emptyList())
        }
    }

    @Test
    fun `rate limited form login uses its configured error redirect`() = runTest {
        val route = Login(securityService, securityConfiguration, cacheManager)
        coEvery {
            securityService.loginWithCredential(any(), true, emptyList())
        } throws SecurityException("bad credentials")

        repeat(10) {
            route.execute(formErrorCall().call)
        }
        val limited = formErrorCall()
        route.execute(limited.call)

        verify {
            limited.response.respondRedirect(
                "https://studio.example/login?error=authentication.rate.limited",
                false,
            )
        }
    }

    private fun jsonCall() = SecurityRouteTestCall(
        body = json.encodeToString(
            LoginRequest(
                identifier = "person@example.com",
                password = "password",
            ),
        ),
    )

    private fun formErrorCall() = SecurityRouteTestCall(
        contentType = "application/x-www-form-urlencoded",
        query = mapOf(
            "redirect.error" to "https://studio.example/login",
        ),
        form = mapOf(
            "identifier" to "person@example.com",
            "password" to "password",
        ),
    )

    private fun response() = LoginResponse(
        principalId = UUID.random(),
        refreshToken = "refresh-token",
        token = Token(
            expiresAt = 3600,
            issuedAt = 1,
            token = "signed-token",
        ),
    )
}
