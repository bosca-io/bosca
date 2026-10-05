package bosca.ai.prompts.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PromptInputTest {

    @Test
    fun `PromptInput stores all properties`() {
        val schema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        val input = PromptInput(
            key = "summarize",
            name = "Summarizer",
            description = "Summarizes text",
            inputType = "text",
            outputType = "text",
            schema = schema,
            systemPrompt = "You are a summarizer.",
            userPrompt = "Summarize: {input}"
        )
        assertEquals("summarize", input.key)
        assertEquals("Summarizer", input.name)
        assertEquals("Summarizes text", input.description)
        assertEquals("text", input.inputType)
        assertEquals("text", input.outputType)
        assertEquals(schema, input.schema)
        assertEquals("You are a summarizer.", input.systemPrompt)
        assertEquals("Summarize: {input}", input.userPrompt)
    }

    @Test
    fun `PromptInput with null schema`() {
        val input = PromptInput(
            key = "k",
            name = "n",
            description = "d",
            inputType = "json",
            outputType = "json",
            schema = null,
            systemPrompt = "sys",
            userPrompt = "usr"
        )
        assertNull(input.schema)
    }

    @Test
    fun `PromptInput equality`() {
        val a = PromptInput("k", "n", "d", "t", "t", null, "s", "u")
        val b = PromptInput("k", "n", "d", "t", "t", null, "s", "u")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `PromptInput copy changes key`() {
        val original = PromptInput("old", "n", "d", "t", "t", null, "s", "u")
        val copied = original.copy(key = "new")
        assertEquals("new", copied.key)
        assertEquals("n", copied.name)
    }
}
