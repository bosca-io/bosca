package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import java.time.OffsetDateTime

class MetadataWorkflowPlanTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val planUuid = Uuid.parse("660e8400-e29b-41d4-a716-446655440001")

    @Test
    fun fieldsArePreserved() {
        val now = OffsetDateTime.now()
        val plan = MetadataWorkflowPlan(
            id = testId,
            planId = planUuid,
            queue = "default",
            created = now
        )
        assertEquals(testId, plan.id)
        assertEquals(planUuid, plan.planId)
        assertEquals("default", plan.queue)
        assertEquals(now, plan.created)
    }

    @Test
    fun dataClassEquality() {
        val now = OffsetDateTime.now()
        val a = MetadataWorkflowPlan(id = testId, planId = planUuid, queue = "q", created = now)
        val b = MetadataWorkflowPlan(id = testId, planId = planUuid, queue = "q", created = now)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun copyModifiesQueue() {
        val now = OffsetDateTime.now()
        val original = MetadataWorkflowPlan(id = testId, planId = planUuid, queue = "default", created = now)
        val copy = original.copy(queue = "priority")
        assertEquals("priority", copy.queue)
        assertEquals(testId, copy.id)
    }
}
