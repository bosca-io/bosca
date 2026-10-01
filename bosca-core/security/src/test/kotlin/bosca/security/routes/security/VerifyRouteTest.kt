package bosca.security.routes.security

import bosca.security.service.EmailAlreadyVerified
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class VerifyRouteTest {

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
    fun `form token verification uses validated form redirects`() = runTest {
        val testCall = SecurityRouteTestCall(
            contentType = "application/x-www-form-urlencoded",
            form = mapOf(
                "token" to "verify-token",
                "redirect" to "https://studio.example/verified",
                "redirect.error" to "https://studio.example/error",
            ),
        )
        coEvery { securityService.verifyWithToken("verify-token") } returns Unit

        Verify(securityService, configuration).execute(testCall.call)

        coVerify { securityService.verifyWithToken("verify-token") }
        verify {
            testCall.response.respondRedirect(
                "https://studio.example/verified",
                false,
            )
        }
    }

    @Test
    fun `query token and redirects override form values`() = runTest {
        val testCall = SecurityRouteTestCall(
            query = mapOf(
                "token" to "query-token",
                "redirect" to "https://studio.example/query-success",
                "redirect.error" to "https://studio.example/query-error",
            ),
            form = mapOf(
                "redirect" to "https://studio.example/form-success",
                "redirect.error" to "https://studio.example/form-error",
            ),
        )
        coEvery { securityService.verifyWithToken("query-token") } returns Unit

        Verify(securityService, configuration).execute(testCall.call)

        verify {
            testCall.response.respondRedirect(
                "https://studio.example/query-success",
                false,
            )
        }
    }

    @Test
    fun `verification failure appends coded error with correct separator`() = runTest {
        val route = Verify(securityService, configuration)
        val plain = failureCall("https://studio.example/error")
        val existingQuery = failureCall("https://studio.example/error?from=verify")
        coEvery {
            securityService.verifyWithToken(any())
        } throws EmailAlreadyVerified()

        route.execute(plain.call)
        route.execute(existingQuery.call)

        verify {
            plain.response.respondRedirect(
                "https://studio.example/error?error=EMAIL_ALREADY_VERIFIED",
                false,
            )
        }
        verify {
            existingQuery.response.respondRedirect(
                "https://studio.example/error?from=verify&error=EMAIL_ALREADY_VERIFIED",
                false,
            )
        }
    }

    @Test
    fun `missing token and uncoded errors use validated fallback redirect`() = runTest {
        val missing = SecurityRouteTestCall(
            query = mapOf("redirect.error" to "https://attacker.example/error"),
        )

        Verify(securityService, configuration).execute(missing.call)

        verify { missing.response.respondRedirect("/", false) }
    }

    @Test
    fun `verification handles missing content type and rejects blank form or query tokens`() = runTest {
        val noContentType = SecurityRouteTestCall(
            contentType = null,
            query = mapOf("token" to "query-token"),
        )
        coEvery { securityService.verifyWithToken("query-token") } returns Unit

        Verify(securityService, configuration).execute(noContentType.call)

        coVerify { securityService.verifyWithToken("query-token") }
        verify { noContentType.response.respondRedirect("/", false) }

        val blankForm = SecurityRouteTestCall(
            contentType = "application/x-www-form-urlencoded",
            form = mapOf("token" to " "),
        )
        val blankQuery = SecurityRouteTestCall(query = mapOf("token" to " "))

        Verify(securityService, configuration).execute(blankForm.call)
        Verify(securityService, configuration).execute(blankQuery.call)

        verify { blankForm.response.respondRedirect("/", false) }
        verify { blankQuery.response.respondRedirect("/", false) }
    }

    @Test
    fun `form without a token and invalid success redirect use safe fallbacks`() = runTest {
        val missingFormToken = SecurityRouteTestCall(
            contentType = "application/x-www-form-urlencoded",
            query = mapOf("redirect.error" to "https://studio.example/error"),
            form = emptyMap(),
        )
        Verify(securityService, configuration).execute(missingFormToken.call)
        verify { missingFormToken.response.respondRedirect("https://studio.example/error", false) }

        val invalidSuccess = SecurityRouteTestCall(
            query = mapOf(
                "token" to "verify-token",
                "redirect" to "https://attacker.example/verified",
            ),
        )
        coEvery { securityService.verifyWithToken("verify-token") } returns Unit
        Verify(securityService, configuration).execute(invalidSuccess.call)
        verify { invalidSuccess.response.respondRedirect("/", false) }
    }

    private fun failureCall(errorRedirect: String) = SecurityRouteTestCall(
        query = mapOf(
            "token" to "bad-token",
            "redirect.error" to errorRedirect,
        ),
    )
}
