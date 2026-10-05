package bosca.content.collection.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class CollectionTemplateAttributeWorkflowTest {

    @Test
    fun `stores all properties`() {
        val metadataId = Uuid.random()
        val obj = CollectionTemplateAttributeWorkflow(
            metadataId = metadataId,
            version = 3,
            key = "attr-key",
            workflowId = "wf-001",
            autoRun = true
        )

        assertEquals(metadataId, obj.metadataId)
        assertEquals(3, obj.version)
        assertEquals("attr-key", obj.key)
        assertEquals("wf-001", obj.workflowId)
        assertEquals(true, obj.autoRun)
    }

    @Test
    fun `autoRun defaults to false`() {
        val obj = CollectionTemplateAttributeWorkflow(
            metadataId = Uuid.random(),
            version = 1,
            key = "k",
            workflowId = "wf"
        )
        assertFalse(obj.autoRun)
    }

    @Test
    fun `equality based on all fields`() {
        val id = Uuid.random()
        val a = CollectionTemplateAttributeWorkflow(metadataId = id, version = 1, key = "k", workflowId = "wf")
        val b = CollectionTemplateAttributeWorkflow(metadataId = id, version = 1, key = "k", workflowId = "wf")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `inequality when fields differ`() {
        val id = Uuid.random()
        val a = CollectionTemplateAttributeWorkflow(metadataId = id, version = 1, key = "k", workflowId = "wf-1")
        val b = CollectionTemplateAttributeWorkflow(metadataId = id, version = 1, key = "k", workflowId = "wf-2")
        assertNotEquals(a, b)
    }

    @Test
    fun `copy preserves and overrides fields`() {
        val original = CollectionTemplateAttributeWorkflow(
            metadataId = Uuid.random(), version = 1, key = "k", workflowId = "wf"
        )
        val copied = original.copy(autoRun = true, version = 2)
        assertEquals(true, copied.autoRun)
        assertEquals(2, copied.version)
        assertEquals(original.metadataId, copied.metadataId)
        assertEquals(original.key, copied.key)
    }
}
