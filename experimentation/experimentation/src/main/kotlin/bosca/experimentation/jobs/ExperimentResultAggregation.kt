package bosca.experimentation.jobs

import bosca.analytics.model.EventType
import bosca.analytics.model.aggregationEventTypePredicateValue
import bosca.db.ConnectionPool
import bosca.db.transaction
import bosca.db.use
import bosca.di.provide
import bosca.experimentation.isValidItemExtraKey
import bosca.experimentation.configuration.ExperimentationConfig
import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.ExperimentActivationFilter
import bosca.experimentation.model.ExperimentResult
import bosca.experimentation.model.GoalMetricType
import bosca.experimentation.model.TargetingRule
import bosca.experimentation.model.Variation
import bosca.experimentation.repository.AssignmentRepository
import bosca.experimentation.repository.ExperimentRepository
import bosca.experimentation.repository.ExperimentResultRepository
import bosca.experimentation.repository.FeatureFlagRepository
import bosca.experimentation.service.ExperimentService
import bosca.observability.ErrorCapture
import bosca.serialization.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.apache.commons.math3.stat.descriptive.StatisticalSummaryValues
import org.apache.commons.math3.stat.inference.ChiSquareTest
import org.apache.commons.math3.stat.inference.TTest
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private val log = LoggerFactory.getLogger("bosca.experimentation.jobs.ExperimentResultAggregation")

/**
 * The converting subject's stable identity, used to attribute analytics events back to a variation
 * assignment. The namespace prefix is part of the value so a UUID-form installation id cannot be
 * mistaken for a principal and identical text in the two namespaces remains two distinct subjects.
 * Authenticated identity takes precedence over installation identity. This is deliberately NOT the
 * events table's top-level `client_id`, which is a per-event dedup key.
 */
private const val CONVERTING_SUBJECT =
    "CASE WHEN context.user_id IS NOT NULL " +
        "THEN CONCAT('principal:', context.user_id) " +
        "ELSE CONCAT('installation:', context.device.installation_id) END"

/** Browser event ids are stable across delivery retries; the Iceberg id covers clients without one. */
private const val EVENT_DEDUPLICATION_KEY = "coalesce(client_id, CAST(id AS VARCHAR))"

/** Mirrors the browser and BML analytics session timeout. */
private const val SESSION_INACTIVITY_TIMEOUT_MILLISECONDS = 300_000L

private val SCROLL_MEASUREMENT_ELEMENT_TYPES = setOf("scroll_depth", "scroll_max_depth")

private fun activityEventPredicate(alias: String = ""): String {
    val qualifier = alias.takeIf { it.isNotEmpty() }?.let { "$it." }.orEmpty()
    return "(${qualifier}type <> 'Impression' OR ${qualifier}element.type = 'page')"
}

/** User activity that can extend a session; server lifecycle records do not. */
private fun sessionActivityEventPredicate(alias: String = ""): String {
    val qualifier = alias.takeIf { it.isNotEmpty() }?.let { "$it." }.orEmpty()
    return "(${qualifier}type IN ('Session', 'Interaction', 'Completion') " +
        "OR (${qualifier}type = 'Impression' AND ${qualifier}element.type IN ('page', 'media_playback')))"
}

private fun scrollMeasurementPredicate(): String =
    "coalesce(element.type, '') in ('scroll_depth', 'scroll_max_depth')"

private fun countableBehaviorEventPredicate(): String =
    "(${activityEventPredicate()} AND NOT (type = 'Interaction' AND ${scrollMeasurementPredicate()}))"

private fun isExplicitScrollMeasurement(elementType: String?): Boolean =
    elementType in SCROLL_MEASUREMENT_ELEMENT_TYPES

/**
 * Returns the exact string the aggregation job binds into the Trino
 * `type = ?` predicate for a conversion goal's [bosca.analytics.model.EventType].
 *
 * Extracted as a top-level function so the
 * `ConversionGoalEventTypeSerializationTest` regression guard can call
 * the same code the production aggregation calls — without having to
 * stand up a full ExperimentResultAggregation invocation. Anyone changing
 * `countConversions` to bind a different form (e.g. `it.serialName`)
 * must also change this helper, which the test will then catch.
 */

/**
 * Aggregates experiment conversion metrics from real data sources and computes
 * statistical significance.
 *
 * For each variation/goal combination on the experiment's attached rule:
 * 1. Counts raw assignments and, when configured, subjects whose activation event was observed
 * 2. Counts conversions by querying matching analytics events in Trino
 * 3. Computes conversion rates, chi-squared significance, and lift over control
 *
 * A configured account exclusion removes the account's entire assignment row, including
 * activity attributed through that assignment's installation id, from every step above.
 *
 * Every comparison uses [bosca.experimentation.model.Experiment.controlVariationKey].
 * A missing or uninvolved control is a data-integrity failure; aggregation never
 * infers a replacement baseline.
 */
suspend fun aggregateExperimentResults(experimentId: UUID) {
    val experimentService: ExperimentService = provide()
    val experimentRepository: ExperimentRepository = provide()
    val flagRepository: FeatureFlagRepository = provide()
    val resultRepository: ExperimentResultRepository = provide()
    val assignmentRepository: AssignmentRepository = provide()
    val config: ExperimentationConfig = provide()
    val json: Json = provide()
    val errorCapture: ErrorCapture = provide()

    log.info("Aggregating results for experiment: {}", experimentId)

    val experiment = experimentRepository.getById(experimentId)
    if (experiment == null) {
        log.warn("Experiment not found: {} — skipping aggregation.", experimentId)
        return
    }
    val flag = flagRepository.getById(experiment.featureFlagId)
    if (flag == null) {
        log.warn("Feature flag not found for experiment: {}", experimentId)
        return
    }

    val variations = parseVariations(json, flag.variations)
    val goals = experimentService.getConversionGoals(experimentId)
    if (variations.isEmpty() || goals.isEmpty()) {
        log.info("No variations or goals defined for experiment: {}", experimentId)
        return
    }

    // Determine which variations the experiment's attached rule is splitting across.
    // If the experiment is on the default-variation path, all flag variations are eligible.
    val rules = parseRules(json, flag.targetingRules)
    val attachedRule = experiment.targetingRuleId?.let { id -> rules.find { it.id == id } }
    val variationKeys = if (attachedRule != null) {
        attachedRule.rollout.variationWeights.map { it.variationKey }.distinct()
    } else {
        variations.map { it.key }
    }
    val involvedVariations = variations.filter { it.key in variationKeys }
    if (involvedVariations.size < 2) {
        log.info("Experiment {} has fewer than 2 distinct variations to compare", experimentId)
    }

    // Sorting is presentation/determinism only; it has no control semantics.
    val sortedVariations = involvedVariations.sortedBy { it.key }
    val controlKey = experiment.controlVariationKey
    require(sortedVariations.any { it.key == controlKey }) {
        "Experiment $experimentId control variation '$controlKey' is not involved in the experiment"
    }

    val excludedPrincipalIds = experiment.excludedPrincipalIds.toSet()
    val sortedExcludedPrincipalIds = excludedPrincipalIds.sortedBy(UUID::toString)
    // Experiment assignments may remain anonymous when their installation was already linked to
    // a principal that owns another assignment row. The per-flag assignment retains that owner,
    // including after sign-out, so resolve the complete exclusion set once for this run.
    val excludedInstallationIds = if (sortedExcludedPrincipalIds.isEmpty()) {
        emptySet()
    } else {
        assignmentRepository.getExcludedInstallationIds(
            experimentId = experimentId,
            excludedPrincipalIds = sortedExcludedPrincipalIds,
        ).toSet()
    }
    val sortedExcludedInstallationIds = excludedInstallationIds.sorted()
    val effectiveStartDate = experiment.startDate ?: experiment.created
    val aggregationTime = OffsetDateTime.now(java.time.ZoneOffset.UTC)
    val effectiveEndDate = minOf(experiment.endDate ?: aggregationTime, aggregationTime)
    // All queries use the same event-time cutoff. Late-arriving warehouse events can still
    // change a later aggregation of this window.
    val assignmentsByVariation = mutableMapOf<String, Long>()
    for (variation in sortedVariations) {
        assignmentsByVariation[variation.key] = assignmentRepository.countEligibleByVariation(
            experimentId,
            variation.key,
            sortedExcludedPrincipalIds,
            sortedExcludedInstallationIds,
            effectiveEndDate,
        )
    }
    val activatedCohort = experiment.activationFilter?.let { filter ->
        queryActivatedCohort(
            config = config,
            experimentId = experimentId,
            activationFilter = filter,
            startDate = effectiveStartDate,
            endDate = effectiveEndDate,
            excludedPrincipalIds = excludedPrincipalIds,
            excludedInstallationIds = excludedInstallationIds,
        )
    }
    val impressionsByVariation = if (activatedCohort == null) {
        assignmentsByVariation
    } else {
        sortedVariations.associate { variation ->
            variation.key to (activatedCohort.countsByVariation[variation.key] ?: 0L)
        }
    }
    val conversionsByVariationGoal = countConversions(
        experimentId, sortedVariations, goals, config,
        startDate = effectiveStartDate,
        endDate = effectiveEndDate,
        excludedPrincipalIds = excludedPrincipalIds,
        excludedInstallationIds = excludedInstallationIds,
        activatedCohort = activatedCohort,
        errorCapture = errorCapture,
    )

    val controlImpressions = impressionsByVariation[controlKey] ?: 0L
    val controlStatsByGoal = goals.associate { goal ->
        goal.id to (conversionsByVariationGoal[controlKey to goal.id] ?: GoalCountStats.ZERO)
    }

    // When no prior is configured, fall back to the default (uniform Beta / flat Normal).
    // When the stored prior is corrupted (parseBayesianPrior returns null), leave
    // bayesianPrior null so Bayesian columns are skipped rather than producing results
    // with a silently different prior than the operator configured.
    val bayesianPrior: bosca.experimentation.model.BayesianPrior? = if (experiment.bayesianPrior != null) {
        val parsed = parseBayesianPrior(json, experiment.bayesianPrior!!)
        if (parsed == null) {
            val err = IllegalStateException("Corrupted bayesian_prior for experiment $experimentId")
            errorCapture.capture(err, null, mapOf("experimentId" to experimentId.toString()))
        }
        parsed
    } else {
        bosca.experimentation.model.BayesianPrior.DEFAULT
    }
    // A seed derived from the experiment id keeps the Bayesian Monte
    // Carlo reproducible across aggregation runs for the same
    // experiment without needing to thread the report id (which
    // doesn't exist yet at aggregation time) all the way through.
    val bayesianSeed = seedFromExperimentId(experimentId)

    // Phase 6 — CUPED variance reduction. For every EVENT_COUNT goal
    // with a covariate configured, run a second per-user query over
    // the pre-period window, join with the post-period per-user
    // counts, and compute the OLS-adjusted mean/variance per arm.
    // Result is keyed by (goalId → variationKey → CupedResult) so
    // the main loop above can look up the adjustment per row without
    // a second pass through the data.
    val cupedResultsByGoalArm: Map<UUID, Map<String, CupedResult>> = computeCupedAdjustments(
        experimentId = experimentId,
        experiment = experiment,
        goals = goals,
        sortedVariations = sortedVariations,
        assignmentRepository = assignmentRepository,
        config = config,
        json = json,
        aggregationEndDate = effectiveEndDate,
        assignmentsByVariation = impressionsByVariation,
        activatedCohort = activatedCohort,
        excludedInstallationIds = excludedInstallationIds,
    )

    val results = mutableListOf<ExperimentResult>()
    for (variation in sortedVariations) {
        val assignments = assignmentsByVariation[variation.key] ?: 0L
        val impressions = impressionsByVariation[variation.key] ?: 0L
        for (goal in goals) {
            val stats = conversionsByVariationGoal[variation.key to goal.id] ?: GoalCountStats.ZERO
            val ctrlStats = controlStatsByGoal[goal.id] ?: GoalCountStats.ZERO

            val base = when (goal.metricType) {
                GoalMetricType.UNIQUE_CONVERSION -> buildUniqueConversionResult(
                    experimentId, variation.key, goal.id,
                    impressions, controlImpressions,
                    stats, ctrlStats,
                    isControl = variation.key == controlKey,
                    assignments = assignments,
                )
                GoalMetricType.EVENT_COUNT -> buildEventCountResult(
                    experimentId, variation.key, goal.id,
                    impressions, controlImpressions,
                    stats, ctrlStats,
                    isControl = variation.key == controlKey,
                    assignments = assignments,
                )
                GoalMetricType.SESSION_DURATION -> buildSessionDurationResult(
                    experimentId, variation.key, goal.id,
                    impressions, stats, ctrlStats,
                    isControl = variation.key == controlKey,
                    assignments = assignments,
                )
            }
            // Populate the Bayesian columns when the experiment is
            // configured for Bayesian analysis. The frequentist
            // columns on the row remain unchanged — both methods
            // share one schema, the analyzer picks which column to
            // read based on experiment.analysisMethod.
            val bayesianAugmented = if (experiment.analysisMethod == bosca.experimentation.model.AnalysisMethod.BAYESIAN
                && variation.key != controlKey
                && bayesianPrior != null
            ) {
                augmentWithBayesian(
                    base,
                    goal = goal,
                    controlImpressions = controlImpressions,
                    controlStats = ctrlStats,
                    treatmentImpressions = impressions,
                    treatmentStats = stats,
                    prior = bayesianPrior,
                    seed = bayesianSeed,
                )
            } else base
            // CUPED adjustment is orthogonal to Bayesian vs
            // frequentist — the adjusted columns tighten the
            // frequentist CI and can also inform a future Bayesian-
            // on-CUPED-residuals path. Populated only when the goal
            // has a covariate AND the metric type supports CUPED
            // (EVENT_COUNT, first pass). The map is keyed per goal
            // per arm and precomputed once per goal below.
            val cupedResultsByArm = cupedResultsByGoalArm[goal.id]
            val cupedForArm = cupedResultsByArm?.get(variation.key)
            val result = if (cupedForArm != null && cupedForArm.applied) {
                bayesianAugmented.copy(
                    adjustedMean = cupedForArm.adjustedMean,
                    adjustedVariance = cupedForArm.adjustedVariance,
                )
            } else bayesianAugmented
            results.add(result)
        }
    }

    transaction {
        val current = experimentRepository.getByIdForUpdate(experimentId)
        check(current?.analysisRevision == experiment.analysisRevision) {
            "Experiment $experimentId changed during aggregation; retry with its current definition"
        }
        for (result in results) resultRepository.upsert(result)
    }

    log.info("Results aggregated for experiment: {}", experimentId)
}

private fun parseVariations(json: Json, variationsJson: JsonElement): List<Variation> =
    json.decodeFromJsonElement(ListSerializer(Variation.serializer()), variationsJson)

/**
 * Runs the CUPED adjustment step for every EVENT_COUNT goal with a
 * covariate configured. Returns a map keyed by goal id → variation
 * key → [CupedResult]. Goals without a covariate (or with a metric
 * type other than EVENT_COUNT) do not appear in the result at all,
 * so the caller's lookup returns null for them and the main loop
 * leaves the adjusted columns untouched.
 *
 * For each applicable goal the function:
 *
 *   1. Runs a per-user query against the analytics events table
 *      for the goal's OWN filter shape over the experiment window
 *      — the post-period outcome.
 *   2. Runs a second per-user query for the covariate's filter
 *      shape over the pre-period window
 *      `[experimentStart - lookbackWindow, experimentStart)`.
 *   3. Loads the variation assignment for each user that appears
 *      in either map.
 *   4. Groups the per-user values by arm, hands them to
 *      [adjustAllArms] which fits OLS and returns
 *      [CupedResult] per arm.
 *
 * Query failures propagate before any result rows are written. A failed CUPED query must
 * not silently replace the configured analysis with an unadjusted result.
 */
private suspend fun computeCupedAdjustments(
    experimentId: UUID,
    experiment: bosca.experimentation.model.Experiment,
    goals: List<ConversionGoal>,
    sortedVariations: List<Variation>,
    assignmentRepository: AssignmentRepository,
    config: ExperimentationConfig,
    json: Json,
    aggregationEndDate: OffsetDateTime,
    assignmentsByVariation: Map<String, Long>,
    activatedCohort: ActivatedCohort?,
    excludedInstallationIds: Set<String>,
): Map<UUID, Map<String, CupedResult>> {
    val applicable = goals.filter {
        it.metricType == GoalMetricType.EVENT_COUNT && it.cupedCovariate != null
    }
    if (applicable.isEmpty()) return emptyMap()

    val trinoPool: ConnectionPool = provide(name = "trino-readonly")
    val effectiveStartDate = experiment.startDate ?: experiment.created
    val effectiveEndDate = aggregationEndDate

    val out = mutableMapOf<UUID, Map<String, CupedResult>>()

    for (goal in applicable) {
        val covariateElement = goal.cupedCovariate ?: continue
        val covariate = try {
            json.decodeFromJsonElement(
                bosca.experimentation.model.CupedCovariate.serializer(),
                covariateElement,
            )
        } catch (e: SerializationException) {
            log.warn(
                "Invalid cupedCovariate on goal {} — skipping CUPED adjustment: {}",
                goal.id, e.message,
            )
            continue
        }
        val lookback = try {
            java.time.Duration.parse(covariate.lookbackWindow)
        } catch (e: java.time.format.DateTimeParseException) {
            log.warn(
                "Invalid lookbackWindow '{}' on goal {} — skipping CUPED adjustment",
                covariate.lookbackWindow, goal.id,
            )
            continue
        }
        val preStart = effectiveStartDate.minus(lookback)
        val preEnd = effectiveStartDate

        val outcomes = queryAttributedGoalEventCounts(
            trinoPool = trinoPool,
            config = config,
            experimentId = experimentId,
            goal = goal,
            windowStart = effectiveStartDate,
            windowEnd = effectiveEndDate,
            excludedPrincipalIds = experiment.excludedPrincipalIds,
            excludedInstallationIds = excludedInstallationIds,
            activatedCohort = activatedCohort,
        )
        val covariatesByIdentity = queryPerUserEventCounts(
            trinoPool = trinoPool,
            config = config,
            eventType = covariate.eventType ?: goal.eventType,
            elementType = covariate.elementType ?: goal.elementType,
            elementId = covariate.elementId ?: goal.elementId,
            pagePath = covariate.pagePath ?: goal.pagePath,
            pagePathPrefixes = if (covariate.pagePath == null) goal.pagePathPrefixes else emptyList(),
            windowStart = preStart,
            windowEnd = preEnd,
        )

        // Pre-period covariates intentionally precede assigned_at, so resolve their analytics
        // identities through PostgreSQL, then merge principal + installation activity under the
        // same assignment id. Post-period outcomes were already assignment-joined in Trino.
        val assignmentByIdentity = loadAssignmentsForConverters(
            assignmentRepository = assignmentRepository,
            experimentId = experimentId,
            clientIds = covariatesByIdentity.keys,
            excludedPrincipalIds = experiment.excludedPrincipalIds.toSet(),
            excludedInstallationIds = excludedInstallationIds,
            assignedBefore = effectiveEndDate,
        )
        val eligibleAssignmentByIdentity = if (activatedCohort == null) {
            assignmentByIdentity
        } else {
            assignmentByIdentity.filterValues { it.subjectId in activatedCohort.subjectIds }
        }
        val covariatesBySubject = mergeCountsByAssignment(covariatesByIdentity, eligibleAssignmentByIdentity)
        val variationBySubject = outcomes.variationBySubject.toMutableMap()
        for (assignment in eligibleAssignmentByIdentity.values) {
            variationBySubject[assignment.subjectId] = assignment.variationKey
        }

        out[goal.id] = cupedAdjustForGoal(
            sortedVariations = sortedVariations,
            outcomesByClient = outcomes.countsBySubject,
            covariatesByClient = covariatesBySubject,
            variationByClient = variationBySubject,
            assignmentsByVariation = assignmentsByVariation,
        )
    }
    return out
}

/**
 * Pure CUPED adjustment for a single goal, given the per-user
 * outcome and covariate maps and a clientId → arm assignment.
 * Splits each map by arm and delegates to [adjustAllArms].
 *
 * Extracted as a top-level testable function so the join logic —
 * which determines whether a user with a pre-period covariate but
 * no post-period outcome (or vice versa) contributes to the OLS
 * fit — can be exercised without standing up Trino. The Trino-
 * fetching wrapper [computeCupedAdjustments] is the same shape but
 * with two real queries replacing the per-test fake maps.
 */
internal fun cupedAdjustForGoal(
    sortedVariations: List<Variation>,
    outcomesByClient: Map<String, Long>,
    covariatesByClient: Map<String, Long>,
    variationByClient: Map<String, String>,
    assignmentsByVariation: Map<String, Long> = variationByClient.values.groupingBy { it }.eachCount().mapValues { it.value.toLong() },
): Map<String, CupedResult> {
    val armsOutcomes = sortedVariations.associate { v -> v.key to HashMap<String, Double>() }.toMutableMap()
    val armsCovariates = sortedVariations.associate { v -> v.key to HashMap<String, Double>() }.toMutableMap()
    for ((clientId, arm) in variationByClient) {
        val y = (outcomesByClient[clientId] ?: 0L).toDouble()
        val x = (covariatesByClient[clientId] ?: 0L).toDouble()
        armsOutcomes[arm]?.put(clientId, y)
        armsCovariates[arm]?.put(clientId, x)
    }
    return adjustAllArms(
        armsOutcomes = armsOutcomes.mapValues { it.value.toMap() },
        armsCovariates = armsCovariates.mapValues { it.value.toMap() },
        implicitZeroCounts = sortedVariations.associate { variation ->
            val missing = (assignmentsByVariation[variation.key] ?: 0L) - armsOutcomes.getValue(variation.key).size
            check(missing >= 0L) { "CUPED observations exceed eligible assignments for ${variation.key}" }
            variation.key to missing
        },
    )
}

/**
 * The SQL + bound parameter list produced by
 * [buildPerUserEventCountsQuery]. Kept as a plain data class so the
 * unit test can inspect both sides of the prepared statement
 * without reaching into a mocked JDBC PreparedStatement.
 */
internal data class PerUserQuery(val sql: String, val params: List<String>)

private data class SubjectCohortSql(
    val ctes: String,
    val params: List<String>,
    val hasActivation: Boolean,
)

/** Builds assignment identities and joins the captured activation cohort for outcome queries. */
private fun buildSubjectCohortSql(
    assignmentsTable: String,
    experimentId: UUID,
    excludedPrincipalIds: Collection<UUID>,
    excludedInstallationIds: Collection<String>,
    activatedCohort: ActivatedCohortBatch? = null,
): SubjectCohortSql {
    val excludedIds = excludedPrincipalIds.distinct().sortedBy(UUID::toString)
    val excludedInstallations = excludedInstallationIds.distinct().sorted()
    val principalExclusion = if (excludedIds.isEmpty()) {
        ""
    } else {
        val placeholders = excludedIds.joinToString(", ") { "CAST(? AS UUID)" }
        " AND (principal_id IS NULL OR principal_id NOT IN ($placeholders))"
    }
    val installationExclusion = if (excludedInstallations.isEmpty()) {
        ""
    } else {
        val placeholders = excludedInstallations.joinToString(", ") { "?" }
        " AND (installation_id IS NULL OR installation_id NOT IN ($placeholders))"
    }
    val params = mutableListOf(experimentId.toString())
    params.addAll(excludedIds.map(UUID::toString))
    params.addAll(excludedInstallations)
    val base = """
        assignment_rows AS (
            SELECT id AS subject_id, principal_id, installation_id, variation_key,
                   CAST(assigned_at AT TIME ZONE 'UTC' AS TIMESTAMP) AS assigned_at
            FROM $assignmentsTable
            WHERE experiment_id = CAST(? AS UUID)$principalExclusion$installationExclusion
        ), experiment_subjects AS (
            SELECT subject_id,
                   CONCAT('principal:', CAST(principal_id AS VARCHAR)) AS client_id,
                   variation_key,
                   installation_id,
                   assigned_at
            FROM assignment_rows
            WHERE principal_id IS NOT NULL
            UNION ALL
            SELECT subject_id,
                   CONCAT('installation:', installation_id) AS client_id,
                   variation_key,
                   installation_id,
                   assigned_at
            FROM assignment_rows
            WHERE installation_id IS NOT NULL
        )
    """.trimIndent().trimStart()
    if (activatedCohort == null) {
        return SubjectCohortSql(base, params, hasActivation = false)
    }

    params.add(activatedCohort.encodedSubjects)
    val ctes = """
        $base, activated_subjects AS (
            SELECT CAST(subject_id AS UUID) AS subject_id, variation_key,
                   CAST(activated_at AS TIMESTAMP(6)) AS activated_at
            FROM UNNEST(CAST(json_parse(?) AS ARRAY(ROW(
                subject_id VARCHAR, variation_key VARCHAR, activated_at VARCHAR
            )))) AS frozen(subject_id, variation_key, activated_at)
        ), eligible_subjects AS (
            SELECT subjects.subject_id, subjects.client_id, subjects.variation_key,
                   subjects.installation_id, activated.activated_at
            FROM experiment_subjects subjects
            INNER JOIN activated_subjects activated
              ON activated.subject_id = subjects.subject_id
             AND activated.variation_key = subjects.variation_key
        )
    """.trimIndent().trimStart()
    return SubjectCohortSql(ctes, params, hasActivation = true)
}

/** Returns one row per activated assignment, capturing its first activation for the entire run. */
internal fun buildActivatedCohortQuery(
    eventsTable: String,
    assignmentsTable: String,
    experimentId: UUID,
    activationFilter: ExperimentActivationFilter,
    startDate: OffsetDateTime?,
    endDate: OffsetDateTime?,
    excludedPrincipalIds: Collection<UUID> = emptyList(),
    excludedInstallationIds: Collection<String> = emptyList(),
): PerUserQuery {
    val cohort = buildSubjectCohortSql(
        assignmentsTable, experimentId, excludedPrincipalIds, excludedInstallationIds,
    )
    val params = cohort.params.toMutableList()

    val conditions = mutableListOf<String>()
    activationFilter.eventType?.let {
        conditions.add("type = ?")
        params.add(aggregationEventTypePredicateValue(it))
    }
    if (activationFilter.eventType == EventType.Impression && activationFilter.elementType != "media_playback") {
        conditions.add("coalesce(element.type, '') <> 'media_playback'")
    }
    if (activationFilter.eventType == EventType.Interaction &&
        !isExplicitScrollMeasurement(activationFilter.elementType)
    ) {
        conditions.add("NOT ${scrollMeasurementPredicate()}")
    }
    if (activationFilter.eventType == null && !isExplicitScrollMeasurement(activationFilter.elementType)) {
        conditions.add(countableBehaviorEventPredicate())
    }
    activationFilter.elementType?.let {
        conditions.add("element.type = ?")
        params.add(it)
    }
    activationFilter.elementId?.let {
        conditions.add("element.id = ?")
        params.add(it)
    }
    val pagePredicates = mutableListOf<String>()
    activationFilter.pagePath?.let {
        pagePredicates.add("page.path = ?")
        params.add(it)
    }
    for (prefix in activationFilter.pagePathPrefixes) {
        pagePredicates.add("starts_with(page.path, ?)")
        params.add(prefix)
    }
    if (pagePredicates.isNotEmpty()) {
        conditions.add("(${pagePredicates.joinToString(" OR ")})")
    }
    activationFilter.itemExtraKey?.let { key ->
        require(isValidItemExtraKey(key)) { "Invalid activation itemExtraKey '$key'" }
        conditions.add("cardinality(element.content) > 0")
        val path = "$.${key}"
        val value = activationFilter.itemExtraValue
        if (value == null) {
            conditions.add("json_extract_scalar(try(json_parse(element.extras)), '$path') IS NOT NULL")
        } else {
            conditions.add("json_extract_scalar(try(json_parse(element.extras)), '$path') = ?")
            params.add(value)
        }
    } ?: require(activationFilter.itemExtraValue == null) {
        "Activation itemExtraValue requires itemExtraKey"
    }
    startDate?.let {
        conditions.add("created >= CAST(? AS TIMESTAMP)")
        params.add(it.atZoneSameInstant(java.time.ZoneOffset.UTC).format(TRINO_TIMESTAMP_FORMAT))
    }
    endDate?.let {
        conditions.add("created <= CAST(? AS TIMESTAMP)")
        params.add(it.atZoneSameInstant(java.time.ZoneOffset.UTC).format(TRINO_TIMESTAMP_FORMAT))
    }
    require(conditions.isNotEmpty()) { "Activation filter must contain a narrowing predicate" }
    val activationWhere = conditions.joinToString(" AND ")
    val ctes = """
        WITH ${cohort.ctes}, activation_candidates AS (
            SELECT $CONVERTING_SUBJECT AS client_id, created
            FROM $eventsTable
            WHERE $activationWhere
        ), activation_matches AS (
            SELECT subjects.subject_id, subjects.variation_key, candidates.created
            FROM activation_candidates candidates
            INNER JOIN experiment_subjects subjects ON subjects.client_id = candidates.client_id
            WHERE candidates.created >= subjects.assigned_at
        ), activated_subjects AS (
            SELECT subject_id, variation_key, min(created) AS activated_at
            FROM activation_matches
            GROUP BY subject_id, variation_key
        )
        SELECT CAST(subject_id AS VARCHAR) AS client_id, variation_key,
               CAST(activated_at AS VARCHAR) AS activated_at FROM activated_subjects
    """.trimIndent().trimStart()
    return PerUserQuery(ctes, params)
}

/**
 * Builds the conversion-count query for a single goal. Returns
 * `null` when the combination of filter + time-window would
 * produce an unbounded table scan; the caller skips the goal and
 * logs. Pure — no JDBC, no provide(), no logging — so the SQL
 * construction can be pinned by unit tests independently of the
 * Trino connection pool.
 *
 * Branches on [goal.metricType]:
 *  - `UNIQUE_CONVERSION` → `SELECT DISTINCT client_id` (each row
 *    contributes 1 to its variation's stats).
 *  - `EVENT_COUNT` → `SELECT client_id, count(DISTINCT event_key) AS cnt GROUP BY
 *    client_id` (each logical browser event contributes once to its variation's
 *    sum and `count²` to its sum of squares).
 */
internal fun buildConversionCountQuery(
    eventsTable: String,
    assignmentsTable: String,
    experimentId: UUID,
    goal: ConversionGoal,
    startDate: OffsetDateTime?,
    endDate: OffsetDateTime?,
    excludedPrincipalIds: Collection<UUID> = emptyList(),
    excludedInstallationIds: Collection<String> = emptyList(),
    activatedCohort: ActivatedCohortBatch? = null,
): PerUserQuery? {
    val conditions = mutableListOf<String>()
    val eventParams = mutableListOf<String>()
    // goal.eventType?.name (capitalized, e.g. "Interaction") is the
    // form IcebergEventsToRecordTransform writes into the events
    // table. Binding the enum's `.name` — rather than its
    // @SerialName or a free-form string — keeps the goal filter in
    // lock-step with the storage format. When eventType is null the
    // other filters can match events across types; passive impressions and
    // scroll-depth milestones are excluded below unless the goal explicitly
    // selects a scroll measurement element type.
    goal.eventType?.let {
        conditions.add("type = ?")
        eventParams.add(aggregationEventTypePredicateValue(it))
    }
    if (goal.eventType == EventType.Impression && goal.elementType != "media_playback") {
        conditions.add("coalesce(element.type, '') <> 'media_playback'")
    }
    if (goal.eventType == EventType.Interaction && !isExplicitScrollMeasurement(goal.elementType)) {
        conditions.add("NOT ${scrollMeasurementPredicate()}")
    }
    goal.elementType?.let {
        conditions.add("element.type = ?")
        eventParams.add(it)
    }
    goal.elementId?.let {
        conditions.add("element.id = ?")
        eventParams.add(it)
    }
    val pagePredicates = mutableListOf<String>()
    goal.pagePath?.let {
        pagePredicates.add("page.path = ?")
        eventParams.add(it)
    }
    for (prefix in goal.pagePathPrefixes) {
        pagePredicates.add("starts_with(page.path, ?)")
        eventParams.add(prefix)
    }
    if (pagePredicates.isNotEmpty()) {
        conditions.add("(${pagePredicates.joinToString(" OR ")})")
    }
    startDate?.let {
        conditions.add("created >= CAST(? AS TIMESTAMP)")
        eventParams.add(it.atZoneSameInstant(java.time.ZoneOffset.UTC).format(TRINO_TIMESTAMP_FORMAT))
    }
    endDate?.let {
        conditions.add("created <= CAST(? AS TIMESTAMP)")
        eventParams.add(it.atZoneSameInstant(java.time.ZoneOffset.UTC).format(TRINO_TIMESTAMP_FORMAT))
    }
    val itemExtraKey = goal.itemExtraKey
    if (itemExtraKey == null) {
        require(goal.itemExtraValue == null) { "itemExtraValue requires itemExtraKey" }
    } else {
        require(isValidItemExtraKey(itemExtraKey)) { "Invalid itemExtraKey '$itemExtraKey'" }
    }
    // An empty candidate predicate would produce a full-table scan. An item selector
    // is itself narrowing even when no other filter or time bound is present.
    if (conditions.isEmpty() && itemExtraKey == null) return null
    if (goal.eventType == null && !isExplicitScrollMeasurement(goal.elementType)) {
        conditions.add(0, countableBehaviorEventPredicate())
    }
    if (itemExtraKey != null) {
        conditions.add("cardinality(element.content) > 0")
    }
    val whereClause = conditions.joinToString(" AND ")
    val cohort = buildSubjectCohortSql(
        assignmentsTable, experimentId, excludedPrincipalIds, excludedInstallationIds, activatedCohort,
    )
    val params = cohort.params.toMutableList()
    params.addAll(eventParams)

    // Resolve warehouse identities to experiment assignments before reducing events. This makes
    // assigned_at the lower bound for every subject, and groups principal + installation identity
    // paths under the assignment id so one person cannot become two unique converters.
    val candidateWhere = if (whereClause.isEmpty()) "" else "WHERE $whereClause"
    val attributionLowerBound = if (cohort.hasActivation) {
        "candidates.created > subjects.activated_at"
    } else {
        "candidates.created >= subjects.assigned_at"
    }
    val commonCtes = """
        WITH ${cohort.ctes}, event_candidates AS (
            SELECT $EVENT_DEDUPLICATION_KEY AS event_key,
                   $CONVERTING_SUBJECT AS client_id,
                   created,
                   element.extras AS raw_extras
            FROM $eventsTable
            $candidateWhere
        ), attributed AS (
            SELECT subjects.subject_id,
                   subjects.variation_key,
                   candidates.event_key,
                   candidates.raw_extras
            FROM event_candidates candidates
            INNER JOIN ${if (cohort.hasActivation) "eligible_subjects" else "experiment_subjects"} subjects
              ON subjects.client_id = candidates.client_id
            WHERE $attributionLowerBound
        )
    """.trimIndent().trimStart()

    // Safe: eventsTable and assignmentsTable are validated at startup by ExperimentationConfig.init
    // against a strict alphanumeric-and-dot regex — no user-controlled input.
    if (itemExtraKey == null) {
        val sql = when (goal.metricType) {
            GoalMetricType.UNIQUE_CONVERSION ->
                "$commonCtes\nSELECT DISTINCT CAST(subject_id AS VARCHAR) AS client_id, variation_key FROM attributed"
            GoalMetricType.EVENT_COUNT ->
                "$commonCtes\nSELECT CAST(subject_id AS VARCHAR) AS client_id, variation_key, count(DISTINCT event_key) AS cnt " +
                    "FROM attributed GROUP BY subject_id, variation_key"
            GoalMetricType.SESSION_DURATION -> error("SESSION_DURATION uses buildSessionDurationQuery")
        }
        return PerUserQuery(sql = sql, params = params)
    }

    val path = "$.${itemExtraKey}"
    val itemExtraValue = goal.itemExtraValue
    val matchPredicate = if (itemExtraValue == null) {
        "json_extract_scalar(extras_json, '$path') IS NOT NULL"
    } else {
        params.add(itemExtraValue)
        "json_extract_scalar(extras_json, '$path') = ?"
    }
    if (goal.metricType == GoalMetricType.SESSION_DURATION) {
        error("SESSION_DURATION uses buildSessionDurationQuery")
    }
    val sql = """
        $commonCtes,
        parsed AS (
            SELECT subject_id,
                   variation_key,
                   event_key,
                   raw_extras,
                   try(json_parse(raw_extras)) AS extras_json
            FROM attributed
        ), candidates AS (
            SELECT subject_id,
                   variation_key,
                   event_key,
                   raw_extras,
                   extras_json,
                   try_cast(extras_json AS MAP(VARCHAR, JSON)) AS extras_object
            FROM parsed
        ), classified AS (
            SELECT subject_id,
                   variation_key,
                   event_key,
                   ($matchPredicate) AS item_matches,
                   raw_extras IS NULL OR extras_object IS NULL AS invalid_item_extras,
                   extras_object IS NOT NULL
                       AND json_extract_scalar(extras_json, '$path') IS NULL AS missing_item_extra_values
            FROM candidates
        )
        SELECT CASE WHEN grouping(subject_id) = 1 THEN CAST(NULL AS VARCHAR) ELSE CAST(subject_id AS VARCHAR) END AS client_id,
               CASE WHEN grouping(subject_id) = 1 THEN CAST(NULL AS VARCHAR) ELSE variation_key END AS variation_key,
               CASE WHEN grouping(subject_id) = 0 THEN count(DISTINCT IF(item_matches, event_key, NULL)) ELSE CAST(0 AS BIGINT) END AS cnt,
               CASE WHEN grouping(subject_id) = 1 THEN count(DISTINCT IF(invalid_item_extras, event_key, NULL)) ELSE CAST(0 AS BIGINT) END AS invalid_item_extras,
               CASE WHEN grouping(subject_id) = 1 THEN count(DISTINCT IF(missing_item_extra_values, event_key, NULL)) ELSE CAST(0 AS BIGINT) END AS missing_item_extra_values
        FROM classified
        GROUP BY GROUPING SETS ((subject_id, variation_key), ())
        HAVING grouping(subject_id) = 1 OR count(DISTINCT IF(item_matches, event_key, NULL)) > 0
    """.trimIndent()
    return PerUserQuery(sql = sql, params = params)
}

/**
 * Builds the bounded Trino query that reduces session spans to one value per subject.
 *
 * A client-supplied session id is only a hint: the query defensively starts a new session after
 * five minutes without activity. Successful player events can cover longer gaps using their
 * remaining duration, bounded by the query end. A later pause, seek, completion, or error limits
 * that allowance. Seek completion resumes the allowance only if the player was playing.
 * Media progress impressions instead report observed seconds under a playback id. Their intervals
 * are bounded by the preceding report, and never project beyond the recorded event. A completion
 * with no new playback closes the visit without renewing an idle session.
 *
 * Player telemetry uses button play/pause/seek and media player-completion events, with audio,
 * currentTime, and duration extras. An optional playbackRate scales remaining time; older events
 * without it assume normal speed. Invalid metadata falls back to ordinary inactivity handling.
 * Sessions end five minutes after their last activity or playback allowance. Playback can bridge
 * a client session-id rollover on the same device, but cannot merge activity from another device.
 */
internal fun buildSessionDurationQuery(
    eventsTable: String,
    assignmentsTable: String,
    experimentId: UUID,
    startDate: OffsetDateTime?,
    endDate: OffsetDateTime?,
    excludedPrincipalIds: Collection<UUID> = emptyList(),
    excludedInstallationIds: Collection<String> = emptyList(),
    activatedCohort: ActivatedCohortBatch? = null,
): PerUserQuery {
    require(startDate != null) { "Session-duration aggregation requires a lower time bound" }
    val conditions = mutableListOf(
        "created >= CAST(? AS TIMESTAMP)",
        "$CONVERTING_SUBJECT IS NOT NULL",
        "context.session_id IS NOT NULL",
        // Scroll timestamps are legitimate evidence of session activity even though each depth
        // milestone is not a separate engagement or conversion. Server-generated Assignment,
        // Installation, and Error records are not evidence of a user's session.
        sessionActivityEventPredicate("events"),
    )
    val cohort = buildSubjectCohortSql(
        assignmentsTable, experimentId, excludedPrincipalIds, excludedInstallationIds, activatedCohort,
    )
    val params = cohort.params.toMutableList()
    params.add(startDate.atZoneSameInstant(java.time.ZoneOffset.UTC).format(TRINO_TIMESTAMP_FORMAT))
    endDate?.let {
        conditions.add("created <= CAST(? AS TIMESTAMP)")
        params.add(it.atZoneSameInstant(java.time.ZoneOffset.UTC).format(TRINO_TIMESTAMP_FORMAT))
    }
    val where = conditions.joinToString(" AND ")
    val playbackCutoff = if (endDate == null) "predicted_end" else {
        params.add(endDate.atZoneSameInstant(java.time.ZoneOffset.UTC).format(TRINO_TIMESTAMP_FORMAT))
        "least(predicted_end, CAST(? AS TIMESTAMP))"
    }
    val sql = """
        WITH ${cohort.ctes}, activity_candidates AS (
            SELECT DISTINCT subjects.subject_id,
                   subjects.variation_key,
                   events.context.session_id AS client_session_id,
                   coalesce(events.context.device.installation_id, subjects.installation_id, '') AS device_id,
                   events.created,
                   events.type AS event_type,
                   events.element.id AS element_id,
                   events.element.type AS element_type,
                   try(events.element.content[1].id) AS content_id,
                   try(json_parse(events.element.extras)) AS extras
            FROM $eventsTable events
            INNER JOIN ${if (cohort.hasActivation) "eligible_subjects" else "experiment_subjects"} subjects
              ON subjects.client_id = $CONVERTING_SUBJECT
            WHERE events.created >= subjects.${if (cohort.hasActivation) "activated_at" else "assigned_at"}
              AND $where
        ), media_events AS (
            SELECT *,
                   json_extract_scalar(extras, '$.audio') AS is_audio,
                   try_cast(json_extract_scalar(extras, '$.currentTime') AS DECIMAL(18, 3)) AS position_seconds,
                   try_cast(json_extract_scalar(extras, '$.duration') AS DECIMAL(18, 3)) AS duration_seconds,
                   CASE WHEN json_extract_scalar(extras, '$.playbackRate') IS NULL THEN DECIMAL '1'
                        ELSE try_cast(json_extract_scalar(extras, '$.playbackRate') AS DECIMAL(12, 3)) END AS playback_rate,
                   CASE
                       WHEN json_extract_scalar(extras, '$.error') = 'true'
                         OR (event_type = 'Interaction' AND element_id = 'pause')
                         OR (event_type = 'Completion' AND element_id = 'player') THEN false
                       WHEN event_type = 'Completion' AND element_id = 'play' THEN true
                       ELSE CAST(NULL AS BOOLEAN)
                   END AS playing,
                   CASE
                       WHEN json_extract_scalar(extras, '$.error') = 'true' THEN 6
                       WHEN element_id = 'player' THEN 5
                       WHEN element_id = 'pause' THEN 4
                       WHEN element_id = 'seek' AND event_type = 'Completion' THEN 3
                       WHEN element_id = 'seek' THEN 2
                       ELSE 1
                   END AS media_order
            FROM activity_candidates
            WHERE content_id IS NOT NULL AND trim(content_id) <> ''
              AND json_extract_scalar(extras, '$.audio') IN ('true', 'false')
              AND ((element_type = 'button' AND element_id IN ('play', 'pause', 'seek'))
                OR (element_type = 'media' AND element_id = 'player'))
              AND event_type IN ('Interaction', 'Completion')
        ), media_sequence AS (
            SELECT *,
                   last_value(playing) IGNORE NULLS OVER (
                       PARTITION BY subject_id, variation_key, device_id, content_id, is_audio
                       ORDER BY created, media_order
                       ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                   ) AS is_playing,
                   lead(created) OVER (
                       PARTITION BY subject_id, variation_key, device_id, content_id, is_audio
                       ORDER BY created, media_order
                   ) AS next_media_event
            FROM media_events
        ), media_predictions AS (
            SELECT *,
                   CASE WHEN is_playing AND event_type = 'Completion' AND element_id IN ('play', 'seek')
                              AND position_seconds >= 0 AND duration_seconds > position_seconds AND playback_rate > 0
                        THEN coalesce(try(date_add('millisecond',
                            CAST((duration_seconds - position_seconds) * 1000 / playback_rate AS BIGINT), created)), created)
                        ELSE created
                   END AS predicted_end
            FROM media_sequence
        ), media_intervals AS (
            SELECT subject_id, variation_key, device_id, client_session_id, created,
                   least($playbackCutoff, coalesce(next_media_event, predicted_end)) AS activity_end
            FROM media_predictions
        ), progress_events AS (
            SELECT *,
                   try_cast(json_extract_scalar(extras, '$.observedSeconds') AS DECIMAL(18, 3)) AS observed_seconds,
                   lag(created) OVER (
                       PARTITION BY subject_id, variation_key, device_id, element_id
                       ORDER BY created, coalesce(try_cast(json_extract_scalar(extras, '$.observedSeconds') AS DECIMAL(18, 3)), DECIMAL '0') DESC
                   ) AS previous_progress
            FROM activity_candidates
            WHERE event_type IN ('Impression', 'Completion') AND element_type = 'media_playback'
              AND element_id IS NOT NULL AND trim(element_id) <> ''
        ), confirmed_playback AS (
            SELECT subject_id, variation_key, device_id, client_session_id,
                   CASE WHEN observed_seconds > 0 AND previous_progress IS NOT NULL
                              AND observed_seconds <= CAST(date_diff('millisecond', previous_progress, created) AS DECIMAL(18, 3)) / 1000
                        THEN greatest(previous_progress, coalesce(try(date_add('millisecond',
                            -CAST(observed_seconds * 1000 AS BIGINT), created)), created))
                        ELSE created END AS activity_start,
                   created AS activity_end
            FROM progress_events
            WHERE event_type <> 'Completion' OR observed_seconds > 0
        ), activity_intervals AS (
            SELECT subject_id, variation_key, device_id, client_session_id, created,
                   created AS activity_end, CAST(NULL AS TIMESTAMP) AS media_until
            FROM activity_candidates
            WHERE event_type <> 'Completion' OR coalesce(element_type, '') <> 'media_playback'
            UNION ALL
            SELECT subject_id, variation_key, device_id, client_session_id, created,
                   activity_end, date_add('millisecond', $SESSION_INACTIVITY_TIMEOUT_MILLISECONDS, activity_end) AS media_until
            FROM media_intervals
            WHERE activity_end > created
            UNION ALL
            SELECT subject_id, variation_key, device_id, client_session_id, activity_start,
                   activity_end, date_add('millisecond', $SESSION_INACTIVITY_TIMEOUT_MILLISECONDS, activity_end) AS media_until
            FROM confirmed_playback
        ), session_event_times AS (
            SELECT subject_id, variation_key, device_id, client_session_id, created,
                   max(activity_end) AS activity_end, max(media_until) AS media_until
            FROM activity_intervals
            GROUP BY subject_id, variation_key, device_id, client_session_id, created
        ), session_event_boundaries AS (
            SELECT *,
                   max(activity_end) OVER (
                       PARTITION BY subject_id, variation_key, device_id, client_session_id
                       ORDER BY created
                       ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
                   ) AS previous_activity_end
            FROM session_event_times
        ), sessionized_event_times AS (
            SELECT *,
                   sum(
                       CASE
                           WHEN previous_activity_end IS NULL
                             OR date_diff('millisecond', previous_activity_end, created) >= $SESSION_INACTIVITY_TIMEOUT_MILLISECONDS
                           THEN 1 ELSE 0
                       END
                   ) OVER (
                       PARTITION BY subject_id, variation_key, device_id, client_session_id
                       ORDER BY created
                       ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                   ) AS derived_session_number
            FROM session_event_boundaries
        ), session_spans AS (
            SELECT subject_id, variation_key, device_id, client_session_id, derived_session_number,
                   min(created) AS session_start, max(activity_end) AS session_end, max(media_until) AS media_until
            FROM sessionized_event_times
            GROUP BY subject_id, variation_key, device_id, client_session_id, derived_session_number
        ), playback_bridges AS (
            SELECT *,
                   max(media_until) OVER (
                       PARTITION BY subject_id, variation_key, device_id
                       ORDER BY session_start, session_end, client_session_id, derived_session_number
                       ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
                   ) AS previous_media_until
            FROM session_spans
        ), connected_sessions AS (
            SELECT *,
                   sum(CASE WHEN previous_media_until IS NULL OR session_start >= previous_media_until
                            THEN 1 ELSE 0 END) OVER (
                       PARTITION BY subject_id, variation_key, device_id
                       ORDER BY session_start, session_end, client_session_id, derived_session_number
                       ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                   ) AS connected_session_number
            FROM playback_bridges
        ), session_durations AS (
            SELECT subject_id, variation_key, device_id, connected_session_number,
                   greatest(CAST(date_diff('millisecond', min(session_start), max(session_end)) AS DOUBLE) / 1000.0, 0.0)
                       + CAST($SESSION_INACTIVITY_TIMEOUT_MILLISECONDS AS DOUBLE) / 1000.0 AS duration_seconds
            FROM connected_sessions
            GROUP BY subject_id, variation_key, device_id, connected_session_number
        )
        SELECT CAST(subject_id AS VARCHAR) AS client_id, variation_key, avg(duration_seconds) AS value
        FROM session_durations
        GROUP BY subject_id, variation_key
    """.trimIndent()
    return PerUserQuery(sql, params)
}

/**
 * Builds the per-user event-count SQL + parameter list for a
 * given filter shape and time window. Pure — no JDBC, no
 * provide(), no logging — so the SQL construction can be pinned
 * by unit tests independently of the Trino connection pool. The
 * companion [queryPerUserEventCounts] wraps this in the actual
 * JDBC execution.
 * Exact page paths and prefixes are alternatives within the otherwise conjunctive filter.
 */
internal fun buildPerUserEventCountsQuery(
    eventsTable: String,
    eventType: bosca.analytics.model.EventType?,
    elementType: String?,
    elementId: String?,
    pagePath: String?,
    pagePathPrefixes: List<String> = emptyList(),
    itemExtraKey: String? = null,
    itemExtraValue: String? = null,
    windowStart: OffsetDateTime,
    windowEnd: OffsetDateTime?,
): PerUserQuery {
    val conditions = mutableListOf<String>()
    val params = mutableListOf<String>()
    if (eventType == EventType.Impression && elementType != "media_playback") {
        conditions.add("coalesce(element.type, '') <> 'media_playback'")
    }
    if (eventType == null) {
        if (!isExplicitScrollMeasurement(elementType)) {
            conditions.add(countableBehaviorEventPredicate())
        }
    } else {
        conditions.add("type = ?")
        params.add(aggregationEventTypePredicateValue(eventType))
        if (eventType == EventType.Interaction && !isExplicitScrollMeasurement(elementType)) {
            conditions.add("NOT ${scrollMeasurementPredicate()}")
        }
    }
    elementType?.let {
        conditions.add("element.type = ?")
        params.add(it)
    }
    elementId?.let {
        conditions.add("element.id = ?")
        params.add(it)
    }
    val pagePredicates = mutableListOf<String>()
    pagePath?.let {
        pagePredicates.add("page.path = ?")
        params.add(it)
    }
    for (prefix in pagePathPrefixes) {
        pagePredicates.add("starts_with(page.path, ?)")
        params.add(prefix)
    }
    if (pagePredicates.isNotEmpty()) {
        conditions.add("(${pagePredicates.joinToString(" OR ")})")
    }
    if (itemExtraKey == null) {
        require(itemExtraValue == null) { "itemExtraValue requires itemExtraKey" }
    } else {
        require(isValidItemExtraKey(itemExtraKey)) { "Invalid itemExtraKey '$itemExtraKey'" }
        conditions.add("cardinality(element.content) > 0")
        val path = "$.${itemExtraKey}"
        if (itemExtraValue == null) {
            conditions.add("json_extract_scalar(try(json_parse(element.extras)), '$path') IS NOT NULL")
        } else {
            conditions.add("json_extract_scalar(try(json_parse(element.extras)), '$path') = ?")
            params.add(itemExtraValue)
        }
    }
    conditions.add("created >= CAST(? AS TIMESTAMP)")
    params.add(windowStart.atZoneSameInstant(java.time.ZoneOffset.UTC).format(TRINO_TIMESTAMP_FORMAT))
    windowEnd?.let {
        conditions.add("created < CAST(? AS TIMESTAMP)")
        params.add(it.atZoneSameInstant(java.time.ZoneOffset.UTC).format(TRINO_TIMESTAMP_FORMAT))
    }
    val where = conditions.joinToString(" AND ")
    // Safe: eventsTable is validated at startup by ExperimentationConfig.init
    // against a strict alphanumeric-and-dot regex — no user-controlled input.
    val sql =
        "SELECT $CONVERTING_SUBJECT AS client_id, count(DISTINCT $EVENT_DEDUPLICATION_KEY) AS cnt " +
            "FROM $eventsTable WHERE $where GROUP BY $CONVERTING_SUBJECT"
    return PerUserQuery(sql = sql, params = params)
}

/**
 * Runs a per-user `SELECT client_id, count(DISTINCT event_key) FROM events WHERE …
 * GROUP BY client_id` against the configured events table with the
 * filter shape of a conversion goal or a CUPED covariate. Returns
 * a `client_id → count` map.
 *
 * Shares the same safety rails as [countConversions]: refuses an
 * unbounded query (no filters, no time bounds) by throwing, so the
 * caller's runCatching converts it into a "skip this goal" warning
 * instead of scanning the whole warehouse.
 */
private suspend fun queryPerUserEventCounts(
    trinoPool: ConnectionPool,
    config: ExperimentationConfig,
    eventType: bosca.analytics.model.EventType?,
    elementType: String?,
    elementId: String?,
    pagePath: String?,
    pagePathPrefixes: List<String> = emptyList(),
    itemExtraKey: String? = null,
    itemExtraValue: String? = null,
    windowStart: OffsetDateTime,
    windowEnd: OffsetDateTime?,
): Map<String, Long> {
    val built = buildPerUserEventCountsQuery(
        eventsTable = config.safeEventsTable,
        eventType = eventType,
        elementType = elementType,
        elementId = elementId,
        pagePath = pagePath,
        pagePathPrefixes = pagePathPrefixes,
        itemExtraKey = itemExtraKey,
        itemExtraValue = itemExtraValue,
        windowStart = windowStart,
        windowEnd = windowEnd,
    )
    val sql = built.sql
    val params = built.params

    val result = mutableMapOf<String, Long>()
    trinoPool.connection().use { connection ->
        connection.useStatement(sql) { stmt ->
            stmt.queryTimeout = TRINO_QUERY_TIMEOUT_SECONDS
            var idx = 1
            for (p in params) stmt.setString(idx++, p)
            val rs = stmt.executeQuery()
            while (rs.next()) {
                val clientId = rs.getString("client_id") ?: continue
                result[clientId] = rs.getLong("cnt")
            }
        }
    }
    return result
}

private data class AttributedEventCounts(
    val countsBySubject: Map<String, Long>,
    val variationBySubject: Map<String, String>,
)

/** One assignment's first activation timestamp, captured before querying outcomes. */
@Serializable
internal data class ActivatedSubject(
    @SerialName("subject_id") val subjectId: String,
    @SerialName("variation_key") val variationKey: String,
    @SerialName("activated_at") val activatedAt: String,
)

/** Immutable cohort reused by denominators, outcome queries, and CUPED within one aggregation. */
internal class ActivatedCohort(subjects: List<ActivatedSubject>) {
    val countsByVariation: Map<String, Long> = subjects.groupingBy { it.variationKey }.eachCount()
        .mapValues { it.value.toLong() }
    val subjectIds: Set<String> = subjects.mapTo(mutableSetOf()) { it.subjectId }
    val batches: List<ActivatedCohortBatch> = buildList {
        var encoded = StringBuilder("[")
        var literalLength = 2 // Include both array brackets.
        for (subject in subjects) {
            val row = Json.encodeToString(ActivatedSubject.serializer(), subject)
            // Trino JDBC embeds string parameters in SQL and doubles apostrophes.
            val rowLiteralLength = row.length + row.count { it == '\'' }
            require(rowLiteralLength + 2 <= MAX_ACTIVATED_COHORT_LITERAL_LENGTH) {
                "An activated subject exceeds the Trino cohort parameter limit"
            }
            val separatorLength = if (encoded.length > 1) 1 else 0
            if (literalLength + rowLiteralLength + separatorLength > MAX_ACTIVATED_COHORT_LITERAL_LENGTH) {
                add(ActivatedCohortBatch(encoded.append(']').toString()))
                encoded = StringBuilder("[")
                literalLength = 2
            }
            if (encoded.length > 1) {
                encoded.append(',')
                literalLength++
            }
            encoded.append(row)
            literalLength += rowLiteralLength
        }
        // An empty captured cohort must remain gated, rather than becoming a null cohort.
        add(ActivatedCohortBatch(encoded.append(']').toString()))
    }
}

/** A disjoint part of a frozen cohort, sized for one Trino query parameter. */
internal class ActivatedCohortBatch(val encodedSubjects: String)

// Leave space for query text, filters, and JDBC's EXECUTE wrapper within Trino's 1M default.
internal const val MAX_ACTIVATED_COHORT_LITERAL_LENGTH = 250_000

/** Loads the activated assignment ids once for denominators and CUPED cohort restriction. */
private suspend fun queryActivatedCohort(
    config: ExperimentationConfig,
    experimentId: UUID,
    activationFilter: ExperimentActivationFilter,
    startDate: OffsetDateTime?,
    endDate: OffsetDateTime?,
    excludedPrincipalIds: Collection<UUID>,
    excludedInstallationIds: Collection<String>,
): ActivatedCohort {
    val built = buildActivatedCohortQuery(
        eventsTable = config.safeEventsTable,
        assignmentsTable = config.safeAssignmentsTable,
        experimentId = experimentId,
        activationFilter = activationFilter,
        startDate = startDate,
        endDate = endDate,
        excludedPrincipalIds = excludedPrincipalIds,
        excludedInstallationIds = excludedInstallationIds,
    )
    val subjects = mutableListOf<ActivatedSubject>()
    val trinoPool: ConnectionPool = provide(name = "trino-readonly")
    trinoPool.connection().use { connection ->
        connection.useStatement(built.sql) { statement ->
            statement.queryTimeout = TRINO_QUERY_TIMEOUT_SECONDS
            built.params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            val rows = statement.executeQuery()
            while (rows.next()) {
                val subjectId = rows.getString("client_id") ?: continue
                val variationKey = rows.getString("variation_key") ?: continue
                subjects.add(ActivatedSubject(subjectId, variationKey, checkNotNull(rows.getString("activated_at"))))
            }
        }
    }
    return ActivatedCohort(subjects)
}

/** Runs the same assignment-bounded event query used by the primary aggregation for CUPED outcomes. */
private suspend fun queryAttributedGoalEventCounts(
    trinoPool: ConnectionPool,
    config: ExperimentationConfig,
    experimentId: UUID,
    goal: ConversionGoal,
    windowStart: OffsetDateTime,
    windowEnd: OffsetDateTime,
    excludedPrincipalIds: Collection<UUID>,
    excludedInstallationIds: Collection<String>,
    activatedCohort: ActivatedCohort?,
): AttributedEventCounts {
    val counts = mutableMapOf<String, Long>()
    val variations = mutableMapOf<String, String>()
    trinoPool.connection().use { connection ->
        for (batch in activatedCohort?.batches ?: listOf(null)) {
            val built = checkNotNull(buildConversionCountQuery(
                eventsTable = config.safeEventsTable,
                assignmentsTable = config.safeAssignmentsTable,
                experimentId = experimentId,
                goal = goal,
                startDate = windowStart,
                endDate = windowEnd,
                excludedPrincipalIds = excludedPrincipalIds,
                excludedInstallationIds = excludedInstallationIds,
                activatedCohort = batch,
            ))
            connection.useStatement(built.sql) { statement ->
                statement.queryTimeout = TRINO_QUERY_TIMEOUT_SECONDS
                built.params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                val rows = statement.executeQuery()
                while (rows.next()) {
                    val subjectId = rows.getString("client_id") ?: continue
                    val variationKey = rows.getString("variation_key") ?: continue
                    val count = rows.getLong("cnt")
                    if (count <= 0L) continue
                    counts[subjectId] = count
                    variations[subjectId] = variationKey
                }
            }
        }
    }
    return AttributedEventCounts(counts, variations)
}

/**
 * Deserializes a stored [bosca.experimentation.model.BayesianPrior]
 * blob, returning `null` on any parse failure so the caller can skip
 * the Bayesian columns entirely rather than silently producing results
 * with a different prior than the operator configured.
 */
private fun parseBayesianPrior(
    json: Json,
    element: JsonElement,
): bosca.experimentation.model.BayesianPrior? =
    try {
        json.decodeFromJsonElement(bosca.experimentation.model.BayesianPrior.serializer(), element)
    } catch (e: Exception) {
        log.error("Failed to parse bayesian_prior blob — Bayesian columns will be skipped for this aggregation. " +
            "This is a data integrity issue; fix the stored prior before the next aggregation run: {}", e.message)
        null
    }

/**
 * Derives a stable seed from the experiment id for the Bayesian
 * Monte Carlo step. Analysis time uses the same helper keyed on
 * the report id, but at aggregation time no report exists yet —
 * seeding from the experiment id keeps each experiment's
 * Bayesian numbers reproducible across re-aggregations without
 * needing a new identifier. Uses `kotlin.uuid.Uuid.toLongs` via
 * the helper in BayesianAnalysis.kt.
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun seedFromExperimentId(experimentId: UUID): Long =
    experimentId.toLongs { msb, _ -> msb }

/**
 * Populates the Bayesian columns (`probabilityBeatsControl`,
 * `expectedLoss`) on [base] using either the Beta-MC path (for
 * proportion goals) or the Normal closed-form (for event-count
 * goals). Only called on non-control variations — the control row
 * has nothing to compare against.
 */
internal fun augmentWithBayesian(
    base: ExperimentResult,
    goal: ConversionGoal,
    controlImpressions: Long,
    controlStats: GoalCountStats,
    treatmentImpressions: Long,
    treatmentStats: GoalCountStats,
    prior: bosca.experimentation.model.BayesianPrior,
    seed: Long,
): ExperimentResult = when (goal.metricType) {
    GoalMetricType.UNIQUE_CONVERSION -> {
        if (controlImpressions <= 0L || treatmentImpressions <= 0L) {
            base
        } else {
            val controlPosterior = betaPosterior(
                conversions = controlStats.convertedUsers,
                impressions = controlImpressions,
                prior = prior,
            )
            val treatmentPosterior = betaPosterior(
                conversions = treatmentStats.convertedUsers,
                impressions = treatmentImpressions,
                prior = prior,
            )
            val pWins = betaProbabilityBeatsControl(controlPosterior, treatmentPosterior, seed)
            val loss = betaExpectedLoss(controlPosterior, treatmentPosterior, seed)
            base.copy(probabilityBeatsControl = pWins, expectedLoss = loss)
        }
    }
    GoalMetricType.EVENT_COUNT,
    GoalMetricType.SESSION_DURATION -> {
        val continuous = goal.metricType == GoalMetricType.SESSION_DURATION
        val cN = if (continuous) controlStats.observationCount else controlImpressions
        val tN = if (continuous) treatmentStats.observationCount else treatmentImpressions
        val cMean = if (continuous) sampleMean(controlStats.sumValues, cN) else perUserMean(controlStats.sumEvents, cN)
        val cVar = if (continuous) sampleVariance(controlStats.sumValues, controlStats.sumSquaredValues, cN)
            else perUserVariance(controlStats.sumEvents, controlStats.sumSquaredEvents, cN)
        val tMean = if (continuous) sampleMean(treatmentStats.sumValues, tN) else perUserMean(treatmentStats.sumEvents, tN)
        val tVar = if (continuous) sampleVariance(treatmentStats.sumValues, treatmentStats.sumSquaredValues, tN)
            else perUserVariance(treatmentStats.sumEvents, treatmentStats.sumSquaredEvents, tN)
        if (cMean == null || cVar == null || tMean == null || tVar == null) {
            base
        } else {
            val cPost = bayesianNormalPosterior(cMean, cVar, cN, prior)
            val tPost = bayesianNormalPosterior(tMean, tVar, tN, prior)
            val pWins = normalProbabilityBeatsControl(
                controlMean = cPost.mean, controlVariance = cPost.variance,
                treatmentMean = tPost.mean, treatmentVariance = tPost.variance,
            )
            val loss = normalExpectedLoss(
                controlMean = cPost.mean, controlVariance = cPost.variance,
                treatmentMean = tPost.mean, treatmentVariance = tPost.variance,
            )
            base.copy(probabilityBeatsControl = pWins, expectedLoss = loss)
        }
    }
}

private fun parseRules(json: Json, rulesJson: JsonElement?): List<TargetingRule> {
    if (rulesJson == null) return emptyList()
    return json.decodeFromJsonElement(ListSerializer(TargetingRule.serializer()), rulesJson)
}

/**
 * Per-variation aggregate statistics for a single conversion goal, holding
 * the inputs both metric types need.
 *
 *  - [convertedUsers] is the number of distinct exposed users who emitted at
 *    least one matching event. This is the conversion count for a
 *    `UNIQUE_CONVERSION` goal and the chi-squared numerator.
 *
 *  - [sumEvents] is the total number of matching events attributed to the
 *    variation. For a `UNIQUE_CONVERSION` goal this equals [convertedUsers]
 *    (since the SQL clamps each user to one event); for an `EVENT_COUNT`
 *    goal it is the per-user counts summed across users.
 *
 *  - [sumSquaredEvents] is `Σ count_i²` across all converting users in the
 *    variation. Combined with [sumEvents] and the variation's impressions
 *    (which provides the zero-event users implicitly), it lets the
 *    aggregator compute a sample mean and unbiased variance for Welch's t
 *    without needing to materialize per-user rows.
 *
 * Users who were exposed but emitted zero matching events are *not*
 * represented in this struct — they are folded in by the caller, which
 * divides by `impressions` (the full sample size) when computing the mean
 * and variance for an `EVENT_COUNT` goal.
 */
internal data class GoalCountStats(
    val convertedUsers: Long,
    val sumEvents: Long,
    val sumSquaredEvents: Double,
    val observationCount: Long = convertedUsers,
    val sumValues: Double = sumEvents.toDouble(),
    val sumSquaredValues: Double = sumSquaredEvents,
) {
    companion object {
        val ZERO = GoalCountStats(0L, 0L, 0.0)
    }
}

/**
 * Splits a bag of Trino `client_id` strings into the two id spaces the
 * assignments table uses. The warehouse query prefixes every identity with its
 * source namespace, so UUID-form installation IDs never cross into principal lookup.
 */
private data class ClientIdSplit(
    val principalIds: List<UUID>,
    val installationIds: List<String>,
)

private fun splitClientIds(clientIds: Collection<String>): ClientIdSplit {
    val principals = ArrayList<UUID>()
    val installations = ArrayList<String>(clientIds.size)
    for (id in clientIds) {
        when {
            id.startsWith(PRINCIPAL_ID_PREFIX) -> {
                runCatching { kotlin.uuid.Uuid.parse(id.removePrefix(PRINCIPAL_ID_PREFIX)) }
                    .getOrNull()
                    ?.let(principals::add)
            }
            id.startsWith(INSTALLATION_ID_PREFIX) -> {
                installations.add(id.removePrefix(INSTALLATION_ID_PREFIX))
            }
        }
    }
    return ClientIdSplit(principals, installations)
}

private const val PRINCIPAL_ID_PREFIX = "principal:"
private const val INSTALLATION_ID_PREFIX = "installation:"

internal data class AssignmentAttribution(
    val subjectId: String,
    val variationKey: String,
)

internal fun mergeCountsByAssignment(
    countsByIdentity: Map<String, Long>,
    assignmentByIdentity: Map<String, AssignmentAttribution>,
): Map<String, Long> {
    val merged = mutableMapOf<String, Long>()
    for ((identity, count) in countsByIdentity) {
        val assignment = assignmentByIdentity[identity] ?: continue
        merged.merge(assignment.subjectId, count, Long::plus)
    }
    return merged
}

/** Resolves pre-period analytics identities to stable experiment assignment ids and variations. */
private suspend fun loadAssignmentsForConverters(
    assignmentRepository: AssignmentRepository,
    experimentId: UUID,
    clientIds: Collection<String>,
    excludedPrincipalIds: Set<UUID>,
    excludedInstallationIds: Set<String>,
    assignedBefore: OffsetDateTime,
): Map<String, AssignmentAttribution> {
    if (clientIds.isEmpty()) return emptyMap()
    val split = splitClientIds(clientIds)
    val byClient = HashMap<String, AssignmentAttribution>(clientIds.size)
    if (split.principalIds.isNotEmpty()) {
        val rows = assignmentRepository.getByConvertingPrincipals(
            experimentId = experimentId,
            principalIds = split.principalIds,
        )
        for (a in rows) {
            if (
                a.principalId !in excludedPrincipalIds &&
                a.installationId !in excludedInstallationIds &&
                !a.assignedAt.isAfter(assignedBefore)
            ) {
                a.principalId?.let {
                    byClient[PRINCIPAL_ID_PREFIX + it] = AssignmentAttribution(a.id.toString(), a.variationKey)
                }
            }
        }
    }
    if (split.installationIds.isNotEmpty()) {
        val rows = assignmentRepository.getByConvertingInstallations(
            experimentId = experimentId,
            installationIds = split.installationIds,
        )
        for (a in rows) {
            if (
                a.principalId !in excludedPrincipalIds &&
                a.installationId !in excludedInstallationIds &&
                !a.assignedAt.isAfter(assignedBefore)
            ) {
                a.installationId?.let {
                    byClient[INSTALLATION_ID_PREFIX + it] = AssignmentAttribution(a.id.toString(), a.variationKey)
                }
            }
        }
    }
    return byClient
}

/**
 * Queries assignment-attributed Trino events for every goal and reduces them into variation stats.
 * Assignment timestamps are enforced in SQL, and every query failure propagates so a partial run
 * cannot replace the prior aggregate.
 */
private suspend fun countConversions(
    experimentId: UUID,
    variations: List<Variation>,
    goals: List<ConversionGoal>,
    config: ExperimentationConfig,
    startDate: OffsetDateTime?,
    endDate: OffsetDateTime?,
    excludedPrincipalIds: Set<UUID>,
    excludedInstallationIds: Set<String>,
    activatedCohort: ActivatedCohort?,
    errorCapture: ErrorCapture,
): Map<Pair<String, UUID>, GoalCountStats> {
    val result = mutableMapOf<Pair<String, UUID>, GoalCountStats>()
    val trinoPool: ConnectionPool = provide(name = "trino-readonly")

    val batches = activatedCohort?.batches ?: listOf(null)
    val sessionGoals = goals.filter { it.metricType == GoalMetricType.SESSION_DURATION }
    if (sessionGoals.isNotEmpty()) {
        val statsByVariation = mutableMapOf<String, GoalCountStats>()
        trinoPool.connection().use { connection ->
            for (batch in batches) {
                val built = buildSessionDurationQuery(
                    eventsTable = config.safeEventsTable,
                    assignmentsTable = config.safeAssignmentsTable,
                    experimentId = experimentId,
                    startDate = startDate,
                    endDate = endDate,
                    excludedPrincipalIds = excludedPrincipalIds,
                    excludedInstallationIds = excludedInstallationIds,
                    activatedCohort = batch,
                )
                connection.useStatement(built.sql) { stmt ->
                    stmt.queryTimeout = TRINO_QUERY_TIMEOUT_SECONDS
                    built.params.forEachIndexed { index, param -> stmt.setString(index + 1, param) }
                    val rs = stmt.executeQuery()
                    while (rs.next()) {
                        val variationKey = rs.getString("variation_key") ?: continue
                        val value = rs.getDouble("value")
                        if (!value.isFinite() || value < 0.0) continue
                        val prior = statsByVariation[variationKey] ?: GoalCountStats.ZERO
                        statsByVariation[variationKey] = prior.copy(
                            observationCount = prior.observationCount + 1,
                            sumValues = prior.sumValues + value,
                            sumSquaredValues = prior.sumSquaredValues + value * value,
                        )
                    }
                }
            }
        }
        for (goal in sessionGoals) {
            for (variation in variations) {
                result[variation.key to goal.id] = statsByVariation[variation.key] ?: GoalCountStats.ZERO
            }
        }
    }

    for (goal in goals) {
        if (goal.metricType == GoalMetricType.SESSION_DURATION) continue
        // For UNIQUE_CONVERSION goals we only need a set of converting users.
        // For EVENT_COUNT goals we need each user's per-user event count so
        // that the caller can derive a per-user mean and variance.
        val statsByVariation = mutableMapOf<String, GoalCountStats>()
        var invalidItemExtras = 0L
        var missingItemExtraValues = 0L
        trinoPool.connection().use { connection ->
            for (batch in batches) {
                val built = buildConversionCountQuery(
                    eventsTable = config.safeEventsTable,
                    assignmentsTable = config.safeAssignmentsTable,
                    experimentId = experimentId,
                    goal = goal,
                    startDate = startDate,
                    endDate = endDate,
                    excludedPrincipalIds = excludedPrincipalIds,
                    excludedInstallationIds = excludedInstallationIds,
                    activatedCohort = batch,
                )
                if (built == null) {
                    log.error(
                        "Refusing to run unbounded conversion query for goal '{}': " +
                            "no eventType, element, page, or time bounds set.", goal.name,
                    )
                    continue
                }
                val sql = built.sql
                val baseParams = built.params
                connection.useStatement(sql) { stmt ->
                    stmt.queryTimeout = TRINO_QUERY_TIMEOUT_SECONDS
                    var idx = 1
                    for (param in baseParams) {
                        stmt.setString(idx++, param)
                    }
                    val rs = stmt.executeQuery()
                    while (rs.next()) {
                        if (goal.itemExtraKey != null) {
                            invalidItemExtras += rs.getLong("invalid_item_extras")
                            missingItemExtraValues += rs.getLong("missing_item_extra_values")
                        }
                        rs.getString("client_id") ?: continue
                        val variationKey = rs.getString("variation_key") ?: continue
                        val matchedCount = if (
                            goal.metricType == GoalMetricType.UNIQUE_CONVERSION && goal.itemExtraKey == null
                        ) {
                            1L
                        } else {
                            rs.getLong("cnt")
                        }
                        if (matchedCount <= 0L) continue
                        val count = when (goal.metricType) {
                            GoalMetricType.UNIQUE_CONVERSION -> 1L
                            GoalMetricType.EVENT_COUNT -> matchedCount
                            GoalMetricType.SESSION_DURATION -> error("handled before event-count query")
                        }
                        val prior = statsByVariation[variationKey] ?: GoalCountStats.ZERO
                        statsByVariation[variationKey] = GoalCountStats(
                            convertedUsers = prior.convertedUsers + 1L,
                            sumEvents = prior.sumEvents + count,
                            sumSquaredEvents = prior.sumSquaredEvents + (count.toDouble() * count.toDouble()),
                        )
                    }
                }
            }
        }
        if (invalidItemExtras > 0L || missingItemExtraValues > 0L) {
            val diagnostic = IllegalStateException(
                "Goal ${goal.id} excluded item candidates: " +
                    "$invalidItemExtras invalid extras and $missingItemExtraValues missing or non-scalar values",
            )
            log.warn(diagnostic.message)
            errorCapture.capture(
                diagnostic,
                null,
                mapOf(
                    "experimentId" to experimentId.toString(),
                    "goalId" to goal.id.toString(),
                    "invalidItemExtras" to invalidItemExtras.toString(),
                    "missingItemExtraValues" to missingItemExtraValues.toString(),
                ),
            )
        }

        for (variation in variations) {
            result[variation.key to goal.id] = statsByVariation[variation.key] ?: GoalCountStats.ZERO
        }
    }

    return result
}

private const val TRINO_QUERY_TIMEOUT_SECONDS = 120

/**
 * Trino's canonical timestamp literal format, used to format the experiment
 * window bounds before binding them as varchar parameters and casting to
 * `TIMESTAMP` in SQL. The events table column is `timestamp(6) without time
 * zone`, so we format in UTC and use a microsecond fraction.
 */
private val TRINO_TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")

/**
 * Shared [ChiSquareTest] instance used by [chiSquaredConfidence].
 * Commons Math's test classes are stateless and thread-safe, so a
 * single instance is reused across all aggregation calls.
 */
private val CHI_SQUARE_TEST = ChiSquareTest()

/**
 * Two-sample chi-squared test of independence between control and
 * treatment conversion rates built on top of Apache Commons Math's
 * [ChiSquareTest.chiSquareTest]. Returns the confidence level
 * `1 - p` in `[0, 1]`, or `null` when the smallest expected cell
 * count in the 2x2 contingency table drops below 5 — the standard
 * cutoff below which the chi-squared approximation is unreliable and
 * an exact test (e.g. Fisher's) would be required instead.
 */
internal fun chiSquaredConfidence(
    controlN: Long, controlConversions: Long,
    treatmentN: Long, treatmentConversions: Long
): Double? {
    // Guard against data integrity issues where conversion counts exceed impressions.
    if (controlConversions > controlN || treatmentConversions > treatmentN) return null

    val totalN = controlN + treatmentN
    val totalConversions = controlConversions + treatmentConversions
    val totalNonConversions = totalN - totalConversions

    // totalN cannot be zero without totalConversions also being zero for valid
    // non-negative counts, so the first condition covers the empty table too.
    if (totalConversions == 0L || totalNonConversions == 0L) return 0.0

    // Expected cell counts under the null hypothesis of independence.
    // Commons Math's test does not surface these directly, so compute
    // them here to enforce the "minimum expected count ≥ 5" rule that
    // guards the chi-squared approximation's validity.
    val eControlConv = controlN.toDouble() * totalConversions / totalN
    val eControlNon = controlN.toDouble() * totalNonConversions / totalN
    val eTreatmentConv = treatmentN.toDouble() * totalConversions / totalN
    val eTreatmentNon = treatmentN.toDouble() * totalNonConversions / totalN
    if (minOf(eControlConv, eControlNon, eTreatmentConv, eTreatmentNon) < 5.0) return null

    val controlNonConv = controlN - controlConversions
    val treatmentNonConv = treatmentN - treatmentConversions
    val counts = arrayOf(
        longArrayOf(controlConversions, controlNonConv),
        longArrayOf(treatmentConversions, treatmentNonConv),
    )
    val pValue = CHI_SQUARE_TEST.chiSquareTest(counts)
    return (1.0 - pValue).coerceIn(0.0, 1.0)
}

/**
 * Builds an [ExperimentResult] for a `UNIQUE_CONVERSION` goal — distinct
 * converting users per variation, analyzed with chi-squared.
 *
 * The reported `conversions` count is the number of distinct converting
 * users (always ≤ `impressions`), `conversionRate` is a proportion in
 * [0, 1], and `confidenceLevel` comes from a 2x2 chi-squared test against
 * the control. `mean` and `variance` are left null because they are not
 * meaningful for a binary outcome.
 */
internal fun buildUniqueConversionResult(
    experimentId: UUID,
    variationKey: String,
    goalId: UUID,
    impressions: Long,
    controlImpressions: Long,
    stats: GoalCountStats,
    controlStats: GoalCountStats,
    isControl: Boolean,
    assignments: Long = impressions,
): ExperimentResult {
    val conversions = stats.convertedUsers
    val ctrlConversions = controlStats.convertedUsers
    val rate = if (impressions > 0) conversions.toDouble() / impressions else 0.0
    val controlRate = if (controlImpressions > 0) ctrlConversions.toDouble() / controlImpressions else 0.0

    val confidenceLevel = if (!isControl && impressions > 0 && controlImpressions > 0) {
        chiSquaredConfidence(controlImpressions, ctrlConversions, impressions, conversions)
    } else null

    val lift = if (!isControl && controlRate > 0) {
        ((rate - controlRate) / controlRate) * 100.0
    } else null

    return ExperimentResult(
        id = UUID.NIL,
        experimentId = experimentId,
        variationKey = variationKey,
        goalId = goalId,
        assignments = assignments,
        impressions = impressions,
        observationCount = impressions,
        conversions = conversions,
        conversionRate = rate,
        confidenceLevel = confidenceLevel,
        liftOverControl = lift,
        mean = null,
        variance = null,
    )
}

/**
 * Builds an [ExperimentResult] for an `EVENT_COUNT` goal — total matching
 * events plus per-user mean and variance, analyzed with Welch's t-test.
 *
 * The denominator for both the mean and the variance is `impressions`, the
 * total number of exposed users — *not* `convertedUsers`. Users who were
 * exposed but emitted zero matching events implicitly contribute 0 to both
 * the sum and the sum of squares, so dividing by the full sample size keeps
 * the mean honest about the population. This is sometimes called the
 * "zero-inflated" view of count metrics; treating the mean as
 * `sumEvents / convertedUsers` would silently overstate the effect by
 * ignoring everyone who saw the variant and did nothing.
 *
 * `conversions` is set to `sumEvents` so the existing UI/results plumbing
 * has something non-zero to display, but the statistically meaningful
 * fields are `mean`, `variance`, and `confidenceLevel`.
 */
internal fun buildEventCountResult(
    experimentId: UUID,
    variationKey: String,
    goalId: UUID,
    impressions: Long,
    controlImpressions: Long,
    stats: GoalCountStats,
    controlStats: GoalCountStats,
    isControl: Boolean,
    assignments: Long = impressions,
): ExperimentResult {
    val mean = perUserMean(stats.sumEvents, impressions)
    val variance = perUserVariance(stats.sumEvents, stats.sumSquaredEvents, impressions)
    val controlMean = perUserMean(controlStats.sumEvents, controlImpressions)
    val controlVariance = perUserVariance(controlStats.sumEvents, controlStats.sumSquaredEvents, controlImpressions)

    val confidenceLevel = if (!isControl && impressions > 1 && controlImpressions > 1
        && variance != null && controlVariance != null
    ) {
        welchsTConfidence(
            controlMean = controlMean ?: 0.0,
            controlVariance = controlVariance,
            controlN = controlImpressions,
            treatmentMean = mean ?: 0.0,
            treatmentVariance = variance,
            treatmentN = impressions,
        )
    } else null

    val lift = if (!isControl && controlMean != null && controlMean > 0.0 && mean != null) {
        ((mean - controlMean) / controlMean) * 100.0
    } else null

    return ExperimentResult(
        id = UUID.NIL,
        experimentId = experimentId,
        variationKey = variationKey,
        goalId = goalId,
        assignments = assignments,
        impressions = impressions,
        observationCount = impressions,
        conversions = stats.sumEvents,
        conversionRate = 0.0,
        confidenceLevel = confidenceLevel,
        liftOverControl = lift,
        mean = mean,
        variance = variance,
    )
}

/** Builds a continuous session-duration result from one value per observed subject. */
internal fun buildSessionDurationResult(
    experimentId: UUID,
    variationKey: String,
    goalId: UUID,
    impressions: Long,
    stats: GoalCountStats,
    controlStats: GoalCountStats,
    isControl: Boolean,
    assignments: Long = impressions,
): ExperimentResult {
    val mean = sampleMean(stats.sumValues, stats.observationCount)
    val variance = sampleVariance(stats.sumValues, stats.sumSquaredValues, stats.observationCount)
    val controlMean = sampleMean(controlStats.sumValues, controlStats.observationCount)
    val controlVariance = sampleVariance(
        controlStats.sumValues, controlStats.sumSquaredValues, controlStats.observationCount,
    )
    val confidence = if (!isControl && mean != null && variance != null && controlMean != null && controlVariance != null) {
        welchsTConfidence(
            controlMean, controlVariance, controlStats.observationCount,
            mean, variance, stats.observationCount,
        )
    } else null
    val lift = if (!isControl && mean != null && controlMean != null && controlMean > 0.0) {
        ((mean - controlMean) / controlMean) * 100.0
    } else null
    return ExperimentResult(
        experimentId = experimentId,
        variationKey = variationKey,
        goalId = goalId,
        assignments = assignments,
        impressions = impressions,
        observationCount = stats.observationCount,
        conversions = 0,
        conversionRate = 0.0,
        confidenceLevel = confidence,
        liftOverControl = lift,
        mean = mean,
        variance = variance,
    )
}

internal fun sampleMean(sum: Double, n: Long): Double? =
    if (n <= 0L) null else sum / n.toDouble()

internal fun sampleVariance(sum: Double, sumSquares: Double, n: Long): Double? {
    if (n < 2L) return null
    val raw = (sumSquares - sum * sum / n.toDouble()) / (n.toDouble() - 1.0)
    return raw.coerceAtLeast(0.0)
}

// The variance formula below uses Bessel's correction (n - 1
// denominator) and is documented on `ExperimentResult.variance` in the
// model. If you change either of those, change the other in the same
// commit so the contract stays explicit on both sides.
internal fun perUserMean(sumEvents: Long, n: Long): Double? {
    if (n <= 0L) return null
    return sumEvents.toDouble() / n.toDouble()
}

/**
 * Unbiased sample variance with Bessel's correction (n - 1 denominator),
 * computed from running totals: `var = (Σx² - (Σx)² / n) / (n - 1)`.
 *
 * Returns `null` when `n < 2` (variance is undefined for a single sample).
 * Floating-point cancellation can produce a very small negative result for
 * near-zero variance; clamp to 0 to keep the [ExperimentResult] init block
 * happy.
 */
internal fun perUserVariance(sumEvents: Long, sumSquaredEvents: Double, n: Long): Double? {
    if (n < 2L) return null
    // Direct textbook formula: var = (Σx² - (Σx)²/n) / (n-1).
    // This avoids the intermediate division-then-multiplication roundtrip
    // of the (Σx²/n - mean²) * n/(n-1) form, which suffers from catastrophic
    // cancellation when sumEvents is large relative to the variance.
    // Computing (Σx)² as a Double product of two Longs avoids Long overflow
    // for sums up to ~3 billion.
    val sumSq = sumEvents.toDouble() * sumEvents.toDouble()
    val raw = (sumSquaredEvents - sumSq / n.toDouble()) / (n.toDouble() - 1.0)
    return if (raw < 0.0) 0.0 else raw
}

/**
 * Minimum per-arm sample size before Welch's t-test is allowed to
 * produce a confidence. The Welch–Satterthwaite degrees-of-freedom
 * approximation degrades badly below this threshold; producing a
 * "significant!" report from two handfuls of samples is worse than
 * returning nothing because the sign can flip on the next aggregation
 * run and operators treat the result as noise.
 */
internal const val WELCH_MIN_SAMPLES_PER_ARM = 30L

/**
 * Shared [TTest] instance used by [welchsTConfidence]. Commons Math's
 * test classes are stateless and thread-safe, so a single instance is
 * reused across all aggregation calls.
 */
private val T_TEST = TTest()

/**
 * Welch's t-test confidence between two independent samples with
 * possibly unequal variances, built on Apache Commons Math's
 * [TTest.tTest] summary-statistics overload. Returns the confidence
 * level `1 - p` in `[0, 1]`, or `null` when either arm is below
 * [WELCH_MIN_SAMPLES_PER_ARM] or the pooled standard error is not
 * positive.
 */
internal fun welchsTConfidence(
    controlMean: Double,
    controlVariance: Double,
    controlN: Long,
    treatmentMean: Double,
    treatmentVariance: Double,
    treatmentN: Long,
): Double? {
    if (controlN < WELCH_MIN_SAMPLES_PER_ARM || treatmentN < WELCH_MIN_SAMPLES_PER_ARM) return null
    if (controlVariance + treatmentVariance <= 0.0) return null
    // Commons Math's summary-only Welch overload `tTest(m1, m2, v1, v2,
    // n1, n2)` is protected. The public entry point accepts two
    // `StatisticalSummary` objects, so wrap the (mean, variance, n)
    // triples in [StatisticalSummaryValues] — the unused max/min/sum
    // slots don't affect Welch's t-test, which only reads mean,
    // variance and n.
    val control = StatisticalSummaryValues(
        controlMean, controlVariance, controlN, Double.NaN, Double.NaN, Double.NaN,
    )
    val treatment = StatisticalSummaryValues(
        treatmentMean, treatmentVariance, treatmentN, Double.NaN, Double.NaN, Double.NaN,
    )
    val pValue = T_TEST.tTest(treatment, control)
    return (1.0 - pValue).coerceIn(0.0, 1.0)
}
