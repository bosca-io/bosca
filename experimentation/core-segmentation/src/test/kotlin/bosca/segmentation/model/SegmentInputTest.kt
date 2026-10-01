@file:OptIn(ExperimentalUuidApi::class)

package bosca.segmentation.model

import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

class SegmentInputTest {

    @Test
    fun `SegmentInput stores required fields`() {
        val input = SegmentInput(
            name = "VIP Users",
            type = SegmentType.STATIC
        )
        assertEquals("VIP Users", input.name)
        assertEquals(SegmentType.STATIC, input.type)
    }

    @Test
    fun `SegmentInput has sensible defaults`() {
        val input = SegmentInput(name = "test", type = SegmentType.DYNAMIC)
        assertEquals("", input.description)
        assertEquals(SegmentStatus.DRAFT, input.status)
        assertNull(input.analyticsQueryId)
        assertNull(input.configuration)
        assertNull(input.evaluationSchedule)
    }

    @Test
    fun `SegmentInput with all fields`() {
        val queryId = UUID.random()
        val config = JsonObject(mapOf("threshold" to JsonPrimitive(100)))
        val input = SegmentInput(
            name = "Active Users",
            description = "Users who logged in this week",
            type = SegmentType.DYNAMIC,
            status = SegmentStatus.ACTIVE,
            analyticsQueryId = queryId,
            configuration = config,
            evaluationSchedule = "0 0 * * *"
        )
        assertEquals("Users who logged in this week", input.description)
        assertEquals(SegmentStatus.ACTIVE, input.status)
        assertEquals(queryId, input.analyticsQueryId)
        assertEquals(config, input.configuration)
        assertEquals("0 0 * * *", input.evaluationSchedule)
    }

    @Test
    fun `SegmentInput equality`() {
        val a = SegmentInput(name = "s", type = SegmentType.EVERYONE)
        val b = SegmentInput(name = "s", type = SegmentType.EVERYONE)
        assertEquals(a, b)
    }
}
