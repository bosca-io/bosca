package bosca.experimentation.jobs

import bosca.db.ConnectionPool
import bosca.db.use
import bosca.di.provide
import bosca.experimentation.configuration.ExperimentationConfig
import bosca.experimentation.model.*
import bosca.observability.ErrorCapture
import bosca.serialization.UUID
import org.apache.commons.math3.distribution.TDistribution
import java.time.OffsetDateTime
import kotlin.math.sqrt

/** Exact order-statistic interpolation over subject observations, including implicit event-count zeros. */
internal fun summarizeDistribution(values: List<Double>, zeroCount: Long = 0): MetricDistribution? {
    require(zeroCount >= 0)
    val sorted = values.sorted()
    val n = sorted.size.toLong() + zeroCount
    if (n == 0L) return null
    fun at(index: Long) = if (index < zeroCount) 0.0 else sorted[(index - zeroCount).toInt()]
    fun percentile(p: Double): Double {
        val index = (n - 1) * p
        val lower = index.toLong()
        return at(lower) + (at(minOf(lower + 1, n - 1)) - at(lower)) * (index - lower)
    }
    val mean = sorted.sum() / n
    val variance = if (n > 1) (sorted.sumOf { (it - mean) * (it - mean) } + zeroCount * mean * mean) / (n - 1) else 0.0
    return MetricDistribution(n, mean, sqrt(variance), percentile(.5), percentile(.75), percentile(.9),
        percentile(.95), percentile(.99), at(n - 1))
}

/** Wilson 95% interval for a subject rate; an empty denominator has no estimate. */
internal fun wilsonInterval(numerator: Long, denominator: Long, alpha: Double = 0.05): MetricInterval? {
    require(numerator in 0..denominator)
    if (denominator == 0L) return null
    val z = org.apache.commons.math3.distribution.NormalDistribution().inverseCumulativeProbability(1 - alpha / 2)
    val p = numerator.toDouble() / denominator
    val d = 1 + z * z / denominator
    val center = (p + z * z / (2 * denominator)) / d
    val half = z * sqrt(p * (1 - p) / denominator + z * z / (4 * denominator * denominator.toDouble())) / d
    return MetricInterval(maxOf(0.0, center - half), minOf(1.0, center + half))
}

/**
 * Wilson interval for counts read from separate stores. Trino outcomes can exceed a
 * PostgreSQL assignment denominator, and that pair has no valid rate; the raw counts
 * remain on the population so the discrepancy stays visible.
 */
internal fun observedRateInterval(numerator: Long, denominator: Long): MetricInterval? =
    if (numerator > denominator) null else wilsonInterval(numerator, denominator)

internal fun populationSummary(metric: GoalMetricType, subjects: Long, stats: GoalCountStats): GoalPopulation {
    if (metric == GoalMetricType.UNIQUE_CONVERSION) return GoalPopulation(subjects, stats.convertedUsers, subjects,
        if (subjects == 0L || stats.convertedUsers > subjects) null else stats.convertedUsers.toDouble() / subjects, null,
        observedRateInterval(stats.convertedUsers, subjects))
    val distribution = summarizeDistribution(stats.values,
        if (metric == GoalMetricType.EVENT_COUNT) (subjects - stats.values.size).coerceAtLeast(0) else 0)
    val n = distribution?.count ?: 0
    val variance = distribution?.standardDeviation?.let { it * it }
    val interval = if (distribution != null && n > 1) {
        val half = TDistribution((n - 1).toDouble()).inverseCumulativeProbability(.975) * sqrt(checkNotNull(variance) / n)
        MetricInterval(distribution.mean - half, distribution.mean + half)
    } else null
    return GoalPopulation(subjects, stats.convertedUsers, n, distribution?.mean, variance, interval, distribution)
}

/** Captures assignment eligibility once when no experiment event gate is configured. */
internal suspend fun queryAssignmentCohort(
    config: ExperimentationConfig, experimentId: UUID, cutoff: OffsetDateTime,
    excludedPrincipals: Collection<UUID>, excludedInstallations: Collection<String>,
): ActivatedCohort {
    val cohort = buildSubjectCohortSql(config.safeAssignmentsTable, experimentId, excludedPrincipals, excludedInstallations)
    val query = PerUserQuery("""
        WITH ${cohort.ctes}
        SELECT DISTINCT CAST(subject_id AS VARCHAR) AS subject_id, variation_key,
               CAST(assigned_at AS VARCHAR) AS activated_at
        FROM experiment_subjects WHERE assigned_at <= CAST(? AS TIMESTAMP)
    """.trimIndent(), cohort.params + cutoff.toLocalDateTime().toString().replace('T', ' '))
    val subjects = mutableListOf<ActivatedSubject>()
    readGoalQuery(query) { row -> subjects.add(ActivatedSubject(row.getString("subject_id"), row.getString("variation_key"), row.getString("activated_at"))) }
    return ActivatedCohort(subjects)
}

internal suspend fun readGoalQuery(query: PerUserQuery, read: (java.sql.ResultSet) -> Unit) {
    val pool: ConnectionPool = provide(name = "trino-readonly")
    pool.connection().use { connection ->
        connection.useStatement(query.sql) { statement ->
            statement.queryTimeout = 300
            query.params.forEachIndexed { i, value -> statement.setString(i + 1, value) }
            val rows = statement.executeQuery()
            while (rows.next()) read(rows)
        }
    }
}

/** Reuses the captured cohort and ordinary outcome queries for each descriptive population. */
internal suspend fun aggregateGoalPopulationAnalysis(
    experimentId: UUID, goal: ConversionGoal, variations: List<Variation>, config: ExperimentationConfig,
    start: OffsetDateTime, cutoff: OffsetDateTime, cohort: ActivatedCohort?,
    eligibleCounts: Map<String, Long>, overallStats: Map<Pair<String, UUID>, GoalCountStats>,
    excludedPrincipals: Set<UUID>, excludedInstallations: Set<String>, errorCapture: ErrorCapture,
): Map<String, GoalPopulationAnalysis> {
    val activation = goal.activation
    val filters = variations.mapNotNull { activation?.filterFor(it.key) }.distinct()
    val observations = mutableMapOf<ExperimentActivationFilter, MutableMap<String, ObservedActivation>>()
    val diagnostics = mutableMapOf<String, GoalDiagnostics>()
    for (filter in filters) {
        val observed = observations.getOrPut(filter) { mutableMapOf() }
        for (batch in checkNotNull(cohort).batches) {
            val query = buildGoalActivationQuery(config.safeEventsTable, config.safeAssignmentsTable, experimentId,
                filter, checkNotNull(activation), batch, start, cutoff, excludedPrincipals, excludedInstallations)
            readGoalQuery(query) { row ->
                val arm = row.getString("variation_key")
                val id = row.getString("subject_id")
                row.getString("activated_at")?.let { time ->
                    observed[id] = ObservedActivation(ActivatedSubject(id, arm, time, row.getString("app_id"), row.getString("session_id")), row.getLong("event_count"))
                }
                // Each arm's expected rule supplies its delivery diagnostics once.
                if (activation.filterFor(arm) == filter) {
                    val prior = diagnostics[arm] ?: GoalDiagnostics()
                    diagnostics[arm] = prior.copy(duplicateDeliveries = prior.duplicateDeliveries + row.getLong("duplicates"),
                        outsideApplication = prior.outsideApplication + row.getLong("outside_application"),
                        missingApplication = prior.missingApplication + row.getLong("missing_application"))
                }
            }
        }
    }
    val activatedSubjects = cohort?.subjects.orEmpty().mapNotNull { subject ->
        activation?.filterFor(subject.variationKey)?.let { observations[it]?.get(subject.subjectId)?.subject }
    }
    val activatedIds = activatedSubjects.mapTo(hashSetOf()) { it.subjectId }
    val unactivatedSubjects = cohort?.subjects.orEmpty().filter {
        activation?.filterFor(it.variationKey) != null && it.subjectId !in activatedIds
    }
    suspend fun counts(subjects: List<ActivatedSubject>, after: Boolean): Map<Pair<String, UUID>, GoalCountStats> =
        if (subjects.isEmpty()) emptyMap() else countConversions(experimentId, variations, listOf(goal), config,
            start, cutoff, excludedPrincipals, excludedInstallations, ActivatedCohort(subjects), errorCapture, after)
    val activeStats = counts(activatedSubjects, true)
    val inactiveStats = counts(unactivatedSubjects, false)
    val attribution = if (goal.outcomeScope?.attributeRecommendationDestination == true && activatedSubjects.isNotEmpty()) {
        queryDestinationAttribution(config, experimentId, goal, ActivatedCohort(activatedSubjects), start, cutoff,
            excludedPrincipals, excludedInstallations)
    } else emptyMap()
    return variations.associate { variation ->
        val key = variation.key
        val filter = activation?.filterFor(key)
        val eligible = eligibleCounts[key] ?: 0
        val active = activatedSubjects.count { it.variationKey == key }.toLong()
        val expected = observations[filter].orEmpty()
        val unexpected = observations.filterKeys { it != filter }.values.flatMap { it.keys }.toSet()
        val subjects = cohort?.subjects.orEmpty().filter { it.variationKey == key }
        val both = subjects.count { it.subjectId in expected && it.subjectId in unexpected }.toLong()
        val unexpectedOnly = subjects.count { it.subjectId !in expected && it.subjectId in unexpected }.toLong()
        val intensity = expected.values.filter { it.subject.variationKey == key }.map { it.events.toDouble() }
        key to GoalPopulationAnalysis(cutoff.toString(), filter != null,
            activation?.variationRules?.any { it.variationKey == key && it.mode == GoalActivationMode.NOT_APPLICABLE } == true,
            populationSummary(goal.metricType, eligible, overallStats[key to goal.id] ?: GoalCountStats.ZERO),
            if (filter != null) populationSummary(goal.metricType, active, activeStats[key to goal.id] ?: GoalCountStats.ZERO) else null,
            if (filter != null) populationSummary(goal.metricType, eligible - active, inactiveStats[key to goal.id] ?: GoalCountStats.ZERO) else null,
            if (filter != null && eligible > 0 && active <= eligible) active.toDouble() / eligible else null,
            if (filter != null) observedRateInterval(active, eligible) else null,
            if (activation != null) PlacementIntegrity(active - both, unexpectedOnly, both, eligible - active - unexpectedOnly) else null,
            if (filter != null) intensity.sum().toLong() else null,
            if (filter != null) summarizeDistribution(intensity) else null,
            if (filter != null && goal.outcomeScope?.attributeRecommendationDestination == true) attribution[key] ?: DestinationAttribution() else null,
            (diagnostics[key] ?: GoalDiagnostics()).copy(excludedPrincipals = excludedPrincipals.size.toLong(), excludedInstallations = excludedInstallations.size.toLong()))
    }
}
