package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.bql.BqlBoundParameter
import bosca.workops.model.bql.BqlBoundType
import bosca.workops.model.bql.BqlField
import bosca.workops.model.bql.BqlFieldType
import bosca.workops.model.bql.BqlNameResolver
import bosca.workops.model.project.Project
import bosca.workops.model.task.Priority
import bosca.workops.model.task.Resolution
import bosca.workops.model.task.TaskType
import bosca.workops.model.workflow.Status
import bosca.workops.repository.PriorityRepository
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.ResolutionRepository
import bosca.workops.repository.StatusRepository
import bosca.workops.repository.TaskTypeRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskQueryServiceTest {

    private val priorities = mockk<PriorityRepository>()
    private val statuses = mockk<StatusRepository>()
    private val taskTypes = mockk<TaskTypeRepository>()
    private val resolutions = mockk<ResolutionRepository>()
    private val projects = mockk<ProjectRepository>()
    private val service = TaskQueryServiceImpl(priorities, statuses, taskTypes, resolutions, projects)

    @Test
    fun `validation distinguishes blank malformed and valid queries`() = runTest {
        assertTrue(service.validate("   ").isEmpty())
        assertTrue(service.validate("=== garbage").isNotEmpty())
        assertTrue(service.validate("summary = task").isEmpty())
    }

    @Test
    fun `parameter binding preserves every supported JDBC representation`() {
        val statement = mockk<PreparedStatement>(relaxed = true)
        val connection = mockk<Connection>()
        val jdbcArray = mockk<java.sql.Array>()
        every { statement.connection } returns connection
        every { connection.createArrayOf(any(), any()) } returns jdbcArray
        val method = TaskQueryServiceImpl::class.java.getDeclaredMethod(
            "bindParameter",
            PreparedStatement::class.java,
            BqlBoundParameter::class.java,
        ).apply { isAccessible = true }
        val uuid = UUID.random()
        val timestamp = OffsetDateTime.now()

        listOf(
            BqlBoundParameter(1, "text", BqlBoundType.TEXT),
            BqlBoundParameter(2, uuid, BqlBoundType.UUID),
            BqlBoundParameter(3, 3L, BqlBoundType.NUMBER),
            BqlBoundParameter(4, 4, BqlBoundType.NUMBER),
            BqlBoundParameter(5, 5.5, BqlBoundType.NUMBER),
            BqlBoundParameter(6, 6.5f, BqlBoundType.NUMBER),
            BqlBoundParameter(7, BigDecimal.TEN, BqlBoundType.NUMBER),
            BqlBoundParameter(8, true, BqlBoundType.BOOLEAN),
            BqlBoundParameter(9, timestamp, BqlBoundType.TIMESTAMP),
            BqlBoundParameter(10, arrayOf(uuid), BqlBoundType.UUID_ARRAY),
            BqlBoundParameter(11, arrayOf("one"), BqlBoundType.TEXT_ARRAY),
        ).forEach { method.invoke(service, statement, it) }

        verify { statement.setString(1, "text") }
        verify { statement.setLong(3, 3L) }
        verify { statement.setInt(4, 4) }
        verify { statement.setDouble(5, 5.5) }
        verify { statement.setFloat(6, 6.5f) }
        verify { statement.setObject(7, BigDecimal.TEN) }
        verify { statement.setBoolean(8, true) }
        verify { statement.setTimestamp(9, Timestamp.from(timestamp.toInstant())) }
        verify(exactly = 2) { statement.setArray(any(), jdbcArray) }
    }

    @Test
    fun `name resolution checks every lookup catalog and unknown columns`() = runTest {
        val priorityId = UUID.random()
        val statusId = UUID.random()
        val taskTypeId = UUID.random()
        val resolutionId = UUID.random()
        val projectId = UUID.random()
        val priority = mockk<Priority> {
            every { name } returns "High"
            every { id } returns priorityId
        }
        val status = mockk<Status> {
            every { name } returns "Done"
            every { id } returns statusId
        }
        val taskType = mockk<TaskType> {
            every { name } returns "Bug"
            every { id } returns taskTypeId
        }
        val resolution = mockk<Resolution> {
            every { name } returns "Fixed"
            every { id } returns resolutionId
        }
        val project = mockk<Project> {
            every { id } returns projectId
        }
        coEvery { priorities.getAll() } returns listOf(priority)
        coEvery { statuses.getAll() } returns listOf(status)
        coEvery { taskTypes.getAll() } returns listOf(taskType)
        coEvery { resolutions.getAll() } returns listOf(resolution)
        coEvery { projects.getByKey("APP") } returns project
        coEvery { projects.getByKey("MISSING") } returns null

        val method = TaskQueryServiceImpl::class.java.getDeclaredMethod("nameResolver").apply { isAccessible = true }
        val resolver = method.invoke(service) as BqlNameResolver
        suspend fun resolve(column: String, name: String) = resolver.resolve(
            BqlField(column, column, BqlFieldType.NAME_REFERENCE),
            name,
        )

        assertEquals(priorityId, resolve("priority_id", "high"))
        assertNull(resolve("priority_id", "missing"))
        assertEquals(statusId, resolve("status_id", "done"))
        assertNull(resolve("status_id", "missing"))
        assertEquals(taskTypeId, resolve("task_type_id", "bug"))
        assertNull(resolve("task_type_id", "missing"))
        assertEquals(resolutionId, resolve("resolution_id", "fixed"))
        assertNull(resolve("resolution_id", "missing"))
        assertEquals(projectId, resolve("project_id", "app"))
        assertNull(resolve("project_id", "missing"))
        assertNull(resolve("other_id", "anything"))
    }

    @Test
    fun `row conversion helpers preserve null and non-null JDBC values`() {
        val resultSet = mockk<ResultSet>()
        val javaUuid = java.util.UUID.randomUUID()
        val timestamp = Timestamp.from(java.time.Instant.now())

        val uuidMethod = TaskQueryServiceImpl::class.java.getDeclaredMethod(
            "uuid",
            ResultSet::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        every { resultSet.getObject("uuid") } returns javaUuid
        every { resultSet.getObject("missing_uuid") } returns null
        assertEquals(UUID.fromLongs(javaUuid.mostSignificantBits, javaUuid.leastSignificantBits), uuidMethod.invoke(service, resultSet, "uuid"))
        assertNull(uuidMethod.invoke(service, resultSet, "missing_uuid"))

        val arrayMethod = TaskQueryServiceImpl::class.java.getDeclaredMethod(
            "uuidArray",
            ResultSet::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        val jdbcArray = mockk<java.sql.Array> { every { array } returns arrayOf(javaUuid) }
        every { resultSet.getArray("uuids") } returns jdbcArray
        every { resultSet.getArray("missing_uuids") } returns null
        assertEquals(1, (arrayMethod.invoke(service, resultSet, "uuids") as List<*>).size)
        assertTrue((arrayMethod.invoke(service, resultSet, "missing_uuids") as List<*>).isEmpty())

        val timeMethod = TaskQueryServiceImpl::class.java.getDeclaredMethod(
            "odt",
            ResultSet::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        every { resultSet.getTimestamp("timestamp") } returns timestamp
        every { resultSet.getTimestamp("missing_timestamp") } returns null
        assertEquals(timestamp.toInstant(), (timeMethod.invoke(service, resultSet, "timestamp") as OffsetDateTime).toInstant())
        assertNull(timeMethod.invoke(service, resultSet, "missing_timestamp"))
    }

}
