package bosca.workops.model.bql

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the BQL planner. Verifies SQL fragment generation,
 * bound-parameter types, and function resolution against a controlled
 * field catalog and mock resolvers.
 */
class BqlPlannerTest {

    // ---- test fixtures ------------------------------------------------

    private val testUuid = UUID.parse("00000000-0000-0000-0000-000000000001")
    private val currentUserUuid = UUID.parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

    /** Fixed timestamp: 2025-06-15T10:30:00+00:00 (a Sunday). */
    private val fixedNow: OffsetDateTime =
        LocalDate.of(2025, 6, 15).atTime(10, 30, 0).atOffset(ZoneOffset.UTC)

    private val catalog = BqlFieldCatalog(
        listOf(
            BqlField("status", "status_id", BqlFieldType.NAME_REFERENCE),
            BqlField("priority", "priority_id", BqlFieldType.NAME_REFERENCE),
            BqlField("assignee", "assignee_profile_id", BqlFieldType.UUID),
            BqlField("summary", "summary", BqlFieldType.TEXT),
            BqlField("description", "description_markdown", BqlFieldType.TEXT),
            BqlField("storyPoints", "story_points", BqlFieldType.NUMBER),
            BqlField("label", "label_ids", BqlFieldType.ARRAY_UUID),
            BqlField("created", "created_at", BqlFieldType.TIMESTAMP),
            BqlField("modified", "modified_at", BqlFieldType.TIMESTAMP),
        ),
    )

    /** Maps "Done" -> testUuid, "High" -> testUuid for any field. */
    private val nameResolver = BqlNameResolver { _, name ->
        when (name.lowercase()) {
            "done" -> testUuid
            "high" -> testUuid
            "critical" -> UUID.parse("00000000-0000-0000-0000-000000000002")
            else -> null
        }
    }

    private val functionResolver = object : BqlFunctionResolver {
        override suspend fun currentUserProfileId(): UUID = currentUserUuid
        override suspend fun now(): OffsetDateTime = fixedNow
    }

    private fun planner() = BqlPlanner(catalog, nameResolver, functionResolver)

    private fun parse(src: String): BqlQuery {
        val result = BqlParser(src).parse()
        check(result.isSuccess) { "parser failed: ${result.errors}" }
        return result.query!!
    }

    private suspend fun plan(src: String): BqlQueryPlan = planner().plan(parse(src))

    // ---- tests --------------------------------------------------------

    @Test
    fun `simple name-reference equality resolves to UUID parameter`() = runTest {
        val plan = plan("status = Done")
        assertEquals("status_id = ?", plan.whereSql)
        assertEquals(1, plan.parameters.size)
        assertEquals(testUuid, plan.parameters[0].value)
        assertEquals(BqlBoundType.UUID, plan.parameters[0].sqlType)
    }

    @Test
    fun `text equality binds as TEXT`() = runTest {
        val plan = plan("summary = \"hello\"")
        assertEquals("summary = ?", plan.whereSql)
        assertEquals(1, plan.parameters.size)
        assertEquals("hello", plan.parameters[0].value)
        assertEquals(BqlBoundType.TEXT, plan.parameters[0].sqlType)
    }

    @Test
    fun `text LIKE produces ilike and adds free-text term`() = runTest {
        val plan = plan("summary ~ \"widget\"")
        assertEquals("summary ilike ?", plan.whereSql)
        assertEquals(1, plan.parameters.size)
        assertEquals("%widget%", plan.parameters[0].value)
        assertEquals(BqlBoundType.TEXT, plan.parameters[0].sqlType)
        assertEquals(listOf("widget"), plan.freeTextTerms)
    }

    @Test
    fun `AND combination produces two-part where`() = runTest {
        val plan = plan("status = Done AND priority = High")
        assertEquals("(status_id = ? and priority_id = ?)", plan.whereSql)
        assertEquals(2, plan.parameters.size)
        assertEquals(BqlBoundType.UUID, plan.parameters[0].sqlType)
        assertEquals(BqlBoundType.UUID, plan.parameters[1].sqlType)
    }

    @Test
    fun `OR combination`() = runTest {
        val plan = plan("status = Done OR priority = High")
        assertEquals("(status_id = ? or priority_id = ?)", plan.whereSql)
        assertEquals(2, plan.parameters.size)
    }

    @Test
    fun `IN list with NAME_REFERENCE field uses UUID_ARRAY`() = runTest {
        val plan = plan("priority in (High, Critical)")
        assertEquals("priority_id = any(?)", plan.whereSql)
        assertEquals(1, plan.parameters.size)
        assertEquals(BqlBoundType.UUID_ARRAY, plan.parameters[0].sqlType)
        val arr = plan.parameters[0].value as Array<*>
        assertEquals(2, arr.size)
        assertEquals(testUuid, arr[0])
        assertEquals(UUID.parse("00000000-0000-0000-0000-000000000002"), arr[1])
    }

    @Test
    fun `NOT IN list with NAME_REFERENCE field negates`() = runTest {
        val plan = plan("priority not in (High)")
        assertEquals("not (priority_id = any(?))", plan.whereSql)
    }

    @Test
    fun `IN list with TEXT field uses TEXT_ARRAY not UUID_ARRAY`() = runTest {
        val plan = plan("summary in (\"alpha\", \"beta\")")
        assertEquals("summary = any(?)", plan.whereSql)
        assertEquals(1, plan.parameters.size)
        assertEquals(BqlBoundType.TEXT_ARRAY, plan.parameters[0].sqlType)
        val arr = plan.parameters[0].value as Array<*>
        assertEquals(2, arr.size)
        assertEquals("alpha", arr[0])
        assertEquals("beta", arr[1])
    }

    @Test
    fun `NOT IN with TEXT field negates correctly`() = runTest {
        val plan = plan("summary not in (\"alpha\")")
        assertEquals("not (summary = any(?))", plan.whereSql)
        assertEquals(BqlBoundType.TEXT_ARRAY, plan.parameters[0].sqlType)
    }

    @Test
    fun `IN list with UUID field uses UUID_ARRAY`() = runTest {
        val uuid1 = "00000000-0000-0000-0000-000000000001"
        val plan = plan("assignee in (\"$uuid1\")")
        assertEquals("assignee_profile_id = any(?)", plan.whereSql)
        assertEquals(BqlBoundType.UUID_ARRAY, plan.parameters[0].sqlType)
    }

    @Test
    fun `IN list with ARRAY_UUID field uses overlap operator`() = runTest {
        val uuid1 = "00000000-0000-0000-0000-000000000001"
        val plan = plan("label in (\"$uuid1\")")
        assertEquals("label_ids && ?", plan.whereSql)
        assertEquals(BqlBoundType.UUID_ARRAY, plan.parameters[0].sqlType)
    }

    @Test
    fun `IS NULL produces is null without parameters`() = runTest {
        val plan = plan("assignee is null")
        assertEquals("assignee_profile_id is null", plan.whereSql)
        assertTrue(plan.parameters.isEmpty())
    }

    @Test
    fun `IS NOT NULL produces is not null`() = runTest {
        val plan = plan("assignee is not null")
        assertEquals("assignee_profile_id is not null", plan.whereSql)
        assertTrue(plan.parameters.isEmpty())
    }

    @Test
    fun `number comparison binds as NUMBER`() = runTest {
        val plan = plan("storyPoints > 5")
        assertEquals("story_points > ?", plan.whereSql)
        assertEquals(1, plan.parameters.size)
        assertEquals(BqlBoundType.NUMBER, plan.parameters[0].sqlType)
        assertEquals(5L, plan.parameters[0].value)
    }

    @Test
    fun `ORDER BY produces order by clause`() = runTest {
        val plan = plan("status = Done ORDER BY created DESC")
        assertEquals("order by created_at desc", plan.orderBySql)
    }

    @Test
    fun `ORDER BY with multiple keys`() = runTest {
        val plan = plan("ORDER BY created DESC, modified ASC")
        assertEquals("order by created_at desc, modified_at asc", plan.orderBySql)
        assertEquals("", plan.whereSql)
    }

    @Test
    fun `currentUser function resolves to profile UUID`() = runTest {
        val plan = plan("assignee = currentUser()")
        assertEquals("assignee_profile_id = ?", plan.whereSql)
        assertEquals(currentUserUuid, plan.parameters[0].value)
        assertEquals(BqlBoundType.UUID, plan.parameters[0].sqlType)
    }

    @Test
    fun `endOfDay function produces 23-59-59-999999999`() = runTest {
        val plan = plan("created < endOfDay()")
        assertEquals("created_at < ?", plan.whereSql)
        val ts = plan.parameters[0].value as OffsetDateTime
        assertEquals(23, ts.hour)
        assertEquals(59, ts.minute)
        assertEquals(59, ts.second)
        assertEquals(999_999_999, ts.nano)
    }

    @Test
    fun `startOfDay function produces midnight`() = runTest {
        val plan = plan("created >= startOfDay()")
        assertEquals("created_at >= ?", plan.whereSql)
        val ts = plan.parameters[0].value as OffsetDateTime
        assertEquals(0, ts.hour)
        assertEquals(0, ts.minute)
        assertEquals(0, ts.second)
        assertEquals(0, ts.nano)
    }

    @Test
    fun `endOfWeek function produces Sunday 23-59-59-999999999`() = runTest {
        // fixedNow is 2025-06-15 which is a Sunday (dayOfWeek=7, value-1=6).
        // endOfWeek should be the same day (Sunday) at 23:59:59.999999999.
        val plan = plan("created <= endOfWeek()")
        val ts = plan.parameters[0].value as OffsetDateTime
        assertEquals(15, ts.dayOfMonth) // same Sunday
        assertEquals(23, ts.hour)
        assertEquals(59, ts.minute)
        assertEquals(59, ts.second)
        assertEquals(999_999_999, ts.nano)
    }

    @Test
    fun `startOfWeek function produces Monday midnight`() = runTest {
        // 2025-06-15 is Sunday; start of week (Monday) is 2025-06-09.
        val plan = plan("created >= startOfWeek()")
        val ts = plan.parameters[0].value as OffsetDateTime
        assertEquals(9, ts.dayOfMonth)
        assertEquals(0, ts.hour)
        assertEquals(0, ts.minute)
    }

    @Test
    fun `empty IN list produces false literal`() = runTest {
        // Construct an InList AST node with no values directly since
        // the parser won't produce an empty IN list.
        val query = BqlQuery(
            where = BqlExpr.InList("status", negated = false, values = emptyList(), start = 0, end = 0),
        )
        val result = planner().plan(query)
        assertEquals("false", result.whereSql)
        assertTrue(result.parameters.isEmpty())
    }

    @Test
    fun `empty NOT IN list produces true literal`() = runTest {
        val query = BqlQuery(
            where = BqlExpr.InList("status", negated = true, values = emptyList(), start = 0, end = 0),
        )
        val result = planner().plan(query)
        assertEquals("true", result.whereSql)
    }

    @Test
    fun `parameter ordinals are sequential`() = runTest {
        val plan = plan("status = Done AND priority = High AND storyPoints > 3")
        assertEquals(3, plan.parameters.size)
        assertEquals(1, plan.parameters[0].ordinal)
        assertEquals(2, plan.parameters[1].ordinal)
        assertEquals(3, plan.parameters[2].ordinal)
    }
}
