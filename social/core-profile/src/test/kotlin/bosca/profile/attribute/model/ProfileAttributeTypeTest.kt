package bosca.profile.attribute.model

import bosca.profile.model.ProfileVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileAttributeTypeTest {

    @Test
    fun `ProfileAttributeType creation preserves all fields`() {
        val type = ProfileAttributeType(
            id = "contact-info",
            description = "Contact information attributes",
            name = "Contact Info",
            visibility = ProfileVisibility.USER,
            protected = false
        )
        assertEquals("contact-info", type.id)
        assertEquals("Contact information attributes", type.description)
        assertEquals("Contact Info", type.name)
        assertEquals(ProfileVisibility.USER, type.visibility)
        assertFalse(type.protected)
    }

    @Test
    fun `ProfileAttributeType with protected flag set to true`() {
        val type = ProfileAttributeType(
            id = "system-attr",
            description = "System-level attribute",
            name = "System",
            visibility = ProfileVisibility.SYSTEM,
            protected = true
        )
        assertTrue(type.protected)
    }

    @Test
    fun `ProfileAttributeType publicList is always false`() {
        val type = ProfileAttributeType(
            id = "test",
            description = "desc",
            name = "Test",
            visibility = ProfileVisibility.PUBLIC,
            protected = false
        )
        assertFalse(type.publicList)
    }

    @Test
    fun `ProfileAttributeType publicContent is always false`() {
        val type = ProfileAttributeType(
            id = "test",
            description = "desc",
            name = "Test",
            visibility = ProfileVisibility.PUBLIC,
            protected = false
        )
        assertFalse(type.publicContent)
    }

    @Test
    fun `ProfileAttributeType publicSupplementary is always false`() {
        val type = ProfileAttributeType(
            id = "test",
            description = "desc",
            name = "Test",
            visibility = ProfileVisibility.PUBLIC,
            protected = false
        )
        assertFalse(type.publicSupplementary)
    }

    @Test
    fun `ProfileAttributeType isPublished is always true`() {
        val type = ProfileAttributeType(
            id = "test",
            description = "desc",
            name = "Test",
            visibility = ProfileVisibility.USER,
            protected = false
        )
        assertTrue(type.isPublished)
    }

    @Test
    fun `ProfileAttributeType isAdvertised is always false`() {
        val type = ProfileAttributeType(
            id = "test",
            description = "desc",
            name = "Test",
            visibility = ProfileVisibility.USER,
            protected = false
        )
        assertFalse(type.isAdvertised)
    }

    @Test
    fun `ProfileAttributeType public is always true`() {
        val type = ProfileAttributeType(
            id = "test",
            description = "desc",
            name = "Test",
            visibility = ProfileVisibility.USER,
            protected = false
        )
        assertTrue(type.public)
    }

    @Test
    fun `ProfileAttributeType isDeleted is always false`() {
        val type = ProfileAttributeType(
            id = "test",
            description = "desc",
            name = "Test",
            visibility = ProfileVisibility.USER,
            protected = false
        )
        assertFalse(type.isDeleted)
    }

    @Test
    fun `ProfileAttributeType data class equality`() {
        val a = ProfileAttributeType(
            id = "test",
            description = "desc",
            name = "Test",
            visibility = ProfileVisibility.PUBLIC,
            protected = false
        )
        val b = ProfileAttributeType(
            id = "test",
            description = "desc",
            name = "Test",
            visibility = ProfileVisibility.PUBLIC,
            protected = false
        )
        assertEquals(a, b)
    }

    @Test
    fun `ProfileAttributeType with different visibility values`() {
        ProfileVisibility.entries.forEach { visibility ->
            val type = ProfileAttributeType(
                id = "test-$visibility",
                description = "desc",
                name = "Test",
                visibility = visibility,
                protected = false
            )
            assertEquals(visibility, type.visibility)
        }
    }

    @Test
    fun `ProfileAttributeType copy changes specific fields`() {
        val original = ProfileAttributeType(
            id = "original",
            description = "Original desc",
            name = "Original",
            visibility = ProfileVisibility.USER,
            protected = false
        )
        val copy = original.copy(name = "Updated", protected = true)
        assertEquals("Updated", copy.name)
        assertTrue(copy.protected)
        assertEquals(original.id, copy.id)
        assertEquals(original.description, copy.description)
        assertEquals(original.visibility, copy.visibility)
    }
}
