package bosca.scheduler.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduledJobInputTest {

    @Test
    fun `ScheduledJobInput stores required fields`() {
        val input = ScheduledJobInput(
            name = "Daily Backup",
            jobName = "backup-job",
            cronExpression = "0 2 * * *"
        )
        assertEquals("Daily Backup", input.name)
        assertEquals("backup-job", input.jobName)
        assertEquals("0 2 * * *", input.cronExpression)
    }

    @Test
    fun `ScheduledJobInput has sensible defaults`() {
        val input = ScheduledJobInput(
            name = "test",
            jobName = "job",
            cronExpression = "* * * * *"
        )
        assertNull(input.description)
        assertTrue(input.jobParameters is JsonObject)
        assertEquals(true, input.enabled)
        assertEquals(false, input.allowConcurrent)
        assertEquals(false, input.catchUp)
        assertEquals(1, input.maxCatchUp)
        assertNull(input.requiresPrincipal)
    }

    @Test
    fun `ScheduledJobInput with custom parameters`() {
        val params = JsonObject(mapOf("key" to JsonPrimitive("value")))
        val input = ScheduledJobInput(
            name = "Custom Job",
            description = "A custom scheduled job",
            jobName = "custom-job",
            jobParameters = params,
            cronExpression = "0 */6 * * *",
            enabled = false,
            allowConcurrent = true,
            catchUp = true,
            maxCatchUp = 5,
            requiresPrincipal = true,
        )
        assertEquals("A custom scheduled job", input.description)
        assertEquals(params, input.jobParameters)
        assertEquals(false, input.enabled)
        assertEquals(true, input.allowConcurrent)
        assertEquals(true, input.catchUp)
        assertEquals(5, input.maxCatchUp)
        assertEquals(true, input.requiresPrincipal)
    }

    @Test
    fun `ScheduledJobInput equality`() {
        val a = ScheduledJobInput(name = "a", jobName = "j", cronExpression = "0 0 * * *")
        val b = ScheduledJobInput(name = "a", jobName = "j", cronExpression = "0 0 * * *")
        assertEquals(a, b)
    }
}
