package bosca.profile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Verifies field preservation, defaults, transient computed properties,
 * and copy/equality behavior of the [Profile] data class.
 */
class ProfileTest {

    @Test
    fun `Profile stores all explicit properties`() {
        val id = Uuid.random()
        val principalId = Uuid.random()
        val collectionId = Uuid.random()
        val profile = Profile(
            id = id,
            type = ProfileType.ORGANIZATION,
            principal = principalId,
            collectionId = collectionId,
            name = "Test Org",
            visibility = ProfileVisibility.PUBLIC
        )
        assertEquals(id, profile.id)
        assertEquals(ProfileType.ORGANIZATION, profile.type)
        assertEquals(principalId, profile.principal)
        assertEquals(collectionId, profile.collectionId)
        assertEquals("Test Org", profile.name)
        assertEquals(ProfileVisibility.PUBLIC, profile.visibility)
    }

    @Test
    fun `Profile id defaults to NIL`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER)
        assertEquals(Uuid.NIL, profile.id)
    }

    @Test
    fun `Profile nullable fields default to null`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER)
        assertNull(profile.principal)
        assertNull(profile.collectionId)
    }

    // --- Profile transient properties ---

    @Test
    fun `Profile version is null`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER)
        assertNull(profile.version)
    }

    @Test
    fun `Profile languageTag is null`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER)
        assertNull(profile.languageTag)
    }

    @Test
    fun `Profile attributes and itemAttributes are null`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER)
        assertNull(profile.attributes)
        assertNull(profile.itemAttributes)
    }

    @Test
    fun `Profile workflowStateId is published`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER)
        assertEquals("published", profile.workflowStateId)
    }

    @Test
    fun `Profile workflowStatePendingId is null`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER)
        assertNull(profile.workflowStatePendingId)
    }

    @Test
    fun `Profile ready is null`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER)
        assertNull(profile.ready)
    }

    @Test
    fun `Profile public is true when visibility is PUBLIC`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.PUBLIC)
        assertTrue(profile.`public`)
    }

    @Test
    fun `Profile public is false when visibility is not PUBLIC`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER)
        assertFalse(profile.`public`)
    }

    @Test
    fun `Profile publicContent is false`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.PUBLIC)
        assertFalse(profile.publicContent)
    }

    @Test
    fun `Profile publicList is false`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.PUBLIC)
        assertFalse(profile.publicList)
    }

    @Test
    fun `Profile publicSupplementary is false`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.PUBLIC)
        assertFalse(profile.publicSupplementary)
    }

    @Test
    fun `Profile isPublished is true`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER)
        assertTrue(profile.isPublished)
    }

    @Test
    fun `Profile isAdvertised is false`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER)
        assertFalse(profile.isAdvertised)
    }

    @Test
    fun `Profile isDeleted is false`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER)
        assertFalse(profile.isDeleted)
    }

    @Test
    fun `Profile isSearchable is true`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER)
        assertTrue(profile.isSearchable)
    }

    @Test
    fun `Profile isSearchable is false when search is disabled`() {
        val profile = Profile(
            type = ProfileType.GENERIC,
            name = "test",
            visibility = ProfileVisibility.PUBLIC,
            searchable = false,
        )
        assertFalse(profile.isSearchable)
    }

    @Test
    fun `Profile isPrimary is false`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER)
        assertFalse(profile.isPrimary)
    }

    // --- Profile equality and copy ---

    @Test
    fun `Profile equality is based on constructor fields`() {
        val id = Uuid.random()
        val now = java.time.OffsetDateTime.now()
        val p1 = Profile(id = id, type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER, created = now, modified = now)
        val p2 = Profile(id = id, type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER, created = now, modified = now)
        assertEquals(p1, p2)
        assertEquals(p1.hashCode(), p2.hashCode())
    }

    @Test
    fun `Profile inequality when name differs`() {
        val id = Uuid.random()
        val p1 = Profile(id = id, type = ProfileType.GENERIC, name = "a", visibility = ProfileVisibility.USER)
        val p2 = Profile(id = id, type = ProfileType.GENERIC, name = "b", visibility = ProfileVisibility.USER)
        assertNotEquals(p1, p2)
    }

    @Test
    fun `Profile copy changes only specified fields`() {
        val profile = Profile(type = ProfileType.GENERIC, name = "old", visibility = ProfileVisibility.USER)
        val copied = profile.copy(name = "new", visibility = ProfileVisibility.PUBLIC)
        assertEquals("new", copied.name)
        assertEquals(ProfileVisibility.PUBLIC, copied.visibility)
        assertEquals(profile.type, copied.type)
        assertEquals(profile.id, copied.id)
    }

    @Test
    fun `Profile equality and copy distinguish every constructor field`() {
        val created = java.time.OffsetDateTime.parse("2024-01-01T00:00:00Z")
        val base = Profile(
            id = Uuid.random(),
            type = ProfileType.ORGANIZATION,
            principal = Uuid.random(),
            collectionId = Uuid.random(),
            name = "Organization",
            visibility = ProfileVisibility.PUBLIC,
            created = created,
            modified = created.plusDays(1),
            deletedAt = created.plusDays(2),
        )
        assertEquals(base, base)
        assertEquals(base, base.copy())
        assertFalse(base.equals(null))
        assertFalse(base.equals("profile"))
        listOf(
            base.copy(id = Uuid.random()),
            base.copy(type = ProfileType.CHILD),
            base.copy(principal = Uuid.random()),
            base.copy(collectionId = Uuid.random()),
            base.copy(name = "Different"),
            base.copy(visibility = ProfileVisibility.USER),
            base.copy(created = created.minusDays(1)),
            base.copy(modified = created.plusDays(3)),
            base.copy(deletedAt = null),
        ).forEach { assertNotEquals(base, it) }
        assertTrue(base.isDeleted)
        assertFalse(base.isSearchable)

        listOf(
            Profile(id = base.id, type = base.type, name = base.name, visibility = base.visibility),
            Profile(type = base.type, principal = base.principal, name = base.name, visibility = base.visibility),
            Profile(type = base.type, collectionId = base.collectionId, name = base.name, visibility = base.visibility),
            Profile(type = base.type, name = base.name, visibility = base.visibility, created = base.created),
            Profile(type = base.type, name = base.name, visibility = base.visibility, modified = base.modified),
            Profile(type = base.type, name = base.name, visibility = base.visibility, deletedAt = base.deletedAt),
        ).forEach { it.hashCode() }
    }
}
