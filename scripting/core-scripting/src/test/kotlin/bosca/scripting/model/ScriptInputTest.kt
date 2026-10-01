package bosca.scripting.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScriptInputTest {

    @Test
    fun `ScriptInput stores required fields`() {
        val input = ScriptInput(
            key = "my-script",
            name = "My Script",
            source = "console.log('hello')"
        )
        assertEquals("my-script", input.key)
        assertEquals("My Script", input.name)
        assertEquals("console.log('hello')", input.source)
    }

    @Test
    fun `ScriptInput has sensible defaults`() {
        val input = ScriptInput(key = "k", name = "n", source = "s")
        assertEquals("", input.description)
        assertEquals(ScriptType.GENERAL, input.type)
        assertNull(input.inputSchema)
        assertNull(input.outputSchema)
        assertNull(input.configuration)
    }

    @Test
    fun `ScriptInput with all fields`() {
        val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        val outputSchema = JsonObject(mapOf("type" to JsonPrimitive("string")))
        val config = JsonObject(mapOf("timeout" to JsonPrimitive(30)))
        val input = ScriptInput(
            key = "trigger-script",
            name = "Trigger Script",
            description = "Runs on content events",
            type = ScriptType.TRIGGER,
            source = "function run() {}",
            inputSchema = inputSchema,
            outputSchema = outputSchema,
            configuration = config
        )
        assertEquals("Runs on content events", input.description)
        assertEquals(ScriptType.TRIGGER, input.type)
        assertEquals(inputSchema, input.inputSchema)
        assertEquals(outputSchema, input.outputSchema)
        assertEquals(config, input.configuration)
    }

    @Test
    fun `ScriptInput equality`() {
        val a = ScriptInput(key = "k", name = "n", source = "s")
        val b = ScriptInput(key = "k", name = "n", source = "s")
        assertEquals(a, b)
    }
}
