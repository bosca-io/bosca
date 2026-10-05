package bosca.security.graphql

import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PrincipalsControllerTest {

    private val securityService = mockk<SecurityService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val authentication = mockk<AuthenticationContext>()
    private val controller = PrincipalsController(securityService, groupEvaluator)

    @Test
    fun `admin can list and look up principals`() = runTest {
        val id = UUID.random()
        val principal = Principal(id = id)
        every { groupEvaluator.hasAdminGroup(authentication) } returns true
        coEvery { securityService.getPrincipals(20, 10, true) } returns listOf(principal)
        coEvery { securityService.getPrincipalById(id) } returns principal

        assertEquals(listOf(principal), controller.all(authentication, 10, 20, true))
        assertEquals(principal, controller.principal(authentication, id))

        coVerify { securityService.getPrincipals(20, 10, true) }
        coVerify { securityService.getPrincipalById(id) }
    }

    @Test
    fun `non admin cannot list or look up principals`() = runTest {
        every { groupEvaluator.hasAdminGroup(authentication) } returns false
        every { groupEvaluator.throwUnauthorized() } throws SecurityException("unauthorized")

        assertFailsWith<SecurityException> {
            controller.all(authentication, 10, 0)
        }
        assertFailsWith<SecurityException> {
            controller.principal(authentication, UUID.random())
        }
        verify(exactly = 2) { groupEvaluator.throwUnauthorized() }
    }

    @Test
    fun `current returns authenticated principal without repository lookup`() = runTest {
        val principal = Principal(id = UUID.random())
        val authenticated = mockk<AuthenticatedPrincipal> {
            every { asPrincipal() } returns principal
        }
        every { authentication.principal() } returns authenticated

        assertEquals(principal, controller.current(authentication))
        coVerify(exactly = 0) { securityService.getPrincipalById(any()) }
    }

    @Test
    fun `current falls back to anonymous principal`() = runTest {
        val anonymous = Principal(id = UUID.NIL, anonymous = true)
        coEvery { securityService.getPrincipalById(UUID.NIL) } returns anonymous

        assertEquals(anonymous, controller.current(null))
        every { authentication.principal() } returns null
        assertEquals(anonymous, controller.current(authentication))
    }

    @Test
    fun `current fails when the anonymous principal is missing`() = runTest {
        every { authentication.principal() } returns null
        coEvery { securityService.getPrincipalById(UUID.NIL) } returns null

        assertFailsWith<IllegalStateException> {
            controller.current(authentication)
        }
    }

    @Test
    fun `principal lookup preserves a missing result`() = runTest {
        every { groupEvaluator.hasAdminGroup(authentication) } returns true
        coEvery { securityService.getPrincipalById(any()) } returns null

        assertNull(controller.principal(authentication, UUID.random()))
    }
}
