package bosca.git.ci.parser

import bosca.git.model.JobDefinition
import bosca.git.model.StepDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * creation-time job selection: decidable conditions are consumed (evaluated once,
 * cleared from survivors), deferred conditions (needs.* / always() / failure() / cancelled()) ride
 * through untouched for settle-time evaluation, and needs-edges to dropped jobs are pruned.
 */
class PipelineJobFilterTest {

    private fun job(condition: String? = null, needs: List<String> = emptyList()) = JobDefinition(
        condition = condition,
        needs = needs,
        steps = listOf(StepDefinition(name = "S", run = "true")),
    )

    @Test
    fun `a decidable condition is evaluated and consumed`() {
        val jobs = mapOf(
            "release-only" to job(condition = "event == 'release'"),
            "promotion-only" to job(condition = "event == 'promotion'"),
        )

        val kept = PipelineJobFilter.filter(jobs, ExpressionContext(event = "release"))

        assertEquals(setOf("release-only"), kept.keys)
        assertNull(kept.getValue("release-only").condition, "a consumed condition must be cleared")
    }

    @Test
    fun `a deferred condition is kept untouched`() {
        val jobs = mapOf(
            "deploy" to job(),
            "rollback" to job(condition = "needs.deploy.result == 'failure'", needs = listOf("deploy")),
            "notify" to job(condition = "always()", needs = listOf("deploy")),
        )

        val kept = PipelineJobFilter.filter(jobs, ExpressionContext(event = "release"))

        assertEquals(jobs.keys, kept.keys)
        assertEquals("needs.deploy.result == 'failure'", kept.getValue("rollback").condition)
        assertEquals("always()", kept.getValue("notify").condition)
    }

    @Test
    fun `needs edges to dropped jobs are pruned from survivors`() {
        val jobs = mapOf(
            "excluded" to job(condition = "event == 'promotion'"),
            "kept" to job(),
            "dependent" to job(needs = listOf("excluded", "kept")),
        )

        val kept = PipelineJobFilter.filter(jobs, ExpressionContext(event = "release"))

        assertEquals(setOf("kept", "dependent"), kept.keys)
        assertEquals(listOf("kept"), kept.getValue("dependent").needs)
    }

    @Test
    fun `deferred detection covers needs and completion functions only`() {
        assertTrue(PipelineJobFilter.isDeferred("needs.deploy.result == 'failure'"))
        assertTrue(PipelineJobFilter.isDeferred("always()"))
        assertTrue(PipelineJobFilter.isDeferred("failure()"))
        assertTrue(PipelineJobFilter.isDeferred("cancelled()"))
        assertTrue(PipelineJobFilter.isDeferred("event == 'promotion' && needs.deploy.result == 'failure'"))
        assertFalse(PipelineJobFilter.isDeferred("event == 'release'"))
        assertFalse(PipelineJobFilter.isDeferred("contains(release.projects, 'server')"))
        assertFalse(PipelineJobFilter.isDeferred("ref == 'refs/heads/main'"))
    }
}
