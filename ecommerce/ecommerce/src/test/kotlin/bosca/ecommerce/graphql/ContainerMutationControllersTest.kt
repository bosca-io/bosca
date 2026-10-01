package bosca.ecommerce.graphql

import bosca.ecommerce.model.Container
import bosca.ecommerce.model.ContainerInput
import bosca.ecommerce.service.ContainerService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Container mutations: each gates on the ecom-admin group, then delegates to the service with the principal. */
@OptIn(ExperimentalUuidApi::class)
class ContainerMutationControllersTest {

    private val containerService = mockk<ContainerService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true) // verifyHasGroup is a no-op (caller allowed)
    private val auth = mockk<AuthenticationContext>()

    private val containerId = UUID.random()
    private val companyId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null // -> principalId is null
    }

    private fun input() = ContainerInput(
        companyId = companyId, name = "Box", width = 5.0, height = 5.0, length = 5.0, weight = 0.2,
        supportedWidth = 4.5, supportedHeight = 4.5, supportedLength = 4.5, supportedWeight = 10.0,
    )

    private fun container() = Container(
        id = containerId, companyId = companyId, name = "Box", width = 5.0, height = 5.0, length = 5.0, weight = 0.2,
        supportedWidth = 4.5, supportedHeight = 4.5, supportedLength = 4.5, supportedWeight = 10.0,
    )

    @Test
    fun `add gates on admin then creates via the service`() = runTest {
        coEvery { containerService.add(input(), null) } returns container()
        val controller = ContainersMutationController(containerService, groups)

        val result = controller.add(auth, input())

        assertEquals(containerId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { containerService.add(input(), null) }
    }

    @Test
    fun `container accessor returns the id-scoped mutation namespace`() {
        val controller = ContainersMutationController(containerService, groups)
        assertEquals(containerId, controller.container(containerId).id)
    }

    @Test
    fun `edit gates on admin then edits via the service`() = runTest {
        coEvery { containerService.edit(containerId, input(), null) } returns container()
        val controller = ContainerMutationController(containerService, groups)

        controller.edit(auth, ContainerMutation(containerId), input())

        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { containerService.edit(containerId, input(), null) }
    }

    @Test
    fun `delete gates on admin then deletes via the service`() = runTest {
        coEvery { containerService.delete(containerId, null) } returns true
        val controller = ContainerMutationController(containerService, groups)

        assertEquals(true, controller.delete(auth, ContainerMutation(containerId)))

        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { containerService.delete(containerId, null) }
    }

    @Test
    fun `add forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { containerService.add(input(), principalId) } returns container()
        val controller = ContainersMutationController(containerService, groups)

        controller.add(auth, input())

        coVerify(exactly = 1) { containerService.add(input(), principalId) }
    }

    @Test
    fun `edit forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { containerService.edit(containerId, input(), principalId) } returns container()
        val controller = ContainerMutationController(containerService, groups)

        controller.edit(auth, ContainerMutation(containerId), input())

        coVerify(exactly = 1) { containerService.edit(containerId, input(), principalId) }
    }

    @Test
    fun `delete forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { containerService.delete(containerId, principalId) } returns true
        val controller = ContainerMutationController(containerService, groups)

        controller.delete(auth, ContainerMutation(containerId))

        coVerify(exactly = 1) { containerService.delete(containerId, principalId) }
    }
}
