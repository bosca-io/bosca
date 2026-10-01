package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ContainerRendererInputTest {

    @Test
    fun `ContainerRendererInput stores all properties`() {
        val config = buildJsonObject { put("width", 800) }
        val input = ContainerRendererInput(
            name = "grid-renderer",
            configuration = config
        )
        assertEquals("grid-renderer", input.name)
        assertEquals(config, input.configuration)
    }

    @Test
    fun `ContainerRendererInput configuration defaults to null`() {
        val input = ContainerRendererInput(name = "default-renderer")
        assertEquals("default-renderer", input.name)
        assertNull(input.configuration)
    }

    @Test
    fun `ContainerRendererInput with explicit null configuration`() {
        val input = ContainerRendererInput(
            name = "simple",
            configuration = null
        )
        assertNull(input.configuration)
    }

    @Test
    fun `ContainerRendererInput with JsonNull configuration`() {
        val input = ContainerRendererInput(
            name = "renderer",
            configuration = JsonNull
        )
        assertEquals(JsonNull, input.configuration)
    }

    @Test
    fun `data class equality`() {
        val config = buildJsonObject { put("theme", "dark") }
        val input1 = ContainerRendererInput(name = "renderer", configuration = config)
        val input2 = ContainerRendererInput(name = "renderer", configuration = config)
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `data class inequality with different names`() {
        val input1 = ContainerRendererInput(name = "renderer-a")
        val input2 = ContainerRendererInput(name = "renderer-b")
        assertNotEquals(input1, input2)
    }

    @Test
    fun `data class inequality with different configurations`() {
        val config1 = buildJsonObject { put("key", "value1") }
        val config2 = buildJsonObject { put("key", "value2") }
        val input1 = ContainerRendererInput(name = "renderer", configuration = config1)
        val input2 = ContainerRendererInput(name = "renderer", configuration = config2)
        assertNotEquals(input1, input2)
    }

    @Test
    fun `data class copy preserves unchanged fields`() {
        val config = buildJsonObject { put("setting", true) }
        val input = ContainerRendererInput(name = "original", configuration = config)
        val copied = input.copy(name = "modified")
        assertEquals("modified", copied.name)
        assertEquals(config, copied.configuration)
    }

    @Test
    fun `data class equality with both null configurations`() {
        val input1 = ContainerRendererInput(name = "renderer")
        val input2 = ContainerRendererInput(name = "renderer")
        assertEquals(input1, input2)
    }
}
