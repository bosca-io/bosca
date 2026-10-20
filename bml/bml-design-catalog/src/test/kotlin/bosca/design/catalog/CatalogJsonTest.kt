package bosca.design.catalog

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CatalogJsonTest {
    @Test
    fun `validation retains drafts and only successful saves issue a notice`() = runBlocking {
        val editor = CatalogJson("first")
        val other = CatalogJson("other")
        editor.save("{\"saved\":true}")
        editor.save("  { broken ")
        assertEquals("  { broken ", editor.draft.value)
        assertTrue(editor.draft.error.isNotEmpty())
        assertEquals("{\"saved\":true}", editor.draft.saved)
        assertEquals(1, editor.draft.saves)
        assertEquals(1, editor.noticeSequence)
        editor.toggleReadOnly()
        assertFailsWith<IllegalStateException> { editor.save("null") }
        editor.toggleReadOnly()
        editor.toggleDisabled()
        assertFailsWith<IllegalStateException> { editor.save("null") }
        editor.toggleDisabled()
        editor.toggleVisible()
        assertFailsWith<IllegalStateException> { editor.save("null") }
        assertEquals(0, other.draft.saves)
        assertEquals(0, other.noticeSequence)
    }

    @Test
    fun `session serialization preserves the validated draft and controlled options`() = runBlocking {
        val editor = CatalogJson("settings")
        editor.save("[1,2,3]")
        editor.nextMode()
        editor.replace()
        editor.refresh()
        editor.toggleReadOnly()
        val restored = Json.decodeFromString(CatalogJson.serializer(), Json.encodeToString(CatalogJson.serializer(), editor))
        assertEquals("[1,2,3]", restored.draft.saved)
        assertEquals("{\"component\":\"json-editor\",\"revision\":1}", restored.draft.value)
        assertEquals("text", restored.mode)
        assertTrue(restored.readOnly)
        assertEquals("Example refreshed.", restored.notice)
        assertEquals("info", restored.noticeTone)
        assertEquals(2, restored.noticeSequence)
        restored.replace()
        assertEquals("{\"component\":\"json-editor\",\"revision\":2}", restored.draft.value)
    }
}
