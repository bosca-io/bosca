package bosca.experimentation.model

import bosca.analytics.model.EventType
import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Defines what analytics events count as conversions for an experiment.
 *
 * Conversion goals are matched against incoming analytics events using the
 * [eventType], and optionally [elementType] and [elementId], to determine
 * whether a tracked user completed the desired action. Multiple goals per
 * experiment enable tracking of primary and secondary metrics.
 *
 * The [eventType] is a typed [EventType] rather than a free-form string so
 * that goals cannot be created with a value that silently fails to match
 * anything in the analytics events table. The Iceberg events table stores the
 * enum's Kotlin `.name` (capitalized) via
 * `IcebergEventsToRecordTransform.setField("type", type.name)`, so the
 * aggregation job binds [EventType.name] when building its Trino query.
 */
@BatchKey("id")
@Serializable
data class ConversionGoal(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("experiment_id")
    @Contextual
    val experimentId: UUID,
    val name: String,
    /**
     * Filters matching events by their analytics event type. When `null`, the
     * other goal filters may match events across types — useful for "did total
     * activity per user grow?" metrics — but passive impressions and scroll-depth
     * milestones are excluded. Page impressions remain eligible. Selecting an
     * [elementType] of `scroll_depth` or `scroll_max_depth` opts into those measurements,
     * including when this field is [EventType.Interaction] or `null`; this keeps view
     * depth available as an explicit quality metric without turning every milestone
     * into a conversion. Set this explicitly to [EventType.Impression] for an exposure metric.
     * Playback progress impressions also require [elementType] `media_playback` so periodic
     * consumption measurements do not inflate broad exposure counts.
     */
    @ColumnName("event_type")
    val eventType: EventType? = null,
    /**
     * Filters matching events by their `element.type` analytics field — the
     * categorical value the SDK populates when a user interacts with a
     * tagged UI element (e.g., `"button"`, `"link"`, `"card"`). This is the
     * raw analytics taxonomy field, NOT a Bosca content type id. When
     * `null` no element-type filter is applied.
     */
    @ColumnName("element_type")
    val elementType: String? = null,
    /**
     * Filters matching events by their `element.id` analytics field — the
     * SDK-supplied identifier for an interaction target (e.g., the value
     * of `data-analytics-id` on a clicked button). This is the raw
     * analytics field, NOT a Bosca content UUID; element ids are
     * application-defined strings and do not have to be UUIDs. When
     * `null` no element-id filter is applied.
     */
    @ColumnName("element_id")
    val elementId: String? = null,
    /**
     * Selects which statistical metric this goal reports.
     * `UNIQUE_CONVERSION` (the default) counts each converting user once
     * and analyzes the resulting proportion with chi-squared.
     * `EVENT_COUNT` totals matching events per user, reports a per-user
     * mean and variance, and analyzes the difference with Welch's t-test.
     * `SESSION_DURATION` reports each observed subject's average session span
     * in seconds, defensively starts a new span after five minutes of inactivity,
     * and analyzes those subject observations with Welch's t-test. Audio/video playback
     * progress impressions confirm observed playback, including gaps while browser timers were
     * suspended. Older play events estimate remaining duration, limited by later player events
     * and the aggregation cutoff. Each span ends five minutes after its final activity or
     * playback; a single ordinary event contributes five minutes.
     * These metrics cannot share a single statistical engine — see
     * `GoalMetricType` and `ExperimentResultAggregation.buildEventCountResult`.
     */
    @ColumnName("metric_type")
    val metricType: GoalMetricType = GoalMetricType.UNIQUE_CONVERSION,
    /**
     * Restricts a conversion goal to events emitted on a specific page,
     * matched against the top-level `page.path` column on the analytics
     * events table (populated by the browser SDK at emit time as
     * `window.location.pathname`). When `null` no page filter is applied
     * and the goal counts events from any page.
     */
    @ColumnName("page_path")
    val pagePath: String? = null,
    /**
     * Alternative prefix matches against `page.path`, combined with [pagePath] using OR.
     * This lets one goal count follow-up impressions across content routes such as articles,
     * talks, and studies while omitting intermediate routes such as search.
     */
    @ColumnName("page_path_prefixes")
    val pagePathPrefixes: List<String> = emptyList(),
    /**
     * Selects interactions whose analytics element carries this extra key.
     * Matching also requires at least one content reference on the element.
     */
    @ColumnName("item_extra_key")
    val itemExtraKey: String? = null,
    /** Exact, case-sensitive scalar value match. Null means key presence is sufficient. */
    @ColumnName("item_extra_value")
    val itemExtraValue: String? = null,
    /**
     * Role this goal plays in the experiment verdict logic. Defaults to
     * [ConversionGoalRole.PRIMARY] so pre-role goals keep their historical
     * "drive SHIP/DO_NOT_SHIP" semantics after the Phase 1 migration
     * backfills existing rows.
     */
    val role: ConversionGoalRole = ConversionGoalRole.PRIMARY,
    /**
     * Serialized [CupedCovariate] configuration. When non-null, the
     * aggregator runs a second per-user pre-period query and
     * produces [ExperimentResult.adjustedMean] /
     * [ExperimentResult.adjustedVariance] alongside the raw
     * unadjusted values. Only consulted for `EVENT_COUNT` goals —
     * CUPED on a binary proportion requires a different math path
     * (logistic regression) that is out of scope for the first pass.
     */
    @ColumnName("cuped_covariate")
    @Contextual
    val cupedCovariate: kotlinx.serialization.json.JsonElement? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now()
)

/**
 * Input for creating a conversion goal for an experiment.
 */
@Serializable
data class ConversionGoalInput(
    val name: String,
    val eventType: EventType? = null,
    val elementType: String? = null,
    val elementId: String? = null,
    val metricType: GoalMetricType = GoalMetricType.UNIQUE_CONVERSION,
    val pagePath: String? = null,
    val pagePathPrefixes: List<String> = emptyList(),
    val itemExtraKey: String? = null,
    val itemExtraValue: String? = null,
    val role: ConversionGoalRole = ConversionGoalRole.PRIMARY,
    /** Typed CUPED configuration. Null = no pre-period adjustment. */
    val cupedCovariate: CupedCovariate? = null,
)
