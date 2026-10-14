package bosca.experimentation.jobs

import bosca.experimentation.configuration.ExperimentationConfig
import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.DestinationAttribution
import bosca.experimentation.model.GoalOutcomeScope
import bosca.serialization.UUID
import java.time.OffsetDateTime

/** Resolves selected target UUIDs and requires the exact later route in the same subject/session/app. */
internal fun buildDestinationAttributionQuery(
    config: ExperimentationConfig, experimentId: UUID, goal: ConversionGoal,
    cohortBatch: ActivatedCohortBatch, start: OffsetDateTime, cutoff: OffsetDateTime,
    excludedPrincipals: Collection<UUID>, excludedInstallations: Collection<String>,
): PerUserQuery {
    val cohort = buildSubjectCohortSql(config.safeAssignmentsTable, experimentId, excludedPrincipals, excludedInstallations, cohortBatch)
    val params = cohort.params.toMutableList()
    val format = { time: OffsetDateTime -> time.toLocalDateTime().toString().replace('T', ' ') }
    params.add(format(start)); params.add(format(cutoff))
    val conditions = mutableListOf("type = 'Interaction'", recommendationCardPredicate(false))
    val scope = (goal.outcomeScope ?: GoalOutcomeScope()).copy(recommendationCard = true)
    appendOutcomeEventFilter(scope, conditions, params)
    val scopePredicate = outcomeScopePredicate(scope, true, "c")
    params.add(format(start)); params.add(format(cutoff))
    return PerUserQuery("""
        WITH ${cohort.ctes}, card_candidates AS (
            SELECT $CONVERTING_SUBJECT AS client_id, $EVENT_DEDUPLICATION_KEY AS event_key,
                   created, context.session_id AS session_id, context.app_id AS app_id,
                   try_cast(element_at(element.content, 1).id AS UUID) AS target_id,
                   regexp_extract(element.id, '-rec-([^"/]+)$', 1) AS card_slug
            FROM ${config.safeEventsTable}
            WHERE created >= CAST(? AS TIMESTAMP) AND created <= CAST(? AS TIMESTAMP)
              AND ${conditions.joinToString(" AND ")}
        ), selected AS (
            SELECT DISTINCT subjects.subject_id, subjects.variation_key, c.event_key, c.created,
                   c.session_id, c.app_id, c.target_id, c.card_slug
            FROM card_candidates c JOIN eligible_subjects subjects ON subjects.client_id = c.client_id
            WHERE c.created > subjects.activated_at $scopePredicate
        ), targets AS (
            SELECT i.*, m.id AS resolved_id,
                   CASE json_extract_scalar(m.attributes, '$.type')
                     WHEN 'Article' THEN 'articles' WHEN 'Talk' THEN 'talks' WHEN 'Study' THEN 'studies'
                     WHEN 'Worship' THEN 'worship' WHEN 'Classroom' THEN 'classroom' WHEN 'Podcast' THEN 'podcasts'
                   END AS route_type,
                   nullif(i.card_slug, '') AS route_slug
            FROM selected i LEFT JOIN ${config.safePostgresCatalog}.public.metadata m ON m.id = i.target_id
        ), pages AS (
            SELECT $CONVERTING_SUBJECT AS client_id, created, context.session_id AS session_id,
                   context.app_id AS app_id, regexp_replace(page.path, '/+$', '') AS path
            FROM ${config.safeEventsTable}
            WHERE created >= CAST(? AS TIMESTAMP) AND created <= CAST(? AS TIMESTAMP)
              AND type = 'Impression' AND element.type = 'page'
        ), destinations AS (
            SELECT DISTINCT subjects.subject_id, p.created, p.session_id, p.app_id, p.path
            FROM pages p JOIN eligible_subjects subjects ON subjects.client_id = p.client_id
        ), matched AS (
            SELECT i.subject_id, i.variation_key, i.event_key,
                   i.resolved_id, i.route_type, i.route_slug, i.session_id,
                   min(p.created) AS destination_at
            FROM targets i LEFT JOIN destinations p ON p.subject_id = i.subject_id
                AND p.session_id = i.session_id AND p.app_id = i.app_id
                AND p.created > i.created AND p.created <= date_add('minute', 5, i.created)
                AND p.path = concat('/', i.route_type, '/', i.route_slug)
            GROUP BY i.subject_id, i.variation_key, i.event_key, i.resolved_id, i.route_type, i.route_slug, i.session_id
        )
        SELECT variation_key, count(DISTINCT subject_id) AS engaged_subjects,
               count(DISTINCT event_key) AS interactions,
               count(DISTINCT subject_id) FILTER (WHERE destination_at IS NOT NULL) AS attributed_subjects,
               count(DISTINCT event_key) FILTER (WHERE destination_at IS NOT NULL) AS attributed_interactions,
               count(DISTINCT event_key) FILTER (WHERE resolved_id IS NULL) AS unresolved_targets,
               count(DISTINCT event_key) FILTER (WHERE resolved_id IS NOT NULL AND route_type IS NULL) AS unsupported_types,
               count(DISTINCT event_key) FILTER (WHERE route_slug IS NULL) AS missing_slugs,
               count(DISTINCT event_key) FILTER (WHERE session_id IS NULL) AS missing_sessions,
               count(DISTINCT event_key) FILTER (WHERE resolved_id IS NOT NULL AND route_type IS NOT NULL
                   AND route_slug IS NOT NULL AND session_id IS NOT NULL AND destination_at IS NULL) AS unmatched_destinations
        FROM matched GROUP BY variation_key
    """.trimIndent(), params)
}

internal suspend fun queryDestinationAttribution(
    config: ExperimentationConfig, experimentId: UUID, goal: ConversionGoal, cohort: ActivatedCohort,
    start: OffsetDateTime, cutoff: OffsetDateTime, excludedPrincipals: Set<UUID>, excludedInstallations: Set<String>,
): Map<String, DestinationAttribution> {
    val out = mutableMapOf<String, DestinationAttribution>()
    for (batch in cohort.batches) {
        readGoalQuery(buildDestinationAttributionQuery(config, experimentId, goal, batch, start, cutoff, excludedPrincipals, excludedInstallations)) { row ->
            val key = row.getString("variation_key")
            val old = out[key] ?: DestinationAttribution()
            out[key] = DestinationAttribution(
                old.engagedSubjects + row.getLong("engaged_subjects"), old.interactions + row.getLong("interactions"),
                old.attributedSubjects + row.getLong("attributed_subjects"), old.attributedInteractions + row.getLong("attributed_interactions"),
                null, old.unresolvedTargets + row.getLong("unresolved_targets"), old.unsupportedTypes + row.getLong("unsupported_types"),
                old.missingSlugs + row.getLong("missing_slugs"), old.missingSessions + row.getLong("missing_sessions"),
                old.unmatchedDestinations + row.getLong("unmatched_destinations"))
        }
    }
    return out.mapValues { (_, value) -> value.copy(interval = wilsonInterval(value.attributedSubjects, value.engagedSubjects)) }
}
