package bosca.design.catalog

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CatalogCommentTest {
    @Test
    fun `validation and rejection retain drafts while successful advanced posts start a new draft`() = runBlocking {
        val editor = CatalogComment("first", advanced = true)
        val other = CatalogComment("other", advanced = true)
        editor.post("First complete comment")
        assertEquals("", editor.draft.value)
        assertEquals(1, editor.revision)
        editor.post("  x  ")
        assertEquals("  x  ", editor.draft.value)
        assertTrue(editor.draft.error.isNotEmpty())
        assertEquals(1, editor.noticeSequence)
        editor.toggleRejection()
        editor.post("  Keep this rejected draft.  ")
        assertEquals("  Keep this rejected draft.  ", editor.draft.value)
        assertEquals("", editor.draft.error)
        assertEquals("First complete comment", editor.draft.saved)
        assertEquals(1, editor.draft.saves)
        assertEquals(1, editor.revision)
        assertEquals("error", editor.noticeTone)
        assertEquals(2, editor.noticeSequence)
        editor.toggleRejection()
        editor.post(editor.draft.value)
        assertEquals("  Keep this rejected draft.  ", editor.draft.saved)
        assertEquals("", editor.draft.value)
        assertEquals(2, editor.revision)
        assertEquals(3, editor.noticeSequence)
        assertEquals("success", editor.noticeTone)
        assertEquals(0, other.draft.saves)
        assertEquals(0, other.noticeSequence)
        val basic = CatalogComment("basic")
        basic.post("A retained basic example")
        assertEquals(basic.draft.saved, basic.draft.value)
        assertEquals(0, basic.revision)
    }

    @Test
    fun `availability guards posting and session serialization retains controlled state`() = runBlocking {
        val editor = CatalogComment("comment", advanced = true)
        editor.toggleLocked()
        assertFailsWith<IllegalStateException> { editor.post("A complete comment") }
        editor.toggleLocked()
        editor.toggleReadOnly()
        assertFailsWith<IllegalStateException> { editor.post("A complete comment") }
        editor.toggleReadOnly()
        editor.toggleDisabled()
        assertFailsWith<IllegalStateException> { editor.post("A complete comment") }
        editor.toggleDisabled()
        editor.toggleVisible()
        assertFailsWith<IllegalStateException> { editor.post("A complete comment") }
        editor.replace()
        editor.refresh()
        val restored = Json.decodeFromString(CatalogComment.serializer(), Json.encodeToString(CatalogComment.serializer(), editor))
        assertTrue(restored.advanced)
        assertEquals(false, restored.visible)
        assertEquals("Replacement draft 1 from this example.", restored.draft.value)
        assertEquals("info", restored.noticeTone)
        assertEquals(1, restored.noticeSequence)
        restored.clear()
        restored.clear()
        assertEquals("", restored.draft.value)
        assertEquals(2, restored.revision)
        restored.replace()
        assertEquals("Replacement draft 2 from this example.", restored.draft.value)
    }
}
