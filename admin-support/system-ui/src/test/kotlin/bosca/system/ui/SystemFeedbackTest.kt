package bosca.system.ui

import bosca.system.ui.generated.SystemFeedbackComponent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SystemFeedbackTest {
    @Test fun `repeated successes issue new notices with identity retained across actions`() = runTest {
        val original = SystemFeedback()
        original.perform { original.message = "Saved." }
        assertEquals(1, original.noticeSequence)
        val restored = Json.decodeFromString(SystemFeedback.serializer(), Json.encodeToString(SystemFeedback.serializer(), original))
        assertEquals(original.noticeId, restored.noticeId)
        restored.perform { restored.message = "Saved." }
        assertEquals(2, restored.noticeSequence)
        assertNotEquals(original.noticeId, SystemFeedback().noticeId)
    }

    @Test fun `reads stay quiet while repeated failures issue error notices and cancellation propagates`() = runTest {
        val feedback = SystemFeedback()
        feedback.perform { feedback.message = "Saved." }
        feedback.perform { }
        assertEquals(1, feedback.noticeSequence)
        feedback.perform {
            feedback.message = "Unfinished success."
            error("Save failed.")
        }
        assertTrue(feedback.failed)
        assertEquals("Save failed.", feedback.message)
        assertEquals(2, feedback.noticeSequence)
        feedback.perform { error("Save failed.") }
        assertEquals(3, feedback.noticeSequence)
        assertFailsWith<CancellationException> {
            feedback.perform { throw CancellationException("Cancelled") }
        }
        assertEquals(3, feedback.noticeSequence)
    }
    @Test fun `compiled feedback renders errors as toasts and explicit inline validation remains available`() = runTest {
        val feedback = SystemFeedback(message = "Connection failed.", failed = true, noticeSequence = 1)
        val ctx = bosca.bml.render.RenderContext()
        SystemFeedbackComponent.render(ctx, mapOf("feedback" to feedback)) {}
        val html = ctx.writer.toString()
        assertTrue("data-system-error" in html)
        assertTrue("data-announce-initial" in html)
        assertTrue("data-tone=\"error\"" in html)
        assertTrue("Connection failed." in html)
        assertTrue("class=\"alert" !in html)
        val inline = bosca.bml.render.RenderContext()
        SystemFeedbackComponent.render(inline, mapOf("feedback" to feedback, "notify" to false)) {}
        assertTrue("Connection failed." in inline.writer.toString())
        assertTrue("toast-region" !in inline.writer.toString())
    }
}
