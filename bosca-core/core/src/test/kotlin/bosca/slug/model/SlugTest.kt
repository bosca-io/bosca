package bosca.slug.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Verifies field preservation, defaults, equality, and copy behavior
 * of the [Slug] data class.
 */
class SlugTest {

    @Test
    fun `Slug stores all explicit properties`() {
        val metadataId = Uuid.random()
        val collectionId = Uuid.random()
        val profileId = Uuid.random()
        val slug = Slug(
            slug = "my-slug",
            metadataId = metadataId,
            collectionId = collectionId,
            languageTag = "en-US",
            profileId = profileId
        )
        assertEquals("my-slug", slug.slug)
        assertEquals(metadataId, slug.metadataId)
        assertEquals(collectionId, slug.collectionId)
        assertEquals("en-US", slug.languageTag)
        assertEquals(profileId, slug.profileId)
    }

    @Test
    fun `Slug nullable fields default to null`() {
        val slug = Slug(slug = "test")
        assertNull(slug.metadataId)
        assertNull(slug.collectionId)
        assertNull(slug.languageTag)
        assertNull(slug.profileId)
    }

    @Test
    fun `Slug equality is based on all fields`() {
        val metadataId = Uuid.random()
        val s1 = Slug(slug = "test", metadataId = metadataId, languageTag = "en")
        val s2 = Slug(slug = "test", metadataId = metadataId, languageTag = "en")
        assertEquals(s1, s2)
        assertEquals(s1.hashCode(), s2.hashCode())
    }

    @Test
    fun `Slug inequality when slug string differs`() {
        val s1 = Slug(slug = "first")
        val s2 = Slug(slug = "second")
        assertNotEquals(s1, s2)
    }

    @Test
    fun `Slug inequality when metadataId differs`() {
        val s1 = Slug(slug = "test", metadataId = Uuid.random())
        val s2 = Slug(slug = "test", metadataId = Uuid.random())
        assertNotEquals(s1, s2)
    }

    @Test
    fun `Slug copy changes only specified fields`() {
        val slug = Slug(slug = "original", languageTag = "en")
        val copied = slug.copy(slug = "updated")
        assertEquals("updated", copied.slug)
        assertEquals("en", copied.languageTag)
        assertNull(copied.metadataId)
    }

    @Test
    fun `Slug copy can set nullable field to non-null`() {
        val slug = Slug(slug = "test")
        val profileId = Uuid.random()
        val copied = slug.copy(profileId = profileId)
        assertEquals(profileId, copied.profileId)
    }

    @Test
    fun `Slug copy can set field back to null`() {
        val slug = Slug(slug = "test", languageTag = "en")
        val copied = slug.copy(languageTag = null)
        assertNull(copied.languageTag)
    }

    @Test
    fun `Slug equality and copy distinguish remaining fields`() {
        val base = Slug("slug", Uuid.random(), Uuid.random(), "en", Uuid.random())
        assertEquals(base, base)
        assertEquals(base, base.copy())
        listOf(
            base.copy(slug = "other"),
            base.copy(metadataId = Uuid.random()),
            base.copy(collectionId = Uuid.random()),
            base.copy(languageTag = "fr"),
            base.copy(profileId = Uuid.random()),
        ).forEach { assertNotEquals(base, it) }
        assertEquals(false, base.equals(null))
        assertEquals(false, base.equals("slug"))

        listOf(
            Slug("slug", metadataId = base.metadataId),
            Slug("slug", collectionId = base.collectionId),
            Slug("slug", languageTag = "en"),
            Slug("slug", profileId = base.profileId),
        ).forEach { it.hashCode() }
    }
}
