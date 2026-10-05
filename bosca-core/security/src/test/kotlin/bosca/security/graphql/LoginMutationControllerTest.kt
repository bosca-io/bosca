package bosca.security.graphql

import bosca.cache.Cache
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.security.model.LoginResponse
import bosca.security.model.Token
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.security.service.AuthenticationContext
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.serialization.UUID
import bosca.server.ServerCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LoginMutationControllerTest {

    private val securityService = mockk<SecurityService>()
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val controller = LoginMutationController(securityService, cacheManager)
    private val call = mockk<ServerCall> { every { request.appOrigin } returns "https://studio.test" }

    @Test
    fun `exchangeToken should return login response for valid token`() = runTest {
        val principalId = UUID.random()
        val expectedResponse = LoginResponse(
            principalId = principalId,
            refreshToken = "refresh-token",
            token = Token(3600, 1000, "jwt-token")
        )

        coEvery { securityService.loginWithExchangeToken("valid-token") } returns expectedResponse

        val result = controller.exchangeToken("valid-token")

        assertNotNull(result)
        assertEquals(principalId, result.principalId)
        assertEquals("jwt-token", result.token.token)
        assertEquals("refresh-token", result.refreshToken)
        coVerify { securityService.loginWithExchangeToken("valid-token") }
    }

    @Test
    fun `exchangeToken should propagate SecurityException for invalid token`() = runTest {
        coEvery { securityService.loginWithExchangeToken("invalid-token") } throws SecurityException("exchange token not found or expired")

        assertFailsWith<SecurityException> {
            controller.exchangeToken("invalid-token")
        }
    }

    @Test
    fun `exchangeToken should propagate SecurityException for anonymous principal`() = runTest {
        coEvery { securityService.loginWithExchangeToken("anon-token") } throws SecurityException("principal is anonymous")

        assertFailsWith<SecurityException> {
            controller.exchangeToken("anon-token")
        }
    }

    @Test
    fun `password should return login response for valid credentials`() = runTest {
        val principalId = UUID.random()
        val expectedResponse = LoginResponse(
            principalId = principalId,
            refreshToken = "refresh-token",
            token = Token(3600, 1000, "jwt-token")
        )

        coEvery { securityService.loginWithCredential(any(), generateRefreshToken = true) } returns expectedResponse

        val result = controller.password("user@example.com", "password123", null)

        assertNotNull(result)
        assertEquals(principalId, result.principalId)
    }

    @Test
    fun `password login echoes the caller-supplied originator`() = runTest {
        val principalId = UUID.random()
        val expectedResponse = LoginResponse(
            principalId = principalId,
            refreshToken = "refresh-token",
            token = Token(3600, 1000, "jwt-token")
        )
        coEvery { securityService.loginWithCredential(any(), generateRefreshToken = true, originator = "studio") } returns expectedResponse.copy(originator = "studio")

        val result = controller.password("user@example.com", "password123", "studio")

        assertEquals("studio", result.originator)
        coVerify { securityService.loginWithCredential(any(), generateRefreshToken = true, originator = "studio") }
    }

    @Test
    fun `refreshToken should return login response for valid refresh token`() = runTest {
        val principalId = UUID.random()
        val expectedResponse = LoginResponse(
            principalId = principalId,
            refreshToken = "new-refresh-token",
            token = Token(3600, 1000, "new-jwt-token")
        )

        coEvery { securityService.loginWithRefreshToken("old-refresh-token") } returns expectedResponse

        val result = controller.refreshToken("old-refresh-token")

        assertNotNull(result)
        assertEquals(principalId, result.principalId)
        assertEquals("new-refresh-token", result.refreshToken)
    }

    @Test
    fun `forgotPassword should return true on success`() = runTest {
        coEvery { securityService.forgotPassword("user@example.com", any()) } returns Unit

        val result = controller.forgotPassword("user@example.com", call)

        assertTrue(result)
        coVerify { securityService.forgotPassword("user@example.com", any()) }
    }

    @Test
    fun `resetPassword should return true on success`() = runTest {
        coEvery { securityService.resetPassword("reset-token", "new-password-long") } returns Unit

        val result = controller.resetPassword("reset-token", "new-password-long")

        assertTrue(result)
        coVerify { securityService.resetPassword("reset-token", "new-password-long") }
    }

    @Test
    fun `resetPassword rejects passwords outside the allowed range`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            controller.resetPassword("token", "short")
        }
        assertFailsWith<IllegalArgumentException> {
            controller.resetPassword("token", "x".repeat(129))
        }
    }

    @Test
    fun `password records ordinary failures but preserves cancellation`() = runTest {
        val failure = SecurityException("invalid credentials")
        coEvery {
            securityService.loginWithCredential(any(), generateRefreshToken = true, originator = null)
        } throws failure

        assertEquals(
            failure,
            assertFailsWith<SecurityException> {
                controller.password("failed@example.com", "password", null)
            }
        )

        coEvery {
            securityService.loginWithCredential(any(), generateRefreshToken = true, originator = null)
        } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> {
            controller.password("cancelled@example.com", "password", null)
        }
    }

    @Test
    fun `rate limited password and forgot password calls fail before the service`() = runTest {
        val cache = mockk<Cache<String>>()
        val value = mockk<CacheValue> {
            every { exists } returns true
            every { this@mockk.value } returns "10"
        }
        coEvery { cache.get(any()) } returns value
        coEvery { cacheManager.maybeAddCache<String>(any(), any(), any()) } returns cache
        val limitedController = LoginMutationController(securityService, cacheManager)

        assertFailsWith<SecurityException> {
            limitedController.password("limited@example.com", "password", null)
        }
        assertFailsWith<SecurityException> {
            limitedController.forgotPassword("limited@example.com", call)
        }
    }

    @Test
    fun `signOut invalidates authenticated sessions and always clears cookies`() = runTest {
        val principalId = UUID.random()
        val loginId = 42L
        val authenticated = AuthenticatedPrincipal(Principal(id = principalId), emptyList(), loginId)
        val authentication = mockk<AuthenticationContext> {
            every { principal() } returns authenticated
        }
        val authenticatedCall = mockk<ServerCall>(relaxed = true)
        coEvery { securityService.signOut(principalId, loginId) } returns Unit

        assertTrue(controller.signOut(authentication, authenticatedCall))
        coVerify { securityService.signOut(principalId, loginId) }

        val legacyAuthentication = mockk<AuthenticationContext> {
            every { principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        }
        coEvery { securityService.signOut(principalId, null) } returns Unit
        assertTrue(controller.signOut(legacyAuthentication, authenticatedCall))
        coVerify { securityService.signOut(principalId, null) }

        val anonymousCall = mockk<ServerCall>(relaxed = true)
        assertTrue(controller.signOut(null, anonymousCall))
        val missingPrincipal = mockk<AuthenticationContext> {
            every { principal() } returns null
        }
        assertTrue(controller.signOut(missingPrincipal, anonymousCall))
        coVerify(exactly = 2) { securityService.signOut(any(), any()) }
    }
}
