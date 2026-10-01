package bosca.security.routes.security

import bosca.cache.CacheManager
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class LoginForgotPasswordRouteTest {

    private val securityService = mockk<SecurityService>()
    private val configuration = mockk<SecurityConfiguration> {
        every { allowedRedirects } returns listOf("https://studio.example")
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
    fun `form request sends reset email and redirects`() = runTest {
        val testCall = SecurityRouteTestCall(
            contentType = "application/x-www-form-urlencoded",
            form = mapOf(
                "identifier" to "person@example.com",
                "redirect" to "https://studio.example/check-email",
            ),
        )
        coEvery { securityService.forgotPassword("person@example.com") } returns Unit

        LoginForgotPassword(securityService, configuration).execute(testCall.call)

        coVerify { securityService.forgotPassword("person@example.com") }
        verify {
            testCall.response.respondRedirect(
                "https://studio.example/check-email",
                false,
            )
        }
    }

    @Test
    fun `json request sends reset email and returns an empty success`() = runTest {
        val testCall = SecurityRouteTestCall(
            body = Json.encodeToString(ForgotPasswordRequest("person@example.com")),
        )
        coEvery { securityService.forgotPassword("person@example.com") } returns Unit

        LoginForgotPassword(securityService, configuration).execute(testCall.call)

        verify { testCall.response.status(HttpStatusCode.OK) }
        verify { testCall.response.commit(any()) }
    }

    @Test
    fun `security failures return unauthorized JSON`() = runTest {
        val testCall = SecurityRouteTestCall(
            body = Json.encodeToString(ForgotPasswordRequest("person@example.com")),
        )
        coEvery {
            securityService.forgotPassword("person@example.com")
        } throws java.lang.SecurityException("failure")

        LoginForgotPassword(securityService, configuration).execute(testCall.call)

        verify {
            testCall.response.respondText(
                match { it.contains("forgot.password.failed") },
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
        }
    }

    @Test
    fun `form security failures distinguish unverified and generic failures`() = runTest {
        val route = LoginForgotPassword(securityService, configuration)
        val unverified = formErrorCall()
        val failed = formErrorCall()
        coEvery {
            securityService.forgotPassword("person@example.com")
        } throws java.lang.SecurityException("not verified") andThenThrows
            java.lang.SecurityException()

        route.execute(unverified.call)
        route.execute(failed.call)

        verify {
            unverified.response.respondRedirect(
                "https://studio.example/login?error=not.verified",
                false,
            )
        }
        verify {
            failed.response.respondRedirect(
                "https://studio.example/login?error=forgot.failed",
                false,
            )
        }
    }

    private fun formErrorCall() = SecurityRouteTestCall(
        contentType = "application/x-www-form-urlencoded",
        query = mapOf("redirect.error" to "https://studio.example/login"),
        form = mapOf("identifier" to "person@example.com"),
    )
}
