package bosca.ai.chat.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatSessionProcessingTest {

    @Test
    fun `ChatSessionProcessing stores processing true`() {
        val p = ChatSessionProcessing(processing = true)
        assertTrue(p.processing)
    }

    @Test
    fun `ChatSessionProcessing stores processing false`() {
        val p = ChatSessionProcessing(processing = false)
        assertFalse(p.processing)
    }

    @Test
    fun `ChatSessionProcessing equality`() {
        val a = ChatSessionProcessing(true)
        val b = ChatSessionProcessing(true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ChatSessionProcessing copy toggles value`() {
        val original = ChatSessionProcessing(true)
        val toggled = original.copy(processing = false)
        assertFalse(toggled.processing)
    }
}
