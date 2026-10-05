package bosca.source.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SourceInputTest {

    @Test
    fun `SourceInput stores all fields`() {
        val config = buildJsonObject { put("url", "https://example.com") }
        val input = SourceInput(
            name = "External API",
            description = "An external data source",
            configuration = config
        )
        assertEquals("External API", input.name)
        assertEquals("An external data source", input.description)
        assertEquals(config, input.configuration)
    }

    @Test
    fun `SourceInput data class equality`() {
        val config = JsonObject(emptyMap())
        val input1 = SourceInput(name = "src", description = "d", configuration = config)
        val input2 = SourceInput(name = "src", description = "d", configuration = config)
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `SourceInput inequality on different name`() {
        val config = JsonObject(emptyMap())
        val input1 = SourceInput(name = "src1", description = "d", configuration = config)
        val input2 = SourceInput(name = "src2", description = "d", configuration = config)
        assertNotEquals(input1, input2)
    }

    @Test
    fun `SourceInput inequality on different configuration`() {
        val config1 = buildJsonObject { put("a", 1) }
        val config2 = buildJsonObject { put("b", 2) }
        val input1 = SourceInput(name = "src", description = "d", configuration = config1)
        val input2 = SourceInput(name = "src", description = "d", configuration = config2)
        assertNotEquals(input1, input2)
    }

    @Test
    fun `SourceInput copy preserves unchanged fields`() {
        val config = buildJsonObject { put("key", "value") }
        val input = SourceInput(name = "Original", description = "desc", configuration = config)
        val copied = input.copy(name = "Updated")
        assertEquals("Updated", copied.name)
        assertEquals("desc", copied.description)
        assertEquals(config, copied.configuration)
    }
}
