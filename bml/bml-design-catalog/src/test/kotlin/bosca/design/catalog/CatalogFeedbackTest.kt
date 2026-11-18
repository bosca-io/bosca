package bosca.design.catalog

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogFeedbackTest {
    @Test
    fun `clipboard outcomes and repeated notices issue isolated sequences`() {
        val feedback = CatalogFeedback("first")
        val other = CatalogFeedback("other")
        feedback.copied(false)
        assertEquals("error", feedback.noticeTone)
        assertTrue(feedback.notice.contains("Select and copy"))
        feedback.copied(true)
        assertEquals("success", feedback.noticeTone)
        assertEquals("Value copied.", feedback.notice)
        feedback.notify("success")
        feedback.notify("success")
        assertEquals(4, feedback.noticeSequence)
        assertEquals("Example saved.", feedback.notice)
        assertFailsWith<IllegalArgumentException> { feedback.notify("invalid") }
        assertEquals(4, feedback.noticeSequence)
        feedback.toggleLongMessage()
        feedback.notify("warning")
        assertEquals(18, feedback.notice.lines().size)
        feedback.replace()
        feedback.toggleVisible()
        val restored = Json.decodeFromString(CatalogFeedback.serializer(), Json.encodeToString(CatalogFeedback.serializer(), feedback))
        assertEquals(feedback.value(), restored.value())
        assertTrue(restored.description().contains("<button>"))
        assertFalse(restored.visible)
        assertEquals(1, restored.revision)
        assertEquals("warning", restored.noticeTone)
        assertEquals(feedback.notice, restored.notice)
        assertEquals(0, other.noticeSequence)
        assertEquals("io.bosca:bml-design", other.value())
    }
}
