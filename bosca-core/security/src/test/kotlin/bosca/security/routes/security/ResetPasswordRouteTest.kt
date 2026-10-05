package bosca.security.routes.security

import bosca.cache.CacheManager
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
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

class ResetPasswordRouteTest {

    private val securityService = mockk<SecurityService>()
    private val configuration = mockk<SecurityConfiguration> {
        every { allowedRedirects } returns listOf("https://studio.example")
    }
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
    fun `api form request resets password and returns ok`() = runTest {
        val testCall = formCall()
        coEvery { securityService.resetPassword("reset-token", "valid-password") } returns Unit

        ResetPassword(securityService, cacheManager).execute(testCall.call)

        coVerify { securityService.resetPassword("reset-token", "valid-password") }
        verify {
            testCall.response.respondText(
                match { it.contains("\"message\":\"ok\"") },
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        }
    }

    @Test
    fun `api JSON request resets password`() = runTest {
        val route = ResetPassword(securityService, cacheManager)
        val valid = SecurityRouteTestCall(
            body = """{"token":"json-token","password":"valid-password"}""",
        )
        coEvery { securityService.resetPassword("json-token", "valid-password") } returns Unit

        route.execute(valid.call)

        coVerify { securityService.resetPassword("json-token", "valid-password") }
    }

    @Test
    fun `api rejects invalid password length`() = runTest {
        val tooShort = formCall(password = "short")
        val tooLong = formCall(password = "x".repeat(129), token = "long-token")

        val route = ResetPassword(securityService, cacheManager)
        route.execute(tooShort.call)
        route.execute(tooLong.call)

        coVerify(exactly = 0) { securityService.resetPassword(any(), any()) }
        verify {
            tooShort.response.respondText(
                match { it.contains("Password must be between") },
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            tooLong.response.respondText(
                match { it.contains("Password must be between") },
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
        }
    }

    @Test
    fun `api maps domain and unexpected reset failures`() = runTest {
        val route = ResetPassword(securityService, cacheManager)
        val unauthorized = formCall(token = "domain-token")
        val failed = formCall(token = "failed-token")
        coEvery {
            securityService.resetPassword("domain-token", any())
        } throws SecurityException("expired")
        coEvery {
            securityService.resetPassword("failed-token", any())
        } throws IllegalStateException("database")

        route.execute(unauthorized.call)
        route.execute(failed.call)

        verify {
            unauthorized.response.respondText(
                match { it.contains("reset.password.failed") },
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
        }
        verify {
            failed.response.respondText(
                match { it.contains("reset.password.failed") },
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    @Test
    fun `api sixth attempt for a token is rate limited`() = runTest {
        val route = ResetPassword(securityService, cacheManager)
        coEvery { securityService.resetPassword(any(), any()) } throws SecurityException("expired")

        repeat(5) { route.execute(formCall().call) }
        val limited = formCall()
        route.execute(limited.call)

        coVerify(exactly = 5) { securityService.resetPassword(any(), any()) }
        verify {
            limited.response.respondText(
                match { it.contains("reset.password.rate.limited") },
                ContentType.Application.Json,
                HttpStatusCode.TooManyRequests,
            )
        }
    }

    @Test
    fun `login form reset redirects after success`() = runTest {
        val testCall = formCall(
            redirect = "https://studio.example/login",
            errorRedirect = "https://studio.example/reset",
        )
        coEvery { securityService.resetPassword(any(), any()) } returns Unit

        LoginResetPassword(securityService, configuration, cacheManager).execute(testCall.call)

        verify {
            testCall.response.respondRedirect("https://studio.example/login", false)
        }
    }

    @Test
    fun `login JSON reset succeeds without a redirect`() = runTest {
        val testCall = SecurityRouteTestCall(
            body = """{"password":"valid-password","token":"json-token"}""",
        )
        coEvery { securityService.resetPassword("json-token", "valid-password") } returns Unit

        LoginResetPassword(securityService, configuration, cacheManager).execute(testCall.call)

        verify { testCall.response.status(HttpStatusCode.OK) }
        verify { testCall.response.commit(any()) }
    }

    @Test
    fun `login reset rejects weak passwords as json and redirect`() = runTest {
        val route = LoginResetPassword(securityService, configuration, cacheManager)
        val json = SecurityRouteTestCall(
            body = """{"password":"short","token":"one"}""",
        )
        val form = formCall(
            token = "two",
            password = "x".repeat(129),
            errorRedirect = "https://studio.example/reset",
        )

        route.execute(json.call)
        route.execute(form.call)

        verify {
            json.response.respondText(
                match { it.contains("Password must be between") },
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
        }
        verify {
            form.response.respondRedirect(
                "https://studio.example/reset?error=invalid.password",
                false,
            )
        }
    }

    @Test
    fun `login reset maps domain failures for json and form`() = runTest {
        val route = LoginResetPassword(securityService, configuration, cacheManager)
        val json = SecurityRouteTestCall(
            body = """{"password":"valid-password","token":"one"}""",
        )
        val unverified = formCall(
            token = "two",
            errorRedirect = "https://studio.example/reset",
        )
        val failed = formCall(
            token = "three",
            errorRedirect = "https://studio.example/reset",
        )
        coEvery {
            securityService.resetPassword("one", any())
        } throws SecurityException("expired")
        coEvery {
            securityService.resetPassword("two", any())
        } throws SecurityException("not verified")
        coEvery {
            securityService.resetPassword("three", any())
        } throws SecurityException("expired")

        route.execute(json.call)
        route.execute(unverified.call)
        route.execute(failed.call)

        verify {
            json.response.respondText(
                match { it.contains("expired") },
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
        }
        verify {
            unverified.response.respondRedirect(
                "https://studio.example/reset?error=not.verified",
                false,
            )
        }
        verify {
            failed.response.respondRedirect(
                "https://studio.example/reset?error=reset.failed",
                false,
            )
        }
    }

    @Test
    fun `login reset maps unexpected failures for json and form`() = runTest {
        val route = LoginResetPassword(securityService, configuration, cacheManager)
        val json = SecurityRouteTestCall(
            body = """{"password":"valid-password","token":"one"}""",
        )
        val form = formCall(
            token = "two",
            errorRedirect = "https://studio.example/reset",
        )
        coEvery { securityService.resetPassword(any(), any()) } throws IllegalStateException("database")

        route.execute(json.call)
        route.execute(form.call)

        verify {
            json.response.respondText(
                match { it.contains("reset.password.failed") },
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
        verify {
            form.response.respondRedirect(
                "https://studio.example/reset?error=reset.failed",
                false,
            )
        }
    }

    @Test
    fun `login reset eleventh request for token uses redirect rate limit response`() = runTest {
        val route = LoginResetPassword(securityService, configuration, cacheManager)
        coEvery { securityService.resetPassword(any(), any()) } throws SecurityException("expired")

        repeat(10) { route.execute(formCall().call) }
        val limited = formCall(errorRedirect = "https://studio.example/reset")
        route.execute(limited.call)

        verify {
            limited.response.respondRedirect(
                "https://studio.example/reset?error=reset.password.rate.limited",
                false,
            )
        }
    }

    @Test
    fun `login reset eleventh JSON request uses JSON rate limit response`() = runTest {
        val route = LoginResetPassword(securityService, configuration, cacheManager)
        coEvery { securityService.resetPassword(any(), any()) } throws SecurityException("expired")

        repeat(10) {
            route.execute(
                SecurityRouteTestCall(
                    body = """{"password":"valid-password","token":"json-rate-token"}""",
                ).call,
            )
        }
        val limited = SecurityRouteTestCall(
            body = """{"password":"valid-password","token":"json-rate-token"}""",
        )
        route.execute(limited.call)

        verify {
            limited.response.respondText(
                match { it.contains("reset.password.rate.limited") },
                ContentType.Application.Json,
                HttpStatusCode.TooManyRequests,
            )
        }
    }

    private fun formCall(
        token: String = "reset-token",
        password: String = "valid-password",
        redirect: String? = null,
        errorRedirect: String? = null,
    ): SecurityRouteTestCall {
        val query = errorRedirect?.let { mapOf("redirect.error" to it) }.orEmpty()
        return SecurityRouteTestCall(
            contentType = "application/x-www-form-urlencoded",
            query = query,
            form = buildMap {
                put("token", token)
                put("password", password)
                redirect?.let { put("redirect", it) }
            },
        )
    }
}
