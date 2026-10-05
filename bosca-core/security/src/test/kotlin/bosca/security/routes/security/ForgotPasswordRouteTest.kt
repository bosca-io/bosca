package bosca.security.routes.security

import bosca.cache.CacheManager
import bosca.security.service.SecurityService
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class ForgotPasswordRouteTest {

    private val securityService = mockk<SecurityService>()
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
    fun `valid email requests a reset using the application origin`() = runTest {
        val testCall = SecurityRouteTestCall(
            query = mapOf("email" to "person@example.com"),
            appOrigin = "https://app.example",
        )
        coEvery {
            securityService.forgotPassword("person@example.com", "https://app.example")
        } returns Unit

        ForgotPassword(securityService, cacheManager).execute(testCall.call)

        coVerify {
            securityService.forgotPassword("person@example.com", "https://app.example")
        }
        verify {
            testCall.response.respondText(
                match { it.contains("\"message\":\"ok\"") },
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        }
    }

    @Test
    fun `service failures are hidden to prevent account enumeration`() = runTest {
        val testCall = SecurityRouteTestCall(
            query = mapOf("email" to "missing@example.com"),
        )
        coEvery {
            securityService.forgotPassword("missing@example.com", any())
        } throws IllegalStateException("not found")

        ForgotPassword(securityService, cacheManager).execute(testCall.call)

        verify {
            testCall.response.respondText(
                match { it.contains("\"message\":\"ok\"") },
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        }
    }

    @Test
    fun `blank and malformed emails are rejected before calling the service`() = runTest {
        val route = ForgotPassword(securityService, cacheManager)
        val missing = SecurityRouteTestCall()
        val blank = SecurityRouteTestCall(query = mapOf("email" to " "))
        val malformed = SecurityRouteTestCall(query = mapOf("email" to "not-an-email"))

        route.execute(missing.call)
        route.execute(blank.call)
        route.execute(malformed.call)

        coVerify(exactly = 0) { securityService.forgotPassword(any(), any()) }
        verify {
            missing.response.respondText(
                match { it.contains("missing email") },
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
        }
        verify {
            blank.response.respondText(
                match { it.contains("missing email") },
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
        }
        verify {
            malformed.response.respondText(
                match {
                    it.contains("\"status\":400") &&
                        it.contains("\"message\":\"Invalid email address format\"")
                },
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
        }
    }

    @Test
    fun `sixth request for an email is rate limited`() = runTest {
        val route = ForgotPassword(securityService, cacheManager)
        coEvery { securityService.forgotPassword(any(), any()) } returns Unit

        repeat(5) {
            route.execute(
                SecurityRouteTestCall(
                    query = mapOf("email" to "person@example.com"),
                ).call,
            )
        }
        val limited = SecurityRouteTestCall(
            query = mapOf("email" to "person@example.com"),
        )
        route.execute(limited.call)

        coVerify(exactly = 5) { securityService.forgotPassword(any(), any()) }
        verify {
            limited.response.respondText(
                match { it.contains("rate limited") },
                ContentType.Application.Json,
                HttpStatusCode.TooManyRequests,
            )
        }
    }
}
