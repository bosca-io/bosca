package bosca.content.collection.model

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class CollectionWorkflowPlanTest {

    private val now = OffsetDateTime.now()

    @Test
    fun `stores all properties`() {
        val id = Uuid.random()
        val planId = Uuid.random()

        val plan = CollectionWorkflowPlan(
            id = id,
            planId = planId,
            queue = "default",
            created = now
        )

        assertEquals(id, plan.id)
        assertEquals(planId, plan.planId)
        assertEquals("default", plan.queue)
        assertEquals(now, plan.created)
    }

    @Test
    fun `equality based on all fields`() {
        val id = Uuid.random()
        val planId = Uuid.random()
        val a = CollectionWorkflowPlan(id = id, planId = planId, queue = "q", created = now)
        val b = CollectionWorkflowPlan(id = id, planId = planId, queue = "q", created = now)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `inequality when fields differ`() {
        val id = Uuid.random()
        val planId = Uuid.random()
        val a = CollectionWorkflowPlan(id = id, planId = planId, queue = "q1", created = now)
        val b = CollectionWorkflowPlan(id = id, planId = planId, queue = "q2", created = now)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy preserves and overrides fields`() {
        val original = CollectionWorkflowPlan(
            id = Uuid.random(), planId = Uuid.random(), queue = "default", created = now
        )
        val copied = original.copy(queue = "priority")
        assertEquals("priority", copied.queue)
        assertEquals(original.id, copied.id)
        assertEquals(original.planId, copied.planId)
        assertEquals(original.created, copied.created)
    }
}
