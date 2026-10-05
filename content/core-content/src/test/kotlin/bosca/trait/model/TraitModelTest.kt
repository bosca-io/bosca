package bosca.trait.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TraitModelTest {

    // ========================
    // TraitConfiguration
    // ========================

    @Test
    fun `TraitConfiguration stores traitId`() {
        val config = TraitConfiguration(traitId = "trait-abc")
        assertEquals("trait-abc", config.traitId)
    }

    @Test
    fun `TraitConfiguration companion ACTIVITY_ID constant`() {
        assertEquals("metadata.trait.process", TraitConfiguration.ACTIVITY_ID)
    }

    @Test
    fun `TraitConfiguration equality`() {
        val config1 = TraitConfiguration(traitId = "trait-1")
        val config2 = TraitConfiguration(traitId = "trait-1")
        assertEquals(config1, config2)
        assertEquals(config1.hashCode(), config2.hashCode())
    }

    @Test
    fun `TraitConfiguration inequality`() {
        val config1 = TraitConfiguration(traitId = "trait-1")
        val config2 = TraitConfiguration(traitId = "trait-2")
        assertNotEquals(config1, config2)
    }

    @Test
    fun `TraitConfiguration copy`() {
        val config = TraitConfiguration(traitId = "original")
        val copied = config.copy(traitId = "modified")
        assertEquals("modified", copied.traitId)
    }

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
        val tw1 = TraitWorkflow(traitId = "t1", workflowId = "w1")
        val tw2 = TraitWorkflow(traitId = "t1", workflowId = "w1")
        assertEquals(tw1, tw2)
        assertEquals(tw1.hashCode(), tw2.hashCode())
    }

    @Test
    fun `TraitWorkflow inequality on different workflowId`() {
        val tw1 = TraitWorkflow(traitId = "t1", workflowId = "w1")
        val tw2 = TraitWorkflow(traitId = "t1", workflowId = "w2")
        assertNotEquals(tw1, tw2)
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
        val tct1 = TraitContentType(traitId = "t1", contentType = "text/plain")
        val tct2 = TraitContentType(traitId = "t1", contentType = "text/plain")
        assertEquals(tct1, tct2)
        assertEquals(tct1.hashCode(), tct2.hashCode())
    }

    @Test
    fun `TraitContentType inequality on different contentType`() {
        val tct1 = TraitContentType(traitId = "t1", contentType = "text/plain")
        val tct2 = TraitContentType(traitId = "t1", contentType = "text/html")
        assertNotEquals(tct1, tct2)
    }

    @Test
    fun `TraitContentType copy`() {
        val tct = TraitContentType(traitId = "t1", contentType = "image/png")
        val copied = tct.copy(contentType = "image/jpeg")
        assertEquals("t1", copied.traitId)
        assertEquals("image/jpeg", copied.contentType)
    }
}
