package bosca.search.installer

import bosca.search.model.IndexConfiguration
import bosca.search.pipeline.SearchDocumentPipeline
import bosca.serialization.UUID
import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemInput
import bosca.storage.service.StorageSystemService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
}
