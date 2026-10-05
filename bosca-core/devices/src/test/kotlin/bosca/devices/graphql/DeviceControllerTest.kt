package bosca.devices.graphql

import bosca.devices.model.Device
import bosca.devices.model.PlatformType
import bosca.devices.service.DeviceService
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class DeviceControllerTest {

    private val deviceService = mockk<DeviceService>()
    private val controller = DeviceController(deviceService)

    private val principalId = Uuid.random()
    private val deviceId = Uuid.random()

    private val device = Device(
        id = deviceId,
        principalId = principalId,
        platform = PlatformType.ANDROID,
        installationId = "install-123"
    )

    @Test
    fun `id returns device id`() {
        assertEquals(deviceId, controller.id(device))
    }

    @Test
    fun `platform returns device platform`() {
        assertEquals(PlatformType.ANDROID, controller.platform(device))
    }

    @Test
    fun `created returns device created timestamp`() {
        assertEquals(device.created, controller.created(device))
    }

    @Test
    fun `modified returns device modified timestamp`() {
        assertEquals(device.modified, controller.modified(device))
    }

    @Test
    fun `lastCheckIn returns device lastCheckIn timestamp`() {
        assertEquals(device.lastCheckIn, controller.lastCheckIn(device))
    }

    @Test
    fun `installationId returns device installationId`() {
        assertEquals("install-123", controller.installationId(device))
    }

    @Test
    fun `installationId returns null when not set`() {
        val deviceNoInstall = Device(
            id = Uuid.random(),
            principalId = principalId,
            platform = PlatformType.WEB
        )
        assertNull(controller.installationId(deviceNoInstall))
    }
}
