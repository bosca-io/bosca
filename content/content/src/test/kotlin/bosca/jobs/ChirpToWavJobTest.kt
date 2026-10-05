package bosca.jobs

import bosca.content.transformations.DocumentToTextConfiguration
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChirpToWavJobTest {

    @Test
    fun `field preservation with all values`() {
        val id = UUID.random()
        val suppId = UUID.random()
        val config = DocumentToTextConfiguration(includeTitle = false, includeTtsMarkup = true)
        val job = ChirpToWavJob(
            id = id,
            version = 3,
            modelKey = "chirp-hd",
            configuration = config,
            supplementaryId = suppId
        )
        assertEquals(id, job.id)
        assertEquals(3, job.version)
        assertEquals("chirp-hd", job.modelKey)
        assertEquals(config, job.configuration)
        assertEquals(suppId, job.supplementaryId)
    }

    @Test
    fun `supplementaryId defaults to null`() {
        val id = UUID.random()
        val config = DocumentToTextConfiguration()
        val job = ChirpToWavJob(id = id, version = 1, modelKey = "key", configuration = config)
        assertNull(job.supplementaryId)
    }

    @Test
    fun `data class equality`() {
        val id = UUID.random()
        val config = DocumentToTextConfiguration()
        val a = ChirpToWavJob(id = id, version = 1, modelKey = "k", configuration = config)
        val b = ChirpToWavJob(id = id, version = 1, modelKey = "k", configuration = config)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes modelKey`() {
        val id = UUID.random()
        val config = DocumentToTextConfiguration()
        val job = ChirpToWavJob(id = id, version = 1, modelKey = "old", configuration = config)
        val modified = job.copy(modelKey = "new")
        assertEquals("new", modified.modelKey)
        assertEquals(id, modified.id)
    }

    @Test
    fun `configuration is preserved through copy`() {
        val config = DocumentToTextConfiguration(includeTtsMarkup = true, excludeContainers = setOf("X"))
        val job = ChirpToWavJob(id = UUID.random(), version = 1, modelKey = "k", configuration = config)
        val copied = job.copy()
        assertEquals(config, copied.configuration)
    }
}
