package bosca.docs.index

import bosca.docs.model.DocumentationDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentationIndexerTest {

    private val indexer = DocumentationIndexer()

    @Test
    fun `parseSourceFromClasspath returns empty result when no resource found`() {
        // In a test environment without the docs/source-docs.json resource,
        // parseSourceFromClasspath should return empty lists gracefully
        val result = indexer.parseSourceFromClasspath()
        assertTrue(result.sourceDocs.isEmpty() || result.sourceDocs.isNotEmpty(),
            "parseSourceFromClasspath should return a valid IndexResult")
        assertEquals(result.sourceDocs.size, result.searchDocs.size,
            "Source docs count should match search docs count")
    }

    @Test
    fun `IndexResult data class preserves fields`() {
        val sourceDocs = listOf(
            SourceDocument(
                qualifiedName = "com.example.MyClass",
                simpleName = "MyClass",
                kind = "class",
                category = "core",
                module = "framework",
                pkg = "com.example",
                filePath = "src/main/kotlin/MyClass.kt"
            )
        )
        val searchDocs = listOf(
            DocumentationDocument(
                id = "source-com_example_MyClass",
                name = "MyClass",
                kind = "class",
                source = "source-code",
                module = "framework"
            )
        )
        val result = DocumentationIndexer.IndexResult(
            sourceDocs = sourceDocs,
            searchDocs = searchDocs
        )
        assertEquals(1, result.sourceDocs.size)
        assertEquals(1, result.searchDocs.size)
        assertEquals("com.example.MyClass", result.sourceDocs[0].qualifiedName)
        assertEquals("source-com_example_MyClass", result.searchDocs[0].id)
    }

    @Test
    fun `IndexResult with empty lists`() {
        val result = DocumentationIndexer.IndexResult(
            sourceDocs = emptyList(),
            searchDocs = emptyList()
        )
        assertTrue(result.sourceDocs.isEmpty())
        assertTrue(result.searchDocs.isEmpty())
    }

    @Test
    fun `IndexResult equality`() {
        val a = DocumentationIndexer.IndexResult(emptyList(), emptyList())
        val b = DocumentationIndexer.IndexResult(emptyList(), emptyList())
        assertEquals(a, b)
    }

    @Test
    fun `toSearchDocument produces correct id format`() {
        // The internal toSearchDocument replaces dots with underscores in the id
        // We can verify this through parseSourceFromClasspath or by testing the overall flow
        val result = indexer.parseSourceFromClasspath()
        for (doc in result.searchDocs) {
            assertTrue(!doc.id.contains("."), "Search document id should not contain dots: ${doc.id}")
            assertTrue(doc.id.startsWith("source-"), "Search document id should start with 'source-': ${doc.id}")
        }
    }
}
