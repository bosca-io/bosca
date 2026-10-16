package bosca.experimentation.jobs

import bosca.analytics.model.EventType
import bosca.experimentation.model.*
import bosca.serialization.UUID
import java.time.OffsetDateTime
import kotlin.test.*

class GoalScopeQueryTest {
    @Test fun `application selectors are bound literal values and contextual scope starts at the right boundary`() {
        val conditions = mutableListOf<String>(); val params = mutableListOf<String>()
        appendExtendedEventFilter(ExperimentActivationFilter(eventType = EventType.Interaction,
            elementIdContains = "card_%'", applicationIds = listOf("site", "mobile"), recommendationCard = true), conditions, params)
        assertEquals(listOf("card_%'", "site", "mobile"), params)
        assertTrue(conditions.joinToString().contains("strpos(element.id, ?)"))
        assertFalse(conditions.joinToString().contains("visibility_threshold"))
        assertFalse(conditions.joinToString().contains("card_%'"))
        assertEquals("", outcomeScopePredicate(null, false, "c"))
        assertEquals("", outcomeScopePredicate(GoalOutcomeScope(applicationIds = listOf("site")), false, "c"))
        val assignment = outcomeScopePredicate(GoalOutcomeScope(maxDelaySeconds = 60), false, "c")
        assertTrue(assignment.contains("subjects.assigned_at"))
        val captured = outcomeScopePredicate(GoalOutcomeScope(sameSession = true, maxDelaySeconds = 60), true, "e", true)
        assertFalse(captured.contains("app_id"), "Unlisted applications are not restricted")
        assertTrue(captured.contains("e.context.session_id = subjects.session_id"))
        assertTrue(captured.contains("subjects.activated_at"))
        assertFailsWith<IllegalArgumentException> { outcomeScopePredicate(GoalOutcomeScope(sameSession = true), false, "c") }
        assertFailsWith<IllegalArgumentException> { outcomeScopePredicate(GoalOutcomeScope(maxDelaySeconds = 0), true, "c") }
        appendOutcomeEventFilter(GoalOutcomeScope(applicationIds = listOf("site")), conditions, params)
        assertEquals("site", params.last())
    }

    @Test fun `rule application overrides goal scope and optional ordering constraints stay bound to frozen eligibility`() {
        val batch = ActivatedCohort(listOf(ActivatedSubject(UUID.random().toString(), "Mid", "2026-09-01 10:00:00", "site", "s"))).batches.single()
        val start = OffsetDateTime.parse("2026-09-01T10:00:00Z")
        fun query(filter: ExperimentActivationFilter, activation: GoalActivation) = buildGoalActivationQuery("events", "assignments", UUID.NIL,
            filter, activation, batch, start, start.plusHours(1), emptyList(), emptyList())
        val filter = ExperimentActivationFilter(eventType = EventType.Impression, recommendationCard = true)
        val explicit = query(filter.copy(applicationIds = listOf("mobile")), GoalActivation(applicationIds = listOf("site")))
        assertEquals("mobile", explicit.params.last())
        assertFalse(explicit.params.contains("site"))
        assertTrue(explicit.sql.contains("in_application"))
        assertFalse(explicit.sql.contains("c.session_id = subjects.session_id"))
        val fallback = query(filter, GoalActivation(applicationIds = listOf("site"), sameSession = true, maxDelaySeconds = 5))
        assertEquals("site", fallback.params.last())
        assertTrue(fallback.sql.contains("c.session_id = subjects.session_id"))
        assertTrue(fallback.sql.contains("date_add('second', 5, subjects.activated_at)"))
        assertFailsWith<IllegalArgumentException> { query(filter, GoalActivation(maxDelaySeconds = 0)) }
        val unscoped = query(filter, GoalActivation())
        assertTrue(unscoped.sql.contains("coalesce(true, false) AS in_application"))
        assertFalse(unscoped.sql.contains("c.app_id = subjects.app_id"))
    }
}
