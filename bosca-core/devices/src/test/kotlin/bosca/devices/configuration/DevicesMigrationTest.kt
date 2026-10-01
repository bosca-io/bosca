package bosca.devices.configuration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DevicesMigrationTest {

    private val migration = DevicesMigration()

    @Test
    fun `schema is devices`() {
        assertEquals("devices", migration.schema)
    }

    @Test
    fun `resources contains all device migrations`() {
        assertTrue(migration.resources.contains("V1__devices.sql"))
        assertTrue(migration.resources.contains("V2__unique_push_tokens.sql"))
        assertTrue(migration.resources.contains("V3__nullable_device_principal.sql"))
    }

    @Test
    fun `resources has exactly three entries`() {
        assertEquals(3, migration.resources.size)
    }
}
