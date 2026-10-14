package bosca.experimentation.service

import bosca.analytics.model.EventType
import bosca.experimentation.graphql.GoalActivationInput
import bosca.experimentation.graphql.GoalActivationRuleInput
import bosca.experimentation.graphql.ExperimentActivationFilterInput
import bosca.experimentation.graphql.GraphQLEventType
import bosca.experimentation.graphql.toModel
import bosca.experimentation.model.*
import kotlin.test.*

class GoalActivationValidationTest {
    private val keys = setOf("Mid", "Bottom", "None")
    private val filter = ExperimentActivationFilter(eventType = EventType.Impression)

    @Test fun `effective defaults and explicit modes resolve independently`() {
        val activation = GoalActivation(filter, listOf(
            GoalActivationRule("Mid", GoalActivationMode.EVENT, filter.copy(elementIdContains = "inline")),
            GoalActivationRule("Bottom", GoalActivationMode.INHERIT),
            GoalActivationRule("None", GoalActivationMode.NOT_APPLICABLE)))
        val normalized = assertNotNull(normalizeGoalActivation(activation, keys))
        assertEquals("inline", normalized.filterFor("Mid")?.elementIdContains)
        assertEquals(filter, normalized.filterFor("Bottom"))
        assertNull(normalized.filterFor("None"))
        assertEquals(filter, normalized.filterFor("unspecified"))
        assertNull(GoalActivation().filterFor("Mid"))
        assertNull(normalizeGoalActivation(null, keys))
        assertEquals(normalized, normalizeGoalActivation(normalized, keys))
        assertEquals(normalized, normalizeGoalActivation(activation.copy(variationRules = activation.variationRules.reversed()), keys))
        assertEquals(GoalActivation(filter), normalizeGoalActivation(GoalActivation(filter, listOf(GoalActivationRule("Mid", GoalActivationMode.EVENT, filter))), keys))
    }

    @Test fun `rejects ambiguous unknown and unbounded activation definitions`() {
        val invalid = listOf(
            GoalActivation(maxDelaySeconds = 0),
            GoalActivation(variationRules = listOf(GoalActivationRule("Unknown", GoalActivationMode.NOT_APPLICABLE))),
            GoalActivation(variationRules = listOf(GoalActivationRule("Mid", GoalActivationMode.EVENT))),
            GoalActivation(variationRules = listOf(GoalActivationRule("Mid", GoalActivationMode.INHERIT))),
            GoalActivation(filter, listOf(GoalActivationRule("Mid", GoalActivationMode.INHERIT, filter))),
            GoalActivation(filter, listOf(GoalActivationRule("Mid", GoalActivationMode.NOT_APPLICABLE, filter))),
            GoalActivation(variationRules = List(2) { GoalActivationRule("Mid", GoalActivationMode.EVENT, filter) }),
            GoalActivation(defaultFilter = ExperimentActivationFilter()),
        )
        invalid.forEach { value -> assertFailsWith<IllegalArgumentException>(value.toString()) { normalizeGoalActivation(value, keys) } }
    }

    @Test fun `event filters normalize unordered selectors but preserve exact extra values`() {
        val normalized = normalizeEventFilter(filter.copy(elementType = " card ", elementId = " ", elementIdContains = " -rec- ",
            pagePath = " /articles/a ", pagePathPrefixes = listOf(" /talks/ ", "/articles/", "/talks/", " "),
            itemExtraKey = " source ", itemExtraValue = " Premium ", applicationIds = listOf("site", " Site ", "site"), recommendationCard = true))
        assertEquals("card", normalized.elementType); assertNull(normalized.elementId)
        assertEquals("-rec-", normalized.elementIdContains); assertEquals(" Premium ", normalized.itemExtraValue)
        assertEquals(listOf("/articles/", "/talks/"), normalized.pagePathPrefixes)
        assertEquals(listOf("Site", "site"), normalized.applicationIds)
        assertEquals(normalized, normalizeEventFilter(normalized))
        listOf(
            filter.copy(elementId = "x".repeat(513)), filter.copy(pagePathPrefixes = List(33) { "/$it" }),
            filter.copy(itemExtraKey = "invalid-key"), filter.copy(itemExtraValue = "without-key"),
            filter.copy(itemExtraKey = "key", itemExtraValue = "x".repeat(513)),
            filter.copy(eventType = EventType.Session, recommendationCard = true),
        ).forEach { assertFailsWith<IllegalArgumentException> { normalizeEventFilter(it) } }
        listOf(listOf(""), listOf("invalid space"), listOf("x".repeat(129)), List(33) { "app$it" }).forEach {
            assertFailsWith<IllegalArgumentException> { normalizeApplicationIds(it) }
        }
        assertNull(normalizeOutcomeScope(null)); assertNull(normalizeOutcomeScope(GoalOutcomeScope()))
        assertFailsWith<IllegalArgumentException> { normalizeOutcomeScope(GoalOutcomeScope(maxDelaySeconds = -1)) }
        assertEquals(listOf("a", "b"), normalizeOutcomeScope(GoalOutcomeScope(applicationIds = listOf("b", "a", "b")))?.applicationIds)
    }

    @Test fun `each bounded selector can define activation without an event type`() {
        val filters = listOf(
            ExperimentActivationFilter(elementType = "card"), ExperimentActivationFilter(elementId = "specific-card"),
            ExperimentActivationFilter(elementIdContains = "-rec-"), ExperimentActivationFilter(pagePath = "/articles/a"),
            ExperimentActivationFilter(pagePathPrefixes = listOf("/articles/")), ExperimentActivationFilter(itemExtraKey = "location"),
            ExperimentActivationFilter(eventType = EventType.Interaction, recommendationCard = true),
        )
        filters.forEach { assertEquals(it, normalizeEventFilter(it)) }
        assertEquals(60, normalizeGoalActivation(GoalActivation(filter, maxDelaySeconds = 60), keys)?.maxDelaySeconds)
        assertEquals(60, normalizeOutcomeScope(GoalOutcomeScope(maxDelaySeconds = 60))?.maxDelaySeconds)
        val noDefaults = GoalActivationInput().toModel()
        assertEquals(GoalActivation(), noDefaults)
        val event = GoalActivationRuleInput("Mid", GoalActivationMode.EVENT, ExperimentActivationFilterInput(eventType = GraphQLEventType.Interaction)).toModel()
        assertEquals(EventType.Interaction, event.filter?.eventType)
    }

    @Test fun `GraphQL nested filters use schema event enums`() {
        val input = GoalActivationInput(defaultFilter = ExperimentActivationFilterInput(eventType = GraphQLEventType.Impression, elementIdContains = "-rec-", applicationIds = listOf("site"), recommendationCard = true),
            variationRules = listOf(GoalActivationRuleInput("None", GoalActivationMode.NOT_APPLICABLE)), sameSession = true, maxDelaySeconds = 60)
        val model = input.toModel()
        assertEquals(EventType.Impression, model.defaultFilter?.eventType)
        assertEquals(listOf("site"), model.defaultFilter?.applicationIds)
        assertTrue(model.sameSession); assertEquals(60, model.maxDelaySeconds)
        assertNull(model.filterFor("None"))
    }
}
