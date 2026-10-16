package bosca.experimentation.service

import bosca.analytics.model.EventType
import bosca.experimentation.isValidItemExtraKey
import bosca.experimentation.model.*

internal fun normalizeApplicationIds(ids: List<String>): List<String> {
    require(ids.size <= 32) { "At most 32 application identifiers may be configured" }
    return ids.map(String::trim).distinct().sorted().also { normalized ->
        require(normalized.all { it.length in 1..128 && it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]*")) }) {
            "Application identifiers must be nonblank, at most 128 characters, and contain only letters, digits, _, ., :, /, or -"
        }
    }
}

internal fun normalizeEventFilter(filter: ExperimentActivationFilter): ExperimentActivationFilter {
    fun String?.normalized() = this?.trim()?.takeIf(String::isNotEmpty)?.also {
        require(it.length <= 512) { "Event filter values must be at most 512 characters" }
    }
    val result = filter.copy(
        elementType = filter.elementType.normalized(), elementId = filter.elementId.normalized(),
        elementIdContains = filter.elementIdContains.normalized(), pagePath = filter.pagePath.normalized(),
        pagePathPrefixes = filter.pagePathPrefixes.mapNotNull { it.normalized() }.distinct().sorted(),
        itemExtraKey = filter.itemExtraKey.normalized(), applicationIds = normalizeApplicationIds(filter.applicationIds),
    )
    require(result.pagePathPrefixes.size <= 32) { "At most 32 page path prefixes may be configured" }
    require(result.itemExtraKey?.let(::isValidItemExtraKey) != false) { "Invalid activation itemExtraKey" }
    require(result.itemExtraValue?.let { result.itemExtraKey != null && it.length <= 512 } != false) {
        "Activation itemExtraValue requires a key and must be at most 512 characters"
    }
    require(result.eventType != null || result.elementType != null || result.elementId != null ||
        result.elementIdContains != null || result.pagePath != null || result.pagePathPrefixes.isNotEmpty() ||
        result.itemExtraKey != null || result.recommendationCard) { "Activation filter must contain a bounded event predicate" }
    require(!result.recommendationCard || result.eventType in setOf(EventType.Impression, EventType.Interaction)) {
        "Recommendation cards require Impression or Interaction events"
    }
    return result
}

internal fun normalizeGoalActivation(activation: GoalActivation?, variationKeys: Set<String>): GoalActivation? {
    if (activation == null) return null
    require(activation.maxDelaySeconds?.let { it > 0 } != false) { "Activation maximum delay must be positive" }
    val default = activation.defaultFilter?.let(::normalizeEventFilter)
    val rules = activation.variationRules.map { rule ->
        val key = rule.variationKey.trim()
        require(key in variationKeys) { "Unknown activation variation '$key'" }
        when (rule.mode) {
            GoalActivationMode.EVENT -> require(rule.filter != null) { "EVENT activation requires a filter" }
            GoalActivationMode.INHERIT -> {
                require(default != null) { "INHERIT activation requires a default filter" }
                require(rule.filter == null) { "INHERIT activation cannot contain an override filter" }
            }
            GoalActivationMode.NOT_APPLICABLE -> require(rule.filter == null) { "NOT_APPLICABLE cannot contain a filter" }
        }
        rule.copy(variationKey = key, filter = rule.filter?.let(::normalizeEventFilter))
    }
    require(rules.map { it.variationKey }.distinct().size == rules.size) { "Duplicate activation variation rules" }
    // Explicit inheritance and an EVENT equal to the default have the same effective meaning as omission.
    return activation.copy(defaultFilter = default,
        variationRules = rules.filterNot { it.mode == GoalActivationMode.INHERIT ||
            (it.mode == GoalActivationMode.EVENT && it.filter == default) }.sortedBy { it.variationKey },
        applicationIds = normalizeApplicationIds(activation.applicationIds))
}

internal fun normalizeOutcomeScope(scope: GoalOutcomeScope?): GoalOutcomeScope? = scope?.let {
    require(it.maxDelaySeconds?.let { seconds -> seconds > 0 } != false) { "Outcome maximum delay must be positive" }
    it.copy(applicationIds = normalizeApplicationIds(it.applicationIds)).takeUnless { value -> value == GoalOutcomeScope() }
}
