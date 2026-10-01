package bosca.scheduler.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobDefinitionInfoTest {

    @Test
    fun `JobDefinitionInfo stores required fields`() {
        val info = JobDefinitionInfo(
            id = "backup-job",
            name = "Backup Job",
            queueName = "backup"
        )
        assertEquals("backup-job", info.id)
        assertEquals("Backup Job", info.name)
        assertEquals("backup", info.queueName)
        assertNull(info.parameterSchema)
    }

    @Test
    fun `JobDefinitionInfo with parameter schema`() {
        val schema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "required" to JsonPrimitive("backupId")
            )
        )
        val info = JobDefinitionInfo(
            id = "restore-job",
            name = "Restore Job",
            queueName = "backup",
            parameterSchema = schema
        )
        assertEquals(schema, info.parameterSchema)
    }

    @Test
    fun `JobDefinitionInfo equality`() {
        val a = JobDefinitionInfo(id = "j1", name = "Job 1", queueName = "q1")
        val b = JobDefinitionInfo(id = "j1", name = "Job 1", queueName = "q1")
        assertEquals(a, b)
    }

    @Test
    fun `JobDefinitionInfo copy with modification`() {
        val original = JobDefinitionInfo(id = "j1", name = "Job 1", queueName = "q1")
        val modified = original.copy(name = "Updated Job 1")
        assertEquals("Updated Job 1", modified.name)
        assertEquals("j1", modified.id)
    }
}
