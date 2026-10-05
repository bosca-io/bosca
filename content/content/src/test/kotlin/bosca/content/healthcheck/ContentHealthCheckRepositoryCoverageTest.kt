package bosca.content.healthcheck

import bosca.db.ConnectionManager
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Unit coverage for [ContentHealthCheckRepository]. The repository reaches the database
 * only through the top-level [bosca.db.connection] function and [ConnectionManager.useReadOnlyStatement].
 * Both are mocked so the private [ContentHealthCheckRepository.executeQuery] /
 * [ContentHealthCheckRepository.mapResults] plumbing can be driven against a fake
 * [PreparedStatement] / [ResultSet], covering the row-mapping loop (rows / no rows) and the
 * deleted-count branch (count present / absent) without a real Postgres instance.
 */
class ContentHealthCheckRepositoryCoverageTest {

    private val repository = ContentHealthCheckRepository()

    private val manager = mockk<ConnectionManager>()

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.connection() } returns manager
    }

    @AfterTest
    fun teardown() {
        clearAllMocks()
        unmockkAll()
    }

    /**
     * Wires the mocked [ConnectionManager.useReadOnlyStatement] so the block-under-test runs
     * against [statement], and returns whatever the block returns. Captures the SQL and the
     * setInt(...) calls so tests can assert the limit/offset binding.
     */
    private fun stubStatement(statement: PreparedStatement) {
        coEvery { manager.useReadOnlyStatement<Any?>(any(), any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[1] as suspend (PreparedStatement) -> Any?
            block(statement)
        }
    }

    /** Builds a [PreparedStatement] whose executeQuery() returns [rs] and whose setters are no-ops. */
    private fun statementReturning(rs: ResultSet): PreparedStatement {
        val stmt = mockk<PreparedStatement>(relaxed = true)
        every { stmt.executeQuery() } returns rs
        return stmt
    }

    /**
     * A [ResultSet] mock that reports [rowCount] rows. Each successive next() returns true until
     * the rows are exhausted, then false. Every mapped row reads a fresh random UUID for id and a
     * fixed name / workflow state.
     */
    private fun resultSetWithRows(rowCount: Int): ResultSet {
        val rs = mockk<ResultSet>(relaxed = true)
        var remaining = rowCount
        every { rs.next() } answers { if (remaining > 0) { remaining--; true } else false }
        every { rs.getString("id") } returns Uuid.random().toString()
        every { rs.getString("name") } returns "Broken Item"
        every { rs.getString("workflow_state_id") } returns "published"
        return rs
    }

    @Test
    fun `findPublishedWithUnpublishedRelationships maps rows and binds limit and offset`() = runTest {
        val rs = resultSetWithRows(2)
        val stmt = statementReturning(rs)
        stubStatement(stmt)

        val result = repository.findPublishedWithUnpublishedRelationships(offset = 5, limit = 10)

        assertEquals(2, result.size)
        assertEquals("Broken Item", result[0].name)
        assertEquals("published", result[0].workflowState)
        verify { stmt.setInt(1, 10) }
        verify { stmt.setInt(2, 5) }
    }

    @Test
    fun `findScheduledButNotPublished maps a single row`() = runTest {
        val rs = resultSetWithRows(1)
        stubStatement(statementReturning(rs))

        val result = repository.findScheduledButNotPublished(offset = 0, limit = 25)

        assertEquals(1, result.size)
    }

    @Test
    fun `findPendingNotReady returns empty list when no rows`() = runTest {
        val rs = resultSetWithRows(0)
        stubStatement(statementReturning(rs))

        val result = repository.findPendingNotReady(offset = 0, limit = 25)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findGuidesWithUnpublishedSteps maps rows`() = runTest {
        val rs = resultSetWithRows(3)
        stubStatement(statementReturning(rs))

        val result = repository.findGuidesWithUnpublishedSteps(offset = 10, limit = 5)

        assertEquals(3, result.size)
    }

    @Test
    fun `findPublishedCollectionsWithUnpublishedMetadata maps rows`() = runTest {
        val rs = resultSetWithRows(1)
        stubStatement(statementReturning(rs))

        val result = repository.findPublishedCollectionsWithUnpublishedMetadata(offset = 0, limit = 50)

        assertEquals(1, result.size)
        assertEquals("Broken Item", result[0].name)
    }

    @Test
    fun `findFailedJobItems returns empty list when no rows`() = runTest {
        val rs = resultSetWithRows(0)
        stubStatement(statementReturning(rs))

        val result = repository.findFailedJobItems(offset = 0, limit = 25)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findMissingContent maps rows`() = runTest {
        val rs = resultSetWithRows(2)
        stubStatement(statementReturning(rs))

        val result = repository.findMissingContent(offset = 0, limit = 25)

        assertEquals(2, result.size)
    }

    @Test
    fun `countDeletedItems returns count when result present`() = runTest {
        val rs = mockk<ResultSet>(relaxed = true)
        every { rs.next() } returns true
        every { rs.getInt(1) } returns 42
        stubStatement(statementReturning(rs))

        val result = repository.countDeletedItems()

        assertEquals(42, result)
    }

    @Test
    fun `countDeletedItems returns zero when no result row`() = runTest {
        val rs = mockk<ResultSet>(relaxed = true)
        every { rs.next() } returns false
        stubStatement(statementReturning(rs))

        val result = repository.countDeletedItems()

        assertEquals(0, result)
    }
}
