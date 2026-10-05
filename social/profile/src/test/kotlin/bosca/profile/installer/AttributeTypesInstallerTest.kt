package bosca.profile.installer

import bosca.profile.profile.service.ProfileService
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AttributeTypesInstallerTest {

    private val service = mockk<ProfileService>()
    private val installer = AttributeTypesInstaller(service)

    @Test
    fun `version returns expected version string`() {
        assertEquals("1.0.2", installer.version)
    }

    @Test
    fun `installer is not null when created`() {
        assertNotNull(installer)
    }

    @Test
    fun `version is a valid semver string`() {
        val version = installer.version
        val parts = version.split(".")

        assertEquals(3, parts.size)
        parts.forEach { part ->
            assertNotNull(part.toIntOrNull(), "Version part '$part' should be numeric")
        }
    }
}
