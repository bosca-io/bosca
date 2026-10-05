package bosca.devices.graphql

import bosca.devices.model.Device
import bosca.devices.model.PlatformType
import bosca.devices.service.DeviceService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Validates the authorization logic in [DevicesController], ensuring that only
 * the owning principal or an administrator can view device information, and that
 * unauthenticated callers receive empty/null results.
 */
class DevicesControllerTest {

    private val deviceService = mockk<DeviceService>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val controller = DevicesController(deviceService, groupEvaluator)

    private val principalId = Uuid.random()
    private val otherPrincipalId = Uuid.random()
    private val deviceId = Uuid.random()

    private val device = Device(
        id = deviceId,
        principalId = principalId,
        platform = PlatformType.ANDROID,
        installationId = "install-abc"
    )

    private fun authenticatedContext(id: Uuid): AuthenticationContext {
        val principal = Principal(id = id)
        val authenticated = AuthenticatedPrincipal(
            principal,
            listOf(Group(name = "users", description = "default", type = GroupType.PRINCIPAL))
        )
        val ctx = mockk<AuthenticationContext>()
        every { ctx.principal() } returns authenticated
        return ctx
    }

    private fun unauthenticatedContext(): AuthenticationContext {
        val ctx = mockk<AuthenticationContext>()
        every { ctx.principal() } returns null
        return ctx
    }

    // --- getById ---

    @Test
    fun `getById returns device when caller is owner`() = runTest {
        val auth = authenticatedContext(principalId)
        coEvery { deviceService.getById(deviceId) } returns device

        val result = controller.getById(auth, deviceId)

        assertEquals(device, result)
    }

    @Test
    fun `getById returns null when device not found`() = runTest {
        val auth = authenticatedContext(principalId)
        coEvery { deviceService.getById(deviceId) } returns null

        assertNull(controller.getById(auth, deviceId))
    }

    @Test
    fun `getById returns null when caller is not owner and not admin`() = runTest {
        val auth = authenticatedContext(otherPrincipalId)
        coEvery { deviceService.getById(deviceId) } returns device
        every { groupEvaluator.hasAdminGroup(auth) } returns false

        assertNull(controller.getById(auth, deviceId))
    }

    @Test
    fun `getById returns device when caller is admin but not owner`() = runTest {
        val auth = authenticatedContext(otherPrincipalId)
        coEvery { deviceService.getById(deviceId) } returns device
        every { groupEvaluator.hasAdminGroup(auth) } returns true

        assertEquals(device, controller.getById(auth, deviceId))
    }

    @Test
    fun `getById returns null when not authenticated`() = runTest {
        val auth = unauthenticatedContext()
        coEvery { deviceService.getById(deviceId) } returns device

        assertNull(controller.getById(auth, deviceId))
    }

    // --- devices ---

    @Test
    fun `devices returns list for own principalId`() = runTest {
        val auth = authenticatedContext(principalId)
        coEvery { deviceService.getByPrincipal(principalId) } returns listOf(device)

        val result = controller.devices(auth, principalId)

        assertEquals(listOf(device), result)
    }

    @Test
    fun `devices returns list using caller principalId when principalId is null`() = runTest {
        val auth = authenticatedContext(principalId)
        coEvery { deviceService.getByPrincipal(principalId) } returns listOf(device)

        val result = controller.devices(auth, null)

        assertEquals(listOf(device), result)
    }

    @Test
    fun `devices returns empty when requesting other principal and not admin`() = runTest {
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns false

        val result = controller.devices(auth, otherPrincipalId)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `devices returns list for other principal when admin`() = runTest {
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        coEvery { deviceService.getByPrincipal(otherPrincipalId) } returns listOf(device)

        val result = controller.devices(auth, otherPrincipalId)

        assertEquals(listOf(device), result)
    }

    @Test
    fun `devices returns empty when not authenticated`() = runTest {
        val auth = unauthenticatedContext()

        val result = controller.devices(auth, null)

        assertTrue(result.isEmpty())
    }
}
