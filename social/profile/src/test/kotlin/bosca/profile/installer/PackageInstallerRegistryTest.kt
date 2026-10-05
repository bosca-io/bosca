package bosca.profile.installer

import bosca.profile.profile.service.ProfileService
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PackageInstallerRegistryTest {

    private val registry = PackageInstallerRegistry()

    @Test
    fun `attributeTypesInstaller creates AttributeTypesInstaller`() {
        val service = mockk<ProfileService>()

        val installer = registry.attributeTypesInstaller(service)

        assertNotNull(installer)
    }

    @Test
    fun `profilesPackage returns package with correct key`() {
        val pkg = registry.profilesPackage()

        assertEquals("profiles", pkg.key)
    }

    @Test
    fun `profilesPackage returns package with correct name`() {
        val pkg = registry.profilesPackage()

        assertEquals("Profiles", pkg.name)
    }

    @Test
    fun `profilesPackage has five versions`() {
        val pkg = registry.profilesPackage()

        assertEquals(5, pkg.versions.size)
    }

    @Test
    fun `profilesPackage first version seeds only attribute types`() {
        val pkg = registry.profilesPackage()
        val firstVersion = pkg.versions[0]

        assertEquals("1.0.0", firstVersion.version)
        assertEquals(listOf("attribute-types"), firstVersion.installerNames)
    }

    @Test
    fun `profilesPackage second version includes only attribute-types`() {
        val pkg = registry.profilesPackage()
        val secondVersion = pkg.versions[1]

        assertEquals("1.0.1", secondVersion.version)
        assertEquals(listOf("attribute-types"), secondVersion.installerNames)
    }

    @Test
    fun `profilesPackage third version includes only attribute-types`() {
        val pkg = registry.profilesPackage()
        val thirdVersion = pkg.versions[2]

        assertEquals("1.0.2", thirdVersion.version)
        assertEquals(listOf("attribute-types"), thirdVersion.installerNames)
    }

    @Test
    fun `profilesPackage latest version reruns attribute form schemas`() {
        val latestVersion = registry.profilesPackage().versions.last()

        assertEquals("1.0.4", latestVersion.version)
        assertEquals(listOf("attribute-type-form-schemas"), latestVersion.installerNames)
    }
}
