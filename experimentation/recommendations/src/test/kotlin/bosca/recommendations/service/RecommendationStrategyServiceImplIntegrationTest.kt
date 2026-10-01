@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.recommendations.service

import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.recommendations.configuration.RecommendationsMigration
import bosca.recommendations.createProfileAttributeSignalsPrerequisites
import bosca.recommendations.model.RecommendationStrategyInput
import bosca.recommendations.model.RecommendationStrategyType
import bosca.recommendations.repository.RecommendationRepositoryImpl
import bosca.recommendations.repository.RecommendationStrategyRepositoryImpl
import bosca.recommendations.repository.CoEngagementRepositoryImpl
import bosca.recommendations.repository.CohortCoEngagementRepositoryImpl
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.service.SchedulerService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres tests for [RecommendationStrategyServiceImpl] — the strategy lifecycle (create/edit/delete
 * with scheduled-job sync) and the materialized-strategy evaluation paths (TRENDING → global pool,
 * CO_ENGAGEMENT → item edges, PERSONALIZED → no-op). Repositories run live against a TestContainers
 * instance; the scheduler and analytics-query-execution services are mocked (they are exercised by their
 * own suites). The analytics-query FK target is stubbed so the recommendations migration runs in isolation.
 */
@OptIn(ExperimentalUuidApi::class)
class RecommendationStrategyServiceImplIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_rec_strategy_test")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 5,
                ),
                key = "test",
            ),
        )

        private var schemaInitialized = false

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking { pool.close() }
            postgres.stop()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scheduler = mockk<SchedulerService>(relaxed = true)
    private val analytics = mockk<AnalyticsQueryExecutionService>(relaxed = true)
    private val recommendationService = mockk<RecommendationService>(relaxed = true)
    private val strategyRepo = RecommendationStrategyRepositoryImpl()
    private val recRepo = RecommendationRepositoryImpl()
    private val coEngagementRepo = CoEngagementRepositoryImpl()
    private val cohortCoEngagementRepo = CohortCoEngagementRepositoryImpl()
    private lateinit var service: RecommendationStrategyServiceImpl

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }

        if (!schemaInitialized) {
            withDb {
                connection().useStatement("CREATE TABLE IF NOT EXISTS public.profiles (id uuid PRIMARY KEY)") { it.execute() }
                connection().useStatement("CREATE TABLE IF NOT EXISTS public.analytics_queries (id uuid PRIMARY KEY)") { it.execute() }
                connection().useStatement("CREATE SCHEMA IF NOT EXISTS segmentation") { it.execute() }
                connection().useStatement("CREATE TABLE IF NOT EXISTS segmentation.segments (id uuid PRIMARY KEY)") { it.execute() }
                connection().useStatement("CREATE TABLE IF NOT EXISTS public.scheduled_jobs (id uuid PRIMARY KEY)") { it.execute() }
                createProfileAttributeSignalsPrerequisites()  // for the profile_cohort view (V8)
            }
            runBlocking { FlywayMigration(pool).migrate(listOf(RecommendationsMigration())) }
            schemaInitialized = true
        }

        withDb {
            transaction {
                connection().useStatement("DELETE FROM recommendations.recommendations") { it.execute() }
                connection().useStatement("DELETE FROM recommendations.co_engagements") { it.execute() }
                connection().useStatement("DELETE FROM recommendations.cohort_co_engagements") { it.execute() }
                connection().useStatement("DELETE FROM recommendations.strategies") { it.execute() }
                connection().useStatement("DELETE FROM public.metadata_embeddings") { it.execute() }
                connection().useStatement("DELETE FROM public.metadata") { it.execute() }
                connection().useStatement("DELETE FROM public.collections") { it.execute() }
            }
        }

        service = RecommendationStrategyServiceImpl(
            strategyRepo, recRepo, coEngagementRepo, cohortCoEngagementRepo, analytics, scheduler,
            recommendationService, json,
        )
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun withDb(block: suspend () -> Unit) {
        runBlocking {
            val manager = pool.connection()
            try {
                withContext(manager.asCoroutineContext()) { block() }
            } finally {
                withContext(NonCancellable) { manager.release() }
            }
        }
    }

    @Test
    fun `generated model selection provider reads persisted pins with artifact authorization`() {
        withDb {
            service.add(RecommendationStrategyInput(
                name = "Selected", type = RecommendationStrategyType.PERSONALIZED,
                status = bosca.recommendations.model.RecommendationStrategyStatus.ACTIVE,
                configuration = Json.parseToJsonElement("""{"modelVersion":17}"""),
            ))
            provides<RecommendationStrategyService> { service }
            provides<bosca.experimentation.service.FeatureFlagService> {
                mockk { coEvery { getByKey("recommendation-model") } returns null }
            }
            provides<bosca.experimentation.service.ExperimentService> { mockk() }
            provides<bosca.security.service.GroupEvaluator> { bosca.security.service.GroupEvaluator(mockk()) }
            provides<bosca.artifacts.service.ArtifactPermissionEvaluator> {
                bosca.artifacts.service.ArtifactPermissionEvaluator(mockk())
            }
            val controller = bosca.recommendations.graphql.RecommendationModelSelectionControllerProvider().get()
            val auth = object : bosca.security.service.AuthenticationContext(null, null) {
                override fun principal() = bosca.security.service.ScopedAuthenticatedPrincipal(
                    bosca.security.model.Principal(), emptyList(), listOf("artifacts:ml:model/*:*:pull"), null, 1,
                )
            }
            assertEquals(listOf(17L), controller.personalizedVersions(auth))
        }
    }

    private fun scheduledJob(id: UUID): ScheduledJob = mockk { every { this@mockk.id } returns id }

    /** Inserts a row into the stubbed analytics_queries table (the strategies FK target) and returns its id. */
    private suspend fun seedQuery(): UUID {
        val id = UUID.random()
        connection().useStatement("INSERT INTO public.analytics_queries (id) VALUES (?)") {
            it.setObject(1, id.toJavaUuid()); it.execute()
        }
        return id
    }

    private suspend fun seedMetadata(vararg ids: UUID) {
        for (id in ids) {
            connection().useStatement(
                "INSERT INTO public.metadata (id, recommendation_contexts) VALUES (?, ARRAY['default'])",
            ) {
                it.setObject(1, id.toJavaUuid())
                it.execute()
            }
        }
    }

    private suspend fun seedCollection(id: UUID) {
        connection().useStatement(
            "INSERT INTO public.collections (id, recommendation_contexts) VALUES (?, ARRAY['default'])",
        ) {
            it.setObject(1, id.toJavaUuid())
            it.execute()
        }
    }

    private fun input(
        type: RecommendationStrategyType = RecommendationStrategyType.TRENDING,
        analyticsQueryId: UUID? = null,
        schedule: String? = null,
        configuration: JsonElement? = null,
    ) = RecommendationStrategyInput(
        name = "S",
        type = type,
        analyticsQueryId = analyticsQueryId,
        evaluationSchedule = schedule,
        configuration = configuration,
    )

    private fun response(vararg rows: JsonElement) = AnalyticsQueryResponse(records = rows.toList())

    // ── getters ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `getByIds returns empty for empty input and rows otherwise`() {
        var empty: List<Any> = listOf(Any())
        var all: List<Any> = emptyList()
        var byId: Any? = null
        withDb {
            val created = service.add(input())
            empty = service.getByIds(emptyList())
            all = service.getAll(0, 10)
            byId = service.getById(created.id)
            byId = service.getByIds(listOf(created.id)).firstOrNull()
        }
        assertTrue(empty.isEmpty())
        assertEquals(1, all.size)
        assertTrue(byId != null)
    }

    // ── add ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `add without a schedule creates the strategy and no job`() {
        lateinit var created: bosca.recommendations.model.RecommendationStrategy
        withDb { created = service.add(input(schedule = null)) }
        assertNull(created.scheduledJobId)
        coVerify(exactly = 0) { scheduler.createJob(any(), any()) }
    }

    @Test
    fun `add with a schedule creates a scheduled job and stores its id`() {
        val jobId = UUID.random()
        coEvery { scheduler.createJob(any(), any()) } returns scheduledJob(jobId)
        lateinit var created: bosca.recommendations.model.RecommendationStrategy
        withDb { created = service.add(input(schedule = "0 * * * *")) }
        assertEquals(jobId, created.scheduledJobId)
        coVerify { scheduler.createJob(any(), any()) }
    }

    // ── edit ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `edit throws when the strategy does not exist`() {
        assertFailsWith<NoSuchElementException> {
            withDb { service.edit(UUID.random(), input()) }
        }
    }

    @Test
    fun `editing a personalized strategy invalidates cached feeds`() {
        withDb {
            val created = service.add(input(type = RecommendationStrategyType.PERSONALIZED))
            service.edit(created.id, input(type = RecommendationStrategyType.PERSONALIZED))
        }
        coVerify(exactly = 1) { recommendationService.invalidatePersonalizedFeeds() }
    }

    @Test
    fun `edit adds a job when the strategy had none and a schedule is now set`() {
        val jobId = UUID.random()
        coEvery { scheduler.createJob(any(), any()) } returns scheduledJob(jobId)
        lateinit var edited: bosca.recommendations.model.RecommendationStrategy
        withDb {
            val created = service.add(input(schedule = null))
            edited = service.edit(created.id, input(schedule = "0 3 * * *"))
        }
        assertEquals(jobId, edited.scheduledJobId)
    }

    @Test
    fun `edit updates the existing job in place when a schedule is kept`() {
        val jobId = UUID.random()
        coEvery { scheduler.createJob(any(), any()) } returns scheduledJob(jobId)
        lateinit var edited: bosca.recommendations.model.RecommendationStrategy
        withDb {
            val created = service.add(input(schedule = "0 * * * *"))
            edited = service.edit(created.id, input(schedule = "0 6 * * *"))
        }
        // Same job id retained; the scheduler was asked to update it rather than create/delete.
        assertEquals(jobId, edited.scheduledJobId)
        coVerify { scheduler.updateJob(jobId, any()) }
    }

    @Test
    fun `edit removes the job when the schedule is cleared`() {
        val jobId = UUID.random()
        coEvery { scheduler.createJob(any(), any()) } returns scheduledJob(jobId)
        lateinit var edited: bosca.recommendations.model.RecommendationStrategy
        withDb {
            val created = service.add(input(schedule = "0 * * * *"))
            edited = service.edit(created.id, input(schedule = null))
        }
        assertNull(edited.scheduledJobId)
        coVerify { scheduler.deleteJob(jobId) }
    }

    @Test
    fun `edit of a schedule-less strategy to no schedule touches no scheduler`() {
        lateinit var edited: bosca.recommendations.model.RecommendationStrategy
        withDb {
            val created = service.add(input(schedule = null))            // no scheduled job to begin with
            edited = service.edit(created.id, input(schedule = null))    // still none: nothing to delete
        }
        assertNull(edited.scheduledJobId)
        coVerify(exactly = 0) { scheduler.deleteJob(any()) }
    }

    // ── delete ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `delete removes the strategy and its scheduled job`() {
        val jobId = UUID.random()
        coEvery { scheduler.createJob(any(), any()) } returns scheduledJob(jobId)
        var remaining: Int = -1
        withDb {
            val created = service.add(input(schedule = "0 * * * *"))
            service.delete(created.id)
            remaining = service.getAll(0, 10).size
        }
        assertEquals(0, remaining)
        coVerify { scheduler.deleteJob(jobId) }
    }

    @Test
    fun `delete of a non-existent strategy is a no-op`() {
        withDb { service.delete(UUID.random()) } // existing == null → no job to remove
        coVerify(exactly = 0) { scheduler.deleteJob(any()) }
    }

    @Test
    fun `delete of a strategy without a job touches no scheduler`() {
        withDb {
            val created = service.add(input(schedule = null))
            service.delete(created.id)
        }
        coVerify(exactly = 0) { scheduler.deleteJob(any()) }
    }

    // ── evaluate ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `evaluate throws when the strategy does not exist`() {
        assertFailsWith<NoSuchElementException> { withDb { service.evaluate(UUID.random()) } }
    }

    @Test
    fun `evaluate of a materialized strategy without an analytics query throws`() {
        assertFailsWith<IllegalStateException> {
            withDb {
                val created = service.add(input(type = RecommendationStrategyType.TRENDING, analyticsQueryId = null))
                service.evaluate(created.id)
            }
        }
    }

    @Test
    fun `evaluate TRENDING materializes a metadata and a collection candidate and skips empty rows`() {
        val metadataId = UUID.random()
        val metadataWithoutScore = UUID.random()
        val metadataWithNonPrimitiveOptionals = UUID.random()
        val collectionId = UUID.random()
        var pool: List<bosca.recommendations.model.Recommendation> = emptyList()
        withDb {
            seedMetadata(metadataId, metadataWithoutScore, metadataWithNonPrimitiveOptionals)
            seedCollection(collectionId)
            val created = service.add(input(type = RecommendationStrategyType.TRENDING, analyticsQueryId = seedQuery()))
            coEvery { analytics.execute(created.analyticsQueryId!!, any()) } returns response(
                buildJsonObject { put("metadata_id", metadataId.toString()); put("score", 0.9); put("reason", "hot") },
                buildJsonObject { put("collection_id", collectionId.toString()) }, // no score/reason → defaults
                buildJsonObject { put("metadata_id", metadataWithoutScore.toString()) }, // no score → 0.0, no reason → null
                buildJsonObject { put("score", 0.1) }, // neither id → skipped
                buildJsonObject { put("metadata_id", "not-a-uuid") }, // parse failure → skipped
                buildJsonObject { put("metadata_id", buildJsonObject {}) }, // non-primitive id → skipped
                buildJsonObject { put("collection_id", buildJsonObject {}) }, // non-primitive id → skipped
                buildJsonObject {
                    put("metadata_id", metadataWithNonPrimitiveOptionals.toString())
                    put("score", buildJsonObject {})
                    put("reason", buildJsonObject {})
                }, // non-primitive optional fields → defaults
            )
            service.evaluate(created.id)
            pool = recRepo.getByStrategyId(created.id, "default", 0, 10, metadataEnabled = true)
        }
        assertEquals(4, pool.size)
        assertTrue(pool.any { it.metadataId == metadataId } && pool.any { it.collectionId == collectionId })
        assertEquals(0.9, pool.first { it.metadataId == metadataId }.score)
        assertEquals(0.0, pool.first { it.collectionId == collectionId }.score) // score-absent default
        assertEquals(0.0, pool.first { it.metadataId == metadataWithNonPrimitiveOptionals }.score)
    }

    @Test
    fun `evaluate CO_ENGAGEMENT materializes edges and skips self edges and malformed rows`() {
        val source = UUID.random()
        val related = UUID.random()
        val related2 = UUID.random()
        val relatedWithNonPrimitiveOptionals = UUID.random()
        var edges: List<bosca.recommendations.model.CoEngagement> = emptyList()
        withDb {
            seedMetadata(related, related2, relatedWithNonPrimitiveOptionals)
            val created = service.add(input(type = RecommendationStrategyType.CO_ENGAGEMENT, analyticsQueryId = seedQuery()))
            coEvery { analytics.execute(created.analyticsQueryId!!, any()) } returns response(
                buildJsonObject { put("source_id", source.toString()); put("co_engaged_id", related.toString()); put("score", 0.7); put("reason", "together") },
                buildJsonObject { put("source_id", source.toString()); put("co_engaged_id", related2.toString()) }, // no score/reason → defaults
                buildJsonObject { put("source_id", source.toString()); put("co_engaged_id", source.toString()) }, // self-edge → skipped
                buildJsonObject { put("co_engaged_id", related.toString()) }, // missing source_id → skipped
                buildJsonObject { put("source_id", source.toString()) }, // missing co_engaged_id → skipped
                buildJsonObject { put("source_id", "x"); put("co_engaged_id", related.toString()) }, // parse failure → skipped
                buildJsonObject { put("source_id", buildJsonObject {}); put("co_engaged_id", related.toString()) },
                buildJsonObject { put("source_id", source.toString()); put("co_engaged_id", buildJsonObject {}) },
                buildJsonObject {
                    put("source_id", source.toString())
                    put("co_engaged_id", relatedWithNonPrimitiveOptionals.toString())
                    put("score", buildJsonObject {})
                    put("reason", buildJsonObject {})
                },
            )
            service.evaluate(created.id)
            edges = coEngagementRepo.getBySource(source, true, 10)
        }
        assertEquals(3, edges.size)
        assertEquals(0.7, edges.first { it.coEngagedMetadataId == related }.score)
        assertEquals(0.0, edges.first { it.coEngagedMetadataId == related2 }.score) // score-absent default
        assertEquals(0.0, edges.first { it.coEngagedMetadataId == relatedWithNonPrimitiveOptionals }.score)
    }

    @Test
    fun `evaluate CO_ENGAGEMENT pages until the analytics query returns a partial page`() {
        val source = UUID.random()
        val related = UUID.random()
        val captured = mutableListOf<List<AnalyticsQueryExecutionParameterInput>>()
        var edges: List<bosca.recommendations.model.CoEngagement> = emptyList()
        withDb {
            seedMetadata(related)
            val created = service.add(input(type = RecommendationStrategyType.CO_ENGAGEMENT, analyticsQueryId = seedQuery()))
            coEvery { analytics.execute(created.analyticsQueryId!!, capture(captured)) } returnsMany listOf(
                AnalyticsQueryResponse(records = List(5_000) { buildJsonObject {} }),
                response(
                    buildJsonObject {
                        put("source_id", source.toString())
                        put("co_engaged_id", related.toString())
                        put("score", 0.8)
                    },
                ),
            )

            service.evaluate(created.id)
            edges = coEngagementRepo.getBySource(source, true, 10)
        }

        assertEquals(2, captured.size)
        assertEquals(
            mapOf("offset" to 0, "limit" to 5_000),
            captured[0].associate { it.parameter to it.value.jsonPrimitive.int },
        )
        assertEquals(
            mapOf("offset" to 5_000, "limit" to 5_000),
            captured[1].associate { it.parameter to it.value.jsonPrimitive.int },
        )
        assertEquals(1, edges.size)
        assertEquals(related, edges.single().coEngagedMetadataId)
    }

    @Test
    fun `evaluate COHORT_CO_ENGAGEMENT materializes edges per cohort and skips cohortless self and malformed rows`() {
        val source = UUID.random()
        val related = UUID.random()
        val related2 = UUID.random()
        var young: List<bosca.recommendations.model.CohortCoEngagement> = emptyList()
        var older: List<bosca.recommendations.model.CohortCoEngagement> = emptyList()
        withDb {
            seedMetadata(related, related2)
            val created = service.add(input(type = RecommendationStrategyType.COHORT_CO_ENGAGEMENT, analyticsQueryId = seedQuery()))
            coEvery { analytics.execute(created.analyticsQueryId!!, any()) } returns response(
                buildJsonObject { put("cohort_key", "age_band=25-34"); put("source_id", source.toString()); put("co_engaged_id", related.toString()); put("score", 0.7); put("reason", "like you") },
                buildJsonObject { put("cohort_key", "age_band=25-34"); put("source_id", source.toString()); put("co_engaged_id", related2.toString()) }, // no score/reason → defaults
                buildJsonObject { put("cohort_key", "age_band=35-44"); put("source_id", source.toString()); put("co_engaged_id", related.toString()); put("score", 0.5) }, // a different cohort
                buildJsonObject { put("source_id", source.toString()); put("co_engaged_id", related.toString()) }, // missing cohort_key → skipped
                buildJsonObject { put("cohort_key", "  "); put("source_id", source.toString()); put("co_engaged_id", related.toString()) }, // blank cohort_key → skipped
                buildJsonObject { put("cohort_key", "age_band=25-34"); put("source_id", source.toString()); put("co_engaged_id", source.toString()) }, // self-edge → skipped
                buildJsonObject { put("cohort_key", "age_band=25-34"); put("co_engaged_id", related.toString()) }, // missing source_id → skipped
                buildJsonObject { put("cohort_key", "age_band=25-34"); put("source_id", "x"); put("co_engaged_id", related.toString()) }, // parse failure → skipped
            )
            service.evaluate(created.id)
            young = cohortCoEngagementRepo.getBySourceAndCohorts(source, listOf("age_band=25-34"), true, 10)
            older = cohortCoEngagementRepo.getBySourceAndCohorts(source, listOf("age_band=35-44"), true, 10)
        }
        assertEquals(2, young.size) // the two 25-34 edges; cohortless / blank / self / malformed all skipped
        assertEquals(0.7, young.first { it.coEngagedMetadataId == related }.score)
        assertEquals(0.0, young.first { it.coEngagedMetadataId == related2 }.score) // score-absent default
        assertEquals("age_band=25-34", young.first().cohortKey)
        assertEquals(1, older.size) // reads are partitioned by cohort_key
    }

    @Test
    fun `evaluate COHORT_CO_ENGAGEMENT passes the configured caps as query parameters`() {
        val captured = slot<List<AnalyticsQueryExecutionParameterInput>>()
        withDb {
            val created = service.add(input(
                type = RecommendationStrategyType.COHORT_CO_ENGAGEMENT,
                analyticsQueryId = seedQuery(),
                configuration = Json.encodeToJsonElement(
                    CohortCoEngagementConfiguration.serializer(),
                    CohortCoEngagementConfiguration(perUserItemCap = 50, perSourceCap = 25),
                ),
            ))
            coEvery { analytics.execute(created.analyticsQueryId!!, capture(captured)) } returns response()
            service.evaluate(created.id)
        }
        val caps = captured.captured.associate { it.parameter to it.value.jsonPrimitive.int }
        assertEquals(50, caps["basketCap"]) // Cap A from the strategy configuration
        assertEquals(25, caps["sourceCap"]) // Cap B from the strategy configuration
    }

    @Test
    fun `evaluate COHORT_CO_ENGAGEMENT defaults the caps when no configuration is set`() {
        val captured = slot<List<AnalyticsQueryExecutionParameterInput>>()
        withDb {
            val created = service.add(input(type = RecommendationStrategyType.COHORT_CO_ENGAGEMENT, analyticsQueryId = seedQuery()))
            coEvery { analytics.execute(created.analyticsQueryId!!, capture(captured)) } returns response()
            service.evaluate(created.id)
        }
        val caps = captured.captured.associate { it.parameter to it.value.jsonPrimitive.int }
        assertEquals(200, caps["basketCap"]) // default when the strategy has no configuration
        assertEquals(200, caps["sourceCap"])
    }

    @Test
    fun `evaluate PERSONALIZED is a no-op that only refreshes lastEvaluated`() {
        var recCount = -1
        lateinit var evaluated: bosca.recommendations.model.RecommendationStrategy
        withDb {
            val created = service.add(input(type = RecommendationStrategyType.PERSONALIZED))
            evaluated = service.evaluate(created.id)
            recCount = recRepo.getByStrategyId(created.id, "default", 0, 10).size
        }
        assertEquals(0, recCount)
        assertTrue(evaluated.lastEvaluated != null)
        coVerify(exactly = 0) { analytics.execute(any<UUID>(), any()) }
    }
}
