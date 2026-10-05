package bosca.recommendations.installer

import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryInput
import bosca.analytics.model.AnalyticsQueryParameterInput
import bosca.analytics.model.QueryParameterType
import bosca.analytics.service.AnalyticsQueryService
import bosca.experimentation.configuration.ExperimentationConfig
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.languages.model.LanguageResolutionContextInput
import bosca.languages.model.LanguageTagMappingInput
import bosca.languages.service.LanguagesService
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.service.PipelineService
import bosca.recommendations.pipeline.ClassifyNode
import bosca.recommendations.pipeline.ComputeProfileSignalsNode
import bosca.recommendations.pipeline.InferInterestNode
import bosca.recommendations.model.RecommendationPlacementInput
import bosca.recommendations.model.RecommendationStrategyInput
import bosca.recommendations.model.RecommendationStrategyStatus
import bosca.recommendations.model.RecommendationStrategyType
import bosca.recommendations.jobs.TrainModelJob
import bosca.recommendations.ml.TfServingConfiguration
import bosca.recommendations.service.CohortCoEngagementConfiguration
import bosca.recommendations.service.RecommendationPlacementService
import bosca.recommendations.service.RecommendationService
import bosca.recommendations.service.RecommendationStrategyService
import bosca.scheduler.model.ScheduledJobInput
import bosca.scheduler.service.SchedulerService
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Seeds default analytics queries, recommendation strategies, and placements on first installation.
 * Creates the trending and co-engagement queries with their strategies (hourly/daily evaluation),
 * the ML model strategy (served live), the trainer/feedback queries, and a default "home_feed"
 * placement.
 */
class RecommendationsInstaller(
    private val analyticsQueryService: AnalyticsQueryService,
    private val strategyService: RecommendationStrategyService,
    private val placementService: RecommendationPlacementService,
    private val securityService: SecurityService,
    private val pipelineService: PipelineService,
    private val schedulerService: SchedulerService,
    private val languagesService: LanguagesService,
    private val experimentationConfig: ExperimentationConfig,
) : PackageInstaller {

    override val version: String = "1.0.36"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val group = securityService.getGroupByName("administrators", GroupType.SYSTEM)
            ?: error("No administrators group found")

        installLanguageResolutionContext()
        installQueries(group.id)
        installStrategies()
        installMlModelStrategy()
        installMlTrainingSchedule()
        installCohortCoEngagementStrategy()
        installPlacements()
        installClassificationPipeline()
        installSignalComputePipelines()
        installInferInterestPipeline()
    }

    private suspend fun installLanguageResolutionContext() {
        var context = languagesService.getResolutionContext(RecommendationService.LANGUAGE_RESOLUTION_CONTEXT_KEY)
            ?: languagesService.addResolutionContext(
                LanguageResolutionContextInput(
                    key = RecommendationService.LANGUAGE_RESOLUTION_CONTEXT_KEY,
                    name = "Recommendations",
                    description = "Reconciles profile and content language tags used by recommendation training and serving.",
                    fallbackLanguageTag = RecommendationService.DEFAULT_LANGUAGE_TAG,
                ),
                isProtected = true,
            )
        if (!context.isProtected) {
            context = languagesService.protectResolutionContext(context.id)
        }
        val existingSources = languagesService.getLanguageTagMappings(context.id)
            .mapTo(mutableSetOf()) { it.sourceLanguageTag }
        val defaults = languagesService.getAll().map { it.tag to it.tag } +
            listOf("en-US" to RecommendationService.DEFAULT_LANGUAGE_TAG)
        defaults.filter { (source, _) -> source !in existingSources }.forEach { (source, resolved) ->
            languagesService.setLanguageTagMapping(
                context.id,
                LanguageTagMappingInput(sourceLanguageTag = source, resolvedLanguageTag = resolved),
            )
        }
    }

    private suspend fun installQueries(adminGroupId: UUID) {
        val existingQueries = analyticsQueryService.getQueries(0, 1000).associateBy { it.key }
        val eventsTable = resolveEventsTable(existingQueries)
        val pg = experimentationConfig.safePostgresCatalog

        val queries = listOf(
            AnalyticsQueryInput(
                key = "recommendations-trending-content",
                name = "Trending Content (Recommendations)",
                description = "Identifies content with the highest engagement velocity over the last 7 days, weighted by recency. Engagement includes discrete interactions, completions, and page impressions; passive impressions and scroll-depth measurements are excluded from the event count. Returns metadata_id and score columns for the recommendation strategy evaluator.",
                query = trendingContentQuery(eventsTable),
                parameters = listOf(),
            ),
            AnalyticsQueryInput(
                key = "recommendations-co-engagement",
                name = "Co-engagement (Recommendations)",
                description = "Behavioral co-engagement over the last 90 days: for each source content item, the other items that the same users also engaged with (\"people who engaged with this also engaged with…\"). Engagement includes discrete interactions, completions, and page impressions; passive impressions and scroll-depth measurements are excluded from the event count. Returns source_id, co_engaged_id and score columns for a CO_ENGAGEMENT strategy. Paged via offset/limit.",
                query = """
                    with ${recommendationUsersCtes(pg)},
                    interactions as (
                        select distinct
                            u.profile_id as user_id,
                            c.id as content_id
                        from $eventsTable e
                        cross join unnest(e.element.content) as c(id, type, "index", percent)
                        join recommendation_users u on u.event_user_id = e.context.user_id
                        where e.created >= current_date - interval '90' day
                          and $ENGAGEMENT_EVENT_PREDICATE
                          and $BOT_USER_AGENT_PREDICATE
                          and c.id is not null
                          and e.context.user_id is not null
                    )
                    select
                        cast(a.content_id as varchar) as source_id,
                        cast(b.content_id as varchar) as co_engaged_id,
                        cast(count(distinct a.user_id) as double) as score,
                        'Frequently engaged with together' as reason
                    from interactions a
                    join interactions b
                      on a.user_id = b.user_id
                     and a.content_id <> b.content_id
                    group by a.content_id, b.content_id
                    order by source_id, score desc, co_engaged_id
                    offset :offset limit :limit
                """.trimIndent(),
                parameters = pagingParameters(),
            ),
            AnalyticsQueryInput(
                key = "recommendations-cohort-co-engagement",
                name = "Cohort Co-engagement (Recommendations)",
                description = "\"People like you\" co-engagement: within each cohort membership (one per distinct value from useAsCohort personalization signals), for each source content item the other items that members of that cohort also engaged with over the last 90 days. Engagement includes discrete interactions, completions, and page impressions; passive impressions and scroll-depth measurements are excluded from the event count. Returns cohort_key, source_id, co_engaged_id and score for a COHORT_CO_ENGAGEMENT strategy. Empty until useAsCohort signals are configured + computed.",
                query = """
                    with ${recommendationUsersCtes(pg)},
                    cohort_interactions as (
                        select
                            pc.cohort_key as cohort_key,
                            c.id as content_id,
                            u.profile_id as user_id,
                            max(e.created) as last_engaged
                        from $eventsTable e
                        cross join unnest(e.element.content) as c(id, type, "index", percent)
                        join recommendation_users u on u.event_user_id = e.context.user_id
                        join $pg.recommendations.profile_cohort pc
                          on cast(pc.user_id as varchar) = u.profile_id
                        where e.created >= current_date - interval '90' day
                          and $ENGAGEMENT_EVENT_PREDICATE
                          and $BOT_USER_AGENT_PREDICATE
                          and c.id is not null
                          and e.context.user_id is not null
                        group by pc.cohort_key, c.id, u.profile_id
                    ),
                    capped_interactions as (
                        -- Cap A: keep only each user's most-recently-engaged items so one high-activity user
                        -- can't blow up the co-occurrence self-join (bounds its cost to ~perUserItemCap^2/user).
                        select cohort_key, content_id, user_id
                        from (
                            select cohort_key, content_id, user_id,
                                   row_number() over (partition by cohort_key, user_id order by last_engaged desc) as item_rank
                            from cohort_interactions
                        ) ranked
                        where item_rank <= :basketCap
                    ),
                    edges as (
                        select
                            a.cohort_key as cohort_key,
                            a.content_id as source_id,
                            b.content_id as co_engaged_id,
                            count(distinct a.user_id) as score
                        from capped_interactions a
                        join capped_interactions b
                          on a.cohort_key = b.cohort_key
                         and a.user_id = b.user_id
                         and a.content_id <> b.content_id
                        group by a.cohort_key, a.content_id, b.content_id
                        having count(distinct a.user_id) >= 2
                    )
                    -- Cap B: materialize only the strongest co-engaged items per source per cohort. The serve
                    -- path reads at most limit(<=50) x 3 = 150 per source, so this only trims a tail nothing reads.
                    select
                        cohort_key,
                        cast(source_id as varchar) as source_id,
                        cast(co_engaged_id as varchar) as co_engaged_id,
                        cast(score as double) as score,
                        'People also viewed by people like you' as reason
                    from (
                        select cohort_key, source_id, co_engaged_id, score,
                               row_number() over (partition by cohort_key, source_id order by score desc) as source_rank
                        from edges
                    ) ranked_edges
                    where source_rank <= :sourceCap
                    order by cohort_key, source_id, score desc
                """.trimIndent(),
                // Tuning knobs bound the nightly batch (see CohortCoEngagementConfiguration); declared here so
                // the query runs standalone with the same 200 defaults. Declaration order sets `sort`, which
                // must match the `:basketCap`-then-`:sourceCap` appearance order (positional JDBC binding).
                parameters = listOf(
                    AnalyticsQueryParameterInput(
                        parameter = "basketCap",
                        name = "Per-user item cap",
                        description = "Cap A: keep only each user's top-N most-recently-engaged items before the co-occurrence self-join.",
                        type = QueryParameterType.INTEGER,
                        arrayType = null,
                        defaultValue = JsonPrimitive(200),
                        required = false,
                    ),
                    AnalyticsQueryParameterInput(
                        parameter = "sourceCap",
                        name = "Per-source output cap",
                        description = "Cap B: materialize only the top-N highest-scoring co-engaged items per source per cohort.",
                        type = QueryParameterType.INTEGER,
                        arrayType = null,
                        defaultValue = JsonPrimitive(200),
                        required = false,
                    ),
                ),
            ),
            // --- TFRS recommender training queries (keys the recommendation-trainer executes + pages) ---
            AnalyticsQueryInput(
                key = "recommender-users",
                name = "Recommender: Users",
                description = "Every active profile eligible for the personalized serving index, including profiles without interaction history. Paged via offset/limit.",
                query = """
                    with ${recommendationUsersCtes(pg)}
                    select distinct profile_id as user_id
                    from recommendation_users
                    order by user_id
                    offset :offset limit :limit
                """.trimIndent(),
                parameters = pagingParameters(),
            ),
            AnalyticsQueryInput(
                key = "recommender-interactions",
                name = "Recommender: Interactions",
                description = "Analytics events for personalized training: engagement, exposure, and reading quality with identity and page/session attribution. The trainer combines related events, qualifies ignored impressions, and interprets consumption in percentage points (0–100). Paged against a fixed asOf boundary.",
                query = trainingInteractionsQuery(eventsTable, pg),
                parameters = snapshotParameters(),
            ),
            AnalyticsQueryInput(
                key = "recommender-behavior",
                name = "Recommender: Behavioral Snapshot",
                description = "Analytics-produced co-engagement edges and current cohort memberships captured with a model version. Training uses an earlier completed snapshot only for observations recorded after that snapshot became available.",
                query = trainingBehaviorQuery(pg),
                parameters = snapshotParameters(),
            ),
            AnalyticsQueryInput(
                key = "recommender-content-features",
                name = "Recommender: Content Features",
                description = "Content features and recommendation-context memberships for model training and candidate indexing. Paged via offset/limit.",
                query = """
                    select
                        cast(m.id as varchar) as content_id,
                        m.content_type,
                        case when substr(json_format(json_extract(m.attributes, '$.type')), 1, 1) = '"'
                             then lower(trim(json_extract_scalar(m.attributes, '$.type')))
                             else '' end as editorial_type,
                        coalesce(memberships.collection_ids, cast(array[] as array(varchar))) as collection_ids,
                        coalesce(language_mapping.resolved_language_tag, language_context.fallback_language_tag) as language_tag,
                        m.labels,
                        m.recommendation_contexts
                    from $pg."public".metadata m
                    left join (
                        select ci.child_metadata_id,
                               array_agg(distinct cast(ci.collection_id as varchar)) as collection_ids
                        from $pg."public".collection_items ci
                        join $pg."public".collections c on c.id = ci.collection_id and c.deleted = false
                        where ci.child_metadata_id is not null
                        group by ci.child_metadata_id
                    ) memberships on memberships.child_metadata_id = m.id
                    join $pg."public".language_resolution_contexts language_context
                      on language_context.key = 'recommendations'
                    left join $pg."public".language_tag_mappings language_mapping
                      on language_mapping.context_id = language_context.id
                     and lower(language_mapping.source_language_tag) = lower(m.language_tag)
                    where m.deleted = false
                      and m.recommendable = true
                      and (
                          language_mapping.context_id is not null
                          or lower(m.language_tag) = lower(language_context.fallback_language_tag)
                      )
                    order by content_id
                    offset :offset limit :limit
                """.trimIndent(),
                parameters = pagingParameters(),
            ),
            AnalyticsQueryInput(
                key = "recommender-content-categories",
                name = "Recommender: Content Categories",
                description = "Content-to-category associations for the recommender content tower. Paged via offset/limit.",
                query = """
                    select
                        cast(mc.metadata_id as varchar) as content_id,
                        cast(mc.category_id as varchar) as category_id
                    from $pg."public".metadata_categories mc
                    join $pg."public".metadata m on mc.metadata_id = m.id
                    where m.deleted = false
                      and m.recommendable = true
                    order by content_id
                    offset :offset limit :limit
                """.trimIndent(),
                parameters = pagingParameters(),
            ),
            AnalyticsQueryInput(
                key = "recommender-content-embeddings",
                name = "Recommender: Content Embeddings",
                description = "Ordered semantic embedding chunks and overlap-aware aggregation weights for the recommender content tower. Reads the metadata_embedding view because Trino cannot read the vector type directly; pages content ids so one document's chunks are never split or shifted by chunk count.",
                query = """
                    with content_page as (
                        select e.id as content_id
                        from $pg."public".metadata_embedding e
                        join $pg."public".metadata m on m.id = e.id
                        where m.recommendable = true
                        group by e.id
                        order by e.id
                        offset :offset limit :limit
                    )
                    select
                        cast(e.id as varchar) as content_id,
                        e.chunk_index,
                        e.token_start,
                        e.token_end,
                        e.token_count,
                        e.aggregation_weight,
                        e.embedding
                    from content_page p
                    join $pg."public".metadata_embedding e on e.id = p.content_id
                    order by content_id, e.chunk_index
                """.trimIndent(),
                parameters = pagingParameters(),
            ),
            // Per-user keyed Personalization Signal values — the configurable, self-identifying user-tower
            // features that replaced the profile_type-only `recommender-user-features` (retired: the trainer no
            // longer reads it). Attribute signals are the write-time-cached `ProfileAttribute.signals`, unnested
            // by the `profile_attribute_signals` Postgres view (Trino can't unnest the jsonb itself) and filtered
            // to `use_as_feature` definitions; segment memberships are unioned in as boolean features.
            // `signal_value` is the value's JSON text (parsed per the definition's value_type by the trainer);
            // `value_type` drives that per-type encoding. Keyed by the signal's `key` — a self-identifying feature
            // dimension decoupled from the attribute-type id.
            AnalyticsQueryInput(
                key = "recommender-user-signals",
                name = "Recommender: User Signals",
                description = "Per-(user, signal key, value) personalization signals for the recommender user tower: the write-time-cached ProfileAttribute.signals (via the profile_attribute_signals view) for use_as_feature definitions, plus segment memberships as boolean features. Repeated categorical keys preserve every distinct value. Each row carries the definition's value_type (drives the trainer's per-type encoding) + priority. Empty until signals are defined + computed. Paged via offset/limit.",
                query = """
                    select user_id, signal_key, signal_value, value_type, priority
                    from (
                        select
                            cast(s.user_id as varchar) as user_id,
                            s.signal_key as signal_key,
                            s.signal_value as signal_value,
                            cast(d.value_type as varchar) as value_type,
                            d.priority as priority
                        from $pg."public".profile_attribute_signals s
                        join $pg.recommendations.personalization_signals d
                          on d.key = s.signal_key
                         and d.use_as_feature = true
                         and d.enabled = true
                        union all
                        select
                            cast(sm.profile_id as varchar) as user_id,
                            d.key as signal_key,
                            'true' as signal_value,
                            cast(d.value_type as varchar) as value_type,
                            d.priority as priority
                        from $pg.segmentation.segment_members sm
                        join $pg.recommendations.personalization_signals d
                          on d.source_type = 'segment'
                         and cast(d.source_id as varchar) = cast(sm.segment_id as varchar)
                         and d.use_as_feature = true
                         and d.enabled = true
                    ) signals
                    order by user_id, priority desc, signal_key, signal_value, value_type
                    offset :offset limit :limit
                """.trimIndent(),
                parameters = pagingParameters(),
            ),
            // Explicit feedback labels for the ranker (the feedback->retraining loop). Each row is a
            // strong, supervised engagement label for a (user, content) pair: a 1-5 star rating mapped to
            // [0, 1] as (rating - 1) / 4, and a dismissal as a hard 0 (the strongest negative we have).
            // The trainer overrides the implicit view-percent label with these and weights them higher, and
            // excludes dismissals/negative ratings from the retrieval stage's positive pairs.
            AnalyticsQueryInput(
                key = "recommender-feedback",
                name = "Recommender: Feedback",
                description = "Explicit per-(user, content) feedback (ratings + dismissals) used as supervised labels for the ranking head. Paged via offset/limit.",
                query = trainingFeedbackQuery(pg),
                parameters = snapshotParameters(),
            ),
            AnalyticsQueryInput(
                key = "recommender-guide-completions",
                name = "Recommender: Guide Completions",
                description = "Positive guide and step observations from saved profile progress and completion history. Active progress uses its last-modified time; completed history uses its completion time.",
                query = trainingGuideCompletionsQuery(pg),
                parameters = snapshotParameters(),
            ),
        )

        queries.forEach { queryInput ->
            if (!existingQueries.containsKey(queryInput.key)) {
                val query = analyticsQueryService.addQuery(queryInput)
                analyticsQueryService.addPermission(
                    PermissionInput(entityId = query.id, groupId = adminGroupId, action = PermissionAction.VIEW)
                )
                analyticsQueryService.addPermission(
                    PermissionInput(entityId = query.id, groupId = adminGroupId, action = PermissionAction.EXECUTE)
                )
                log.info("Installed analytics query: {}", queryInput.key)
            } else {
                // editQuery edits + re-inserts parameters by id; carry the existing query's id or the
                // parameter rows reference a NIL query_id and violate the FK. (Idempotent re-install.)
                analyticsQueryService.editQuery(queryInput.copy(id = existingQueries.getValue(queryInput.key).id))
                log.info("Updated analytics query: {}", queryInput.key)
            }
        }
    }

    /**
     * Uses the deployment setting as the source of truth. When an older chart omitted that setting and
     * therefore supplied the default, retain a validated non-default table already present in one of the
     * bundled event queries. This keeps an installer rerun from undoing an instance's manual warehouse fix.
     */
    private fun resolveEventsTable(existingQueries: Map<String, AnalyticsQuery>): String {
        val configured = experimentationConfig.safeEventsTable
        if (configured != ExperimentationConfig.DEFAULT_EVENTS_TABLE) return configured

        for (key in EVENT_QUERY_KEYS) {
            val query = existingQueries[key] ?: continue
            val match = EVENTS_TABLE_PATTERN.find(query.query) ?: continue
            val table = ExperimentationConfig(eventsTable = match.groupValues[1]).safeEventsTable
            if (table == ExperimentationConfig.DEFAULT_EVENTS_TABLE) continue
            log.info("Preserving existing recommendation events table '{}' during installer rerun", table)
            return table
        }
        return configured
    }

    /** Event and feedback pages share a caller-supplied UTC cutoff throughout one training run. */
    private fun snapshotParameters() = listOf(
        AnalyticsQueryParameterInput(
            parameter = "asOf", name = "Snapshot cutoff",
            description = "ISO-8601 timestamp with timezone defining the training snapshot boundary.",
            type = QueryParameterType.STRING, arrayType = null, required = true,
        ),
    ) + pagingParameters()

    /** Offset/limit parameters that let a consumer page through an analytics query's full result set. */
    private fun pagingParameters() = listOf(
        AnalyticsQueryParameterInput(
            parameter = "offset",
            name = "Offset",
            description = "Zero-based row offset for pagination.",
            type = QueryParameterType.INTEGER,
            arrayType = null,
            required = true,
        ),
        AnalyticsQueryParameterInput(
            parameter = "limit",
            name = "Limit",
            description = "Maximum number of rows to return per page.",
            type = QueryParameterType.INTEGER,
            arrayType = null,
            required = true,
        ),
    )

    private suspend fun installStrategies() {
        val existingStrategies = strategyService.getAll(0, 1000)
        val queries = analyticsQueryService.getQueries(0, 1000).associateBy { it.key }

        val trendingQuery = queries["recommendations-trending-content"]
        if (trendingQuery != null && existingStrategies.none { it.type == RecommendationStrategyType.TRENDING }) {
            strategyService.add(
                RecommendationStrategyInput(
                    name = TRENDING_STRATEGY_NAME,
                    description = "Recommends content with the highest recent interaction velocity across all users, refreshed hourly.",
                    type = RecommendationStrategyType.TRENDING,
                    status = RecommendationStrategyStatus.ACTIVE,
                    analyticsQueryId = trendingQuery.id,
                    priority = 1,
                    maxRecommendations = 20,
                    evaluationSchedule = "0 * * * *",
                ),
            )
            log.info("Installed default trending recommendation strategy")
        }

        val coEngagementQuery = queries["recommendations-co-engagement"]
        val existingCoEngagement = existingStrategies.firstOrNull {
            it.type == RecommendationStrategyType.CO_ENGAGEMENT
        }
        if (coEngagementQuery != null && existingCoEngagement == null) {
            strategyService.add(
                RecommendationStrategyInput(
                    name = CO_ENGAGEMENT_STRATEGY_NAME,
                    description = "Behavioral co-engagement (\"people who engaged with this also engaged with…\") powering the item-context surfaces, refreshed daily.",
                    type = RecommendationStrategyType.CO_ENGAGEMENT,
                    status = RecommendationStrategyStatus.ACTIVE,
                    analyticsQueryId = coEngagementQuery.id,
                    priority = 2,
                    maxRecommendations = 20,
                    evaluationSchedule = "0 3 * * *",
                ),
            )
            log.info("Installed default co-engagement recommendation strategy")
        } else if (coEngagementQuery != null && existingCoEngagement != null) {
            val legacyQueryId = queries[LEGACY_CO_ENGAGEMENT_QUERY_KEY]?.id
            val needsCurrentQuery = existingCoEngagement.analyticsQueryId != coEngagementQuery.id
            val usesLegacyQuery = legacyQueryId != null && existingCoEngagement.analyticsQueryId == legacyQueryId
            val isBundledLegacyStrategy = needsCurrentQuery &&
                (usesLegacyQuery ||
                    (existingCoEngagement.name == CO_ENGAGEMENT_STRATEGY_NAME &&
                        existingCoEngagement.analyticsQueryId == null))
            if (isBundledLegacyStrategy) {
                val schedule = existingCoEngagement.scheduledJobId
                    ?.let { schedulerService.getJob(it)?.cronExpression }
                    ?: CO_ENGAGEMENT_CRON
                strategyService.edit(
                    existingCoEngagement.id,
                    RecommendationStrategyInput(
                        name = existingCoEngagement.name,
                        description = existingCoEngagement.description,
                        type = existingCoEngagement.type,
                        status = existingCoEngagement.status,
                        analyticsQueryId = coEngagementQuery.id,
                        configuration = existingCoEngagement.configuration,
                        priority = existingCoEngagement.priority,
                        maxRecommendations = existingCoEngagement.maxRecommendations,
                        evaluationSchedule = schedule,
                    ),
                )
                log.info("Rebound legacy co-engagement strategy to analytics query {}", coEngagementQuery.id)
            }
        }
    }

    /**
     * Seeds the "people like you" cohort co-engagement strategy idempotently — only when no
     * COHORT_CO_ENGAGEMENT strategy exists yet — with a daily materialization schedule (after the whole-crowd
     * co-engagement so both refresh nightly). It produces edges only once useAsCohort personalization signals
     * are configured (its analytics query is empty otherwise), and serving folds them into `recommended`.
     */
    private suspend fun installCohortCoEngagementStrategy() {
        if (strategyService.getAll(0, 1000).any { it.type == RecommendationStrategyType.COHORT_CO_ENGAGEMENT }) return
        val query = analyticsQueryService.getQueries(0, 1000).firstOrNull { it.key == "recommendations-cohort-co-engagement" }
            ?: return
        strategyService.add(
            RecommendationStrategyInput(
                name = "People like you also viewed",
                description = "Behavioral co-engagement conditioned on all of the viewer's cohort memberships (from their personalization signals) — \"people like you who engaged with this also engaged with…\", folded into the item-context recommendations and refreshed daily.",
                type = RecommendationStrategyType.COHORT_CO_ENGAGEMENT,
                status = RecommendationStrategyStatus.ACTIVE,
                analyticsQueryId = query.id,
                priority = 3,
                maxRecommendations = 20,
                evaluationSchedule = "0 4 * * *",
                // Default tuning knobs (200/200), editable via the strategy's configuration without re-seeding.
                configuration = Json.encodeToJsonElement(
                    CohortCoEngagementConfiguration.serializer(), CohortCoEngagementConfiguration(),
                ),
            ),
        )
        log.info("Installed cohort co-engagement recommendation strategy")
    }

    /**
     * Seeds the PERSONALIZED strategy (TensorFlow Serving) idempotently when one does not already exist.
     * Served live per request for the requesting profile (see `RecommendationServiceImpl.getForProfile`).
     * Producing results requires the recommendation-trainer + tf-serving services deployed and interaction
     * events in the warehouse.
     */
    private suspend fun installMlModelStrategy() {
        if (strategyService.getAll(0, 1000).any { it.type == RecommendationStrategyType.PERSONALIZED }) return

        strategyService.add(
            RecommendationStrategyInput(
                name = "Personalized (ML Model)",
                description = "Per-user recommendations from the two-tower TFRS model served by TensorFlow Serving. Served live, per request (retrieved and ranked on demand and cached per profile) — not precomputed — so it has no evaluation schedule; it just needs a trained model loaded in TF Serving.",
                type = RecommendationStrategyType.PERSONALIZED,
                status = RecommendationStrategyStatus.ACTIVE,
                configuration = Json.encodeToJsonElement(TfServingConfiguration.serializer(), TfServingConfiguration()),
                priority = 3,
                maxRecommendations = 20,
                // No schedule: the ML feed is computed live per request (see RecommendationServiceImpl), so
                // there's nothing to precompute/evaluate on a cron.
                evaluationSchedule = null,
            ),
        )
        log.info("Installed ML model (TF Serving) recommendation strategy")
    }

    /**
     * Ensures model training has a recurring scheduler entry independently of strategy seeding.
     *
     * Existing installations can already have the PERSONALIZED strategy while missing the schedule (for
     * example, when the strategy pre-dates scheduler support or the schedule was removed). Looking up the
     * registered job definition also respects an operator-provided schedule and avoids creating a duplicate.
     */
    private suspend fun installMlTrainingSchedule() {
        if (schedulerService.getJobsByName(TRAIN_MODEL_JOB_NAME, limit = 1).isNotEmpty()) return
        schedulerService.createJob(
            input = ScheduledJobInput(
                name = TRAIN_MODEL_SCHEDULE_NAME,
                description = "Triggers the recommendation-trainer to train the two-tower TFRS model from warehouse interaction data.",
                jobName = TRAIN_MODEL_JOB_NAME,
                jobParameters = Json.encodeToJsonElement(TrainModelJob.serializer(), TrainModelJob()),
                cronExpression = TRAIN_MODEL_CRON,
                enabled = true,
                allowConcurrent = false,
            ),
            createdBy = UUID.NIL,
        )
        log.info("Scheduled daily recommendation model training (train-model)")
    }

    private suspend fun installPlacements() {
        val existingPlacements = placementService.getAll()
        if (existingPlacements.isNotEmpty()) return

        val strategies = strategyService.getAll(0, 1000)
        val strategyIds = strategies.map { it.id }

        placementService.add(
            input = RecommendationPlacementInput(
                name = "Home Feed",
                description = "Primary recommendation placement on the home screen, blending results from all active strategies.",
                slug = "home_feed",
                maxItems = 10,
            ),
            strategyIds = strategyIds,
        )
        log.info("Installed default home_feed recommendation placement")
    }

    /**
     * Seeds the triggered content-classification pipeline idempotently: when a content item
     * becomes ready (`MetadataSetReady`), the engine runs `Input → ClassifyNode` to assign the
     * recommendation features used for candidate ranking. The graph is data; the node is code.
     */
    private suspend fun installClassificationPipeline() {
        val existing = pipelineService.getByKey(CLASSIFICATION_PIPELINE_KEY)
        if (existing != null) {
            repairMissingGeneratedEdge(existing, "classify", ClassifyNode::class.java, "input-classify")
            return
        }

        val pipeline = Pipeline(
            id = UUID.NIL,
            name = "Content Classification",
            description = "Classifies content as it becomes ready, assigning normalized categories used for recommendations.",
            acceptedInputType = METADATA_SET_READY_EVENT,
            triggered = true,
            key = CLASSIFICATION_PIPELINE_KEY,
            nodes = listOf(
                InputNode(id = "input", acceptedType = METADATA_SET_READY_EVENT),
                ClassifyNode(id = "classify"),
            ),
            edges = listOf(PipelineEdge(id = "input-classify", source = "input", target = "classify")),
        )
        pipelineService.save(
            id = UUID.NIL,
            name = pipeline.name,
            description = pipeline.description,
            acceptedInputType = METADATA_SET_READY_EVENT,
            triggered = true,
            version = 0,
            graph = pipelineService.graphAsJsonElement(pipeline),
            key = CLASSIFICATION_PIPELINE_KEY,
        )
        log.info("Installed content classification pipeline (triggered on {})", METADATA_SET_READY_EVENT)
    }

    /**
     * Installs the write-time Personalization Signal compute pipelines. Whenever a profile's
     * attributes are added or updated, the engine runs `Input(event) → ComputeProfileSignalsNode`, which
     * recomputes + caches each affected attribute's signals (so the trainer/cohort path reads them via plain
     * Trino, no JVM JSONata). Two triggered pipelines — one per event — since a pipeline accepts a single
     * input type. Keyed for idempotency; the graph is data, the node is code.
     */
    private suspend fun installSignalComputePipelines() {
        installSignalComputePipeline(
            key = SIGNAL_COMPUTE_ADDED_KEY,
            name = "Compute Personalization Signals (attributes added)",
            eventType = PROFILE_ATTRIBUTES_ADDED_EVENT,
        )
        installSignalComputePipeline(
            key = SIGNAL_COMPUTE_UPDATED_KEY,
            name = "Compute Personalization Signals (attributes updated)",
            eventType = PROFILE_ATTRIBUTES_UPDATED_EVENT,
        )
        installSignalComputePipeline(
            key = SIGNAL_COMPUTE_VERIFIED_KEY,
            name = "Compute Personalization Signals (attributes verified)",
            eventType = PROFILE_ATTRIBUTES_VERIFIED_EVENT,
        )
    }

    private suspend fun installSignalComputePipeline(key: String, name: String, eventType: String) {
        val existing = pipelineService.getByKey(key)
        if (existing != null) {
            repairMissingGeneratedEdge(existing, "compute", ComputeProfileSignalsNode::class.java, "input-compute")
            return
        }

        val pipeline = Pipeline(
            id = UUID.NIL,
            name = name,
            description = "Recomputes and caches Personalization Signals for a profile's attributes when they change.",
            acceptedInputType = eventType,
            triggered = true,
            key = key,
            nodes = listOf(
                InputNode(id = "input", acceptedType = eventType),
                ComputeProfileSignalsNode(id = "compute"),
            ),
            edges = listOf(PipelineEdge(id = "input-compute", source = "input", target = "compute")),
        )
        pipelineService.save(
            id = UUID.NIL,
            name = pipeline.name,
            description = pipeline.description,
            acceptedInputType = eventType,
            triggered = true,
            version = 0,
            graph = pipelineService.graphAsJsonElement(pipeline),
            key = key,
        )
        log.info("Installed personalization-signal compute pipeline (triggered on {})", eventType)
    }

    /**
     * Installs the learned-interest inference pipeline (Phase 4): on a `ProfileRatingAdded`
     * event, `Input → InferInterestNode` records a learned (confidence < 100) interest in the rated content's
     * category, which the signal-compute pipeline then picks up subject to each signal's confidence gate.
     * Keyed for idempotency; the graph is data, the node is code.
     */
    private suspend fun installInferInterestPipeline() {
        val existing = pipelineService.getByKey(INFER_INTEREST_PIPELINE_KEY)
        if (existing != null) {
            repairMissingGeneratedEdge(existing, "infer", InferInterestNode::class.java, "input-infer")
            return
        }

        val pipeline = Pipeline(
            id = UUID.NIL,
            name = "Infer Learned Interest",
            description = "On a high content rating, records a learned interest in the rated content's category.",
            acceptedInputType = PROFILE_RATING_ADDED_EVENT,
            triggered = true,
            key = INFER_INTEREST_PIPELINE_KEY,
            nodes = listOf(
                InputNode(id = "input", acceptedType = PROFILE_RATING_ADDED_EVENT),
                InferInterestNode(id = "infer"),
            ),
            edges = listOf(PipelineEdge(id = "input-infer", source = "input", target = "infer")),
        )
        pipelineService.save(
            id = UUID.NIL,
            name = pipeline.name,
            description = pipeline.description,
            acceptedInputType = PROFILE_RATING_ADDED_EVENT,
            triggered = true,
            version = 0,
            graph = pipelineService.graphAsJsonElement(pipeline),
            key = INFER_INTEREST_PIPELINE_KEY,
        )
        log.info("Installed learned-interest inference pipeline (triggered on {})", PROFILE_RATING_ADDED_EVENT)
    }

    /**
     * Repairs only the original generated two-node graph when its sole edge is absent. This keeps
     * every stored node setting, position, group, and pipeline option while avoiding changes to a
     * graph an operator has expanded, rewired, or replaced with another node type.
     */
    private suspend fun repairMissingGeneratedEdge(
        existing: Pipeline,
        targetId: String,
        targetClass: Class<out PipelineNode>,
        edgeId: String,
    ) {
        if (existing.edges.isNotEmpty() || existing.nodes.size != 2) return
        val input = existing.nodes.singleOrNull { it.id == "input" }
        val target = existing.nodes.singleOrNull { it.id == targetId }
        if (input !is InputNode || !targetClass.isInstance(target)) return

        val repaired = existing.copy(
            edges = listOf(PipelineEdge(id = edgeId, source = "input", target = targetId)),
        )
        pipelineService.save(
            id = existing.id,
            name = existing.name,
            description = existing.description,
            acceptedInputType = existing.acceptedInputType,
            triggered = existing.triggered,
            version = existing.version,
            graph = pipelineService.graphAsJsonElement(repaired),
            tags = existing.tags,
            key = existing.key,
            api = existing.api,
            public = existing.public,
            schedule = existing.schedule,
            maxConcurrentRuns = existing.maxConcurrentRuns,
            maxRunsPerMinute = existing.maxRunsPerMinute,
        )
        log.info("Repaired missing generated edge in recommendation pipeline {} ({})", existing.key, existing.id)
    }

    companion object {
        /** Retains the recent-engagement pool so serving can filter each context and language before pagination. */
        internal fun trendingContentQuery(eventsTable: String): String = """
            with interactions as (
                select
                    c.id as metadata_id,
                    count(*) as interaction_count,
                    max(e.created) as last_interaction
                from $eventsTable e
                cross join unnest(e.element.content) as c(id, type, "index", percent)
                where e.created >= current_date - interval '7' day
                  and $ENGAGEMENT_EVENT_PREDICATE
                  and $BOT_USER_AGENT_PREDICATE
                  and c.id is not null
                group by c.id
            )
            select
                metadata_id,
                cast(interaction_count * (1.0 / (1.0 + date_diff('day', last_interaction, current_timestamp))) as double) as score,
                'Trending in the last 7 days' as reason
            from interactions
            order by score desc, metadata_id
        """.trimIndent()

        internal fun trainingBehaviorQuery(pg: String = DEFAULT_POSTGRES_CATALOG): String = """
            with cutoff as (select from_iso8601_timestamp(:asOf) as boundary)
            select * from (
                select 'global' as kind, '' as user_id,
                       cast(e.source_metadata_id as varchar) as source_id,
                       cast(e.co_engaged_metadata_id as varchar) as content_id,
                       '' as cohort_key, max(e.score) as score
                from $pg.recommendations.co_engagements e
                join $pg.recommendations.strategies s on s.id = e.strategy_id and s.status = 'active'
                where e.created <= (select boundary from cutoff)
                group by e.source_metadata_id, e.co_engaged_metadata_id
                union all
                select 'cohort', '', cast(e.source_metadata_id as varchar), cast(e.co_engaged_metadata_id as varchar),
                       e.cohort_key, max(e.score)
                from $pg.recommendations.cohort_co_engagements e
                join $pg.recommendations.strategies s on s.id = e.strategy_id and s.status = 'active'
                where e.created <= (select boundary from cutoff)
                group by e.source_metadata_id, e.co_engaged_metadata_id, e.cohort_key
                union all
                select 'membership', cast(user_id as varchar), '', '', cohort_key, cast(0 as double)
                from $pg.recommendations.profile_cohort
            ) snapshot
            order by kind, user_id, source_id, content_id, cohort_key
            offset :offset limit :limit
        """.trimIndent()

        internal fun trainingInteractionsQuery(eventsTable: String, pg: String = DEFAULT_POSTGRES_CATALOG): String = """
            with ${recommendationUsersCtes(pg)},
            snapshot as (select cast(from_iso8601_timestamp(:asOf) at time zone 'UTC' as timestamp) as boundary)
            select
                u.profile_id as user_id,
                c.id as content_id,
                e.type as interaction_type,
                c.percent as view_percent,
                to_iso8601(with_timezone(e.created, 'UTC')) as interaction_created,
                e.client_id as event_id,
                e.context.app_id as app_id,
                e.context.session_id as session_id,
                case when e.element.type in ('scroll_depth', 'scroll_max_depth')
                     then nullif(e.element.id, '')
                     else coalesce(nullif(e.page.path, ''),
                         case when e.element.type = 'page' then nullif(e.element.id, '') end)
                end as page_id,
                e.element.type as element_type,
                try_cast(coalesce(
                    try(json_extract_scalar(e.element.extras, '$.max_depth_percent')),
                    try(json_extract_scalar(e.element.extras, '$.depth_percent'))
                ) as double) as depth_percent,
                try_cast(coalesce(
                    try(json_extract_scalar(e.element.extras, '$.visible_ms')),
                    try(json_extract_scalar(e.element.extras, '$.dwell_ms'))
                ) as double) as visible_ms,
                try_cast(try(json_extract_scalar(e.element.extras, '$.visibility_threshold')) as double) as visibility_threshold,
                try(json_extract_scalar(e.element.extras, '$.guide_id')) as guide_id,
                try(json_extract_scalar(e.element.extras, '$.guide_version')) as guide_version,
                try(json_extract_scalar(e.element.extras, '$.guide_started')) as guide_started,
                try(json_extract_scalar(e.element.extras, '$.recommendation_source_id')) as recommendation_source_id,
                try(json_extract_scalar(e.element.extras, '$.recommendation_context')) as recommendation_context,
                try(json_extract_scalar(e.element.extras, '$.recommendation_model_version')) as recommendation_model_version,
                try(json_extract_scalar(e.element.extras, '$.recommendation_request_id')) as recommendation_request_id
            from $eventsTable e
            join recommendation_users u on u.event_user_id = e.context.user_id
            cross join snapshot
            left join unnest(e.element.content) as c(id, type, "index", percent) on true
            where e.context.user_id is not null
              and e.created >= boundary - interval '365' day
              and e.created <= boundary
              and e.type in ('Interaction', 'Completion', 'Impression')
              and $BOT_USER_AGENT_PREDICATE
              and (c.id is not null or e.element.type in ('page', 'scroll_depth', 'scroll_max_depth'))
            order by user_id, interaction_created, event_id, content_id, element_type, page_id
            offset :offset limit :limit
        """.trimIndent()

        /**
         * Read durable guide state without requiring clients to send completion analytics. Active steps
         * share the progress row's observation time; history establishes that all versioned steps were
         * complete by its completion time. Neither source records individual step completion times.
         */
        internal fun trainingGuideCompletionsQuery(pg: String = DEFAULT_POSTGRES_CATALOG): String = """
            with snapshot as (select from_iso8601_timestamp(:asOf) as boundary),
            active_steps as (
                select
                    cast(p.profile_id as varchar) as user_id,
                    cast(p.metadata_id as varchar) as guide_id,
                    cast(s.step_metadata_id as varchar) as step_content_id,
                    s.id as step_id,
                    p.modified as observed,
                    cast(p.version as varchar) as guide_version,
                    to_iso8601(p.started at time zone 'UTC') as guide_started,
                    concat('guide-progress:', cast(p.profile_id as varchar), ':',
                        cast(p.metadata_id as varchar), ':', cast(p.version as varchar)) as state_id
                from $pg."public".profile_guide_progress p
                join $pg."public".profiles profile on profile.id = p.profile_id and profile.deleted_at is null
                join $pg."public".guide_steps s on s.metadata_id = p.metadata_id and s.version = p.version
                    and contains(p.completed_step_ids, s.id)
                cross join snapshot
                where p.modified >= boundary - interval '365' day and p.modified <= boundary
            ),
            completed_guides as (
                select
                    cast(h.profile_id as varchar) as user_id,
                    h.metadata_id,
                    h.version,
                    h.completed as observed,
                    concat('guide-history:', cast(h.id as varchar)) as state_id
                from $pg."public".profile_guide_history h
                join $pg."public".profiles profile on profile.id = h.profile_id and profile.deleted_at is null
                cross join snapshot
                where h.completed >= boundary - interval '365' day and h.completed <= boundary
            ),
            observations as (
                -- One parent observation per active progression, regardless of its number of steps.
                select distinct user_id, guide_id as content_id, observed,
                    state_id as event_id, 'guide_progress' as element_type,
                    guide_id, guide_version, guide_started
                from active_steps
                union all
                select user_id, step_content_id, observed,
                    concat(state_id, ':step:', cast(step_id as varchar)), 'guide_step',
                    guide_id, guide_version, guide_started
                from active_steps
                union all
                select user_id, cast(metadata_id as varchar), observed, state_id, 'guide',
                    cast(metadata_id as varchar), cast(version as varchar), cast(null as varchar)
                from completed_guides
                union all
                select h.user_id, cast(s.step_metadata_id as varchar), h.observed,
                    concat(h.state_id, ':step:', cast(s.id as varchar)), 'guide_step',
                    cast(h.metadata_id as varchar), cast(h.version as varchar), cast(null as varchar)
                from completed_guides h
                join $pg."public".guide_steps s on s.metadata_id = h.metadata_id and s.version = h.version
            )
            select user_id, content_id, 'Completion' as interaction_type,
                cast(null as double) as view_percent,
                to_iso8601(observed at time zone 'UTC') as interaction_created,
                event_id, 'bosca-guide-state' as app_id,
                cast(null as varchar) as session_id, cast(null as varchar) as page_id,
                element_type, cast(null as double) as depth_percent,
                cast(null as double) as visible_ms, cast(null as double) as visibility_threshold,
                guide_id, guide_version, guide_started
            from observations
            order by user_id, interaction_created, event_id, content_id, element_type
            offset :offset limit :limit
        """.trimIndent()

        internal fun trainingFeedbackQuery(pg: String = DEFAULT_POSTGRES_CATALOG): String = """
            with snapshot as (select from_iso8601_timestamp(:asOf) as boundary)
            select
                cast(r.profile_id as varchar) as user_id,
                cast(r.metadata_id as varchar) as content_id,
                (cast(r.rating as double) - 1.0) / 4.0 as feedback_label,
                'rating' as feedback_source,
                r.created as feedback_created
            from $pg."public".profile_ratings r
            join $pg."public".profiles p on p.id = r.profile_id and p.deleted_at is null
            cross join snapshot
            where r.metadata_id is not null and r.created <= boundary
            union all
            select
                cast(d.profile_id as varchar) as user_id,
                cast(d.metadata_id as varchar) as content_id,
                cast(0.0 as double) as feedback_label,
                'dismissal' as feedback_source,
                d.created as feedback_created
            from $pg.recommendations.dismissals d
            join $pg."public".profiles p on p.id = d.profile_id and p.deleted_at is null
            cross join snapshot
            where d.metadata_id is not null and d.created <= boundary
            order by user_id, content_id, feedback_created, feedback_source, feedback_label
            offset :offset limit :limit
        """.trimIndent()

        private const val CLASSIFICATION_PIPELINE_KEY = "recommendations-classify"
        private const val METADATA_SET_READY_EVENT = "bosca.content.metadata.events.MetadataSetReady"
        private const val SIGNAL_COMPUTE_ADDED_KEY = "recommendations-compute-signals-added"
        private const val SIGNAL_COMPUTE_UPDATED_KEY = "recommendations-compute-signals-updated"
        private const val SIGNAL_COMPUTE_VERIFIED_KEY = "recommendations-compute-signals-verified"
        private const val PROFILE_ATTRIBUTES_ADDED_EVENT = "bosca.profile.attribute.events.ProfileAttributesAdded"
        private const val PROFILE_ATTRIBUTES_UPDATED_EVENT = "bosca.profile.attribute.events.ProfileAttributesUpdated"
        private const val PROFILE_ATTRIBUTES_VERIFIED_EVENT = "bosca.profile.attribute.events.ProfileAttributesVerified"
        private const val INFER_INTEREST_PIPELINE_KEY = "recommendations-infer-interest"
        private const val PROFILE_RATING_ADDED_EVENT = "bosca.profile.rating.events.ProfileRatingAdded"
        private const val TRAIN_MODEL_JOB_NAME = "train-model"
        private const val TRAIN_MODEL_SCHEDULE_NAME = "Train recommendation model"
        private const val TRAIN_MODEL_CRON = "0 2 * * *"
        private const val TRENDING_STRATEGY_NAME = "Trending Content"
        private const val CO_ENGAGEMENT_STRATEGY_NAME = "People also viewed"
        private const val CO_ENGAGEMENT_CRON = "0 3 * * *"
        private const val LEGACY_CO_ENGAGEMENT_QUERY_KEY = "recommendations-related-cooccurrence"
        /** Scroll milestones are interactions that describe view quality, not additional engagements. */
        private const val ENGAGEMENT_EVENT_PREDICATE =
            "((e.type in ('Interaction', 'Completion') or " +
                "(e.type = 'Impression' and e.element.type = 'page')) and not " +
                "(e.type = 'Interaction' and " +
                "coalesce(e.element.type, '') in ('scroll_depth', 'scroll_max_depth')))"
        private const val BOT_USER_AGENT_PREDICATE =
            "not regexp_like(lower(coalesce(e.context.browser.agent, '')), " +
                "'bot|crawl|spider|headless|phantomjs|googleother|google-inspectiontool|" +
                "mediapartners-google|microsoftpreview|bingvideopreview|facebookexternalhit|chatgpt-user')"
        private fun recommendationUsersCtes(pg: String): String = """
            principal_profile_candidates as (
                select
                    cast(principal.id as varchar) as event_user_id,
                    cast(owned_profile.id as varchar) as profile_id,
                    row_number() over (
                        partition by principal.id
                        order by owned_profile.created desc, owned_profile.id desc
                    ) as profile_rank
                from $pg."public".principals principal
                join $pg."public".profiles owned_profile
                  on owned_profile.principal = principal.id
                 and owned_profile.deleted_at is null
                where principal.deleted_at is null
                  and (
                      principal.primary_profile_id is null
                      or owned_profile.id = principal.primary_profile_id
                  )
            ),
            recommendation_users as (
                -- Analytics records authenticated principals, while recommendation APIs and model
                -- features are profile-scoped. Match ProfileService.getPrimaryProfile by using the
                -- configured primary profile or, when absent, the newest active owned profile. Accept
                -- profile-keyed events too so clients can migrate without splitting one person's history.
                select
                    cast(p.id as varchar) as event_user_id,
                    cast(p.id as varchar) as profile_id
                from $pg."public".profiles p
                where p.deleted_at is null
                union
                select
                    event_user_id,
                    profile_id
                from principal_profile_candidates
                where profile_rank = 1
            )
        """.trimIndent()
        private val EVENT_QUERY_KEYS = listOf(
            "recommendations-trending-content",
            "recommendations-co-engagement",
            "recommendations-cohort-co-engagement",
            "recommender-interactions",
        )
        private val EVENTS_TABLE_PATTERN = Regex(
            """\bfrom\s+([a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z_][a-zA-Z0-9_]*){0,2})\s+e\b""",
            RegexOption.IGNORE_CASE,
        )
        private const val DEFAULT_POSTGRES_CATALOG = ExperimentationConfig.DEFAULT_POSTGRES_CATALOG
        private val log = LoggerFactory.getLogger(RecommendationsInstaller::class.java)
    }
}
