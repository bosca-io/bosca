package bosca.ai.kit.tools.analytics

import bosca.ai.chat.model.AnalyticsInvestigationKind
import bosca.ai.kit.agents.analytics.InvestigationAnnotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InvestigationRecorderTest {

    @Test
    fun `concurrent recordings retain a monotonic ordered sequence`() = runTest {
        val recorder = InvestigationRecorder()
        withContext(Dispatchers.Default) {
            (1..100).map { value ->
                async {
                    recorder.record(
                        AnalyticsInvestigationKind.DISCOVERY,
                        tool = "tool-$value",
                        resultSummary = "done",
                        startedAt = "2026-07-21T12:00:00Z",
                    )
                }
            }.awaitAll()
        }

        assertEquals((1..100).toList(), recorder.steps.map { it.sequence })
        assertEquals(100, recorder.steps.map { it.tool }.toSet().size)
    }

    @Test
    fun `assembly drops unmatched claims and retains unannotated recorded steps`() {
        val recorder = InvestigationRecorder()
        recorder.record(AnalyticsInvestigationKind.DISCOVERY, "describe_table", "4 columns", startedAt = "t1")
        recorder.record(AnalyticsInvestigationKind.QUERY, "execute_query", "1 row", "SELECT 42", "t2")

        val assembled = recorder.assemble(
            listOf(
                InvestigationAnnotation(sequence = 2, sql = "select 42;", purpose = "Verify the answer", conclusion = "Confirmed"),
                InvestigationAnnotation(sequence = 99, purpose = "Invented step", conclusion = "Must be dropped"),
            ),
        )

        assertEquals(2, assembled.size)
        assertNull(assembled[0].purpose)
        assertEquals("Verify the answer", assembled[1].purpose)
        assertEquals("Confirmed", assembled[1].conclusion)
    }

    @Test
    fun `SQL-only annotation and headline lookup use normalized executed SQL`() {
        val recorder = InvestigationRecorder()
        val executed = "SELECT week,\n count(*) FROM signups GROUP BY week"
        recorder.record(AnalyticsInvestigationKind.QUERY, "execute_query", "2 rows", executed, "t1")

        assertEquals(executed, recorder.findExecuted("SELECT week, count(*) FROM signups GROUP BY week;"))
        assertEquals(executed, recorder.lastQuery)
        assertEquals(
            "Trend by week",
            recorder.assemble(listOf(InvestigationAnnotation(sql = "SELECT week, count(*) FROM signups GROUP BY week", purpose = "Trend by week"))).single().purpose,
        )
    }

    @Test
    fun `lookups and annotations reject empty mismatched and explanation-only claims`() {
        val recorder = InvestigationRecorder()
        assertNull(recorder.lastQuery)
        assertNull(recorder.findExecuted(null))
        assertNull(recorder.findExecuted(" ; "))

        recorder.record(AnalyticsInvestigationKind.QUERY, "execute_query", "first", "SELECT 1", "t1")
        recorder.record(AnalyticsInvestigationKind.QUERY, "execute_query", "second", "SELECT 1;", "t2")
        recorder.record(AnalyticsInvestigationKind.DISCOVERY, "describe_table", "one column", startedAt = "t3")
        assertEquals("SELECT 1;", recorder.lastQuery)
        assertNull(recorder.findExecuted("SELECT 2"))

        val assembled = recorder.assemble(
            listOf(
                InvestigationAnnotation(sequence = 1, sql = "SELECT 2", purpose = "wrong SQL"),
                InvestigationAnnotation(sequence = 1, sql = " ", purpose = "matched by sequence"),
                InvestigationAnnotation(sequence = 3, sql = "SELECT 1", purpose = "discovery has no SQL"),
                InvestigationAnnotation(purpose = "no recorded identity"),
                InvestigationAnnotation(sql = "SELECT 9", purpose = "query never ran"),
                InvestigationAnnotation(sql = "SELECT 1", purpose = " ", conclusion = " "),
            ),
        )

        assertEquals("matched by sequence", assembled.first().purpose)
        assertNull(assembled.last().purpose)
        assertNull(assembled.last().conclusion)
    }
}
