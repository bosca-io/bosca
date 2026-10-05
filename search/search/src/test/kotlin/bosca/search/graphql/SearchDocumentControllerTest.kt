package bosca.search.graphql

import bosca.search.model.SearchDocument
import bosca.search.model.SearchResult
import bosca.search.model.SearchResultFacet
import bosca.search.IndexStorageSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchDocumentControllerTest {

    private val controller = SearchDocumentController()

    @Test
    fun `metadata returns document metadata`() {
        val doc = SearchDocument(metadata = null, collection = null, profile = null)
        assertNull(controller.metadata(doc))
    }

    @Test
    fun `collection returns document collection`() {
        val doc = SearchDocument(metadata = null, collection = null, profile = null)
        assertNull(controller.collection(doc))
    }

    @Test
    fun `profile returns document profile`() {
        val doc = SearchDocument(metadata = null, collection = null, profile = null)
        assertNull(controller.profile(doc))
    }

    @Test
    fun `all fields null by default`() {
        val doc = SearchDocument()
        assertNull(controller.metadata(doc))
        assertNull(controller.collection(doc))
        assertNull(controller.profile(doc))
    }
}

class SearchResultControllerTest {

    private val controller = SearchResultController()

    @Test
    fun `documents returns the documents list`() {
        val result = SearchResult(
            documents = listOf(SearchDocument()),
            facets = emptyList(),
            estimatedHits = 1,
            system = IndexStorageSystem(name = "test")
        )
        assertEquals(1, controller.documents(result).size)
    }

    @Test
    fun `documents returns empty list when no results`() {
        val result = SearchResult(
            documents = emptyList(),
            facets = emptyList(),
            estimatedHits = 0,
            system = IndexStorageSystem(name = "test")
        )
        assertTrue(controller.documents(result).isEmpty())
    }

    @Test
    fun `facets returns the facets list`() {
        val facet = SearchResultFacet(count = 5, field = "type", value = "metadata")
        val result = SearchResult(
            documents = emptyList(),
            facets = listOf(facet),
            estimatedHits = 0,
            system = IndexStorageSystem(name = "test")
        )
        assertEquals(1, controller.facets(result).size)
        assertEquals("type", controller.facets(result)[0].field)
    }

    @Test
    fun `estimatedHits returns the count`() {
        val result = SearchResult(
            documents = emptyList(),
            facets = emptyList(),
            estimatedHits = 42,
            system = IndexStorageSystem(name = "test")
        )
        assertEquals(42L, controller.estimatedHits(result))
    }

    @Test
    fun `estimatedHits returns zero for empty results`() {
        val result = SearchResult(
            documents = emptyList(),
            facets = emptyList(),
            estimatedHits = 0,
            system = IndexStorageSystem(name = "test")
        )
        assertEquals(0L, controller.estimatedHits(result))
    }
}

class SearchResultFacetControllerTest {

    private val controller = SearchResultFacetController()

    @Test
    fun `count returns facet count`() {
        val facet = SearchResultFacet(count = 10, field = "category", value = "articles")
        assertEquals(10L, controller.count(facet))
    }

    @Test
    fun `field returns facet field name`() {
        val facet = SearchResultFacet(count = 10, field = "category", value = "articles")
        assertEquals("category", controller.field(facet))
    }

    @Test
    fun `value returns facet value`() {
        val facet = SearchResultFacet(count = 10, field = "category", value = "articles")
        assertEquals("articles", controller.value(facet))
    }

    @Test
    fun `handles zero count`() {
        val facet = SearchResultFacet(count = 0, field = "type", value = "empty")
        assertEquals(0L, controller.count(facet))
    }
}
