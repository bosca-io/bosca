package bosca.trait.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TraitRelationsTest {

    // ========================
    // TraitWorkflow
    // ========================

    @Test
    fun `TraitWorkflow stores traitId and workflowId`() {
        val tw = TraitWorkflow(traitId = "t1", workflowId = "w1")
        assertEquals("t1", tw.traitId)
        assertEquals("w1", tw.workflowId)
    }

    @Test
    fun `TraitWorkflow equality`() {
        val a = TraitWorkflow(traitId = "t1", workflowId = "w1")
        val b = TraitWorkflow(traitId = "t1", workflowId = "w1")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `TraitWorkflow inequality on different workflowId`() {
        val a = TraitWorkflow(traitId = "t1", workflowId = "w1")
        val b = TraitWorkflow(traitId = "t1", workflowId = "w2")
        assertNotEquals(a, b)
    }

    @Test
    fun `TraitWorkflow copy`() {
        val tw = TraitWorkflow(traitId = "t1", workflowId = "w1")
        val copied = tw.copy(workflowId = "w2")
        assertEquals("t1", copied.traitId)
        assertEquals("w2", copied.workflowId)
    }

    // ========================
    // TraitContentType
    // ========================

    @Test
    fun `TraitContentType stores traitId and contentType`() {
        val tct = TraitContentType(traitId = "t1", contentType = "application/json")
        assertEquals("t1", tct.traitId)
        assertEquals("application/json", tct.contentType)
    }

    @Test
    fun `TraitContentType equality`() {
        val a = TraitContentType(traitId = "t1", contentType = "text/plain")
        val b = TraitContentType(traitId = "t1", contentType = "text/plain")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `TraitContentType inequality on different contentType`() {
        val a = TraitContentType(traitId = "t1", contentType = "text/plain")
        val b = TraitContentType(traitId = "t1", contentType = "text/html")
        assertNotEquals(a, b)
    }

    @Test
    fun `TraitContentType copy`() {
        val tct = TraitContentType(traitId = "t1", contentType = "image/png")
        val copied = tct.copy(contentType = "image/jpeg")
        assertEquals("t1", copied.traitId)
        assertEquals("image/jpeg", copied.contentType)
    }
}
