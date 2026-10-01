package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ContainerRendererTest {

    @Test
    fun `field preservation after construction with all fields`() {
        val config = buildJsonObject { put("theme", "dark") }
        val renderer = ContainerRenderer(name = "grid", configuration = config)
        assertEquals("grid", renderer.name)
        assertEquals(config, renderer.configuration)
    }

    @Test
    fun `default configuration is null`() {
        val renderer = ContainerRenderer(name = "list")
        assertNull(renderer.configuration)
    }

    @Test
    fun `configuration can be JsonNull`() {
        val renderer = ContainerRenderer(name = "list", configuration = JsonNull)
        assertEquals(JsonNull, renderer.configuration)
    }

    @Test
    fun `data class equality for identical instances`() {
        val config = buildJsonObject { put("key", "val") }
        val r1 = ContainerRenderer(name = "grid", configuration = config)
        val r2 = ContainerRenderer(name = "grid", configuration = config)
        assertEquals(r1, r2)
        assertEquals(r1.hashCode(), r2.hashCode())
    }

    @Test
    fun `data class equality when both configurations are null`() {
        val r1 = ContainerRenderer(name = "grid")
        val r2 = ContainerRenderer(name = "grid")
        assertEquals(r1, r2)
    }

    @Test
    fun `data class inequality when name differs`() {
        val r1 = ContainerRenderer(name = "grid")
        val r2 = ContainerRenderer(name = "list")
        assertNotEquals(r1, r2)
    }

    @Test
    fun `data class inequality when configuration differs`() {
        val c1 = buildJsonObject { put("a", 1) }
        val c2 = buildJsonObject { put("b", 2) }
        val r1 = ContainerRenderer(name = "grid", configuration = c1)
        val r2 = ContainerRenderer(name = "grid", configuration = c2)
        assertNotEquals(r1, r2)
    }

    @Test
    fun `copy preserves fields and allows overrides`() {
        val config = buildJsonObject { put("theme", "dark") }
        val renderer = ContainerRenderer(name = "grid", configuration = config)
        val copy = renderer.copy(name = "list")
        assertEquals("list", copy.name)
        assertEquals(config, copy.configuration)
    }

    @Test
    fun `copy can set configuration to null`() {
        val config = buildJsonObject { put("x", 1) }
        val renderer = ContainerRenderer(name = "grid", configuration = config)
        val copy = renderer.copy(configuration = null)
        assertEquals("grid", copy.name)
        assertNull(copy.configuration)
    }
}
