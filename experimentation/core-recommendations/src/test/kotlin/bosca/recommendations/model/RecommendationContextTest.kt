package bosca.recommendations.model

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecommendationContextTest {

    @Test
    fun `metadata normalization preserves matching and saved values`() {
        val filter = RecommendationMetadataFilter(
            includedContentTypePrefixes = listOf(" TEXT/ ", "text/", " "),
            excludedContentTypePrefixes = listOf(" TEXT/ "),
            includedAttributeTypes = listOf(" ARTICLE ", "article", ""),
            excludedAttributeTypes = listOf(" ARTICLE "),
        )
        val normalized = filter.normalized()
        assertEquals(listOf("text/"), normalized.includedContentTypePrefixes)
        assertEquals(listOf("article"), normalized.includedAttributeTypes)
        assertEquals(listOf("text/"), normalized.excludedContentTypePrefixes)
        assertEquals(listOf("article"), normalized.excludedAttributeTypes)
        assertEquals(listOf(" TEXT/ ", "text/", " "), filter.includedContentTypePrefixes)
        assertEquals(normalized, normalized.normalized())
        assertTrue(filter.matches(" Text/Plain; charset=utf-8 ", " Article "))
        assertFalse(filter.matches("text/plain", "articles"))
        assertFalse(filter.matches("image/png", "article"))
        for (mime in listOf(null, "", "text/plain", " Text/HTML; charset=utf-8 ", "image/png")) {
            for (type in listOf(null, "", "article", " ARTICLE ", "guide")) {
                assertEquals(filter.matches(mime, type), normalized.matches(mime, type))
            }
        }
    }

    @Test
    fun `blank includes do not suppress exclusions and missing values do not match allow lists`() {
        val filter = RecommendationMetadataFilter(
            includedAttributeTypes = listOf(" "), excludedAttributeTypes = listOf("guide"),
        )
        assertFalse(filter.matches("image/png", null))
        assertFalse(filter.matches("text/plain", " Guide "))
        assertTrue(filter.matches(null, null))
        assertTrue(filter.matches("text/plain", ""))
        assertFalse(filter.copy(includedAttributeTypes = listOf("article")).matches("text/plain", null))
        assertFalse(filter.copy(includedContentTypePrefixes = listOf("text/")).matches(null, "article"))
    }

    @Test
    fun `collection filters use exact matching and independent include precedence`() {
        val filter = RecommendationCollectionFilter(
            includedTypes = listOf(" STANDARD "), excludedTypes = listOf("standard"),
            includedAttributeTypes = listOf(" SERIES "), excludedAttributeTypes = listOf("series"),
        )
        assertTrue(filter.matches("standard", "series"))
        assertFalse(filter.matches("standard-child", "series"))
        assertFalse(filter.matches("standard", null))
        assertFalse(filter.matches("standard", "series-child"))
        assertTrue(RecommendationCollectionFilter.ALL.matches("standard", null))
    }

    @Test
    fun `context stores its request type and saved filter`() {
        val filter = RecommendationContentFilter(
            metadata = RecommendationMetadataFilter(includedContentTypePrefixes = listOf("image/")),
            collections = null,
        )
        val context = RecommendationContext(
            type = "image_picker",
            name = "Image picker",
            description = "Image selection surfaces",
            contentFilter = filter,
        )

        assertEquals(UUID.NIL, context.id)
        assertEquals("image_picker", context.type)
        assertEquals(filter, context.contentFilter)
        assertEquals("default", RecommendationContext.DEFAULT_TYPE)
    }

    @Test
    fun `context input defaults to the standard filter`() {
        val input = RecommendationContextInput(type = "default", name = "Default")

        assertEquals(RecommendationContentFilterInput(), input.contentFilter)
        assertEquals("", input.description)
    }
}
