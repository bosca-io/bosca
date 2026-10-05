package bosca.content.collection.installer

import bosca.content.collection.model.CollectionInput
import bosca.content.collection.service.CollectionService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.slug.model.Slug
import bosca.slug.service.SlugService
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

class BiblesCollectionInstallerCoverageTest {

    private val collectionService = mockk<CollectionService>(relaxed = true)
    private val slugService = mockk<SlugService>(relaxed = true)

    private val installer = BiblesCollectionInstaller(collectionService, slugService)

    private val installation = PackageInstallation(
        key = "bible",
        name = "Bible",
        versions = listOf(
            PackageInstallationVersion(
                version = "1.0.0",
                installerNames = listOf("bibles-collection-installer")
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
    fun `install adds Bibles collection when slug does not exist`() = runTest {
        coEvery { slugService.get("bibles") } returns null

        val inputSlot = slot<CollectionInput>()
        coEvery { collectionService.add(capture(inputSlot)) } returns mockk(relaxed = true)

        installer.install(installation, installationVersion)

        coVerify(exactly = 1) { collectionService.add(any()) }
        val captured = inputSlot.captured
        assertEquals("Bibles", captured.name)
        assertEquals("bibles", captured.slug)
        assertEquals(JsonObject(emptyMap()), captured.attributes)
    }

    @Test
    fun `install skips creation when slug already exists`() = runTest {
        coEvery { slugService.get("bibles") } returns Slug(slug = "bibles")

        installer.install(installation, installationVersion)

        coVerify(exactly = 1) { slugService.get("bibles") }
        coVerify(exactly = 0) { collectionService.add(any()) }
        coVerify(exactly = 0) { collectionService.addRoot(any()) }
    }
}
