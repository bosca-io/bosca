@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.installer.graphql

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.installer.model.InstalledPackage
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationHistory
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstallationService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.AuthenticationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PackageControllersTest {

    private val service = mockk<PackageInstallationService>()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun authentication(vararg groups: String): AuthenticationContext {
        val principal = mockk<AuthenticatedPrincipal>()
        every { principal.hasGroup(any<String>()) } answers { firstArg<String>() in groups }
        val authentication = mockk<AuthenticationContext>()
        every { authentication.principal() } returns principal
        return authentication
    }

    @Test
    fun `packages queries hide data from anonymous and ordinary users`() = runTest {
        val anonymous = mockk<AuthenticationContext>()
        every { anonymous.principal() } returns null
        val ordinary = authentication("users")
        val controller = PackagesController(service)

        assertTrue(controller.all(anonymous).isEmpty())
        assertTrue(controller.history(anonymous).isEmpty())
        assertTrue(controller.all(ordinary).isEmpty())
        assertTrue(controller.history(ordinary).isEmpty())
    }

    @Test
    fun `packages queries expose provider catalogue and history to sa and administrators`() = runTest {
        val installation = PackageInstallation("core", "Core", emptyList())
        provides<PackageInstallation>("core") { installation }
        val history = listOf(PackageInstallationHistory(key = "core", version = "1"))
        coEvery { service.getHistory() } returns history
        val controller = PackagesController(service)

        assertEquals(listOf(installation), controller.all(authentication("sa")))
        assertEquals(history, controller.history(authentication("administrators")))
    }

    @Test
    fun `package mutations require authentication and sa or administrator membership`() = runTest {
        val controller = PackagesMutationController(service)
        val anonymous = mockk<AuthenticationContext>()
        every { anonymous.principal() } returns null
        assertFailsWith<SecurityException> { controller.install(anonymous, "core", "1") }
        assertFailsWith<SecurityException> {
            controller.installInstaller(authentication("users"), "core", "1", "seed", "2")
        }

        coEvery { service.install("core", "1") } returns true
        coEvery { service.install("core", "1", "seed", "2") } returns false
        assertTrue(controller.install(authentication("sa"), "core", "1"))
        assertEquals(false, controller.installInstaller(authentication("administrators"), "core", "1", "seed", "2"))
        coVerify { service.install("core", "1") }
        coVerify { service.install("core", "1", "seed", "2") }
    }

    @Test
    fun `installation history and installed package controllers expose every field`() {
        val created = OffsetDateTime.parse("2024-01-01T00:00:00Z")
        val history = PackageInstallationHistory(Uuid.random(), "core", "1", created)
        val historyController = PackageInstallationHistoryController()
        assertEquals(history.id, historyController.id(history))
        assertEquals("core", historyController.key(history))
        assertEquals("1", historyController.version(history))
        assertEquals(created, historyController.created(history))

        val installed = InstalledPackage("seed", "2")
        val installedController = InstalledPackageController()
        assertEquals("seed", installedController.name(installed))
        assertEquals("2", installedController.version(installed))
    }

    @Test
    fun `installation controller exposes key name and version contexts`() {
        val version = PackageInstallationVersion("1", listOf("seed"))
        val installation = PackageInstallation("core", "Core", listOf(version))
        val controller = PackageInstallationController()
        assertEquals("core", controller.key(installation))
        assertEquals("Core", controller.name(installation))
        val context = controller.versions(installation).single()
        assertEquals(installation, context.installation)
        assertEquals(version, context.version)
    }
}
