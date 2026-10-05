package bosca.installer.service

import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationHistory
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.repository.PackageInstallationsRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KClass
import kotlin.test.*

class UpgradeInstallerTest {

    private val repository = mockk<PackageInstallationsRepository>()
    private lateinit var installer: UpgradeInstaller

    @BeforeTest
    fun setup() {
        installer = UpgradeInstaller(repository)
        @OptIn(InternalDI::class)
        ProviderRegistry.clear()
    }

    @Test
    fun testUpgradeInstallerMarksMissingInstallers() = runTest {
        val mockInstaller = mockk<PackageInstaller>()
        coEvery { mockInstaller.version } returns "1.0.0"

        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstaller::class, object : ObjectProvider<PackageInstaller> {
            override val type: KClass<PackageInstaller> = PackageInstaller::class
            override suspend fun get(): PackageInstaller = mockInstaller
        }, "MockInstaller")

        val version = PackageInstallationVersion(
            version = "1.1.0",
            installerNames = listOf("MockInstaller")
        )
        val pkg = PackageInstallation(
            key = "test-package",
            name = "Test Package",
            versions = listOf(version)
        )

        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstallation::class, object : ObjectProvider<PackageInstallation> {
            override val type: KClass<PackageInstallation> = PackageInstallation::class
            override suspend fun get(): PackageInstallation = pkg
        })

        coEvery { repository.getHistory() } returns listOf(
            PackageInstallationHistory(key = "test-package", version = "1.1.0")
        )

        coEvery { repository.addHistory(any()) } answers { it.invocation.args[0] as PackageInstallationHistory }

        val triggerInstallation = PackageInstallation(key = "trigger", name = "Trigger", versions = emptyList())
        val triggerVersion = PackageInstallationVersion(version = "0.0.0", installerNames = emptyList())
        installer.install(triggerInstallation, triggerVersion)

        coVerify { repository.addHistory(match { it.key == "test-package:MockInstaller" && it.version == "1.0.0" }) }
    }

    @Test
    fun testUpgradeInstallerSkipsAlreadyMarkedInstallers() = runTest {
        val mockInstaller = mockk<PackageInstaller>()
        coEvery { mockInstaller.version } returns "1.0.0"

        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstaller::class, object : ObjectProvider<PackageInstaller> {
            override val type: KClass<PackageInstaller> = PackageInstaller::class
            override suspend fun get(): PackageInstaller = mockInstaller
        }, "MockInstaller")

        val version = PackageInstallationVersion(
            version = "1.1.0",
            installerNames = listOf("MockInstaller")
        )
        val pkg = PackageInstallation(
            key = "test-package",
            name = "Test Package",
            versions = listOf(version)
        )

        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstallation::class, object : ObjectProvider<PackageInstallation> {
            override val type: KClass<PackageInstallation> = PackageInstallation::class
            override suspend fun get(): PackageInstallation = pkg
        })

        coEvery { repository.getHistory() } returns listOf(
            PackageInstallationHistory(key = "test-package", version = "1.1.0"),
            PackageInstallationHistory(key = "test-package:MockInstaller", version = "1.0.0")
        )

        val triggerInstallation = PackageInstallation(key = "trigger", name = "Trigger", versions = emptyList())
        val triggerVersion = PackageInstallationVersion(version = "0.0.0", installerNames = emptyList())
        installer.install(triggerInstallation, triggerVersion)

        coVerify(exactly = 0) { repository.addHistory(any()) }
    }

    @Test
    fun `core package provider exposes the current installation manifest`() = runTest {
        val provider = CorePackageInstallerProvider()

        assertEquals(PackageInstallation::class, provider.type)
        val installation = provider.get()
        assertEquals("core", installation.key)
        assertEquals("Core Package Installer", installation.name)
        assertEquals("3.12.0", installation.versions.single().version)
        assertEquals(listOf("upgrader"), installation.versions.single().installerNames)
    }
}
