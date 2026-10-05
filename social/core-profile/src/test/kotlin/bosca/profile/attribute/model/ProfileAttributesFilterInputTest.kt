package bosca.profile.attribute.model

import bosca.profile.model.ProfileVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileAttributesFilterInputTest {

    @Test
    fun `ProfileAttributesFilterInput creation with required attributes list`() {
        val filter = ProfileAttributesFilterInput(
            attributes = listOf("name", "email")
        )
        assertEquals(listOf("name", "email"), filter.attributes)
    }

    @Test
    fun `ProfileAttributesFilterInput optional fields default to null`() {
        val filter = ProfileAttributesFilterInput(
            attributes = listOf("test")
        )
        assertNull(filter.childAttributes)
        assertNull(filter.visibility)
        assertNull(filter.confidence)
        assertNull(filter.priority)
        assertNull(filter.source)
        assertNull(filter.typeId)
    }

    @Test
    fun `ProfileAttributesFilterInput creation with all fields`() {
        val childFilter = ProfileAttributesFilterInput(
            attributes = listOf("child-attr")
        )
        val filter = ProfileAttributesFilterInput(
            attributes = listOf("name", "email"),
            childAttributes = childFilter,
            visibility = ProfileVisibility.PUBLIC,
            confidence = 80,
            priority = 5,
            source = "verified",
            typeId = "contact"
        )
        assertEquals(listOf("name", "email"), filter.attributes)
        assertEquals(childFilter, filter.childAttributes)
        assertEquals(ProfileVisibility.PUBLIC, filter.visibility)
        assertEquals(80, filter.confidence)
        assertEquals(5, filter.priority)
        assertEquals("verified", filter.source)
        assertEquals("contact", filter.typeId)
    }

    @Test
    fun `ProfileAttributesFilterInput supports nested child attributes`() {
        val grandchild = ProfileAttributesFilterInput(
            attributes = listOf("deep-attr")
        )
        val child = ProfileAttributesFilterInput(
            attributes = listOf("mid-attr"),
            childAttributes = grandchild
        )
        val root = ProfileAttributesFilterInput(
            attributes = listOf("top-attr"),
            childAttributes = child
        )
        assertEquals("mid-attr", root.childAttributes?.attributes?.first())
        assertEquals("deep-attr", root.childAttributes?.childAttributes?.attributes?.first())
    }

    @Test
    fun `ProfileAttributesFilterInput with empty attributes list`() {
        val filter = ProfileAttributesFilterInput(
            attributes = emptyList()
        )
        assertTrue(filter.attributes.isEmpty())
    }

    @Test
    fun `ProfileAttributesFilterInput data class equality`() {
        val a = ProfileAttributesFilterInput(
            attributes = listOf("a", "b"),
            visibility = ProfileVisibility.USER,
            typeId = "contact"
        )
        val b = ProfileAttributesFilterInput(
            attributes = listOf("a", "b"),
            visibility = ProfileVisibility.USER,
            typeId = "contact"
        )
        assertEquals(a, b)
    }

    @Test
    fun `ProfileAttributesFilterInput data class equality with nested children`() {
        val child = ProfileAttributesFilterInput(attributes = listOf("c"))
        val a = ProfileAttributesFilterInput(attributes = listOf("a"), childAttributes = child)
        val b = ProfileAttributesFilterInput(attributes = listOf("a"), childAttributes = child)
        assertEquals(a, b)
    }

    @Test
    fun `ProfileAttributesFilterInput copy changes specific fields`() {
        val original = ProfileAttributesFilterInput(
            attributes = listOf("name"),
            visibility = ProfileVisibility.USER,
            confidence = 50
        )
        val copy = original.copy(confidence = 90, source = "api")
        assertEquals(90, copy.confidence)
        assertEquals("api", copy.source)
        assertEquals(original.attributes, copy.attributes)
        assertEquals(original.visibility, copy.visibility)
    }

    @Test
    fun `ProfileAttributesFilterInput hashCode is consistent for equal instances`() {
        val a = ProfileAttributesFilterInput(attributes = listOf("x"), typeId = "t")
        val b = ProfileAttributesFilterInput(attributes = listOf("x"), typeId = "t")
        assertEquals(a.hashCode(), b.hashCode())
    }
}
