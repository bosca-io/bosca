package bosca.ai.kit.tools.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [SqlQueryRecorder]'s answer-sourcing contract: the model's claimed query resolves to the
 * statement that actually executed (format-insensitively), later sanity probes don't displace the
 * answering statement, and the fallbacks (last-executed, none-executed) behave.
 */
class SqlQueryRecorderTest {

    private val recorder = SqlQueryRecorder()

    @Test
    fun `lastQuery is null before anything runs and tracks the latest statement`() {
        assertNull(recorder.lastQuery)
        recorder.record("SELECT 1")
        recorder.record("SELECT 2")
        assertEquals("SELECT 2", recorder.lastQuery)
    }

    @Test
    fun `findExecuted resolves the answering statement even when probes run afterwards`() {
        val answering = "SELECT day, COUNT(*) AS views FROM events GROUP BY day"
        recorder.record("SELECT * FROM events LIMIT 5")   // exploration
        recorder.record(answering)                        // sourced the answer
        recorder.record("SELECT COUNT(*) FROM events")    // post-answer sanity probe

        // The claim picks the answering statement, not the last-executed probe.
        assertEquals(answering, recorder.findExecuted(answering))
    }

    @Test
    fun `findExecuted matches a reformatted claim and returns the executed text`() {
        val executed = "SELECT day,\n       COUNT(*) AS views\nFROM events\nGROUP BY day"
        recorder.record(executed)

        val claim = "SELECT day, COUNT(*) AS views FROM events GROUP BY day;"
        assertEquals(executed, recorder.findExecuted(claim), "match must ignore whitespace and trailing semicolons, returning the executed text")
    }

    @Test
    fun `findExecuted prefers the most recent execution of a repeated statement`() {
        recorder.record("SELECT 1")
        recorder.record("SELECT 2")
        recorder.record("SELECT 1")
        assertEquals("SELECT 1", recorder.findExecuted("SELECT 1"))
        assertEquals("SELECT 1", recorder.lastQuery)
    }

    @Test
    fun `findExecuted returns null for an unexecuted, blank, or null claim`() {
        recorder.record("SELECT 1")
        assertNull(recorder.findExecuted("SELECT 999"), "a claim that never ran must not be presented as executed")
        assertNull(recorder.findExecuted(""))
        assertNull(recorder.findExecuted("   ;  "))
        assertNull(recorder.findExecuted(null))
    }
}
