@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.experimentation.service

import bosca.analytics.model.EventType
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.experimentation.configuration.ExperimentationMigration
import bosca.experimentation.configuration.JobQueueNames
import bosca.experimentation.FlagCacheTestSupport
import bosca.experimentation.model.AnalysisMethod
import bosca.experimentation.model.AnalysisReport
import bosca.experimentation.model.BayesianPrior
import bosca.experimentation.model.ConversionGoalInput
import bosca.experimentation.model.ConversionGoalRole
import bosca.experimentation.model.CupedCovariate
import bosca.experimentation.model.ExperimentInput
import bosca.experimentation.model.ExperimentActivationFilter
import bosca.experimentation.model.ExperimentResult
import bosca.experimentation.model.ExperimentStatus
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FlagStatus
import bosca.experimentation.model.FlagType
import bosca.experimentation.model.GoalMetricType
import bosca.experimentation.model.RolloutPolicy
import bosca.experimentation.model.RolloutPolicyMode
import bosca.experimentation.model.RolloutStep
import bosca.experimentation.repository.AnalysisReportRepositoryImpl
import bosca.experimentation.repository.AssignmentRepositoryImpl
import bosca.experimentation.repository.ConversionGoalRepositoryImpl
import bosca.experimentation.repository.ExperimentRepositoryImpl
import bosca.experimentation.repository.ExperimentResultRepositoryImpl
import bosca.experimentation.repository.FeatureFlagRepositoryImpl
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.slot
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.uuid.toJavaUuid

/**
 * Integration tests for [ExperimentServiceImpl] against a real
 * PostgreSQL instance via TestContainers.
 *
 * The service-layer tests in this file pin business logic that the
 * repository-only integration tests cannot:
 *
 *   1. `add` / `edit` correctly serialize [RolloutPolicy] /
 *      [BayesianPrior] into JSONB before the repository insert,
 *      and `validatePolicyAgainstFlag` rejects policies whose
 *      treatment variation key is not served by the attached rule.
 *   2. `setStatus(..., RUNNING)` on a SCHEDULED_STEPS policy
 *      enqueues the first delayed rollout job via the
 *      experimentation JobQueue — mocked at the DI boundary.
 *   3. `addConversionGoal` / `editConversionGoal` wire the
 *      `role` and `cupedCovariate` fields all the way through the
 *      service → repository → JSONB column round-trip. This is
 *      load-bearing because the Phase 1/6 UI mutations depend on
 *      these fields surviving `touchAnalysisRevision` + transaction commit.
 *   4. Rollout policy survives an `ExperimentService.edit` that
 *      changes unrelated fields (e.g. the exclusion layer picker).
 *
 * Every test runs against live PG so the `::jsonb` / `::enum`
 * casts in the generated repository SQL are exercised.
 * [PubSubService] and the rollout-step [JobQueue] are mocked at
 * the boundary because pub/sub delivery and delayed job execution
 * are out of scope for this suite and would add a NATS /
 * Redis-backed test container with no incremental coverage.
 */
class ExperimentServiceImplIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_experiment_svc_test")
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
            )
        )

        private var schemaInitialized = false

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking {
                pool.close()
            }
            postgres.stop()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val flagRepository = FeatureFlagRepositoryImpl()
    private val experimentRepository = ExperimentRepositoryImpl()
    private val conversionGoalRepository = ConversionGoalRepositoryImpl()
    private val experimentResultRepository = ExperimentResultRepositoryImpl()
    private val analysisReportRepository = AnalysisReportRepositoryImpl()
    private val assignmentRepository = AssignmentRepositoryImpl()
    private val cacheSupport = FlagCacheTestSupport()

    private val pubSub = mockk<PubSubService>(relaxed = true)
    private val jobQueue = mockk<JobQueue>(relaxed = true)

    private lateinit var service: ExperimentServiceImpl

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }
        provides<CacheManager>(singleton = true) { cacheSupport.cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { cacheSupport.requestCacheSerializer }

        // The SCHEDULED_STEPS enqueue path looks up the
        // experimentation JobQueue by name via
        // `bosca.di.provide<JobQueue>(JobQueueNames.experimentationJobQueue)`.
        // Register the mock under that name so the service's
        // runCatching picks it up instead of throwing and logging.
        mockkObject(ProviderRegistry)
        val originalGet = ProviderRegistry::class.java
            .getMethod("get", kotlin.reflect.KClass::class.java, String::class.java)
        every {
            ProviderRegistry.get(
                JobQueue::class,
                JobQueueNames.experimentationJobQueue,
            )
        } returns object : ObjectProvider<JobQueue> {
            override val type = JobQueue::class
            override suspend fun get(): JobQueue = jobQueue
        }

        if (!schemaInitialized) {
            runBlocking {
                FlywayMigration(pool).migrate(listOf(ExperimentationMigration()))
            }
            schemaInitialized = true
        }

        withDb {
            transaction {
                connection().useStatement("DELETE FROM experimentation.rollout_policy_events") { it.execute() }
                connection().useStatement("DELETE FROM experimentation.experiment_results") { it.execute() }
                connection().useStatement("DELETE FROM experimentation.analysis_reports") { it.execute() }
                connection().useStatement("DELETE FROM experimentation.conversion_goals") { it.execute() }
                connection().useStatement("DELETE FROM experimentation.assignments") { it.execute() }
                connection().useStatement("DELETE FROM experimentation.flag_assignments") { it.execute() }
                connection().useStatement("DELETE FROM experimentation.experiments") { it.execute() }
                connection().useStatement("DELETE FROM experimentation.feature_flags") { it.execute() }
                connection().useStatement("DELETE FROM experimentation.exclusion_layers") { it.execute() }
            }
        }

        service = ExperimentServiceImpl(
            experimentRepository = experimentRepository,
            assignmentRepository = assignmentRepository,
            conversionGoalRepository = conversionGoalRepository,
            experimentResultRepository = experimentResultRepository,
            analysisReportRepository = analysisReportRepository,
            featureFlagRepository = flagRepository,
            rolloutPolicyEventRepository = mockk(relaxed = true),
            pubSubService = pubSub,
            json = json,
            errorCapture = bosca.observability.ErrorCapture.Noop,
        )
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ProviderRegistry)
        ProviderRegistry.clear()
    }

    private fun withDb(block: suspend () -> Unit) {
        runBlocking {
            val manager = pool.connection()
            try {
                withContext(manager.asCoroutineContext()) {
                    cacheSupport.withFlagCache { block() }
                }
            } finally {
                withContext(NonCancellable) { manager.release() }
            }
        }
    }

    // -----------------------------------------------------------------
    // Fixture builders
    // -----------------------------------------------------------------

    private fun variationsJson() = buildJsonArray {
        add(buildJsonObject {
            put("key", "control"); put("name", "Control"); put("description", ""); put("value", false)
        })
        add(buildJsonObject {
            put("key", "treatment"); put("name", "Treatment"); put("description", ""); put("value", true)
        })
    }

    private fun ruleWithBoth50_50() = buildJsonArray {
        add(buildJsonObject {
            put("id", "rule-1")
            put("conditions", buildJsonArray { })
            put("rollout", buildJsonObject {
                put("variationWeights", buildJsonArray {
                    add(buildJsonObject { put("variationKey", "control"); put("weight", 50) })
                    add(buildJsonObject { put("variationKey", "treatment"); put("weight", 50) })
                })
            })
        })
    }

    private suspend fun insertFlag(): FeatureFlag =
        flagRepository.add(
            FeatureFlag(
                key = "svc-test-flag",
                name = "Svc Test Flag",
                description = "",
                type = FlagType.BOOLEAN,
                status = FlagStatus.ENABLED,
                variations = variationsJson(),
                defaultVariationKey = "control",
                targetingRules = ruleWithBoth50_50(),
            )
        )

    private fun experimentInput(
        flag: FeatureFlag,
        policy: RolloutPolicy? = null,
        analysisMethod: AnalysisMethod = AnalysisMethod.FREQUENTIST,
        prior: BayesianPrior? = null,
    ) = ExperimentInput(
        featureFlagId = flag.id,
        controlVariationKey = "control",
        name = "Integration Experiment",
        description = "",
        hypothesis = "treatment > control",
        targetingRuleId = "rule-1",
        rolloutPolicy = policy,
        analysisMethod = analysisMethod,
        bayesianPrior = prior,
    )

    // -----------------------------------------------------------------
    // add / edit — rollout policy + analysis method + prior wiring
    // -----------------------------------------------------------------

    @Test
    fun `add persists rollout policy as JSONB readable through service`() = withDb {
        val flag = transaction { insertFlag() }
        val policy = RolloutPolicy(
            mode = RolloutPolicyMode.ADAPTIVE_STEPS,
            treatmentVariationKey = "treatment",
            steps = listOf(
                RolloutStep(weightPercent = 10),
                RolloutStep(weightPercent = 25),
                RolloutStep(weightPercent = 100),
            ),
            minConfidence = 0.97,
        )
        val saved = service.add(experimentInput(flag, policy = policy))
        val loaded = assertNotNull(service.getById(saved.id))
        val decoded = json.decodeFromJsonElement(
            RolloutPolicy.serializer(),
            assertNotNull(loaded.rolloutPolicy),
        )
        assertEquals(policy, decoded)
    }

    @Test
    fun `account exclusions persist deduplicate and can be cleared`() = withDb {
        val flag = transaction { insertFlag() }
        val first = UUID.random()
        val second = UUID.random()
        val input = experimentInput(flag).copy(
            excludedPrincipalIds = listOf(first, second, first),
        )

        val saved = service.add(input)
        assertEquals(listOf(first, second), saved.excludedPrincipalIds)
        assertEquals(listOf(first, second), service.getById(saved.id)?.excludedPrincipalIds)

        val preserved = service.edit(saved.id, input.copy(excludedPrincipalIds = null))
        assertEquals(listOf(first, second), preserved.excludedPrincipalIds)
        assertEquals(saved.analysisRevision, preserved.analysisRevision)

        val goal = service.addConversionGoal(saved.id, ConversionGoalInput(name = "Click", eventType = EventType.Interaction))
        transaction {
            experimentResultRepository.upsert(ExperimentResult(
                experimentId = saved.id, variationKey = "control", goalId = goal.id,
                impressions = 10, conversions = 2, conversionRate = 0.2,
            ))
        }
        val revision = assertNotNull(service.getById(saved.id)).analysisRevision
        val cleared = service.edit(saved.id, input.copy(excludedPrincipalIds = emptyList()))
        assertEquals(emptyList(), cleared.excludedPrincipalIds)
        assertEquals(revision + 1, cleared.analysisRevision)
        assertTrue(experimentResultRepository.getByExperimentId(saved.id).isEmpty())
    }

    @Test
    fun `activation changes invalidate results while an unchanged normalized filter preserves them`() = withDb {
        val flag = transaction { insertFlag() }
        val input = experimentInput(flag)
        val saved = service.add(input)
        val goal = service.addConversionGoal(saved.id, ConversionGoalInput(name = "Click", eventType = EventType.Interaction))
        val articleFilter = ExperimentActivationFilter(eventType = EventType.Impression, pagePathPrefixes = listOf("/articles/"))
        val filters = listOf(articleFilter, articleFilter.copy(pagePathPrefixes = listOf("/studies/")), null)

        for (filter in filters) {
            transaction {
                experimentResultRepository.upsert(ExperimentResult(
                    experimentId = saved.id, variationKey = "control", goalId = goal.id,
                    impressions = 10, conversions = 2, conversionRate = 0.2,
                ))
            }
            val revision = assertNotNull(service.getById(saved.id)).analysisRevision
            val updated = service.edit(saved.id, input.copy(activationFilter = filter))
            assertEquals(revision + 1, updated.analysisRevision)
            assertTrue(service.getResults(saved.id).isEmpty(), "enabling, changing, and clearing activation must invalidate results")

            transaction {
                experimentResultRepository.upsert(ExperimentResult(
                    experimentId = saved.id, variationKey = "control", goalId = goal.id,
                    impressions = 5, conversions = 1, conversionRate = 0.2,
                ))
            }
            service.edit(saved.id, input.copy(
                name = "Renamed ${updated.analysisRevision}",
                activationFilter = filter?.copy(pagePathPrefixes = filter.pagePathPrefixes.map { " $it " }),
            ))
            assertEquals(1, service.getResults(saved.id).size, "unrelated edits must preserve valid results")
        }
    }

    @Test
    fun `activation filter and follow-up content prefixes normalize and round trip`() = withDb {
        val flag = transaction { insertFlag() }
        val filter = ExperimentActivationFilter(
            eventType = EventType.Impression,
            elementType = " page ",
            elementId = " article-page ",
            pagePath = " /articles/featured ",
            pagePathPrefixes = listOf(" /articles/ ", "/talks/", "/studies/", "/articles/"),
            itemExtraKey = " campaign ",
            itemExtraValue = "reader",
        )
        val saved = service.add(experimentInput(flag).copy(activationFilter = filter))
        val normalized = assertNotNull(saved.activationFilter)
        assertEquals("page", normalized.elementType)
        assertEquals("article-page", normalized.elementId)
        assertEquals("/articles/featured", normalized.pagePath)
        assertEquals(listOf("/articles/", "/talks/", "/studies/"), normalized.pagePathPrefixes)
        assertEquals("campaign", normalized.itemExtraKey)
        assertEquals(normalized, service.getById(saved.id)?.activationFilter)

        val goal = service.addConversionGoal(
            saved.id,
            ConversionGoalInput(
                name = "Follow-up content view",
                eventType = EventType.Impression,
                elementType = "page",
                pagePathPrefixes = listOf(" /articles/ ", "/talks/", "/studies/", "/talks/"),
            ),
        )
        assertEquals(listOf("/articles/", "/talks/", "/studies/"), goal.pagePathPrefixes)
        assertEquals(goal.pagePathPrefixes, conversionGoalRepository.getById(goal.id)?.pagePathPrefixes)

        val cleared = service.edit(saved.id, experimentInput(flag).copy(activationFilter = null))
        assertNull(cleared.activationFilter)
    }

    @Test
    fun `each activation predicate is independently sufficient`() = withDb {
        val flag = transaction { insertFlag() }
        val filters = listOf(
            ExperimentActivationFilter(eventType = EventType.Impression),
            ExperimentActivationFilter(elementType = "page"),
            ExperimentActivationFilter(elementId = "article-page"),
            ExperimentActivationFilter(pagePath = "/articles/featured"),
            ExperimentActivationFilter(pagePathPrefixes = listOf("/articles/")),
            ExperimentActivationFilter(itemExtraKey = "campaign"),
        )

        filters.forEachIndexed { index, filter ->
            val saved = service.add(
                experimentInput(flag).copy(name = "Activation $index", activationFilter = filter),
            )
            assertNotNull(saved.activationFilter)
        }
    }

    @Test
    fun `activation normalization removes blank optional selectors`() = withDb {
        val flag = transaction { insertFlag() }
        val saved = service.add(
            experimentInput(flag).copy(
                activationFilter = ExperimentActivationFilter(
                    eventType = EventType.Impression,
                    elementType = " ",
                    elementId = "\t",
                    pagePath = "  ",
                    pagePathPrefixes = listOf(" ", ""),
                    itemExtraKey = "\n",
                ),
            ),
        )

        val normalized = assertNotNull(saved.activationFilter)
        assertNull(normalized.elementType)
        assertNull(normalized.elementId)
        assertNull(normalized.pagePath)
        assertTrue(normalized.pagePathPrefixes.isEmpty())
        assertNull(normalized.itemExtraKey)
    }

    @Test
    fun `activation validation rejects empty malformed and oversized filters`() = withDb {
        val flag = transaction { insertFlag() }
        val invalid = listOf(
            ExperimentActivationFilter(),
            ExperimentActivationFilter(itemExtraValue = "reader"),
            ExperimentActivationFilter(itemExtraKey = "Campaign-Name"),
            ExperimentActivationFilter(itemExtraKey = "campaign", itemExtraValue = "x".repeat(513)),
            ExperimentActivationFilter(pagePathPrefixes = (1..33).map { "/content-$it/" }),
            ExperimentActivationFilter(pagePathPrefixes = listOf("/" + "x".repeat(512))),
        )

        invalid.forEachIndexed { index, filter ->
            assertFailsWith<IllegalArgumentException>("invalid activation filter $index must fail") {
                service.add(
                    experimentInput(flag).copy(name = "Invalid activation $index", activationFilter = filter),
                )
            }
        }
    }

    @Test
    fun `service failures and optional experiment text are handled explicitly`() = withDb {
        val missingFlagId = UUID.random()
        assertFails("missing feature flag must reject creation") {
            service.add(
                ExperimentInput(
                    featureFlagId = missingFlagId,
                    controlVariationKey = "control",
                    name = "Missing flag",
                ),
            )
        }

        val flag = transaction { insertFlag() }
        val saved = service.add(
            experimentInput(flag).copy(description = null, hypothesis = null),
        )
        assertEquals("", saved.description)
        assertEquals("", saved.hypothesis)

        assertFails("missing experiment must reject edits") {
            service.edit(UUID.random(), experimentInput(flag))
        }
        assertFails("an existing experiment cannot move to another flag") {
            service.edit(saved.id, experimentInput(flag).copy(featureFlagId = UUID.random()))
        }
        assertFails("blank control variation must be rejected") {
            service.add(experimentInput(flag).copy(controlVariationKey = " "))
        }

        // Deleting an already-absent id is intentionally idempotent and emits no update.
        service.delete(UUID.random())
    }

    @Test
    fun `add with bayesian analysis persists method + prior`() = withDb {
        val flag = transaction { insertFlag() }
        val prior = BayesianPrior(
            betaPriorAlpha = 2.0,
            betaPriorBeta = 8.0,
            normalPriorMean = 0.0,
            normalPriorVariance = 1.0,
        )
        val saved = service.add(
            experimentInput(flag, analysisMethod = AnalysisMethod.BAYESIAN, prior = prior)
        )
        val loaded = assertNotNull(service.getById(saved.id))
        assertEquals(AnalysisMethod.BAYESIAN, loaded.analysisMethod)
        val decoded = json.decodeFromJsonElement(
            BayesianPrior.serializer(),
            assertNotNull(loaded.bayesianPrior),
        )
        assertEquals(prior, decoded)
    }

    @Test
    fun `add rejects policy whose treatment variation is not served by the rule`() = withDb {
        val flag = transaction { insertFlag() }
        val badPolicy = RolloutPolicy(
            mode = RolloutPolicyMode.ADAPTIVE_CONTINUOUS,
            treatmentVariationKey = "does-not-exist",
            incrementPercent = 5.0,
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            service.add(experimentInput(flag, policy = badPolicy))
        }
        assertTrue(ex.message?.contains("does-not-exist") == true,
            "validation message should name the missing variation key, got: ${ex.message}")
    }

    @Test
    fun `add persists an explicitly selected non-alphabetic control`() = withDb {
        val flag = transaction { insertFlag() }
        val saved = service.add(
            experimentInput(flag).copy(controlVariationKey = "treatment")
        )
        assertEquals("treatment", saved.controlVariationKey)
        assertEquals("treatment", service.getById(saved.id)?.controlVariationKey)
    }

    @Test
    fun `add rejects a control outside the attached rollout`() = withDb {
        val flag = transaction { insertFlag() }
        val ex = assertFailsWith<IllegalArgumentException> {
            service.add(experimentInput(flag).copy(controlVariationKey = "missing"))
        }
        assertTrue(ex.message?.contains("missing") == true)
    }

    @Test
    fun `add rejects a rollout policy whose treatment is also the control`() = withDb {
        val flag = transaction { insertFlag() }
        val policy = RolloutPolicy(
            mode = RolloutPolicyMode.MANUAL,
            treatmentVariationKey = "control",
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            service.add(experimentInput(flag, policy = policy))
        }
        assertTrue(ex.message?.contains("must differ") == true)
    }

    @Test
    fun `edit preserves rollout policy when only name changes`() = withDb {
        val flag = transaction { insertFlag() }
        val policy = RolloutPolicy(
            mode = RolloutPolicyMode.MANUAL,
            treatmentVariationKey = "treatment",
        )
        val saved = service.add(experimentInput(flag, policy = policy))
        val edited = service.edit(
            saved.id,
            experimentInput(flag, policy = policy).copy(name = "Renamed"),
        )
        assertEquals("Renamed", edited.name)
        val decoded = json.decodeFromJsonElement(
            RolloutPolicy.serializer(),
            assertNotNull(edited.rolloutPolicy),
        )
        assertEquals(policy, decoded)
    }

    @Test
    fun `edit can clear the rollout policy by passing null`() = withDb {
        val flag = transaction { insertFlag() }
        val policy = RolloutPolicy(
            mode = RolloutPolicyMode.MANUAL,
            treatmentVariationKey = "treatment",
        )
        val saved = service.add(experimentInput(flag, policy = policy))
        val edited = service.edit(
            saved.id,
            experimentInput(flag, policy = null),
        )
        assertNull(edited.rolloutPolicy,
            "rolloutPolicy should be null after editing with policy=null")
    }

    @Test
    fun `edit can change control in DRAFT but locks it after the experiment starts`() = withDb {
        val flag = transaction { insertFlag() }
        val saved = service.add(experimentInput(flag))
        val draftEdited = service.edit(
            saved.id,
            experimentInput(flag).copy(controlVariationKey = "treatment"),
        )
        assertEquals("treatment", draftEdited.controlVariationKey)

        service.setStatus(saved.id, ExperimentStatus.RUNNING)
        val ex = assertFailsWith<IllegalArgumentException> {
            service.edit(saved.id, experimentInput(flag).copy(controlVariationKey = "control"))
        }
        assertTrue(ex.message?.contains("control variation") == true)
    }

    @Test
    fun `changing draft control clears current results preserves reports and reconciles treatment`() = withDb {
        val flag = transaction { insertFlag() }
        val policy = RolloutPolicy(
            mode = RolloutPolicyMode.MANUAL,
            treatmentVariationKey = "treatment",
        )
        val saved = service.add(experimentInput(flag, policy = policy))
        val goal = service.addConversionGoal(
            saved.id,
            ConversionGoalInput(name = "Signup", eventType = EventType.Interaction),
        )
        transaction {
            experimentResultRepository.upsert(
                ExperimentResult(
                    experimentId = saved.id,
                    variationKey = "control",
                    goalId = goal.id,
                    impressions = 10,
                    conversions = 2,
                    conversionRate = 0.2,
                )
            )
            analysisReportRepository.add(
                AnalysisReport(
                    experimentId = saved.id,
                    summary = "Historical analysis",
                    recommendation = "Keep collecting",
                    details = buildJsonObject { put("controlVariationKey", "control") },
                )
            )
        }

        val edited = service.edit(
            saved.id,
            experimentInput(flag, policy = policy).copy(controlVariationKey = "treatment"),
        )

        val reconciled = json.decodeFromJsonElement(
            RolloutPolicy.serializer(),
            assertNotNull(edited.rolloutPolicy),
        )
        assertEquals("control", reconciled.treatmentVariationKey)
        assertTrue(experimentResultRepository.getByExperimentId(saved.id).isEmpty())
        val reports = analysisReportRepository.getByExperimentId(saved.id)
        assertEquals(1, reports.size)
        assertEquals("Historical analysis", reports.single().summary)
    }

    // -----------------------------------------------------------------
    // setStatus — scheduled step enqueue
    // -----------------------------------------------------------------

    @Test
    fun `setStatus to RUNNING on SCHEDULED_STEPS policy enqueues first step`() = withDb {
        val flag = transaction { insertFlag() }
        val policy = RolloutPolicy(
            mode = RolloutPolicyMode.SCHEDULED_STEPS,
            treatmentVariationKey = "treatment",
            steps = listOf(
                RolloutStep(weightPercent = 25, afterDuration = "P1D"),
                RolloutStep(weightPercent = 50, afterDuration = "P1D"),
                RolloutStep(weightPercent = 100, afterDuration = "P1D"),
            ),
        )
        val saved = service.add(experimentInput(flag, policy = policy))

        val jobSlot = slot<Job>()
        val durationSlot = slot<Duration>()
        coEvery { jobQueue.enqueueLater(capture(jobSlot), capture(durationSlot)) } returns UUID.random()

        service.setStatus(saved.id, ExperimentStatus.RUNNING)

        coVerify(exactly = 1) {
            jobQueue.enqueueLater(any(), any())
        }
        // The captured duration should match P1D = 1 day in ms.
        assertTrue(durationSlot.captured.inWholeMilliseconds > 0,
            "enqueueLater duration should be a positive step delay, got ${durationSlot.captured}")
    }

    @Test
    fun `setStatus to RUNNING on ADAPTIVE policy does NOT enqueue`() = withDb {
        val flag = transaction { insertFlag() }
        val policy = RolloutPolicy(
            mode = RolloutPolicyMode.ADAPTIVE_STEPS,
            treatmentVariationKey = "treatment",
            steps = listOf(RolloutStep(weightPercent = 100)),
        )
        val saved = service.add(experimentInput(flag, policy = policy))

        service.setStatus(saved.id, ExperimentStatus.RUNNING)

        coVerify(exactly = 0) { jobQueue.enqueueLater(any(), any()) }
    }

    @Test
    fun `setStatus to RUNNING on experiment with no policy does NOT enqueue`() = withDb {
        val flag = transaction { insertFlag() }
        val saved = service.add(experimentInput(flag))
        service.setStatus(saved.id, ExperimentStatus.RUNNING)
        coVerify(exactly = 0) { jobQueue.enqueueLater(any(), any()) }
    }

    @Test
    fun `definition edits advance analysis revision while status changes do not`() = withDb {
        val flag = transaction { insertFlag() }
        val saved = service.add(experimentInput(flag))
        assertEquals(0L, saved.analysisRevision)

        service.addConversionGoal(
            saved.id,
            ConversionGoalInput(
                name = "Signup",
                eventType = EventType.Interaction,
            ),
        )
        val afterGoal = assertNotNull(experimentRepository.getById(saved.id))
        assertEquals(1L, afterGoal.analysisRevision)

        service.setStatus(saved.id, ExperimentStatus.RUNNING)
        val afterStatus = assertNotNull(experimentRepository.getById(saved.id))
        assertEquals(1L, afterStatus.analysisRevision)
        assertEquals(ExperimentStatus.RUNNING, afterStatus.status)
    }

    @Test
    fun `identical experiment and goal edits keep the analysis revision`() = withDb {
        val flag = transaction { insertFlag() }
        val input = experimentInput(flag)
        val saved = service.add(input)
        val goalInput = ConversionGoalInput(
            name = "Signup",
            eventType = EventType.Interaction,
        )
        val goal = service.addConversionGoal(saved.id, goalInput)
        val afterGoal = assertNotNull(experimentRepository.getById(saved.id))
        val revisionAfterGoal = afterGoal.analysisRevision

        val unchangedExperiment = service.edit(saved.id, input)
        val unchangedGoal = service.editConversionGoal(goal.id, goalInput)

        assertEquals(afterGoal.modified, unchangedExperiment.modified)
        assertEquals(goal, unchangedGoal)
        assertEquals(
            revisionAfterGoal,
            assertNotNull(experimentRepository.getById(saved.id)).analysisRevision,
        )
    }

    @Test
    fun `setStatus rejects RUNNING when no targeting rule can assign subjects`() = withDb {
        val flag = transaction { insertFlag() }
        val saved = service.add(experimentInput(flag).copy(targetingRuleId = null))

        val failure = assertFailsWith<IllegalArgumentException> {
            service.setStatus(saved.id, ExperimentStatus.RUNNING)
        }
        assertTrue(failure.message?.contains("targeting rule") == true)
        assertEquals(ExperimentStatus.DRAFT, service.getById(saved.id)?.status)
    }

    // -----------------------------------------------------------------
    // Conversion goals — role + CUPED wiring
    // -----------------------------------------------------------------

    @Test
    fun `addConversionGoal persists role and cupedCovariate through JSONB`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        val covariate = CupedCovariate(
            eventType = EventType.Interaction,
            elementType = "click",
            lookbackWindow = "P21D",
        )
        val goal = service.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "Primary signup",
                eventType = EventType.Interaction,
                metricType = GoalMetricType.EVENT_COUNT,
                role = ConversionGoalRole.GUARDRAIL,
                cupedCovariate = covariate,
            )
        )
        assertEquals(ConversionGoalRole.GUARDRAIL, goal.role,
            "role should survive the service → repository round-trip")
        val loaded = assertNotNull(conversionGoalRepository.getById(goal.id))
        assertEquals(ConversionGoalRole.GUARDRAIL, loaded.role)
        val decoded = json.decodeFromJsonElement(
            CupedCovariate.serializer(),
            assertNotNull(loaded.cupedCovariate, "cuped_covariate JSONB must be populated"),
        )
        assertEquals(covariate, decoded)
    }

    @Test
    fun `editConversionGoal preserves role and cupedCovariate when other fields change`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        val covariate = CupedCovariate(lookbackWindow = "P7D")
        val goal = service.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "Original",
                eventType = EventType.Interaction,
                metricType = GoalMetricType.EVENT_COUNT,
                role = ConversionGoalRole.SECONDARY,
                cupedCovariate = covariate,
            )
        )
        val updated = service.editConversionGoal(
            goal.id,
            ConversionGoalInput(
                name = "Renamed",
                eventType = EventType.Interaction,
                metricType = GoalMetricType.EVENT_COUNT,
                role = ConversionGoalRole.SECONDARY,
                cupedCovariate = covariate,
            )
        )
        assertEquals("Renamed", updated.name)
        assertEquals(ConversionGoalRole.SECONDARY, updated.role)
        assertNotNull(updated.cupedCovariate,
            "cupedCovariate should survive editConversionGoal")
    }

    @Test
    fun `addConversionGoal defaults role to PRIMARY when input does not set it`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        val goal = service.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "Default role goal",
                eventType = EventType.Impression,
                metricType = GoalMetricType.UNIQUE_CONVERSION,
            )
        )
        assertEquals(ConversionGoalRole.PRIMARY, goal.role)
    }

    @Test
    fun `item extra selectors round-trip and can be cleared`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        val keyOnly = service.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "Any recommended item engagement",
                metricType = GoalMetricType.UNIQUE_CONVERSION,
                itemExtraKey = "  experiment_source  ",
            ),
        )
        assertEquals("experiment_source", keyOnly.itemExtraKey)
        assertNull(keyOnly.itemExtraValue)
        val loadedKeyOnly = assertNotNull(conversionGoalRepository.getById(keyOnly.id))
        assertEquals("experiment_source", loadedKeyOnly.itemExtraKey)
        assertNull(loadedKeyOnly.itemExtraValue)

        val goal = service.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "Recommended item engagement",
                eventType = EventType.Interaction,
                metricType = GoalMetricType.UNIQUE_CONVERSION,
                itemExtraKey = "  recommendation_strategy  ",
                itemExtraValue = "  two_tower  ",
            ),
        )
        assertEquals("recommendation_strategy", goal.itemExtraKey)
        assertEquals("  two_tower  ", goal.itemExtraValue)
        val loaded = assertNotNull(conversionGoalRepository.getById(goal.id))
        assertEquals("recommendation_strategy", loaded.itemExtraKey)
        assertEquals("  two_tower  ", loaded.itemExtraValue)

        val cleared = service.editConversionGoal(
            goal.id,
            ConversionGoalInput(
                name = goal.name,
                eventType = EventType.Interaction,
                metricType = goal.metricType,
            ),
        )
        assertNull(cleared.itemExtraKey)
        assertNull(cleared.itemExtraValue)
        val loadedCleared = assertNotNull(conversionGoalRepository.getById(goal.id))
        assertNull(loadedCleared.itemExtraKey)
        assertNull(loadedCleared.itemExtraValue)
    }

    @Test
    fun `item extra validation rejects malformed keys orphan values and oversized values`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        assertFailsWith<IllegalArgumentException> {
            service.addConversionGoal(
                exp.id,
                ConversionGoalInput(name = "Bad key", itemExtraKey = "Recommendation-Source"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.addConversionGoal(
                exp.id,
                ConversionGoalInput(name = "Orphan value", itemExtraValue = "two_tower"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.addConversionGoal(
                exp.id,
                ConversionGoalInput(
                    name = "Long value",
                    itemExtraKey = "recommendation_strategy",
                    itemExtraValue = "x".repeat(513),
                ),
            )
        }
    }

    @Test
    fun `session duration goal needs no event narrowing and rejects incompatible filters`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        val goal = service.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "Observed session duration",
                metricType = GoalMetricType.SESSION_DURATION,
            ),
        )
        assertEquals(GoalMetricType.SESSION_DURATION, goal.metricType)
        assertNull(goal.eventType)
        assertNull(goal.itemExtraKey)

        assertFailsWith<IllegalArgumentException> {
            service.addConversionGoal(
                exp.id,
                ConversionGoalInput(
                    name = "Filtered duration",
                    metricType = GoalMetricType.SESSION_DURATION,
                    eventType = EventType.Interaction,
                    elementType = "button",
                    elementId = "checkout",
                    pagePath = "/checkout",
                    itemExtraKey = "experiment_source",
                    itemExtraValue = "recommendations",
                    cupedCovariate = CupedCovariate(eventType = EventType.Interaction),
                ),
            )
        }


        val independentlyInvalid = listOf(
            ConversionGoalInput("Event", metricType = GoalMetricType.SESSION_DURATION, eventType = EventType.Interaction),
            ConversionGoalInput("Element type", metricType = GoalMetricType.SESSION_DURATION, elementType = "button"),
            ConversionGoalInput("Element id", metricType = GoalMetricType.SESSION_DURATION, elementId = "checkout"),
            ConversionGoalInput("Page", metricType = GoalMetricType.SESSION_DURATION, pagePath = "/checkout"),
            ConversionGoalInput(
                "Page prefixes",
                metricType = GoalMetricType.SESSION_DURATION,
                pagePathPrefixes = listOf("/articles/"),
            ),
            ConversionGoalInput("Item", metricType = GoalMetricType.SESSION_DURATION, itemExtraKey = "source"),
            ConversionGoalInput(
                "CUPED",
                metricType = GoalMetricType.SESSION_DURATION,
                cupedCovariate = CupedCovariate(eventType = EventType.Interaction),
            ),
        )
        independentlyInvalid.forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                service.addConversionGoal(exp.id, invalid)
            }
        }
    }

    @Test
    fun `each non-session narrowing predicate is independently sufficient`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        val inputs = listOf(
            ConversionGoalInput("Element type", elementType = "button"),
            ConversionGoalInput("Element id", elementId = "checkout"),
            ConversionGoalInput("Page", pagePath = "/checkout"),
            ConversionGoalInput("Page prefixes", pagePathPrefixes = listOf("/articles/")),
        )

        val saved = inputs.map { service.addConversionGoal(exp.id, it) }

        assertEquals(listOf("button", null, null, null), saved.map { it.elementType })
        assertEquals(listOf(null, "checkout", null, null), saved.map { it.elementId })
        assertEquals(listOf(null, null, "/checkout", null), saved.map { it.pagePath })
        assertEquals(listOf(emptyList(), emptyList(), emptyList(), listOf("/articles/")), saved.map { it.pagePathPrefixes })
    }

    @Test
    fun `conversion goals remain mutable while experiment is paused`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        service.setStatus(exp.id, ExperimentStatus.RUNNING)
        service.setStatus(exp.id, ExperimentStatus.PAUSED)

        val saved = service.addConversionGoal(
            exp.id,
            ConversionGoalInput("Paused goal", eventType = EventType.Interaction),
        )

        assertEquals("Paused goal", saved.name)
    }

    @Test
    fun `getResults + getAnalysisReports return the empty list for a fresh experiment`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        assertTrue(service.getResults(exp.id).isEmpty())
        assertTrue(service.getResults(exp.id, offset = 0L, limit = 25).isEmpty())
        assertTrue(service.getAnalysisReports(exp.id).isEmpty())
        assertTrue(service.getAnalysisReports(exp.id, offset = 0L, limit = 10).isEmpty())
    }

    @Test
    fun `getByFlagId + getAll return the experiment after creation`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        val byFlag = service.getByFlagId(flag.id)
        assertEquals(1, byFlag.size)
        assertEquals(exp.id, byFlag.single().id)
        val byFlagPaged = service.getByFlagId(flag.id, offset = 0L, limit = 10)
        assertEquals(1, byFlagPaged.size)
        val all = service.getAll(offset = 0L, limit = 10)
        assertTrue(all.any { it.id == exp.id })
    }

    @Test
    fun `delete removes the experiment and its cascading rows`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        service.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "G",
                eventType = EventType.Interaction,
                metricType = GoalMetricType.UNIQUE_CONVERSION,
            )
        )
        service.delete(exp.id)
        assertNull(service.getById(exp.id))
        // Goals should have been removed by FK cascade.
        assertTrue(service.getConversionGoals(exp.id).isEmpty())
    }

    @Test
    fun `deleteConversionGoal removes the goal row`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        val goal = service.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "G",
                eventType = EventType.Interaction,
                metricType = GoalMetricType.UNIQUE_CONVERSION,
            )
        )
        service.deleteConversionGoal(goal.id)
        assertTrue(service.getConversionGoals(exp.id).isEmpty())
    }

    @Test
    fun `setStatus rejects an invalid transition`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        // DRAFT → ARCHIVED is not a valid transition (must go via COMPLETED or stay in DRAFT).
        assertFails("DRAFT → ARCHIVED is not allowed") {
            service.setStatus(exp.id, ExperimentStatus.ARCHIVED)
        }
    }

    @Test
    fun `edit rejects changing the targeting rule attachment after DRAFT`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        service.setStatus(exp.id, ExperimentStatus.RUNNING)
        val ex = assertFails("targeting rule lock should fire after leaving DRAFT") {
            service.edit(
                exp.id,
                experimentInput(flag).copy(targetingRuleId = null),
            )
        }
        assertTrue(
            ex.message?.contains("targeting rule") == true,
            "edit should reject rule reattachment after leaving DRAFT, got: ${ex.message}",
        )
    }

    @Test
    fun `add rejects an experiment attached to a rule that does not exist`() = withDb {
        val flag = transaction { insertFlag() }
        assertFails("should reject missing rule") {
            service.add(
                experimentInput(flag).copy(targetingRuleId = "does-not-exist"),
            )
        }
    }

    // -----------------------------------------------------------------
    // validateGoalNarrowing — must have at least one filter predicate
    // -----------------------------------------------------------------

    @Test
    fun `addConversionGoal rejects goal with no narrowing predicates`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        assertFails("goal without any narrowing must throw") {
            service.addConversionGoal(
                exp.id,
                ConversionGoalInput(
                    name = "Too broad",
                    metricType = GoalMetricType.UNIQUE_CONVERSION,
                    // No eventType, elementType, elementId, or pagePath
                ),
            )
        }
    }

    // -----------------------------------------------------------------
    // validateStatusTransition — invalid transitions
    // -----------------------------------------------------------------

    @Test
    fun `setStatus rejects invalid transitions`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        // DRAFT → PAUSED is not allowed
        assertFails("DRAFT → PAUSED must throw") {
            service.setStatus(exp.id, ExperimentStatus.PAUSED)
        }
        // DRAFT → COMPLETED is not allowed
        assertFails("DRAFT → COMPLETED must throw") {
            service.setStatus(exp.id, ExperimentStatus.COMPLETED)
        }
    }

    @Test
    fun `setStatus rejects RUNNING to DRAFT`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        service.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "Goal",
                eventType = EventType.Interaction,
                metricType = GoalMetricType.UNIQUE_CONVERSION,
            ),
        )
        service.setStatus(exp.id, ExperimentStatus.RUNNING)
        assertFails("RUNNING → DRAFT must throw") {
            service.setStatus(exp.id, ExperimentStatus.DRAFT)
        }
    }

    @Test
    fun `status lifecycle covers pause resume complete and archive`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))

        service.setStatus(exp.id, ExperimentStatus.RUNNING)
        service.setStatus(exp.id, ExperimentStatus.PAUSED)
        service.setStatus(exp.id, ExperimentStatus.RUNNING)
        service.setStatus(exp.id, ExperimentStatus.COMPLETED)
        val archived = service.setStatus(exp.id, ExperimentStatus.ARCHIVED)

        assertEquals(ExperimentStatus.ARCHIVED, archived.status)
        assertFailsWith<IllegalArgumentException> {
            service.setStatus(exp.id, ExperimentStatus.RUNNING)
        }
    }

    // -----------------------------------------------------------------
    // Assignment identity and cache behavior
    // -----------------------------------------------------------------

    @Test
    fun `getAssignment rejects a blank installation id`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        assertFailsWith<IllegalArgumentException> {
            service.getAssignment(exp.id, null, " ")
        }
    }

    @Test
    fun `login claims anonymous assignment without changing variation or creating a second row`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        val installationId = "installation-login"
        val principalId = UUID.random()

        val anonymous = service.assignVariation(exp.id, null, installationId)
        val authenticated = service.assignVariation(exp.id, principalId, installationId)

        assertEquals(anonymous.id, authenticated.id)
        assertEquals(anonymous.variationKey, authenticated.variationKey)
        assertEquals(anonymous.assignedAt, authenticated.assignedAt)
        assertEquals(principalId, authenticated.principalId)
        assertEquals(installationId, authenticated.installationId)
        assertFailsWith<IllegalStateException> {
            service.assignVariation(exp.id, UUID.random(), installationId)
        }

        connection().useStatement(
            """
                select principal_id, installation_id, variation_key, assigned_at, count(*) over () as row_count
                from experimentation.assignments
                where experiment_id = ?
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, exp.id.toJavaUuid())
            val result = statement.executeQuery()
            assertTrue(result.next())
            assertEquals(principalId.toJavaUuid(), result.getObject("principal_id", java.util.UUID::class.java))
            assertEquals(installationId, result.getString("installation_id"))
            assertEquals(anonymous.variationKey, result.getString("variation_key"))
            assertEquals(anonymous.assignedAt, result.getObject("assigned_at", java.time.OffsetDateTime::class.java))
            assertEquals(1L, result.getLong("row_count"))
            assertFalse(result.next())
        }
    }

    @Test
    fun `identity lookup prefers the principal row when identities point at different rows`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        val principalId = UUID.random()
        val principalAssignment = transaction {
            assignmentRepository.addIfAbsent(
                exp.id,
                "control",
                principalId,
                "installation-principal",
            )
        }
        transaction {
            assignmentRepository.addIfAbsent(
                exp.id,
                "treatment",
                null,
                "installation-anonymous",
            )
        }

        val resolved = assignmentRepository.getByIdentity(
            exp.id,
            principalId,
            "installation-anonymous",
        )

        assertEquals(assertNotNull(principalAssignment).id, assertNotNull(resolved).id)
        assertEquals("installation-principal", resolved.installationId)
    }

    @Test
    fun `assignment identity conflicts preserve the original row`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        val principalId = UUID.random()
        val original = transaction {
            assignmentRepository.addIfAbsent(
                exp.id,
                "control",
                principalId,
                "installation-original",
            )
        }

        val installationConflict = transaction {
            assignmentRepository.addIfAbsent(
                exp.id,
                "treatment",
                UUID.random(),
                "installation-original",
            )
        }
        val principalConflict = transaction {
            assignmentRepository.addIfAbsent(
                exp.id,
                "treatment",
                principalId,
                "installation-other",
            )
        }

        assertNull(installationConflict)
        assertNull(principalConflict)
        val persisted = assertNotNull(
            assignmentRepository.getByIdentity(exp.id, principalId, "installation-original"),
        )
        assertEquals(assertNotNull(original).id, persisted.id)
        assertEquals("control", persisted.variationKey)
        assertEquals(principalId, persisted.principalId)
        assertEquals("installation-original", persisted.installationId)
    }

    @Test
    fun `claimPrincipal cannot transfer an assignment after ownership is established`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = service.add(experimentInput(flag))
        val installationId = "installation-claim-owner"
        val ownerId = UUID.random()
        val anonymous = transaction {
            assignmentRepository.addIfAbsent(exp.id, "control", null, installationId)
        }

        val claimed = transaction {
            assignmentRepository.claimPrincipal(exp.id, installationId, ownerId)
        }
        val transfer = transaction {
            assignmentRepository.claimPrincipal(exp.id, installationId, UUID.random())
        }

        assertEquals(assertNotNull(anonymous).id, assertNotNull(claimed).id)
        assertEquals(ownerId, claimed.principalId)
        assertEquals(anonymous.assignedAt, claimed.assignedAt)
        assertNull(transfer)
        val persisted = assertNotNull(assignmentRepository.getByInstallation(exp.id, installationId))
        assertEquals(ownerId, persisted.principalId)
        assertEquals(anonymous.id, persisted.id)
    }
}
