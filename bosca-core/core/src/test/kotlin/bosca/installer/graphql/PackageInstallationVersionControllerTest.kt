package bosca.installer.graphql

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationHistory
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstallationService
import bosca.installer.service.PackageInstaller
import bosca.di.annotation.InternalDI
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KClass
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PackageInstallationVersionControllerTest {

    private val service = mockk<PackageInstallationService>()
    private val controller = PackageInstallationVersionController(service)

    @BeforeTest
    fun setup() {
        @OptIn(InternalDI::class)
        ProviderRegistry.clear()
    }

    @Test
    fun `installed returns list of installed installers`() = runTest {
        val installerA = mockk<PackageInstaller>()
        val installerB = mockk<PackageInstaller>()
        coEvery { installerA.version } returns "1.0.0"
        coEvery { installerB.version } returns "1.0.1"

        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstaller::class, object : ObjectProvider<PackageInstaller> {
            override val type: KClass<PackageInstaller> = PackageInstaller::class
            override suspend fun get(): PackageInstaller = installerA
        }, "InstallerA")

        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstaller::class, object : ObjectProvider<PackageInstaller> {
            override val type: KClass<PackageInstaller> = PackageInstaller::class
            override suspend fun get(): PackageInstaller = installerB
        }, "InstallerB")

        val version = PackageInstallationVersion(
            version = "1.0.0",
            installerNames = listOf("InstallerA", "InstallerB")
        )
        val installation = PackageInstallation(
            key = "test-package",
            name = "Test Package",
            versions = listOf(version)
        )
        val context = PackageInstallationVersionContext(
            installation = installation,
            version = version
        )

        coEvery { service.getHistory() } returns listOf(
            PackageInstallationHistory(key = "test-package:InstallerA", version = "1.0.0")
        )

        val result = controller.installed(context)

        assertEquals(1, result.size)
        assertEquals("InstallerA", result[0].name)
        assertEquals("1.0.0", result[0].version)
    }

    @Test
    fun `installers returns list of installer names`() = runTest {
        val installerA = mockk<PackageInstaller>()
        val installerB = mockk<PackageInstaller>()
        coEvery { installerA.version } returns "1.0.0"
        coEvery { installerB.version } returns "1.0.1"

        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstaller::class, object : ObjectProvider<PackageInstaller> {
            override val type: KClass<PackageInstaller> = PackageInstaller::class
            override suspend fun get(): PackageInstaller = installerA
        }, "InstallerA")

        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstaller::class, object : ObjectProvider<PackageInstaller> {
            override val type: KClass<PackageInstaller> = PackageInstaller::class
            override suspend fun get(): PackageInstaller = installerB
        }, "InstallerB")

        val version = PackageInstallationVersion(
            version = "1.0.0",
            installerNames = listOf("InstallerA", "InstallerB")
        )
        val installation = PackageInstallation(
            key = "test-package",
            name = "Test Package",
            versions = listOf(version)
        )
        val context = PackageInstallationVersionContext(
            installation = installation,
            version = version
        )

        val result = controller.installers(context)

        assertEquals(2, result.size)
        assertEquals("InstallerA", result[0].name)
        assertEquals("1.0.0", result[0].version)
        assertEquals("InstallerB", result[1].name)
        assertEquals("1.0.1", result[1].version)
    }

    @Test
    fun `version returns version`() = runTest {
        val version = PackageInstallationVersion(
            version = "1.0.0",
            installerNames = emptyList()
        )
        val installation = PackageInstallation(
            key = "test-package",
            name = "Test Package",
            versions = listOf(version)
        )
        val context = PackageInstallationVersionContext(
            installation = installation,
            version = version
        )

        val result = controller.version(context)

        assertEquals("1.0.0", result)
        assertEquals(context, context)
        assertFalse(context.equals(Any()))
        assertFalse(context.equals(null))
    }
}
