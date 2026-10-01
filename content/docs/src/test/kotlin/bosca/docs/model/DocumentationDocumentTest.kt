package bosca.docs.model

import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentationDocumentTest {

    @Test
    fun `DocumentationDocument stores all fields`() {
        val doc = DocumentationDocument(
            id = "source-bosca_test_MyType",
            name = "MyType",
            qualifiedName = "bosca.test.MyType",
            kind = "interface",
            source = "source-code",
            module = "core",
            pkg = "bosca.test",
            category = "service",
            signature = "suspend fun getAll(): List<Item>",
            description = "A test service",
            content = "Methods:\n  getAll -> List<Item>"
        )
        assertEquals("source-bosca_test_MyType", doc.id)
        assertEquals("MyType", doc.name)
        assertEquals("bosca.test.MyType", doc.qualifiedName)
        assertEquals("interface", doc.kind)
        assertEquals("source-code", doc.source)
        assertEquals("core", doc.module)
        assertEquals("bosca.test", doc.pkg)
        assertEquals("service", doc.category)
        assertEquals("suspend fun getAll(): List<Item>", doc.signature)
        assertEquals("A test service", doc.description)
        assertEquals("Methods:\n  getAll -> List<Item>", doc.content)
    }

    @Test
    fun `DocumentationDocument defaults for optional fields`() {
        val doc = DocumentationDocument(
            id = "test-id",
            name = "Test",
            kind = "class",
            source = "source-code",
            module = "core"
        )
        assertEquals("", doc.qualifiedName)
        assertEquals("", doc.pkg)
        assertEquals("", doc.category)
        assertEquals("", doc.signature)
        assertEquals("", doc.description)
        assertEquals("", doc.content)
    }

    @Test
    fun `DocumentationDocument data class equality`() {
        val d1 = DocumentationDocument(id = "a", name = "A", kind = "class", source = "s", module = "m")
        val d2 = DocumentationDocument(id = "a", name = "A", kind = "class", source = "s", module = "m")
        assertEquals(d1, d2)
    }

    @Test
    fun `DocumentationDocument copy`() {
        val doc = DocumentationDocument(id = "a", name = "A", kind = "class", source = "s", module = "m")
        val modified = doc.copy(name = "B", kind = "interface")
        assertEquals("B", modified.name)
        assertEquals("interface", modified.kind)
        assertEquals("a", modified.id)
    }
}
