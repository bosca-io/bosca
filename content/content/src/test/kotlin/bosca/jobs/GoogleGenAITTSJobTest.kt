package bosca.jobs

import bosca.content.transformations.DocumentToTextConfiguration
import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class GoogleGenAITTSJobTest {

    private val defaultConfig = DocumentToTextConfiguration()

    @Test
    fun `preserves all fields`() {
        val id = UUID.random()
        val suppId = UUID.random()
        val config = DocumentToTextConfiguration(includeTitle = false, includeTtsMarkup = true)
        val job = GoogleGenAITTSJob(
            id = id,
            version = 7,
            modelKey = "tts-model",
            promptKey = "tts-prompt",
            configuration = config,
            supplementaryId = suppId
        )
        assertEquals(id, job.id)
        assertEquals(7, job.version)
        assertEquals("tts-model", job.modelKey)
        assertEquals("tts-prompt", job.promptKey)
        assertEquals(config, job.configuration)
        assertEquals(suppId, job.supplementaryId)
    }

    @Test
    fun `supplementaryId defaults to null`() {
        val job = GoogleGenAITTSJob(
            id = UUID.random(),
            version = 1,
            modelKey = "m",
            promptKey = "p",
            configuration = defaultConfig
        )
        assertNull(job.supplementaryId)
    }

    @Test
    fun `supplementaryId can be provided`() {
        val suppId = UUID.random()
        val job = GoogleGenAITTSJob(
            id = UUID.random(),
            version = 1,
            modelKey = "m",
            promptKey = "p",
            configuration = defaultConfig,
            supplementaryId = suppId
        )
        assertNotNull(job.supplementaryId)
        assertEquals(suppId, job.supplementaryId)
    }

    @Test
    fun `implements IJobDefinition`() {
        val job = GoogleGenAITTSJob(
            id = UUID.random(),
            version = 1,
            modelKey = "m",
            promptKey = "p",
            configuration = defaultConfig
        )
        assertIs<IJobDefinition>(job)
    }

    @Test
    fun `configuration is required and preserved`() {
        val config = DocumentToTextConfiguration(
            includeTitle = true,
            includeTtsMarkup = true,
            excludeContainers = setOf("notes")
        )
        val job = GoogleGenAITTSJob(
            id = UUID.random(),
            version = 2,
            modelKey = "model",
            promptKey = "prompt",
            configuration = config
        )
        assertEquals(config, job.configuration)
        assertEquals(setOf("notes"), job.configuration.excludeContainers)
    }
}
