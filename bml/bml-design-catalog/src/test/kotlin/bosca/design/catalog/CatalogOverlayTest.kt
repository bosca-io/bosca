package bosca.design.catalog

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CatalogOverlayTest {
    @Test
    fun `invalid and rejected names retain exact values without overwriting a successful save`(): Unit = runBlocking {
        val state = CatalogOverlay("first")
        val other = CatalogOverlay("other")
        state.save("Accepted name")
        state.save(" x ")
        assertEquals(" x ", state.name)
        assertTrue(state.error.isNotBlank())
        assertTrue(state.reopen)
        assertEquals(1, state.noticeSequence)
        state.toggleRejection()
        state.save("  A rejected name  ")
        assertEquals("  A rejected name  ", state.name)
        assertEquals("", state.error)
        assertEquals("Accepted name", state.saved)
        assertEquals("error", state.noticeTone)
        assertEquals(2, state.noticeSequence)
        state.toggleRejection()
        state.save(state.name)
        assertEquals("  A rejected name  ", state.saved)
        assertTrue(state.completed)
        assertEquals(false, state.reopen)
        assertEquals(0, other.noticeSequence)
        state.toggleDisabled()
        assertFailsWith<IllegalStateException> { state.save("Unavailable") }
    }

    @Test
    fun `serialized requests completion and independent popover values survive session restoration`() = runBlocking {
        val state = CatalogOverlay("draft")
        state.requestDraft()
        state.finishDraft("  An exact draft  ")
        state.refreshDraft()
        assertEquals(1, state.noticeSequence)
        val restored = Json.decodeFromString(CatalogOverlay.serializer(), Json.encodeToString(CatalogOverlay.serializer(), state))
        assertEquals("  An exact draft  ", restored.submittedDraft)
        assertTrue(restored.draftCompleted)
        assertEquals(1, restored.request)
        assertEquals(1, restored.draftRefreshes)
        restored.requestDraft()
        assertEquals(2, restored.request)
        assertEquals(false, restored.draftCompleted)
        restored.toggleMounted()
        assertFailsWith<IllegalStateException> { restored.requestDraft() }
        assertFailsWith<IllegalStateException> { restored.finishDraft("Unavailable") }
        assertFailsWith<IllegalStateException> { restored.save("Unavailable") }
        restored.toggleMounted()
        assertEquals(0, restored.request)
        val options = CatalogPopover("first")
        val other = CatalogPopover("other")
        options.apply("  Layout & controls  ")
        val copy = Json.decodeFromString(CatalogPopover.serializer(), Json.encodeToString(CatalogPopover.serializer(), options))
        assertEquals("  Layout & controls  ", copy.filter)
        copy.apply("")
        assertEquals("", copy.filter)
        assertEquals(2, copy.sequence)
        assertEquals("Components", other.filter)
        assertEquals(0, other.sequence)
    }
}
