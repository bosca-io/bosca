package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BibleChapterTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    private fun createChapter(
        metadataId: Uuid = testId,
        version: Int = 1,
        variant: String = "default",
        bookUsfm: String = "GEN",
        usfm: String = "GEN.1",
        components: kotlinx.serialization.json.JsonElement? = null,
        sort: Int = 1
    ) = BibleChapter(
        metadataId = metadataId,
        version = version,
        variant = variant,
        bookUsfm = bookUsfm,
        usfm = usfm,
        components = components,
        sort = sort
    )

    @Test
    fun `field preservation after construction`() {
        val json = buildJsonObject { put("key", "value") }
        val chapter = createChapter(components = json)
        assertEquals(testId, chapter.metadataId)
        assertEquals(1, chapter.version)
        assertEquals("default", chapter.variant)
        assertEquals("GEN", chapter.bookUsfm)
        assertEquals("GEN.1", chapter.usfm)
        assertEquals(json, chapter.components)
        assertEquals(1, chapter.sort)
    }

    @Test
    fun `components can be null`() {
        val chapter = createChapter(components = null)
        assertNull(chapter.components)
    }

    @Test
    fun `components can hold JsonNull`() {
        val chapter = createChapter(components = JsonNull)
        assertNotNull(chapter.components)
        assertEquals(JsonNull, chapter.components)
    }

    @Test
    fun `data class equality for identical instances`() {
        val c1 = createChapter()
        val c2 = createChapter()
        assertEquals(c1, c2)
        assertEquals(c1.hashCode(), c2.hashCode())
    }

    @Test
    fun `data class inequality when fields differ`() {
        val c1 = createChapter(usfm = "GEN.1")
        val c2 = createChapter(usfm = "GEN.2", sort = 2)
        assertNotEquals(c1, c2)
    }

    @Test
    fun `copy preserves fields and allows overrides`() {
        val chapter = createChapter()
        val copy = chapter.copy(usfm = "GEN.3", sort = 3)
        assertEquals("GEN.3", copy.usfm)
        assertEquals(3, copy.sort)
        assertEquals(chapter.metadataId, copy.metadataId)
        assertEquals(chapter.version, copy.version)
        assertEquals(chapter.variant, copy.variant)
        assertEquals(chapter.bookUsfm, copy.bookUsfm)
        assertEquals(chapter.components, copy.components)
    }

    @Test
    fun `getChapterComponents returns null when components is null`() {
        val chapter = createChapter(components = null)
        assertNull(chapter.getChapterComponents())
    }
}
