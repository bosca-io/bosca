package bosca.content.transformations.pipeline

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentVisibilityTest {

    private fun passes(
        public: Boolean = true,
        published: Boolean = true,
        searchable: Boolean = true,
        deleted: Boolean = false,
        requirePublic: Boolean = true,
        requirePublished: Boolean = true,
        requireSearchable: Boolean = true,
        excludeDeleted: Boolean = true,
    ) = ContentVisibility.passes(
        public, published, searchable, deleted,
        requirePublic, requirePublished, requireSearchable, excludeDeleted,
    )

    @Test
    fun `passes when every required flag is satisfied`() {
        assertTrue(passes())
    }

    @Test
    fun `each enabled requirement blocks when its flag is not satisfied`() {
        assertFalse(passes(public = false), "not public")
        assertFalse(passes(published = false), "not published")
        assertFalse(passes(searchable = false), "not searchable")
        assertFalse(passes(deleted = true), "deleted")
    }

    @Test
    fun `turning a requirement off lets the corresponding flag through`() {
        assertTrue(passes(public = false, requirePublic = false))
        assertTrue(passes(published = false, requirePublished = false))
        assertTrue(passes(searchable = false, requireSearchable = false))
        assertTrue(passes(deleted = true, excludeDeleted = false))
    }

    @Test
    fun `matches is true for a blank predicate`() {
        assertTrue(ContentVisibility.matches("", buildJsonObject { put("name", "x") }))
    }

    @Test
    fun `matches evaluates a JSONata predicate against the entity json`() {
        val json = buildJsonObject { put("name", "x") }
        assertTrue(ContentVisibility.matches("name = 'x'", json))
        assertFalse(ContentVisibility.matches("name = 'y'", json))
    }
}
