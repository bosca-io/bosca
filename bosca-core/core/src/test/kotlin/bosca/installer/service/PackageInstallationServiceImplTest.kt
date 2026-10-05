package bosca.installer.service

import bosca.di.annotation.InternalDI
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationHistory
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.repository.PackageInstallationsRepository
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KClass
import kotlin.test.*

class PackageInstallationServiceImplTest {

    private val repository = mockk<PackageInstallationsRepository>()
    private lateinit var service: PackageInstallationServiceImpl

    @BeforeTest
    fun setup() {
        service = PackageInstallationServiceImpl(repository)
        @OptIn(InternalDI::class)
        ProviderRegistry.clear()
    }

    @Test
    fun testInstallVersionWithInstallerVersion() = runTest {
        val installer = mockk<PackageInstaller>()
        coEvery { installer.version } returns "1.1.0"
        coEvery { installer.install(any(), any()) } just Runs

        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstaller::class, object : ObjectProvider<PackageInstaller> {
            override val type: KClass<PackageInstaller> = PackageInstaller::class
            override suspend fun get(): PackageInstaller = installer
        }, "MyInstaller")

        val versionObj = PackageInstallationVersion(
            version = "1.1.0",
            installerNames = listOf("MyInstaller")
        )
        val installation = PackageInstallation(
            key = "test-package",
            name = "Test Package",
            versions = listOf(versionObj)
        )

        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstallation::class, object : ObjectProvider<PackageInstallation> {
            override val type: KClass<PackageInstallation> = PackageInstallation::class
            override suspend fun get(): PackageInstallation = installation
        }, "test-package")

        coEvery { repository.getHistory() } returns emptyList()
        coEvery { repository.addHistory(any()) } answers { it.invocation.args[0] as PackageInstallationHistory }

        val result = service.install("test-package", "1.1.0")

        assertTrue(result)
        coVerify { installer.install(installation, versionObj) }
        coVerify { repository.addHistory(match { it.key == "test-package:MyInstaller" && it.version == "1.1.0" }) }
        coVerify { repository.addHistory(match { it.key == "test-package" && it.version == "1.1.0" }) }
    }

    @Test
    fun testInstallWithInstallerVersionAlreadyInHistory() = runTest {
        val installer = mockk<PackageInstaller>()
        coEvery { installer.version } returns "1.1.0"
        coEvery { installer.install(any(), any()) } just Runs

        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstaller::class, object : ObjectProvider<PackageInstaller> {
            override val type: KClass<PackageInstaller> = PackageInstaller::class
            override suspend fun get(): PackageInstaller = installer
        }, "MyInstaller")

        val versionObj = PackageInstallationVersion(
            version = "1.0.0",
            installerNames = listOf("MyInstaller")
        )
        val installation = PackageInstallation(
            key = "test-package",
            name = "Test Package",
            versions = listOf(versionObj)
        )

        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstallation::class, object : ObjectProvider<PackageInstallation> {
            override val type: KClass<PackageInstallation> = PackageInstallation::class
            override suspend fun get(): PackageInstallation = installation
        }, "test-package")

        coEvery { repository.getHistory() } returns listOf(
            PackageInstallationHistory(key = "test-package:MyInstaller", version = "1.1.0")
        )
        coEvery { repository.addHistory(any()) } answers { it.invocation.args[0] as PackageInstallationHistory }

        service.install(setOf("test-package"))

        coVerify(exactly = 0) { installer.install(any(), any()) }
        coVerify(exactly = 0) { repository.addHistory(match { it.key == "test-package:MyInstaller" }) }
        coVerify { repository.addHistory(match { it.key == "test-package" && it.version == "1.0.0" }) }
    }

    @Test
    fun testInstallVersionWithExactMatch() = runTest {
        val installer = mockk<PackageInstaller>()
        coEvery { installer.version } returns "1.0.0"
        coEvery { installer.install(any(), any()) } just Runs

        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstaller::class, object : ObjectProvider<PackageInstaller> {
            override val type: KClass<PackageInstaller> = PackageInstaller::class
            override suspend fun get(): PackageInstaller = installer
        }, "MyInstaller")

        val versionObj = PackageInstallationVersion(
            version = "1.0.0",
            installerNames = listOf("MyInstaller")
        )
        val installation = PackageInstallation(
            key = "test-package",
            name = "Test Package",
            versions = listOf(versionObj)
        )

        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstallation::class, object : ObjectProvider<PackageInstallation> {
            override val type: KClass<PackageInstallation> = PackageInstallation::class
            override suspend fun get(): PackageInstallation = installation
        }, "test-package")

        coEvery { repository.getHistory() } returns emptyList()
        coEvery { repository.addHistory(any()) } answers { it.invocation.args[0] as PackageInstallationHistory }

        val result = service.install("test-package", "1.0.0")

        assertTrue(result)
        coVerify { installer.install(installation, versionObj) }
        coVerify { repository.addHistory(match { it.key == "test-package:MyInstaller" && it.version == "1.0.0" }) }
        coVerify { repository.addHistory(match { it.key == "test-package" && it.version == "1.0.0" }) }
    }

    @Test
    fun testInstallSkipsFutureVersionsIfHistoryFastForwards() = runTest {
        val installerA = mockk<PackageInstaller>()
        val installerB = mockk<PackageInstaller>()
        coEvery { installerA.version } returns "1.0.1"
        coEvery { installerB.version } returns "1.0.1"
        coEvery { installerA.install(any(), any()) } just Runs
        coEvery { installerB.install(any(), any()) } just Runs

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

        val v1_0_0 = PackageInstallationVersion(
            version = "1.0.0",
            installerNames = listOf("InstallerA")
        )
        val v1_0_1 = PackageInstallationVersion(
            version = "1.0.1",
            installerNames = listOf("InstallerA", "InstallerB")
        )
        val installation = PackageInstallation(
            key = "test-package",
            name = "Test Package",
            versions = listOf(v1_0_0, v1_0_1)
        )

        var currentInstallation = installation.copy(versions = listOf(v1_0_0))

        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstallation::class, object : ObjectProvider<PackageInstallation> {
            override val type: KClass<PackageInstallation> = PackageInstallation::class
            override suspend fun get(): PackageInstallation = currentInstallation
        }, "test-package")

        val historyList = mutableListOf<PackageInstallationHistory>()
        coEvery { repository.getHistory() } answers { historyList.toList() }
        coEvery { repository.addHistory(any()) } answers {
            val h = it.invocation.args[0] as PackageInstallationHistory
            historyList.add(h)
            h
        }

        // First run with only 1.0.0 available
        service.install(setOf("test-package"))

        assertTrue(historyList.any { it.key == "test-package" && it.version == "1.0.0" })
        assertFalse(historyList.any { it.key == "test-package" && it.version == "1.0.1" })

        // Update to include 1.0.1
        currentInstallation = installation

        // Second run with 1.0.1 available (upgrade)
        service.install(setOf("test-package"))

        coVerify { installerA.install(any(), any()) }
        coVerify { installerB.install(any(), any()) }

        assertTrue(historyList.any { it.key == "test-package:InstallerA" && it.version == "1.0.1" })
        assertTrue(historyList.any { it.key == "test-package:InstallerB" && it.version == "1.0.1" })
        assertTrue(historyList.any { it.key == "test-package" && it.version == "1.0.1" })
    }

    @Test
    fun `install returns false for unknown package and version and delegates history`() = runTest {
        coEvery { repository.getHistory() } returns emptyList()
        assertFalse(service.install("missing", "1.0.0"))

        val installation = PackageInstallation("known", "Known", listOf(PackageInstallationVersion("1.0.0", emptyList())))
        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstallation::class, object : ObjectProvider<PackageInstallation> {
            override val type: KClass<PackageInstallation> = PackageInstallation::class
            override suspend fun get() = installation
        }, "known")
        assertFalse(service.install("known", "2.0.0"))

        val history = listOf(PackageInstallationHistory(key = "known", version = "1.0.0"))
        coEvery { repository.getHistory() } returns history
        assertEquals(history, service.getHistory())
    }

    @Test
    fun `specific installer install returns false when installer version is already recorded`() = runTest {
        val installer = mockk<PackageInstaller>()
        coEvery { installer.version } returns "2.0.0"
        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstaller::class, object : ObjectProvider<PackageInstaller> {
            override val type: KClass<PackageInstaller> = PackageInstaller::class
            override suspend fun get() = installer
        }, "seed")
        val version = PackageInstallationVersion("1.0.0", listOf("seed"))
        val installation = PackageInstallation("known", "Known", listOf(version))
        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstallation::class, object : ObjectProvider<PackageInstallation> {
            override val type: KClass<PackageInstallation> = PackageInstallation::class
            override suspend fun get() = installation
        }, "known")
        coEvery { repository.getHistory() } returns listOf(
            PackageInstallationHistory(key = "known:seed", version = "2.0.0"),
        )

        assertFalse(service.install("known", "1.0.0", "seed", "ignored-argument"))
        coVerify(exactly = 0) { installer.install(any(), any()) }
    }

    @Test
    fun `bulk install filters unselected packages and skips fully installed versions`() = runTest {
        val selected = PackageInstallation("selected", "Selected", listOf(PackageInstallationVersion("1", emptyList())))
        val ignored = PackageInstallation("ignored", "Ignored", listOf(PackageInstallationVersion("1", emptyList())))
        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstallation::class, object : ObjectProvider<PackageInstallation> {
            override val type: KClass<PackageInstallation> = PackageInstallation::class
            override suspend fun get() = selected
        }, "selected")
        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstallation::class, object : ObjectProvider<PackageInstallation> {
            override val type: KClass<PackageInstallation> = PackageInstallation::class
            override suspend fun get() = ignored
        }, "ignored")
        coEvery { repository.getHistory() } returns listOf(PackageInstallationHistory(key = "selected", version = "1"))

        service.install(setOf("selected"))

        coVerify(exactly = 0) { repository.addHistory(any()) }
    }

    @Test
    fun `bulk installation resumes each nested loop after suspending work`() = runTest {
        var installs = 0
        val installer = object : PackageInstaller {
            override val version = "1"

            override suspend fun install(
                installation: PackageInstallation,
                version: PackageInstallationVersion,
            ) {
                delay(1)
                installs++
            }
        }
        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstaller::class, object : ObjectProvider<PackageInstaller> {
            override val type: KClass<PackageInstaller> = PackageInstaller::class
            override suspend fun get() = installer
        }, "suspending")
        val installation = PackageInstallation(
            "suspending-package",
            "Suspending Package",
            listOf(PackageInstallationVersion("1", listOf("suspending"))),
        )
        @OptIn(InternalDI::class)
        ProviderRegistry.register(PackageInstallation::class, object : ObjectProvider<PackageInstallation> {
            override val type: KClass<PackageInstallation> = PackageInstallation::class
            override suspend fun get(): PackageInstallation {
                delay(1)
                return installation
            }
        }, installation.key)
        coEvery { repository.getHistory() } coAnswers {
            delay(1)
            emptyList()
        }
        coEvery { repository.addHistory(any()) } coAnswers {
            delay(1)
            firstArg()
        }

        service.install(setOf(installation.key))

        assertEquals(1, installs)
        coVerify(exactly = 2) { repository.addHistory(any()) }
    }
}
