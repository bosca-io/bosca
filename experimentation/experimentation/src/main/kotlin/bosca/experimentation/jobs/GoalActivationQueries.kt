package bosca.experimentation.jobs

import bosca.analytics.model.EventType
import bosca.experimentation.model.ExperimentActivationFilter
import bosca.experimentation.model.GoalOutcomeScope

/** Semantic card events exclude headings and require the selected target's stable content UUID. */
internal fun recommendationCardPredicate(visibility: Boolean): String = buildString {
    append("regexp_like(element.id, '-rec-.+') AND try_cast(element_at(element.content, 1).id AS UUID) IS NOT NULL")
    if (visibility) {
        append(" AND try_cast(json_extract_scalar(try(json_parse(element.extras)), '$.visibility_threshold') AS DOUBLE) >= 0.5")
        append(" AND try_cast(json_extract_scalar(try(json_parse(element.extras)), '$.visible_ms') AS DOUBLE) >= 1000")
    }
}

internal fun appendExtendedEventFilter(
    filter: ExperimentActivationFilter, conditions: MutableList<String>, params: MutableList<String>,
) {
    filter.elementIdContains?.let { conditions.add("strpos(element.id, ?) > 0"); params.add(it) }
    if (filter.applicationIds.isNotEmpty()) {
        conditions.add("context.app_id IN (${filter.applicationIds.joinToString { "?" }})")
        params.addAll(filter.applicationIds)
    }
    if (filter.recommendationCard) conditions.add(recommendationCardPredicate(filter.eventType == EventType.Impression))
}

internal fun appendOutcomeEventFilter(
    scope: GoalOutcomeScope?, conditions: MutableList<String>, params: MutableList<String>,
) {
    if (scope == null) return
    if (scope.applicationIds.isNotEmpty()) {
        conditions.add("context.app_id IN (${scope.applicationIds.joinToString { "?" }})")
        params.addAll(scope.applicationIds)
    }
    if (scope.recommendationCard) {
        conditions.add("type = 'Interaction'")
        conditions.add(recommendationCardPredicate(false))
    }
}

internal fun outcomeScopePredicate(
    scope: GoalOutcomeScope?, hasActivation: Boolean, alias: String, nestedContext: Boolean = false,
): String {
    if (scope == null) return ""
    val context = if (nestedContext) "$alias.context" else alias
    val conditions = mutableListOf<String>()
    if (scope.applicationIds.isEmpty() && hasActivation) conditions.add("$context.app_id = subjects.app_id")
    if (scope.sameSession) {
        require(hasActivation) { "Same-session outcomes require captured experiment activation" }
        conditions.add("$context.session_id = subjects.session_id")
    }
    scope.maxDelaySeconds?.let {
        require(it > 0) { "Maximum delay must be positive" }
        conditions.add("$alias.created <= date_add('second', $it, subjects.${if (hasActivation) "activated_at" else "assigned_at"})")
    }
    return conditions.joinToString("", prefix = "") { " AND $it" }
}

/** One bounded rule scan against the frozen eligible cohort, including rejected application events. */
internal fun buildGoalActivationQuery(
    eventsTable: String, assignmentsTable: String, experimentId: bosca.serialization.UUID,
    filter: ExperimentActivationFilter, activation: bosca.experimentation.model.GoalActivation,
    cohortBatch: ActivatedCohortBatch, start: java.time.OffsetDateTime, cutoff: java.time.OffsetDateTime,
    excludedPrincipals: Collection<bosca.serialization.UUID>, excludedInstallations: Collection<String>,
): PerUserQuery {
    val cohort = buildSubjectCohortSql(assignmentsTable, experimentId, excludedPrincipals, excludedInstallations, cohortBatch)
    val params = cohort.params.toMutableList()
    val conditions = activationEventConditions(filter.copy(applicationIds = emptyList()), start, cutoff, params)
    val apps = filter.applicationIds.ifEmpty { activation.applicationIds }
    val appMatch = if (apps.isEmpty()) "c.app_id = subjects.app_id" else {
        params.addAll(apps)
        "c.app_id IN (${apps.joinToString { "?" }})"
    }
    val sessionMatch = if (activation.sameSession) " AND c.session_id = subjects.session_id" else ""
    val delay = activation.maxDelaySeconds?.let {
        require(it > 0)
        " AND c.created <= date_add('second', $it, subjects.activated_at)"
    }.orEmpty()
    return PerUserQuery("""
        WITH ${cohort.ctes}, candidates AS (
            SELECT $CONVERTING_SUBJECT AS client_id, $EVENT_DEDUPLICATION_KEY AS event_key,
                   created, context.app_id AS app_id, context.session_id AS session_id
            FROM $eventsTable WHERE ${conditions.joinToString(" AND ")}
        ), scoped AS (
            SELECT subjects.subject_id, subjects.variation_key, c.*,
                   coalesce($appMatch, false) AS in_application
            FROM candidates c JOIN eligible_subjects subjects ON subjects.client_id = c.client_id
            WHERE c.created > subjects.activated_at $sessionMatch $delay
        )
        SELECT CAST(subject_id AS VARCHAR) AS subject_id, variation_key,
               CAST(min(created) FILTER (WHERE in_application) AS VARCHAR) AS activated_at,
               min_by(app_id, ROW(created, event_key)) FILTER (WHERE in_application) AS app_id,
               min_by(session_id, ROW(created, event_key)) FILTER (WHERE in_application) AS session_id,
               count(DISTINCT event_key) FILTER (WHERE in_application) AS event_count,
               count(*) - count(DISTINCT event_key) AS duplicates,
               count(DISTINCT event_key) FILTER (WHERE NOT in_application) AS outside_application,
               count(DISTINCT event_key) FILTER (WHERE app_id IS NULL) AS missing_application
        FROM scoped GROUP BY subject_id, variation_key
    """.trimIndent(), params)
}
