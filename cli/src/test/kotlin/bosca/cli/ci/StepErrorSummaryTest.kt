package bosca.cli.ci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StepErrorSummaryTest {

    @Test
    fun `returns null for an empty tail`() {
        assertNull(StepErrorSummary.build(emptyList()))
    }

    @Test
    fun `returns null when the tail is only blank lines`() {
        val tail = listOf(TailLine("", "stdout"), TailLine("   ", "stderr"))
        assertNull(StepErrorSummary.build(tail))
    }

    @Test
    fun `prefers stderr lines over stdout noise`() {
        val tail = listOf(
            TailLine("> Task :app:compileKotlin", "stdout"),
            TailLine("e: Main.kt:10:5 unresolved reference: foo", "stderr"),
            TailLine("> Task :app:test SKIPPED", "stdout"),
            TailLine("FAILURE: Build failed with an exception.", "stderr"),
        )
        val summary = StepErrorSummary.build(tail)
        assertEquals(
            "e: Main.kt:10:5 unresolved reference: foo\nFAILURE: Build failed with an exception.",
            summary,
        )
    }

    @Test
    fun `keeps a gradle failure report intact and in order`() {
        val tail = listOf(
            TailLine("FAILURE: Build failed with an exception.", "stderr"),
            TailLine("* What went wrong:", "stderr"),
            TailLine("Execution failed for task ':app:compileKotlin'.", "stderr"),
            TailLine("> Compilation error. See log for details", "stderr"),
            TailLine("BUILD FAILED in 1m 12s", "stderr"),
        )
        val summary = StepErrorSummary.build(tail)
        assertEquals(
            "FAILURE: Build failed with an exception.\n" +
                "* What went wrong:\n" +
                "Execution failed for task ':app:compileKotlin'.\n" +
                "> Compilation error. See log for details\n" +
                "BUILD FAILED in 1m 12s",
            summary,
        )
    }

    @Test
    fun `falls back to stdout when nothing was written to stderr`() {
        val tail = listOf(
            TailLine("npm ERR! code ELIFECYCLE", "stdout"),
            TailLine("npm ERR! errno 1", "stdout"),
        )
        val summary = StepErrorSummary.build(tail)
        assertEquals("npm ERR! code ELIFECYCLE\nnpm ERR! errno 1", summary)
    }

    @Test
    fun `keeps only the last MAX_LINES lines`() {
        val tail = (1..StepErrorSummary.MAX_LINES + 10).map { TailLine("err $it", "stderr") }
        val summary = StepErrorSummary.build(tail)!!
        val lines = summary.split("\n")
        assertEquals(StepErrorSummary.MAX_LINES, lines.size)
        assertEquals("err 11", lines.first())
        assertEquals("err ${StepErrorSummary.MAX_LINES + 10}", lines.last())
    }

    @Test
    fun `caps at MAX_CHARS keeping the end of the output`() {
        val longLine = "x".repeat(500)
        val tail = (1..20).map { TailLine("$it-$longLine", "stderr") } +
            TailLine("BUILD FAILED in 3s", "stderr")
        val summary = StepErrorSummary.build(tail)!!
        assertTrue(summary.length <= StepErrorSummary.MAX_CHARS)
        assertTrue(summary.endsWith("BUILD FAILED in 3s"))
    }

    @Test
    fun `skips blank stderr lines`() {
        val tail = listOf(
            TailLine("error one", "stderr"),
            TailLine("", "stderr"),
            TailLine("error two", "stderr"),
        )
        assertEquals("error one\nerror two", StepErrorSummary.build(tail))
    }
}
