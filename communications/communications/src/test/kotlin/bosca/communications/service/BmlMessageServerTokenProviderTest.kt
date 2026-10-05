package bosca.communications.service

import bosca.security.model.Principal
import bosca.security.service.SecurityService
import com.auth0.jwt.interfaces.DecodedJWT
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BmlMessageServerTokenProviderTest {

    private val securityService = mockk<SecurityService>()
    private val principal = Principal(verified = true, anonymous = false)
    private val now = AtomicReference(Instant.parse("2026-07-29T00:00:00Z"))
    private val minted = AtomicInteger()

    @Test
    fun `impersonates service account and reuses token before refresh window`() = runBlocking {
        configureTokens()
        val provider = BmlMessageServerTokenProvider(securityService, now::get)

        assertEquals("jwt-1", provider.token())
        now.set(Instant.parse("2026-07-29T00:54:59Z"))
        assertEquals("jwt-1", provider.token())

        coVerify(exactly = 1) { securityService.getPrincipalByIdentifier("sa") }
        coVerify(exactly = 1) { securityService.createJwtToken(principal, emptyMap()) }
    }

    @Test
    fun `refreshes token five minutes before expiration`() = runBlocking {
        configureTokens()
        val provider = BmlMessageServerTokenProvider(securityService, now::get)

        assertEquals("jwt-1", provider.token())
        now.set(Instant.parse("2026-07-29T00:55:00Z"))
        assertEquals("jwt-2", provider.token())

        coVerify(exactly = 2) { securityService.getPrincipalByIdentifier("sa") }
        coVerify(exactly = 2) { securityService.createJwtToken(principal, emptyMap()) }
    }

    @Test
    fun `concurrent refresh is single flight`() = runBlocking {
        configureTokens(mintDelayMillis = 25)
        val provider = BmlMessageServerTokenProvider(securityService, now::get)

        val tokens = List(20) { async { provider.token() } }.awaitAll()

        assertEquals(setOf("jwt-1"), tokens.toSet())
        coVerify(exactly = 1) { securityService.createJwtToken(principal, emptyMap()) }
    }

    @Test
    fun `minted token without expiration fails loudly`() = runBlocking {
        coEvery { securityService.getPrincipalByIdentifier("sa") } returns principal
        coEvery { securityService.getPrincipalGroups(principal.id) } returns emptyList()
        coEvery { securityService.createJwtToken(principal, emptyMap()) } returns mockk<DecodedJWT> {
            every { expiresAtAsInstant } returns null
        }
        val provider = BmlMessageServerTokenProvider(securityService, now::get)

        val failure = assertFailsWith<IllegalArgumentException> { provider.token() }

        assertTrue("has no expiration" in failure.message.orEmpty())
    }

    private fun configureTokens(mintDelayMillis: Long = 0) {
        coEvery { securityService.getPrincipalByIdentifier("sa") } returns principal
        coEvery { securityService.getPrincipalGroups(principal.id) } returns emptyList()
        coEvery { securityService.createJwtToken(principal, emptyMap()) } coAnswers {
            if (mintDelayMillis > 0) delay(mintDelayMillis)
            val sequence = minted.incrementAndGet()
            mockk<DecodedJWT> {
                every { token } returns "jwt-$sequence"
                every { expiresAtAsInstant } returns now.get().plusSeconds(3600)
            }
        }
    }
}
