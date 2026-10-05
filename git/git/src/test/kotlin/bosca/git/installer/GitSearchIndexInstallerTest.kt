package bosca.git.installer

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.serialization.UUID
import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [GitSearchIndexInstaller]: fresh install creates the `git-code` SEARCH
 * storage system; re-install edits the existing one in place; and the index
 * configuration carries the search/filter/sort attribute sets.
 */
class GitSearchIndexInstallerTest {

    private val storageSystemService = mockk<StorageSystemService>(relaxed = true)
    private val installer = GitSearchIndexInstaller(storageSystemService, Json)

    private val installation = mockk<PackageInstallation>(relaxed = true)
    private val version = mockk<PackageInstallationVersion>(relaxed = true)

    @Test
    fun `declares an installer version`() {
        assertTrue(installer.version.isNotBlank())
    }

    @Test
    fun `fresh install adds the git-code search system`() = runTest {
        coEvery { storageSystemService.getByName("git-code") } returns null

        installer.install(installation, version)

        coVerify {
            storageSystemService.add(match {
                it.name == "git-code" && it.type == StorageSystemType.SEARCH
            })
        }
        coVerify(exactly = 0) { storageSystemService.edit(any(), any()) }
    }

    @Test
    fun `re-install edits the existing system in place`() = runTest {
        val existingId = UUID.random()
        coEvery { storageSystemService.getByName("git-code") } returns StorageSystem(
            id = existingId, name = "git-code", description = "", type = StorageSystemType.SEARCH,
            configuration = Json.parseToJsonElement("{}"),
        )

        installer.install(installation, version)

        coVerify { storageSystemService.edit(existingId, match { it.name == "git-code" }) }
        coVerify(exactly = 0) { storageSystemService.add(any()) }
    }

    @Test
    fun `index configuration carries the attribute sets`() = runTest {
        coEvery { storageSystemService.getByName("git-code") } returns null

        installer.install(installation, version)

        coVerify {
            storageSystemService.add(match { input ->
                val cfg = input.configuration.jsonObject
                cfg["indexName"]?.jsonPrimitive?.content == "git-code" &&
                    cfg.toString().contains("branches") &&      // filterable
                    cfg.toString().contains("diskSizeBytes") && // sortable
                    cfg.toString().contains("content")          // searchable
            })
        }
        assertEquals("git-code", GitSearchIndexInstaller.STORAGE_SYSTEM_NAME)
        assertEquals("git-code", GitSearchIndexInstaller.INDEX_NAME)
    }
}
