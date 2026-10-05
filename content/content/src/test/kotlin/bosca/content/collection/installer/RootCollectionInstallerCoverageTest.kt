package bosca.content.collection.installer

import bosca.content.collection.model.CollectionInput
import bosca.content.collection.service.CollectionService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RootCollectionInstallerCoverageTest {

    private val collectionService = mockk<CollectionService>(relaxed = true)

    private val installer = RootCollectionInstaller(collectionService)

    private val installation = PackageInstallation(
        key = "root-collection-installer",
        name = "Root Collection Installer",
        versions = listOf(
            PackageInstallationVersion(
                version = "1.0.0",
                installerNames = listOf("root-collection-installer")
            )
        )
    )
    private val installationVersion = installation.versions[0]

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `version is 1_0_0`() {
        assertEquals("1.0.0", installer.version)
    }

    @Test
    fun `install adds Root collection when it does not exist`() = runTest {
        coEvery { collectionService.getById(UUID.NIL) } returns null

        val inputSlot = slot<CollectionInput>()
        coEvery { collectionService.addRoot(capture(inputSlot)) } returns mockk(relaxed = true)

        installer.install(installation, installationVersion)

        coVerify(exactly = 1) { collectionService.getById(UUID.NIL) }
        coVerify(exactly = 1) { collectionService.addRoot(any()) }
        val captured = inputSlot.captured
        assertEquals("Root", captured.name)
        assertEquals(JsonObject(emptyMap()), captured.attributes)
    }

    @Test
    fun `install skips creation when root collection already exists`() = runTest {
        coEvery { collectionService.getById(UUID.NIL) } returns mockk(relaxed = true)

        installer.install(installation, installationVersion)

        coVerify(exactly = 1) { collectionService.getById(UUID.NIL) }
        coVerify(exactly = 0) { collectionService.addRoot(any()) }
    }
}
