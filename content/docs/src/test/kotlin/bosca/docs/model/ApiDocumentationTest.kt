package bosca.docs.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApiDocumentationTest {

    @Test
    fun `ApiDocumentation stores qualifiedName and content`() {
        val content = JsonObject(mapOf("key" to JsonPrimitive("value")))
        val doc = ApiDocumentation(
            qualifiedName = "bosca.test.MyService",
            content = content
        )
        assertEquals("bosca.test.MyService", doc.qualifiedName)
        assertEquals(content, doc.content)
    }

    @Test
    fun `ApiDocumentation indexedAt defaults to null`() {
        val doc = ApiDocumentation(
            qualifiedName = "bosca.test.SomeClass",
            content = JsonObject(emptyMap())
        )
        assertNull(doc.indexedAt)
    }

    @Test
    fun `ApiDocumentation data class equality`() {
        val content = JsonPrimitive("test")
        val d1 = ApiDocumentation(qualifiedName = "a.b.C", content = content)
        val d2 = ApiDocumentation(qualifiedName = "a.b.C", content = content)
        assertEquals(d1, d2)
    }

    @Test
    fun `ApiDocumentation copy`() {
        val doc = ApiDocumentation(qualifiedName = "a.b.C", content = JsonPrimitive("v1"))
        val modified = doc.copy(content = JsonPrimitive("v2"))
        assertEquals("a.b.C", modified.qualifiedName)
        assertEquals(JsonPrimitive("v2"), modified.content)
    }
}
