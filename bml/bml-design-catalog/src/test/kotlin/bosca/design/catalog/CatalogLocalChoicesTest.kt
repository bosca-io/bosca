package bosca.design.catalog

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CatalogLocalChoicesTest {
    @Test
    fun `rejected choices retain drafts separately from the last accepted values`(): Unit = runBlocking {
        val choices = CatalogLocalChoices("first")
        val other = CatalogLocalChoices("other")
        assertFailsWith<IllegalArgumentException> { choices.save("", "") }
        assertFailsWith<IllegalArgumentException> { choices.save("unavailable", "") }
        assertFailsWith<IllegalArgumentException> { choices.save("unknown", "") }
        choices.save("button", "  Custom label  ")
        choices.toggleRejection()
        choices.save("card", "Rejected label")
        assertEquals("card", choices.component)
        assertEquals("Rejected label", choices.label)
        assertEquals("button", choices.savedComponent)
        assertEquals("  Custom label  ", choices.savedLabel)
        assertEquals(1, choices.saves)
        assertEquals(2, choices.noticeSequence)
        assertEquals("error", choices.noticeTone)
        choices.toggleRejection()
        choices.save(choices.component, choices.label)
        assertEquals("card", choices.savedComponent)
        assertEquals(2, choices.saves)
        assertEquals("success", choices.noticeTone)
        assertEquals("", other.savedComponent)
        assertEquals(0, other.noticeSequence)
        choices.toggleRequired()
        choices.save("", "Optional")
        assertEquals("", choices.savedComponent)
        assertEquals("Optional", choices.savedLabel)
    }

    @Test
    fun `availability guards saving and restored defaults retain controlled revisions`(): Unit = runBlocking {
        val choices = CatalogLocalChoices("choices")
        choices.toggleDisabled()
        assertFailsWith<IllegalStateException> { choices.save("button", "") }
        choices.toggleDisabled()
        choices.toggleLoading()
        assertFailsWith<IllegalStateException> { choices.save("button", "") }
        choices.toggleLoading()
        choices.toggleVisible()
        assertFailsWith<IllegalStateException> { choices.save("button", "") }
        choices.replace()
        choices.changeTagLimit()
        choices.toggleCustom()
        choices.refresh()
        val restored = Json.decodeFromString(CatalogLocalChoices.serializer(), Json.encodeToString(CatalogLocalChoices.serializer(), choices))
        assertEquals(false, restored.visible)
        assertEquals(false, restored.allowCustom)
        assertEquals(1, restored.maxTags)
        assertEquals(1, restored.revision)
        assertEquals("card", restored.component)
        assertEquals("Imported sample", restored.label)
        assertEquals(listOf("checkbox", "switch"), restored.tags)
        assertEquals("info", restored.noticeTone)
        assertEquals(1, restored.noticeSequence)
        restored.replace()
        assertEquals("button", restored.component)
        assertEquals(2, restored.revision)
        assertTrue(restored.savedComponent.isEmpty())
    }
}
