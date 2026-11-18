package bosca.design.catalog

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogDocumentTest {
    @Test
    fun `submitted custom nodes marks and attributes remain intact after accepted and rejected saves`() = runBlocking {
        val text = """{"type":"doc","attrs":{"application":"kept"},"content":[{"type":"customBlock","attrs":{"key":"kept"}},{"type":"paragraph","attrs":{"application":2},"content":[{"type":"text","text":"Draft","marks":[{"type":"customMark","attrs":{"color":"blue"}}]}]}]}"""
        val state = CatalogDocument("custom")
        state.save(text)
        assertEquals(Json.parseToJsonElement(text), state.value)
        state.reject = true
        state.save(text)
        assertEquals(Json.parseToJsonElement(text), state.value)
        assertEquals(text, state.saved)
    }
}
