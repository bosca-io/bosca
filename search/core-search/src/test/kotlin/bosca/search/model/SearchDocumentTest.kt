package bosca.search.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchDocumentItemTest {

    @Test
    fun fieldsArePreserved() {
        val item = SearchDocumentItem(id = "abc-123", name = "Test Doc")
        assertEquals("abc-123", item.id)
        assertEquals("Test Doc", item.name)
    }

    @Test
    fun dataClassEquality() {
        val a = SearchDocumentItem(id = "1", name = "A")
        val b = SearchDocumentItem(id = "1", name = "A")
        assertEquals(a, b)
    }
}

class SearchDocumentTest {

    @Test
    fun defaultsAreNull() {
        val doc = SearchDocument()
        assertNull(doc.collection)
        assertNull(doc.metadata)
        assertNull(doc.profile)
        assertNull(doc.organization)
    }
}
