package bosca.ai.configuration

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultPromptTest {

    @Test
    fun `DefaultPrompt is not blank`() {
        assertTrue(DefaultPrompt.isNotBlank())
    }

    @Test
    fun `DefaultPrompt contains tool usage instruction`() {
        assertTrue(DefaultPrompt.contains("ONLY EVER USE CONTENT FROM THE TOOLS"))
    }

    @Test
    fun `DefaultPrompt contains safety instruction about self-harm`() {
        assertTrue(DefaultPrompt.contains("intent to harm"))
    }

    @Test
    fun `DefaultPrompt contains schema description`() {
        assertTrue(DefaultPrompt.contains("Search Response Schema"))
    }

    @Test
    fun `DefaultPrompt contains previewUrl placeholder`() {
        assertTrue(DefaultPrompt.contains("previewUrl"))
    }

    @Test
    fun `DefaultPrompt does not contain leading whitespace from trimIndent`() {
        assertFalse(DefaultPrompt.startsWith("    "))
    }

    @Test
    fun `DefaultPrompt contains DONE tool instruction`() {
        assertTrue(DefaultPrompt.contains("DONE tool"))
    }
}
