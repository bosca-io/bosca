package bosca.security.graphql

import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
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
import kotlin.test.assertTrue

class AdminMutationControllerTest {

    private val securityService = mockk<SecurityService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val authentication = mockk<AuthenticationContext>()
    private val controller = AdminMutationController(securityService, groupEvaluator)

    @Test
    fun `admin lifecycle mutations authorize and return service results`() = runTest {
        val survivorId = UUID.random()
        val duplicateId = UUID.random()
        val principal = Principal(id = survivorId)
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { securityService.mergePrincipals(survivorId, duplicateId) } returns principal
        coEvery { securityService.markPrincipalDeleted(survivorId) } returns principal
        coEvery { securityService.restorePrincipal(survivorId) } returns principal

        assertEquals(
            principal,
            controller.mergePrincipals(authentication, survivorId, duplicateId),
        )
        assertEquals(principal, controller.markPrincipalDeleted(authentication, survivorId))
        assertEquals(principal, controller.restorePrincipal(authentication, survivorId))

        verify(exactly = 3) { groupEvaluator.verifyHasAdminGroup(authentication) }
    }

    @Test
    fun `permanent deletion requires an existing soft deleted principal`() = runTest {
        val id = UUID.random()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { securityService.getPrincipalById(id) } returnsMany listOf(
            null,
            Principal(id = id),
            Principal(id = id, deletedAt = OffsetDateTime.now()),
        )
        coEvery { securityService.deletePrincipal(id) } returns Unit

        assertFailsWith<SecurityException> {
            controller.deletePrincipal(authentication, id)
        }
        assertFailsWith<SecurityException> {
            controller.deletePrincipal(authentication, id)
        }
        assertTrue(controller.deletePrincipal(authentication, id))
        coVerify(exactly = 1) { securityService.deletePrincipal(id) }
    }
}
