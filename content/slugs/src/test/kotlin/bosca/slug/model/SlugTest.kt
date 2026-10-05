package bosca.slug.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class SlugTest {

    @Test
    fun `Slug stores slug string`() {
        val slug = Slug(slug = "my-slug")
        assertEquals("my-slug", slug.slug)
    }

    @Test
    fun `Slug optional fields default to null`() {
        val slug = Slug(slug = "test")
        assertNull(slug.metadataId)
        assertNull(slug.collectionId)
        assertNull(slug.languageTag)
        assertNull(slug.profileId)
    }

    @Test
    fun `Slug with metadataId`() {
        val id = Uuid.random()
        val slug = Slug(slug = "meta-slug", metadataId = id)
        assertEquals(id, slug.metadataId)
    }

    @Test
    fun `Slug with collectionId and languageTag`() {
        val id = Uuid.random()
        val slug = Slug(slug = "collection-slug", collectionId = id, languageTag = "en")
        assertEquals(id, slug.collectionId)
        assertEquals("en", slug.languageTag)
    }

    @Test
    fun `Slug with profileId`() {
        val id = Uuid.random()
        val slug = Slug(slug = "profile-slug", profileId = id)
        assertEquals(id, slug.profileId)
    }

    @Test
    fun `Slug data class equality`() {
        val id = Uuid.random()
        val s1 = Slug(slug = "test", metadataId = id)
        val s2 = Slug(slug = "test", metadataId = id)
        assertEquals(s1, s2)
    }

    @Test
    fun `Slug copy`() {
        val slug = Slug(slug = "original", metadataId = Uuid.random())
        val modified = slug.copy(slug = "modified")
        assertEquals("modified", modified.slug)
        assertEquals(slug.metadataId, modified.metadataId)
    }

    // --- slugify() extension function tests ---

    @Test
    fun `slugify converts simple text to slug`() {
        assertEquals("hello-world", "Hello World".slugify())
    }

    @Test
    fun `slugify lowercases text`() {
        assertEquals("abc", "ABC".slugify())
    }

    @Test
    fun `slugify replaces spaces with hyphens`() {
        assertEquals("a-b-c", "a b c".slugify())
    }

    @Test
    fun `slugify replaces multiple spaces with single hyphen`() {
        assertEquals("a-b", "a   b".slugify())
    }

    @Test
    fun `slugify removes special characters`() {
        assertEquals("helloworld", "hello!@#world".slugify())
    }

    @Test
    fun `slugify replaces ampersand with and`() {
        assertEquals("salt-and-pepper", "Salt & Pepper".slugify())
    }

    @Test
    fun `slugify replaces colon with hyphen`() {
        assertEquals("a-b", "a:b".slugify())
    }

    @Test
    fun `slugify returns n-a for blank string`() {
        assertEquals("n-a", "".slugify())
        assertEquals("n-a", "   ".slugify())
    }

    @Test
    fun `slugify handles diacritics`() {
        assertEquals("cafe", "caf\u00e9".slugify())
        assertEquals("uber", "\u00fcber".slugify())
    }

    @Test
    fun `slugify transliterates special characters`() {
        assertEquals("ss", "\u00df".slugify()) // German sharp s
        assertEquals("ae", "\u00e6".slugify()) // ae ligature
        assertEquals("oe", "\u0153".slugify()) // oe ligature
    }

    @Test
    fun `slugify trims leading and trailing hyphens`() {
        assertEquals("hello", "-hello-".slugify())
    }

    @Test
    fun `slugify handles underscores`() {
        assertEquals("ab", "a_b".slugify())
    }

    @Test
    fun `slugify preserves numbers`() {
        assertEquals("test-123", "test 123".slugify())
    }
}
