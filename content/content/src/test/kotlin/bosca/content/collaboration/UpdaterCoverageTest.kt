package bosca.content.collaboration

import yks.utils.Doc
import yks.utils.applyUpdate
import yks.utils.encodeStateAsUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdaterCoverageTest {

    private val updater = Updater()

    private fun decode(content: ByteArray): Doc {
        val doc = Doc()
        applyUpdate(doc, content)
        return doc
    }

    // ── isDirty: empty input short-circuits to false ───────────────────────────

    @Test
    fun `areCollectionsDirty returns false for empty content`() {
        assertFalse(updater.areCollectionsDirty(ByteArray(0)))
    }

    @Test
    fun `areRelationshipsDirty returns false for empty content`() {
        assertFalse(updater.areRelationshipsDirty(ByteArray(0)))
    }

    @Test
    fun `areAttributesDirty returns false for empty content`() {
        assertFalse(updater.areAttributesDirty(ByteArray(0)))
    }

    // ── isDirty: non-empty content with no dirty key ───────────────────────────

    @Test
    fun `areCollectionsDirty returns false when key is absent`() {
        val seed = Doc()
        seed.getMap("collections").set("other", "value")
        val content = encodeStateAsUpdate(seed)
        assertFalse(updater.areCollectionsDirty(content))
    }

    @Test
    fun `areRelationshipsDirty returns false when key is absent`() {
        val seed = Doc()
        seed.getMap("metadatas").set("other", "value")
        val content = encodeStateAsUpdate(seed)
        assertFalse(updater.areRelationshipsDirty(content))
    }

    @Test
    fun `areAttributesDirty returns false when key is absent`() {
        val seed = Doc()
        seed.getMap("attrs").set("other", "value")
        val content = encodeStateAsUpdate(seed)
        assertFalse(updater.areAttributesDirty(content))
    }

    // ── isDirty: dirty flag set to a non-"true" value ──────────────────────────

    @Test
    fun `areAttributesDirty returns false when flag is not the string true`() {
        val seed = Doc()
        seed.getMap("attrs").set("|__dirty__|", "false")
        val content = encodeStateAsUpdate(seed)
        assertFalse(updater.areAttributesDirty(content))
    }

    // ── setDirty then isDirty round-trips on each map ──────────────────────────

    @Test
    fun `setCollectionsDirty then areCollectionsDirty is true`() {
        val content = updater.setCollectionsDirty(ByteArray(0))
        assertTrue(updater.areCollectionsDirty(content))
        // Written into the correct map only.
        assertEquals("true", decode(content).getMap("collections").get("|__dirty__|"))
        assertFalse(decode(content).getMap("metadatas").has("|__dirty__|"))
        assertFalse(decode(content).getMap("attrs").has("|__dirty__|"))
    }

    @Test
    fun `setRelationshipsDirty then areRelationshipsDirty is true`() {
        val content = updater.setRelationshipsDirty(ByteArray(0))
        assertTrue(updater.areRelationshipsDirty(content))
        assertEquals("true", decode(content).getMap("metadatas").get("|__dirty__|"))
        assertFalse(updater.areCollectionsDirty(content))
        assertFalse(updater.areAttributesDirty(content))
    }

    @Test
    fun `setAttributesDirty then areAttributesDirty is true`() {
        val content = updater.setAttributesDirty(ByteArray(0))
        assertTrue(updater.areAttributesDirty(content))
        assertEquals("true", decode(content).getMap("attrs").get("|__dirty__|"))
        assertFalse(updater.areCollectionsDirty(content))
        assertFalse(updater.areRelationshipsDirty(content))
    }

    // ── setDirty: non-empty input path merges into existing doc ────────────────

    @Test
    fun `setAttributesDirty merges into existing content without disturbing other maps`() {
        val seed = Doc()
        seed.getMap("metadata").set("featured", "{\"id\":\"x\"}")
        val existing = encodeStateAsUpdate(seed)

        val content = updater.setAttributesDirty(existing)

        val doc = decode(content)
        assertEquals("true", doc.getMap("attrs").get("|__dirty__|"))
        assertEquals("{\"id\":\"x\"}", doc.getMap("metadata").get("featured"))
        assertTrue(updater.areAttributesDirty(content))
    }

    @Test
    fun `setCollectionsDirty over non-empty content preserves prior dirty flags`() {
        val first = updater.setRelationshipsDirty(ByteArray(0))
        val second = updater.setCollectionsDirty(first)

        assertTrue(updater.areCollectionsDirty(second))
        assertTrue(updater.areRelationshipsDirty(second))
        assertFalse(updater.areAttributesDirty(second))
    }

    // ── close() is a no-op that must not throw ─────────────────────────────────

    @Test
    fun `close does not throw`() {
        Updater().use {
            it.setCollectionsDirty(ByteArray(0))
        }
    }
}
