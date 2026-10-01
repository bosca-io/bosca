package bosca.experimentation.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Selects which statistical metric a [ConversionGoal] reports for an
 * experiment.
 *
 * Different questions require different statistics. A binary "did they convert
 * at all?" goal reports a proportion in [0, 1] and is analyzed with a
 * chi-squared test. A "how many times did they do it per user?" goal reports
 * a per-user mean and is analyzed with Welch's t-test. Mixing the two on the
 * same goal would force a single statistical engine to misuse one or the
 * other, so the goal carries an explicit metric type and the aggregation job
 * branches on it.
 */
@DbMapper(GoalMetricTypeMapper::class)
@Serializable
enum class GoalMetricType {
    /**
     * Each converting user contributes 1 to a per-variation conversion count,
     * regardless of how many matching events they emitted. The reported
     * `conversionRate` is a proportion in [0, 1] and the confidence level
     * comes from a chi-squared test of independence on the 2x2
     * (control/treatment) x (converted/did-not-convert) table.
     */
    UNIQUE_CONVERSION,

    /**
     * Each converting user contributes their *count* of matching events to a
     * per-variation total. The reported metric is the per-user mean
     * (`sumEvents / impressions`) and the confidence level comes from
     * Welch's t-test comparing the per-user means of control and treatment.
     * Users who were exposed but emitted zero matching events contribute 0 to
     * both the sum and the sum of squares — the denominator stays at the
     * number of exposed users so the mean reflects the full population.
     */
    EVENT_COUNT,

    /**
     * Mean session duration in seconds. Playback telemetry can cover gaps beyond
     * five minutes using observed progress. Older play events estimate remaining duration,
     * limited by later player events and the aggregation cutoff. Sessions extend five minutes beyond their last
     * activity or playback. Each subject contributes their mean session duration.
     */
    SESSION_DURATION,
}

/**
 * Persists [GoalMetricType] using the lowercase form `unique_conversion` /
 * `event_count` to match the Postgres `experimentation.goal_metric_type`
 * enum declared by the V3 migration.
 */
object GoalMetricTypeMapper : EnumMapper<GoalMetricType>({ GoalMetricType.valueOf(it.uppercase()) })
