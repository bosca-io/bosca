package bosca.search.index

import bosca.search.pipeline.SearchDocumentPipeline
import bosca.serialization.UUID
import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemType
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexInitializerTest {

    @Test
    fun `profile index initialization requests a full profile rebuild`() {
        val system = system(SearchDocumentPipeline.PROFILE_INDEX)

        val job = requireNotNull(system.profileIndexMaintenanceJob())

        assertEquals(system.id, job.storage?.id)
        assertEquals(system.name, job.storage?.name)
        assertTrue(job.deleteFirst)
        assertFalse(job.deleteOnly)
    }

    @Test
    fun `default index initialization removes legacy profile documents`() {
        val job = requireNotNull(system(SearchDocumentPipeline.DEFAULT_INDEX).profileIndexMaintenanceJob())

        assertTrue(job.deleteFirst)
        assertTrue(job.deleteOnly)
    }

    @Test
    fun `other index initialization does not schedule profile maintenance`() {
        assertNull(system(SearchDocumentPipeline.ADMIN_INDEX).profileIndexMaintenanceJob())
    }

    private fun system(name: String) = StorageSystem(
        id = UUID.random(),
        name = name,
        description = name,
        type = StorageSystemType.SEARCH,
        configuration = JsonNull,
    )
}
