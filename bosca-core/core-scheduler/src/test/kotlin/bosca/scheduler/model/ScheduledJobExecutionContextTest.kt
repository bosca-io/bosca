package bosca.scheduler.model

import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduledJobExecutionContextTest {

    @Test
    fun `serialization preserves scheduler and principal ids`() {
        val context = ScheduledJobExecutionContext(UUID.random(), UUID.random())

        val encoded = Json.encodeToJsonElement(ScheduledJobExecutionContext.serializer(), context)

        assertEquals(context, Json.decodeFromJsonElement(ScheduledJobExecutionContext.serializer(), encoded))
    }

    @Test
    fun `principal is optional for infrastructure schedules`() {
        val context = ScheduledJobExecutionContext(UUID.random(), null)

        val encoded = Json.encodeToString(ScheduledJobExecutionContext.serializer(), context)
        val decoded = Json.decodeFromString(ScheduledJobExecutionContext.serializer(), encoded)

        assertNull(decoded.executionPrincipalId)
    }
}
