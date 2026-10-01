package bosca.devices.graphql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DevicesMutationTest {

    @Test
    fun `DevicesMutation class name matches expected`() {
        assertEquals("DevicesMutation", DevicesMutation::class.simpleName)
    }

    @Test
    fun `Devices class name matches expected`() {
        assertEquals("Devices", Devices::class.simpleName)
    }

    @Test
    fun `DevicesMutation and Devices are distinct types`() {
        assertTrue(
            DevicesMutation::class != Devices::class,
            "DevicesMutation and Devices should be distinct types"
        )
    }
}
