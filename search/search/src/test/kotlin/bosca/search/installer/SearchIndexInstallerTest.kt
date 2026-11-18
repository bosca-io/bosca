package bosca.search.installer

import bosca.search.model.IndexConfiguration
import bosca.search.pipeline.SearchDocumentPipeline
import bosca.serialization.UUID
import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemInput
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchIndexInstallerTest {

    @Test
    fun `installer creates a dedicated profile index`() = runTest {
        val storage = mockk<StorageSystemService>()
        val added = mutableListOf<StorageSystemInput>()
        coEvery { storage.getAll() } returns emptyList()
        coEvery { storage.add(capture(added)) } answers {
            val input = firstArg<StorageSystemInput>()
            StorageSystem(
                id = UUID.random(),
                name = input.name,
                description = input.description,
                type = input.type,
                configuration = input.configuration,
            )
        }
        val installer = SearchIndexInstaller(storage, Json)

        installer.install(mockk(), mockk())

        assertEquals("1.0.6", installer.version)
        assertEquals(
            listOf(
                SearchDocumentPipeline.DEFAULT_INDEX,
                SearchDocumentPipeline.ADMIN_INDEX,
                SearchDocumentPipeline.PROFILE_INDEX,
            ),
            added.map { it.name },
        )
        val profile = added.single { it.name == SearchDocumentPipeline.PROFILE_INDEX }
        val configuration = Json.decodeFromJsonElement(IndexConfiguration.serializer(), profile.configuration)
        assertEquals("profiles", configuration.name)
        assertFalse(configuration.contentIndex)
        assertEquals(listOf("name", "description"), configuration.searchable)

        val administration = added.single { it.name == SearchDocumentPipeline.ADMIN_INDEX }
        val administrationConfiguration = Json.decodeFromJsonElement(
            IndexConfiguration.serializer(),
            administration.configuration,
        )
        assertTrue("labels" in administrationConfiguration.filterable)
    }
    @Test
    fun `installer retains supplementary vector and unrelated storage configurations`() = runTest {
        val storage = mockk<StorageSystemService>()
        val adminId = UUID.random()
        coEvery { storage.getAll() } returns listOf(
            StorageSystem(UUID.random(), "Vector", "", StorageSystemType.VECTOR, Json.parseToJsonElement("{\"indexName\":\"administration\"}")),
            StorageSystem(UUID.random(), "Files", "", StorageSystemType.SUPPLEMENTARY, JsonNull),
            StorageSystem(UUID.random(), "Unconfigured", "", StorageSystemType.SEARCH, JsonNull),
            StorageSystem(adminId, "Admin", "", StorageSystemType.SEARCH, Json.parseToJsonElement("{\"indexName\":\"administration\"}")),
        )
        val added = mutableListOf<StorageSystemInput>()
        coEvery { storage.add(capture(added)) } answers {
            val input = firstArg<StorageSystemInput>()
            StorageSystem(UUID.random(), input.name, input.description, input.type, input.configuration)
        }
        coEvery { storage.edit(adminId, any()) } answers {
            val input = secondArg<StorageSystemInput>()
            StorageSystem(adminId, input.name, input.description, input.type, input.configuration)
        }
        SearchIndexInstaller(storage, Json).install(mockk(), mockk())
        assertEquals(2, added.size)
        coVerify(exactly = 1) { storage.edit(adminId, any()) }
        coVerify(exactly = 1) { storage.edit(any(), any()) }
    }

}
