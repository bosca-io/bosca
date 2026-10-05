package bosca.jobs

import bosca.content.transformations.DocumentToTextConfiguration
import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class GoogleGenAIImageJobTest {

    @Test
    fun `preserves all fields`() {
        val id = UUID.random()
        val config = DocumentToTextConfiguration(includeTitle = false, includeTtsMarkup = true, excludeContainers = setOf("sidebar"))
        val job = GoogleGenAIImageJob(
            id = id,
            version = 3,
            modelKey = "model-key",
            promptKey = "prompt-key",
            configuration = config
        )
        assertEquals(id, job.id)
        assertEquals(3, job.version)
        assertEquals("model-key", job.modelKey)
        assertEquals("prompt-key", job.promptKey)
        assertEquals(config, job.configuration)
    }

    @Test
    fun `configuration defaults to default DocumentToTextConfiguration`() {
        val id = UUID.random()
        val job = GoogleGenAIImageJob(
            id = id,
            version = 1,
            modelKey = "m",
            promptKey = "p"
        )
        val defaultConfig = DocumentToTextConfiguration()
        assertEquals(defaultConfig, job.configuration)
        assertTrue(job.configuration.includeTitle)
        assertFalse(job.configuration.includeTtsMarkup)
        assertEquals(emptySet(), job.configuration.excludeContainers)
    }

    @Test
    fun `implements IJobDefinition`() {
        val job = GoogleGenAIImageJob(
            id = UUID.random(),
            version = 1,
            modelKey = "m",
            promptKey = "p"
        )
        assertIs<IJobDefinition>(job)
    }

    @Test
    fun `custom configuration is preserved`() {
        val config = DocumentToTextConfiguration(
            includeTitle = false,
            includeTtsMarkup = true,
            excludeContainers = setOf("header", "footer")
        )
        val job = GoogleGenAIImageJob(
            id = UUID.random(),
            version = 2,
            modelKey = "test-model",
            promptKey = "test-prompt",
            configuration = config
        )
        assertFalse(job.configuration.includeTitle)
        assertTrue(job.configuration.includeTtsMarkup)
        assertEquals(setOf("header", "footer"), job.configuration.excludeContainers)
    }
}
