@file:OptIn(ExperimentalUuidApi::class, bosca.di.annotation.InternalDI::class)

package bosca.recommendations.installer

import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryInput
import bosca.analytics.service.AnalyticsQueryService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.languages.model.Language
import bosca.languages.model.LanguageResolutionContext
import bosca.languages.model.LanguageTagMapping
import bosca.languages.service.LanguagesService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.experimentation.configuration.ExperimentationConfig
import bosca.pipelines.model.Pipeline
import bosca.pipelines.node.InputNode
import bosca.pipelines.service.PipelineService
import bosca.recommendations.model.RecommendationStrategy
import bosca.recommendations.model.RecommendationStrategyInput
import bosca.recommendations.model.RecommendationStrategyType
import bosca.recommendations.configuration.JobQueueNames
import bosca.recommendations.pipeline.ComputeProfileSignalsNode
import bosca.recommendations.service.CohortCoEngagementConfiguration
import bosca.recommendations.service.RecommendationPlacementService
import bosca.recommendations.service.RecommendationStrategyService
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.service.SchedulerService
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Unit tests for [RecommendationsInstaller] — the first-install seeding of analytics queries, the three
 * strategies (trending + co-engagement materialized, ML served live), the home_feed placement, and the
 * triggered classification pipeline. Every collaborator is mocked; the tests pin the idempotency guards
 * (each step no-ops when its artifact already exists) and the fresh-install fan-out.
 */
class RecommendationsInstallerTest {

    private val analyticsQueryService = mockk<AnalyticsQueryService>(relaxed = true)
    private val strategyService = mockk<RecommendationStrategyService>(relaxed = true)
    private val placementService = mockk<RecommendationPlacementService>(relaxed = true)
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val pipelineService = mockk<PipelineService>(relaxed = true)
    private val schedulerService = mockk<SchedulerService>(relaxed = true)
    private val languagesService = mockk<LanguagesService>(relaxed = true)
    private val languageContext = LanguageResolutionContext(
        id = UUID.random(),
        key = "recommendations",
        name = "Recommendations",
        fallbackLanguageTag = "en",
        isProtected = true,
    )

    private val installer = RecommendationsInstaller(
        analyticsQueryService, strategyService, placementService,
        securityService, pipelineService, schedulerService, languagesService, ExperimentationConfig(),
    )

    private val jobQueue = mockk<JobQueue>(relaxed = true)
    private val installation = mockk<PackageInstallation>(relaxed = true)
    private val version = mockk<PackageInstallationVersion>(relaxed = true)

    private fun query(key: String, queryText: String = "") = mockk<AnalyticsQuery> {
        every { id } returns UUID.random()
        every { this@mockk.key } returns key
        every { query } returns queryText
    }

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json }
        provides<JobQueue>(name = JobQueueNames.recommendationsJobQueue, singleton = true) { jobQueue }
        coEvery { securityService.getGroupByName("administrators", GroupType.SYSTEM) } returns
            mockk<Group> { every { id } returns UUID.random() }
        coEvery { analyticsQueryService.addQuery(any()) } returns query("added")
        coEvery { pipelineService.graphAsJsonElement(any()) } returns JsonPrimitive("{}")
        coEvery { languagesService.getResolutionContext("recommendations") } returns languageContext
        coEvery { languagesService.getLanguageTagMappings(languageContext.id) } returns emptyList()
        coEvery { languagesService.getAll() } returns listOf(Language("en", "English", "English"))
        coEvery { languagesService.setLanguageTagMapping(any(), any()) } answers {
            val input = secondArg<bosca.languages.model.LanguageTagMappingInput>()
            LanguageTagMapping(firstArg(), input.sourceLanguageTag, input.resolvedLanguageTag)
        }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    @Test
    fun `install throws when the administrators group is missing`() = runTest {
        coEvery { securityService.getGroupByName("administrators", GroupType.SYSTEM) } returns null
        assertFailsWith<IllegalStateException> { installer.install(installation, version) }
    }

    @Test
    fun `install protects an existing recommendation language context`() = runTest {
        val unprotected = languageContext.copy(isProtected = false)
        coEvery { languagesService.getResolutionContext("recommendations") } returns unprotected
        coEvery { languagesService.protectResolutionContext(unprotected.id) } returns languageContext
        coEvery { languagesService.getLanguageTagMappings(languageContext.id) } returns emptyList()

        installer.install(installation, version)

        coVerify(exactly = 1) { languagesService.protectResolutionContext(unprotected.id) }
        coVerify(exactly = 1) { languagesService.getLanguageTagMappings(languageContext.id) }
    }

    @Test
    fun `install creates the recommendation language context as protected`() = runTest {
        coEvery { languagesService.getResolutionContext("recommendations") } returns null
        coEvery { languagesService.addResolutionContext(any(), true) } returns languageContext

        installer.install(installation, version)

        coVerify(exactly = 1) { languagesService.addResolutionContext(any(), true) }
        coVerify(exactly = 0) { languagesService.protectResolutionContext(any()) }
    }

    @Test
    fun `installation does not queue context recomputation or model training`() = runTest {
        installer.install(installation, version)
        coVerify(exactly = 0) { jobQueue.enqueue(any()) }
    }

    @Test
    fun `fresh install seeds queries, all four strategies, the placement and the pipelines`() = runTest {
        // No queries exist on the first call (add path); the second call (installStrategies) sees the two
        // materialized-strategy queries; the third (installCohortCoEngagementStrategy) also sees the cohort query.
        val trending = query("recommendations-trending-content")
        val coEngagement = query("recommendations-co-engagement")
        val cohort = query("recommendations-cohort-co-engagement")
        coEvery { analyticsQueryService.getQueries(0, 1000) } returnsMany
            listOf(emptyList(), listOf(trending, coEngagement), listOf(trending, coEngagement, cohort))
        coEvery { strategyService.getAll(0, 1000) } returns emptyList()
        coEvery { placementService.getAll() } returns emptyList()
        coEvery { pipelineService.getByKey(any()) } returns null

        installer.install(installation, version)

        // New analytics queries are added with VIEW + EXECUTE permissions.
        coVerify { analyticsQueryService.addQuery(any()) }
        coVerify(atLeast = 2) { analyticsQueryService.addPermission(any()) }
        // Trending + co-engagement + cohort co-engagement (materialized) and the ML model strategy are all seeded.
        coVerify(exactly = 4) { strategyService.add(any()) }
        // The daily model-training job is scheduled.
        coVerify(exactly = 1) { schedulerService.createJob(any(), any()) }
        coVerify(exactly = 1) { placementService.add(any(), any()) }
        // Five triggered pipelines: content classification, the three write-time signal-compute pipelines
        // (attributes added / updated / verified), and the learned-interest inference pipeline.
        coVerify(exactly = 5) { pipelineService.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cohort query declares the tunable caps and the strategy is seeded with default config`() = runTest {
        val trending = query("recommendations-trending-content")
        val coEngagement = query("recommendations-co-engagement")
        val cohort = query("recommendations-cohort-co-engagement")
        coEvery { analyticsQueryService.getQueries(0, 1000) } returnsMany
            listOf(emptyList(), listOf(trending, coEngagement), listOf(trending, coEngagement, cohort))
        coEvery { strategyService.getAll(0, 1000) } returns emptyList()
        coEvery { placementService.getAll() } returns emptyList()
        coEvery { pipelineService.getByKey(any()) } returns null

        val addedQueries = mutableListOf<AnalyticsQueryInput>()
        coEvery { analyticsQueryService.addQuery(capture(addedQueries)) } returns query("added")
        val addedStrategies = mutableListOf<RecommendationStrategyInput>()
        coEvery { strategyService.add(capture(addedStrategies)) } returns mockk(relaxed = true)

        installer.install(installation, version)

        // The cohort query declares basketCap + sourceCap (in that order — positional JDBC binding) and
        // references both placeholders; a name/placeholder mismatch would silently unbind the parameters.
        val cohortQuery = addedQueries.single { it.key == "recommendations-cohort-co-engagement" }
        assertEquals(listOf("basketCap", "sourceCap"), cohortQuery.parameters.map { it.parameter })
        assertTrue(cohortQuery.query.contains(":basketCap"), "query must reference :basketCap")
        assertTrue(cohortQuery.query.contains(":sourceCap"), "query must reference :sourceCap")
        assertTrue(cohortQuery.query.contains("partition by cohort_key, user_id"))
        assertTrue(cohortQuery.query.contains("on a.cohort_key = b.cohort_key"))
        val coEngagementQuery = addedQueries.single { it.key == "recommendations-co-engagement" }
        assertEquals(listOf("offset", "limit"), coEngagementQuery.parameters.map { it.parameter })
        assertTrue(coEngagementQuery.query.contains("offset :offset limit :limit"))
        assertTrue(coEngagementQuery.query.contains("order by source_id, score desc, co_engaged_id"))
        val contentFeatures = addedQueries.single { it.key == "recommender-content-features" }
        assertTrue(contentFeatures.query.contains("m.labels"))
        assertFalse(contentFeatures.query.contains("array_join(m.labels"))
        assertTrue(contentFeatures.query.contains("m.recommendation_contexts"))
        assertFalse(contentFeatures.query.contains("array_join(m.recommendation_contexts"))
        assertTrue(contentFeatures.query.contains("m.recommendable = true"))
        assertTrue(contentFeatures.query.contains("left join bosca.\"public\".language_tag_mappings"))
        assertTrue(contentFeatures.query.contains("language_context.fallback_language_tag"))
        val users = addedQueries.single { it.key == "recommender-users" }
        assertTrue(users.query.contains("select distinct profile_id as user_id"))
        assertTrue(users.query.contains("where p.deleted_at is null"))
        assertEquals(listOf("offset", "limit"), users.parameters.map { it.parameter })
        val trendingQuery = addedQueries.single { it.key == "recommendations-trending-content" }
        assertEquals(RecommendationsInstaller.trendingContentQuery("warehouse.bosca.events"), trendingQuery.query)
        val interactions = addedQueries.single { it.key == "recommender-interactions" }
        assertTrue(interactions.query.contains("to_iso8601(with_timezone(e.created, 'UTC')) as interaction_created"))
        assertTrue(interactions.query.contains("order by user_id, interaction_created, event_id, content_id"))
        assertEquals(listOf("asOf", "offset", "limit"), interactions.parameters.map { it.parameter })
        assertTrue(interactions.query.contains("left join unnest(e.element.content)"))
        assertTrue(interactions.query.contains("visibility_threshold"))
        assertTrue(interactions.query.contains("depth_percent"))
        assertTrue(interactions.query.contains("e.client_id as event_id"))
        listOf(trendingQuery, coEngagementQuery, cohortQuery).forEach { engagementQuery ->
            assertTrue(
                engagementQuery.query.contains(
                    "e.type in ('Interaction', 'Completion') or " +
                        "(e.type = 'Impression' and e.element.type = 'page')",
                ),
                "${engagementQuery.key} must include page impressions as engagement",
            )
            assertFalse(
                engagementQuery.query.contains("e.type in ('Interaction', 'Impression', 'Completion')"),
                "${engagementQuery.key} must exclude passive impressions",
            )
            assertTrue(
                engagementQuery.query.contains(
                    "coalesce(e.element.type, '') in ('scroll_depth', 'scroll_max_depth')",
                ),
                "${engagementQuery.key} must not count scroll milestones as separate engagements",
            )
        }
        val contentCategories = addedQueries.single { it.key == "recommender-content-categories" }
        assertTrue(contentCategories.query.contains("m.recommendable = true"))
        val contentEmbeddings = addedQueries.single { it.key == "recommender-content-embeddings" }
        assertTrue(contentEmbeddings.query.contains("m.recommendable = true"))
        assertTrue(contentEmbeddings.query.contains("e.chunk_index"))
        assertTrue(contentEmbeddings.query.contains("e.token_start"))
        assertTrue(contentEmbeddings.query.contains("e.token_end"))
        assertTrue(contentEmbeddings.query.contains("e.token_count"))
        assertTrue(contentEmbeddings.query.contains("e.aggregation_weight"))
        assertTrue(contentEmbeddings.query.contains("with content_page as"))
        assertTrue(contentEmbeddings.query.contains("order by content_id, e.chunk_index"))
        val userSignals = addedQueries.single { it.key == "recommender-user-signals" }
        assertTrue(
            userSignals.query.contains(
                "order by user_id, priority desc, signal_key, signal_value, value_type",
            ),
        )

        // The cohort strategy is seeded with the default 200/200 configuration.
        val cohortStrategy = addedStrategies.single { it.type == RecommendationStrategyType.COHORT_CO_ENGAGEMENT }
        val config = Json.decodeFromJsonElement(
            CohortCoEngagementConfiguration.serializer(), cohortStrategy.configuration!!,
        )
        assertEquals(200, config.perUserItemCap)
        assertEquals(200, config.perSourceCap)
    }

    @Test
    fun `event-backed queries use the configured warehouse table`() = runTest {
        val addedQueries = mutableListOf<AnalyticsQueryInput>()
        coEvery { analyticsQueryService.addQuery(capture(addedQueries)) } returns query("added")
        coEvery { analyticsQueryService.getQueries(0, 1000) } returns emptyList()
        coEvery { strategyService.getAll(0, 1000) } returns emptyList()
        coEvery { placementService.getAll() } returns listOf(mockk(relaxed = true))
        coEvery { pipelineService.getByKey(any()) } returns mockk<Pipeline>(relaxed = true)

        RecommendationsInstaller(
            analyticsQueryService,
            strategyService,
            placementService,
            securityService,
            pipelineService,
            schedulerService,
            languagesService,
            ExperimentationConfig(
                eventsTable = "siteb_warehouse.bosca.events",
                assignmentsTable = "siteb_bosca.experimentation.assignments",
                postgresCatalog = "siteb_bosca",
            ),
        ).install(installation, version)

        val eventQueryKeys = setOf(
            "recommendations-trending-content",
            "recommendations-co-engagement",
            "recommendations-cohort-co-engagement",
            "recommender-interactions",
        )
        val eventQueries = addedQueries.filter { it.key in eventQueryKeys }
        assertEquals(eventQueryKeys, eventQueries.mapTo(mutableSetOf()) { it.key })
        eventQueries.forEach { query ->
            assertTrue(query.query.contains("from siteb_warehouse.bosca.events e"), query.key)
            assertFalse(query.query.contains("from warehouse.bosca.events"), query.key)
            assertTrue(query.query.contains("not regexp_like(lower(coalesce(e.context.browser.agent, ''))"), query.key)
            assertTrue(query.query.contains("facebookexternalhit|chatgpt-user"), query.key)
        }
        assertTrue(addedQueries.any { it.query.contains("siteb_bosca.\"public\".") })
        assertTrue(addedQueries.any { it.query.contains("siteb_bosca.recommendations.") })
        assertTrue(addedQueries.any { it.query.contains("siteb_bosca.segmentation.") })
        // Any catalog reference other than the configured ones would target another installation.
        val catalogReference = Regex("""(?i)\b(?:from|join)\s+([A-Za-z_][A-Za-z0-9_]*)\.""")
        addedQueries.forEach { query ->
            val catalogs = catalogReference.findAll(query.query).mapTo(mutableSetOf()) { it.groupValues[1] }
            assertTrue(catalogs.all { it in setOf("siteb_bosca", "siteb_warehouse") }, "${query.key}: $catalogs")
        }
    }

    @Test
    fun `default catalog queries are unchanged for existing installations`() {
        assertEquals(
            RecommendationsInstaller.trainingFeedbackQuery(),
            RecommendationsInstaller.trainingFeedbackQuery(ExperimentationConfig.DEFAULT_POSTGRES_CATALOG),
        )
        assertTrue(RecommendationsInstaller.trainingFeedbackQuery().contains("from bosca.\"public\".profile_ratings r"))
        assertTrue(RecommendationsInstaller.trainingBehaviorQuery().contains("from bosca.recommendations.co_engagements e"))
        assertTrue(
            RecommendationsInstaller.trainingInteractionsQuery("warehouse.bosca.events")
                .contains("from bosca.\"public\".principals principal"),
        )
        assertTrue(
            RecommendationsInstaller.trainingGuideCompletionsQuery("sitea_bosca")
                .contains("from sitea_bosca.\"public\".profile_guide_progress p"),
        )
    }

    @Test
    fun `behavioral queries canonicalize event principals to profile ids`() = runTest {
        val addedQueries = mutableListOf<AnalyticsQueryInput>()
        coEvery { analyticsQueryService.addQuery(capture(addedQueries)) } returns query("added")
        coEvery { analyticsQueryService.getQueries(0, 1000) } returns emptyList()
        coEvery { strategyService.getAll(0, 1000) } returns emptyList()
        coEvery { placementService.getAll() } returns listOf(mockk(relaxed = true))
        coEvery { pipelineService.getByKey(any()) } returns mockk<Pipeline>(relaxed = true)

        installer.install(installation, version)

        val behavioralQueries = listOf(
            addedQueries.single { it.key == "recommendations-co-engagement" },
            addedQueries.single { it.key == "recommendations-cohort-co-engagement" },
            addedQueries.single { it.key == "recommender-interactions" },
        )
        behavioralQueries.forEach { query ->
            assertTrue(query.query.contains("with principal_profile_candidates as ("), query.key)
            assertTrue(query.query.contains("cast(principal.id as varchar) as event_user_id"), query.key)
            assertTrue(query.query.contains("cast(owned_profile.id as varchar) as profile_id"), query.key)
            assertTrue(query.query.contains("owned_profile.principal = principal.id"), query.key)
            assertTrue(query.query.contains("owned_profile.deleted_at is null"), query.key)
            assertTrue(query.query.contains("principal.primary_profile_id is null"), query.key)
            assertTrue(query.query.contains("or owned_profile.id = principal.primary_profile_id"), query.key)
            assertTrue(
                query.query.contains("order by owned_profile.created desc, owned_profile.id desc"),
                query.key,
            )
            assertTrue(query.query.contains("where profile_rank = 1"), query.key)
            assertFalse(query.query.contains("principal.primary_profile_id is not null"), query.key)
            assertTrue(query.query.contains("recommendation_users as ("), query.key)
            assertTrue(query.query.contains("cast(p.id as varchar) as event_user_id"), query.key)
            assertTrue(query.query.contains("cast(p.id as varchar) as profile_id"), query.key)
            assertTrue(
                query.query.contains("join recommendation_users u on u.event_user_id = e.context.user_id"),
                query.key,
            )
            assertTrue(query.query.contains("u.profile_id as user_id"), query.key)
            assertFalse(query.query.contains("e.context.user_id as user_id"), query.key)
        }
        val cohortQuery = behavioralQueries.single { it.key == "recommendations-cohort-co-engagement" }
        assertTrue(cohortQuery.query.contains("on cast(pc.user_id as varchar) = u.profile_id"))
        assertFalse(cohortQuery.query.contains("on cast(pc.user_id as varchar) = e.context.user_id"))
    }

    @Test
    fun `reinstall preserves an existing non-default warehouse table when configuration is defaulted`() = runTest {
        val existingTrending = query(
            "recommendations-trending-content",
            "select * from warehouse..events e",
        )
        val existingCoEngagement = query(
            "recommendations-co-engagement",
            "select * from warehouse.bosca.events e",
        )
        val existingCohortCoEngagement = query(
            "recommendations-cohort-co-engagement",
            "select * from warehouse.passion.events e",
        )
        coEvery { analyticsQueryService.getQueries(0, 1000) } returns listOf(
            existingTrending,
            existingCoEngagement,
            existingCohortCoEngagement,
        )
        coEvery { strategyService.getAll(0, 1000) } returns emptyList()
        coEvery { placementService.getAll() } returns listOf(mockk(relaxed = true))
        coEvery { pipelineService.getByKey(any()) } returns mockk<Pipeline>(relaxed = true)
        val installedQueries = mutableListOf<AnalyticsQueryInput>()
        coEvery { analyticsQueryService.addQuery(capture(installedQueries)) } returns query("added")
        coEvery { analyticsQueryService.editQuery(capture(installedQueries)) } returns query("edited")

        installer.install(installation, version)

        val eventQueryKeys = setOf(
            "recommendations-trending-content",
            "recommendations-co-engagement",
            "recommendations-cohort-co-engagement",
            "recommender-interactions",
        )
        val eventQueries = installedQueries.filter { it.key in eventQueryKeys }
        assertEquals(eventQueryKeys, eventQueries.mapTo(mutableSetOf()) { it.key })
        eventQueries.forEach { query ->
            assertTrue(query.query.contains("from warehouse.passion.events e"), query.key)
            assertFalse(query.query.contains("warehouse.bosca.events"), query.key)
        }
    }

    @Test
    fun `fresh install skips strategy seeding when the bound queries are absent`() = runTest {
        // Strategies empty (so installStrategies runs) but the query catalog has neither materialized query.
        coEvery { analyticsQueryService.getQueries(0, 1000) } returnsMany listOf(emptyList(), emptyList())
        coEvery { strategyService.getAll(0, 1000) } returns emptyList()
        coEvery { placementService.getAll() } returns emptyList()
        coEvery { pipelineService.getByKey(any()) } returns null

        installer.install(installation, version)

        // No trending/co-engagement seed (queries missing); only the ML model strategy is added.
        coVerify(exactly = 1) { strategyService.add(any()) }
    }

    @Test
    fun `idempotent re-install edits existing queries and seeds nothing new`() = runTest {
        val trending = query("recommendations-trending-content")
        val coEngagement = query("recommendations-co-engagement")
        coEvery { analyticsQueryService.getQueries(0, 1000) } returns listOf(trending, coEngagement)
        // Every strategy type, the placement and the pipelines already exist, and the co-engagement strategy
        // already uses the current query → every strategy/placement/pipeline seed step is a no-op.
        coEvery { strategyService.getAll(0, 1000) } returns listOf(
            mockk<RecommendationStrategy> { every { type } returns RecommendationStrategyType.PERSONALIZED },
            mockk<RecommendationStrategy> { every { type } returns RecommendationStrategyType.COHORT_CO_ENGAGEMENT },
            mockk<RecommendationStrategy> { every { type } returns RecommendationStrategyType.TRENDING },
            mockk<RecommendationStrategy> {
                every { type } returns RecommendationStrategyType.CO_ENGAGEMENT
                every { analyticsQueryId } returns coEngagement.id
            },
        )
        coEvery { placementService.getAll() } returns listOf(mockk(relaxed = true))
        coEvery { pipelineService.getByKey(any()) } returns mockk<Pipeline>(relaxed = true)
        coEvery { schedulerService.getJobsByName("train-model", 1) } returns
            listOf(mockk<ScheduledJob>(relaxed = true))

        installer.install(installation, version)

        // Existing queries are edited (not re-added); strategies/placement/pipeline all no-op.
        coVerify(atLeast = 1) { analyticsQueryService.editQuery(any<AnalyticsQueryInput>()) }
        coVerify(exactly = 0) { strategyService.add(any()) }
        coVerify(exactly = 0) { strategyService.edit(any(), any()) }
        coVerify(exactly = 0) { schedulerService.createJob(any(), any()) }
        coVerify(exactly = 0) { placementService.add(any(), any()) }
        coVerify(exactly = 0) { pipelineService.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `re-install restores trending and rebinds the bundled legacy co-engagement strategy`() = runTest {
        val trending = query("recommendations-trending-content")
        val currentCoEngagement = query("recommendations-co-engagement")
        val legacyCoEngagement = query("recommendations-related-cooccurrence")
        val trendingQueryId = trending.id
        val currentCoEngagementQueryId = currentCoEngagement.id
        coEvery { analyticsQueryService.getQueries(0, 1000) } returns
            listOf(trending, currentCoEngagement, legacyCoEngagement)

        val scheduledJobId = UUID.random()
        val existingCoEngagement = RecommendationStrategy(
            id = UUID.random(),
            name = "People also viewed",
            description = "Legacy bundled strategy",
            type = RecommendationStrategyType.CO_ENGAGEMENT,
            status = bosca.recommendations.model.RecommendationStrategyStatus.ACTIVE,
            analyticsQueryId = legacyCoEngagement.id,
            priority = 7,
            maxRecommendations = 33,
            scheduledJobId = scheduledJobId,
        )
        coEvery { strategyService.getAll(0, 1000) } returns listOf(
            existingCoEngagement,
            mockk<RecommendationStrategy> { every { type } returns RecommendationStrategyType.PERSONALIZED },
            mockk<RecommendationStrategy> { every { type } returns RecommendationStrategyType.COHORT_CO_ENGAGEMENT },
        )
        coEvery { schedulerService.getJob(scheduledJobId) } returns mockk<ScheduledJob> {
            every { cronExpression } returns "15 5 * * *"
        }
        coEvery { placementService.getAll() } returns listOf(mockk(relaxed = true))
        coEvery { pipelineService.getByKey(any()) } returns mockk<Pipeline>(relaxed = true)
        coEvery { schedulerService.getJobsByName("train-model", 1) } returns
            listOf(mockk<ScheduledJob>(relaxed = true))

        installer.install(installation, version)

        coVerify(exactly = 1) {
            strategyService.add(match {
                it.type == RecommendationStrategyType.TRENDING && it.analyticsQueryId == trendingQueryId
            })
        }
        coVerify(exactly = 1) {
            strategyService.edit(existingCoEngagement.id, match {
                it.name == existingCoEngagement.name &&
                    it.description == existingCoEngagement.description &&
                    it.type == existingCoEngagement.type &&
                    it.status == existingCoEngagement.status &&
                    it.analyticsQueryId == currentCoEngagementQueryId &&
                    it.priority == existingCoEngagement.priority &&
                    it.maxRecommendations == existingCoEngagement.maxRecommendations &&
                    it.evaluationSchedule == "15 5 * * *"
            })
        }
    }

    @Test
    fun `re-install repairs only the missing edge in an untouched generated pipeline`() = runTest {
        val pipelineId = UUID.random()
        val broken = Pipeline(
            id = pipelineId,
            name = "Customized signal name",
            description = "Customized description",
            acceptedInputType = "bosca.profile.attribute.events.ProfileAttributesAdded",
            tags = listOf("recommendations"),
            triggered = false,
            key = "recommendations-compute-signals-added",
            api = true,
            public = true,
            schedule = "0 1 * * *",
            maxConcurrentRuns = 3,
            maxRunsPerMinute = 20,
            nodes = listOf(
                InputNode(
                    id = "input",
                    name = "Customized input",
                    acceptedType = "bosca.profile.attribute.events.ProfileAttributesAdded",
                ),
                ComputeProfileSignalsNode(id = "compute", name = "Customized compute"),
            ),
            edges = emptyList(),
            version = 7,
        )
        val unrelatedExisting = mockk<Pipeline>(relaxed = true)
        coEvery { pipelineService.getByKey(any()) } returns unrelatedExisting
        coEvery { pipelineService.getByKey("recommendations-compute-signals-added") } returns broken
        val encoded = mutableListOf<Pipeline>()
        coEvery { pipelineService.graphAsJsonElement(capture(encoded)) } returns JsonPrimitive("{}")

        installer.install(installation, version)

        val repaired = encoded.single()
        assertEquals(broken.nodes, repaired.nodes)
        assertEquals(broken.groups, repaired.groups)
        assertEquals(
            listOf("input-compute" to ("input" to "compute")),
            repaired.edges.map { it.id to (it.source to it.target) },
        )
        coVerify(exactly = 1) {
            pipelineService.save(
                id = pipelineId,
                name = "Customized signal name",
                description = "Customized description",
                acceptedInputType = "bosca.profile.attribute.events.ProfileAttributesAdded",
                triggered = false,
                version = 7,
                graph = JsonPrimitive("{}"),
                tags = listOf("recommendations"),
                key = "recommendations-compute-signals-added",
                api = true,
                public = true,
                schedule = "0 1 * * *",
                maxConcurrentRuns = 3,
                maxRunsPerMinute = 20,
            )
        }
    }

    @Test
    fun `re-install repairs a missing training schedule when the personalized strategy already exists`() = runTest {
        val trending = query("recommendations-trending-content")
        val coEngagement = query("recommendations-co-engagement")
        val trendingQueryId = trending.id
        val coEngagementQueryId = coEngagement.id
        coEvery { analyticsQueryService.getQueries(0, 1000) } returns listOf(trending, coEngagement)
        coEvery { strategyService.getAll(0, 1000) } returns listOf(
            mockk<RecommendationStrategy> { every { type } returns RecommendationStrategyType.PERSONALIZED },
            mockk<RecommendationStrategy> { every { type } returns RecommendationStrategyType.COHORT_CO_ENGAGEMENT },
        )
        coEvery { placementService.getAll() } returns listOf(mockk(relaxed = true))
        coEvery { pipelineService.getByKey(any()) } returns mockk<Pipeline>(relaxed = true)
        coEvery { schedulerService.getJobsByName("train-model", 1) } returns emptyList()

        installer.install(installation, version)

        coVerify(exactly = 1) {
            strategyService.add(match {
                it.type == RecommendationStrategyType.TRENDING && it.analyticsQueryId == trendingQueryId
            })
        }
        coVerify(exactly = 1) {
            strategyService.add(match {
                it.type == RecommendationStrategyType.CO_ENGAGEMENT && it.analyticsQueryId == coEngagementQueryId
            })
        }
        coVerify(exactly = 1) {
            schedulerService.createJob(
                match {
                    it.name == "Train recommendation model" &&
                        it.jobName == "train-model" &&
                        it.cronExpression == "0 2 * * *" &&
                        it.enabled == true &&
                        it.allowConcurrent == false
                },
                UUID.NIL,
            )
        }
    }
}
