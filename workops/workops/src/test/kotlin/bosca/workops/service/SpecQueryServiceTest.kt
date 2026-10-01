package bosca.workops.service

import bosca.db.ConnectionManager
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.bql.BqlBoundParameter
import bosca.workops.model.bql.BqlBoundType
import bosca.workops.model.bql.BqlField
import bosca.workops.model.bql.BqlFieldType
import bosca.workops.model.bql.BqlParseException
import bosca.workops.model.project.Project
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.StatusCategory
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.StatusRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpecQueryServiceTest {

    private val statusRepository = mockk<StatusRepository>()
    private val projectRepository = mockk<ProjectRepository>()
    private val service = SpecQueryServiceImpl(statusRepository, projectRepository)

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `validate handles blank malformed valid and invalid queries`() = runTest {
        assertTrue(service.validate("   ").isEmpty())
        assertTrue(service.validate("status =").isNotEmpty())
        assertTrue(service.validate("key = \"GIT-SPEC-6\"").isEmpty())

        val invalid = service.validate("statoos = Done")
        assertEquals(1, invalid.size)
        assertTrue(invalid.single().message.contains("unknown field"))
    }

    @Test
    fun `search rejects malformed and invalid queries before opening a connection`() = runTest {
        assertFailsWith<BqlParseException> {
            service.search("=== garbage", actingProfileId = null, offset = 0, limit = 50)
        }
        assertFailsWith<BqlParseException> {
            service.search("statoos = Done", actingProfileId = null, offset = 0, limit = 50)
        }
    }

    @Test
    fun `blank search clamps pagination and uses the default ordering`() = runTest {
        val database = database()

        val result = service.search(" ", actingProfileId = null, offset = -7, limit = 500)

        assertTrue(result.rows.isEmpty())
        assertTrue(result.freeTextTerms.isEmpty())
        assertEquals(
            "select * from workops.spec where deleted_at is null order by modified_at desc limit ? offset ?",
            database.sql.captured,
        )
        verify { database.statement.setInt(1, 200) }
        verify { database.statement.setLong(2, 0) }
    }

    @Test
    fun `planned search binds free text and preserves explicit ordering`() = runTest {
        val database = database()

        val result = service.search(
            "key ~ \"release\" ORDER BY modified ASC",
            actingProfileId = UUID.random(),
            offset = 7,
            limit = 40,
        )

        assertEquals(listOf("release"), result.freeTextTerms)
        assertTrue(database.sql.captured.contains("key ilike ?"))
        assertTrue(database.sql.captured.contains("order by modified_at asc"))
        verify { database.statement.setString(1, "%release%") }
        verify { database.statement.setInt(2, 40) }
        verify { database.statement.setLong(3, 7) }
    }

    @Test
    fun `order only search supplies a true predicate and minimum limit`() = runTest {
        val database = database()

        service.search("ORDER BY created DESC", actingProfileId = null, offset = 3, limit = 0)

        assertTrue(database.sql.captured.contains("and (true) order by created_at desc"))
        verify { database.statement.setInt(1, 1) }
        verify { database.statement.setLong(2, 3) }
    }

    @Test
    fun `bind parameter covers every JDBC representation`() {
        val statement = mockk<PreparedStatement>(relaxed = true)
        val connection = mockk<java.sql.Connection>()
        val uuidSqlArray = mockk<java.sql.Array>()
        val textSqlArray = mockk<java.sql.Array>()
        val uuid = UUID.random()
        val timestamp = OffsetDateTime.of(2026, 7, 22, 12, 30, 0, 0, ZoneOffset.UTC)
        val decimal = BigDecimal("12.75")
        every { statement.connection } returns connection
        every { connection.createArrayOf("uuid", any()) } returns uuidSqlArray
        every { connection.createArrayOf("varchar", any()) } returns textSqlArray

        val parameters = listOf(
            BqlBoundParameter(1, "text", BqlBoundType.TEXT),
            BqlBoundParameter(2, uuid, BqlBoundType.UUID),
            BqlBoundParameter(3, 8L, BqlBoundType.NUMBER),
            BqlBoundParameter(4, 9, BqlBoundType.NUMBER),
            BqlBoundParameter(5, 10.5, BqlBoundType.NUMBER),
            BqlBoundParameter(6, 11.5f, BqlBoundType.NUMBER),
            BqlBoundParameter(7, decimal, BqlBoundType.NUMBER),
            BqlBoundParameter(8, true, BqlBoundType.BOOLEAN),
            BqlBoundParameter(9, timestamp, BqlBoundType.TIMESTAMP),
            BqlBoundParameter(10, arrayOf(uuid), BqlBoundType.UUID_ARRAY),
            BqlBoundParameter(11, arrayOf("one", "two"), BqlBoundType.TEXT_ARRAY),
        )

        parameters.forEach { service.bindParameter(statement, it) }

        verify { statement.setString(1, "text") }
        verify { statement.setObject(2, java.util.UUID.fromString(uuid.toString())) }
        verify { statement.setLong(3, 8L) }
        verify { statement.setInt(4, 9) }
        verify { statement.setDouble(5, 10.5) }
        verify { statement.setFloat(6, 11.5f) }
        verify { statement.setObject(7, decimal) }
        verify { statement.setBoolean(8, true) }
        verify { statement.setTimestamp(9, Timestamp.from(timestamp.toInstant())) }
        verify { statement.setArray(10, uuidSqlArray) }
        verify { statement.setArray(11, textSqlArray) }
    }

    @Test
    fun `spec row maps required optional array JSON and visibility columns`() {
        val values = rowValues()
        val spec = service.specFromRow(resultSet(values))

        assertEquals(values.ids.getValue("id").toKotlinUuid(), spec.id)
        assertEquals("GIT-SPEC-6", spec.key)
        assertEquals(values.ids.getValue("program_id").toKotlinUuid(), spec.programId)
        assertEquals(values.ids.getValue("project_id").toKotlinUuid(), spec.projectId)
        assertEquals(values.ids.getValue("parent_spec_id").toKotlinUuid(), spec.parentSpecId)
        assertEquals(values.ids.getValue("git_repository_id").toKotlinUuid(), spec.gitRepositoryId)
        assertEquals("specs/git-spec-6.md", spec.gitPath)
        assertEquals(listOf(values.watcherId.toKotlinUuid()), spec.watcherProfileIds)
        assertEquals(listOf(values.labelId.toKotlinUuid()), spec.labelIds)
        assertEquals("github", (spec.externalReferences as JsonObject)["source"]?.jsonPrimitive?.content)
        assertTrue(spec.public)
        assertTrue(spec.publicContent)
        assertTrue(spec.publicList)
        assertTrue(spec.publicSupplementary)
        assertEquals(values.deletedAt.toInstant(), spec.deletedAt?.toInstant())
        assertEquals(17, spec.sortOrder)
        assertEquals(4, spec.childCount)
        assertEquals(3, spec.childDoneCount)
        assertEquals(9L, spec.version)
    }

    @Test
    fun `spec row maps absent optional columns to null and empty collections`() {
        val spec = service.specFromRow(resultSet(rowValues(includeOptional = false)))

        assertNull(spec.programId)
        assertNull(spec.projectId)
        assertNull(spec.parentSpecId)
        assertNull(spec.gitRepositoryId)
        assertNull(spec.gitPath)
        assertTrue(spec.watcherProfileIds.isEmpty())
        assertTrue(spec.labelIds.isEmpty())
        assertNull(spec.externalReferences)
        assertNull(spec.deletedAt)
    }

    @Test
    fun `spec row reports every missing required column explicitly`() {
        val requiredColumns = listOf(
            "id",
            "metadata_id",
            "status_id",
            "workflow_id",
            "owner_profile_id",
            "created_at",
            "modified_at",
            "created_by_principal_id",
            "modified_by_principal_id",
        )

        for (column in requiredColumns) {
            val failure = assertFailsWith<IllegalStateException> {
                service.specFromRow(resultSet(rowValues(), missingColumn = column))
            }
            assertEquals("missing spec $column", failure.message)
        }
    }

    @Test
    fun `name resolver handles statuses projects misses and unsupported fields`() = runTest {
        val status = Status(
            id = UUID.random(),
            name = "In Review",
            category = StatusCategory.IN_PROGRESS,
            colorHex = "#123456",
        )
        val project = Project(
            id = UUID.random(),
            programId = UUID.random(),
            key = "GIT",
            name = "Git",
            ownerProfileId = UUID.random(),
        )
        coEvery { statusRepository.getAll() } returns listOf(status)
        coEvery { projectRepository.getByKey("GIT") } returns project
        coEvery { projectRepository.getByKey("MISSING") } returns null
        val resolver = service.nameResolver()

        assertEquals(
            status.id,
            resolver.resolve(BqlField("status", "status_id", BqlFieldType.NAME_REFERENCE), "in review"),
        )
        assertNull(resolver.resolve(BqlField("status", "status_id", BqlFieldType.NAME_REFERENCE), "Done"))
        assertEquals(
            project.id,
            resolver.resolve(BqlField("project", "project_id", BqlFieldType.NAME_REFERENCE), "git"),
        )
        assertNull(
            resolver.resolve(BqlField("project", "project_id", BqlFieldType.NAME_REFERENCE), "missing"),
        )
        assertNull(resolver.resolve(BqlField("owner", "owner_profile_id", BqlFieldType.UUID), "any"))
    }

    @Test
    fun `function resolver returns the acting profile and current time`() = runTest {
        val profileId = UUID.random()
        val before = OffsetDateTime.now()

        val authenticated = service.functionResolver(profileId)
        val anonymous = service.functionResolver(null)

        assertEquals(profileId, authenticated.currentUserProfileId())
        assertNull(anonymous.currentUserProfileId())
        assertTrue(!authenticated.now().isBefore(before))
    }

    private fun database(): DatabaseFixture {
        val manager = mockk<ConnectionManager>()
        val statement = mockk<PreparedStatement>(relaxed = true)
        val resultSet = mockk<ResultSet>(relaxed = true)
        val sql = slot<String>()
        every { statement.executeQuery() } returns resultSet
        every { resultSet.next() } returns false
        coEvery { bosca.db.connection() } returns manager
        coEvery { manager.useStatement<Unit>(capture(sql), any()) } coAnswers {
            secondArg<suspend (PreparedStatement) -> Unit>().invoke(statement)
        }
        return DatabaseFixture(statement, sql)
    }

    private fun rowValues(includeOptional: Boolean = true): RowValues {
        val requiredIds = mapOf(
            "id" to java.util.UUID.randomUUID(),
            "metadata_id" to java.util.UUID.randomUUID(),
            "status_id" to java.util.UUID.randomUUID(),
            "workflow_id" to java.util.UUID.randomUUID(),
            "owner_profile_id" to java.util.UUID.randomUUID(),
            "created_by_principal_id" to java.util.UUID.randomUUID(),
            "modified_by_principal_id" to java.util.UUID.randomUUID(),
        )
        val optionalIds = if (includeOptional) {
            mapOf(
                "program_id" to java.util.UUID.randomUUID(),
                "project_id" to java.util.UUID.randomUUID(),
                "parent_spec_id" to java.util.UUID.randomUUID(),
                "git_repository_id" to java.util.UUID.randomUUID(),
            )
        } else {
            emptyMap()
        }
        return RowValues(
            ids = requiredIds + optionalIds,
            watcherId = java.util.UUID.randomUUID(),
            labelId = java.util.UUID.randomUUID(),
            createdAt = Timestamp.from(java.time.Instant.parse("2026-07-20T10:00:00Z")),
            modifiedAt = Timestamp.from(java.time.Instant.parse("2026-07-21T11:00:00Z")),
            deletedAt = Timestamp.from(java.time.Instant.parse("2026-07-22T12:00:00Z")),
            includeOptional = includeOptional,
        )
    }

    private fun resultSet(values: RowValues, missingColumn: String? = null): ResultSet {
        val resultSet = mockk<ResultSet>(relaxed = true)
        every { resultSet.getObject(any<String>()) } answers {
            firstArg<String>().takeUnless { it == missingColumn }?.let(values.ids::get)
        }
        every { resultSet.getString("key") } returns "GIT-SPEC-6"
        every { resultSet.getString("git_path") } returns
                if (values.includeOptional) "specs/git-spec-6.md" else null
        every { resultSet.getString("external_references") } returns
                if (values.includeOptional) "{\"source\":\"github\"}" else null
        val watcherArray = mockk<java.sql.Array> {
            every { array } returns arrayOf(values.watcherId)
        }
        val labelArray = mockk<java.sql.Array> {
            every { array } returns arrayOf(values.labelId)
        }
        every { resultSet.getArray("watcher_profile_ids") } returns
                if (values.includeOptional) watcherArray else null
        every { resultSet.getArray("label_ids") } returns if (values.includeOptional) labelArray else null
        every { resultSet.getTimestamp(any<String>()) } answers {
            when (val column = firstArg<String>()) {
                missingColumn -> null
                "created_at" -> values.createdAt
                "modified_at" -> values.modifiedAt
                "deleted_at" -> values.deletedAt.takeIf { values.includeOptional }
                else -> null
            }
        }
        every { resultSet.getInt("sort_order") } returns 17
        every { resultSet.getInt("child_count") } returns 4
        every { resultSet.getInt("child_done_count") } returns 3
        every { resultSet.getBoolean(any<String>()) } returns values.includeOptional
        every { resultSet.getLong("version") } returns 9L
        return resultSet
    }

    private fun java.util.UUID.toKotlinUuid(): UUID = UUID.fromLongs(mostSignificantBits, leastSignificantBits)

    private data class DatabaseFixture(
        val statement: PreparedStatement,
        val sql: io.mockk.CapturingSlot<String>,
    )

    private data class RowValues(
        val ids: Map<String, java.util.UUID>,
        val watcherId: java.util.UUID,
        val labelId: java.util.UUID,
        val createdAt: Timestamp,
        val modifiedAt: Timestamp,
        val deletedAt: Timestamp,
        val includeOptional: Boolean,
    )
}
