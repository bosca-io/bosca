@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.experimentation.repository

import bosca.analytics.model.EventType
import bosca.analytics.server.ServerAnalyticsClient
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.experimentation.FlagCacheTestSupport
import bosca.experimentation.configuration.ExperimentationMigration
import bosca.experimentation.jobs.readLatestAnalysisDetails
import bosca.experimentation.model.AnalysisMethod
import bosca.experimentation.model.AnalysisReport
import bosca.experimentation.model.BayesianPrior
import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.ConversionGoalRole
import bosca.experimentation.model.CupedCovariate
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentStatus
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FlagStatus
import bosca.experimentation.model.FlagType
import bosca.experimentation.model.GoalMetricType
import bosca.experimentation.model.RolloutPolicy
import bosca.experimentation.model.RolloutPolicyAction
import bosca.experimentation.model.RolloutPolicyEvent
import bosca.experimentation.model.RolloutPolicyMode
import bosca.experimentation.model.RolloutStep
import bosca.experimentation.service.ExperimentService
import bosca.experimentation.service.FeatureFlagServiceImpl
import bosca.observability.ErrorCapture
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.PubSubService
import bosca.segmentation.service.SegmentService
import bosca.serialization.UUID
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.toJavaUuid

/**
 * End-to-end integration tests for the experimentation repositories
 * against a real PostgreSQL instance via TestContainers. The suite
 * doubles as the migration smoke test — every `@BeforeTest` clean
 * runs Flyway which applies V1 → V2 → V3, so any SQL error in a new
 * migration surfaces as a setup failure before any assertion runs.
 *
 * What this file pins, which the unit-test suite cannot:
 *
 *   1. Every `::jsonb` and `::enum` cast in the KSP-generated
 *      repository SQL actually round-trips against a live PG
 *      instance. A typo in `experimentation.conversion_goal_role`
 *      or `experimentation.rollout_policy_action` would fail the
 *      insert here instead of at first deploy.
 *   2. The V2/V3 migrations apply cleanly on top of V1.
 *   3. JSONB columns (`rollout_policy`, `bayesian_prior`,
 *      `cuped_covariate`, `old_weights`, `new_weights`) deserialize
 *      correctly when read back — the experimentation service decodes
 *      them via kotlinx-serialization, but the model-layer reads
 *      come back through the default JsonElement mapper, so this
 *      test exercises both sides.
 *   4. Enum mappings (`GoalMetricType`, `ConversionGoalRole`,
 *      `AnalysisMethod`, `RolloutPolicyAction`) match the Postgres
 *      enum labels case-for-case. A drift between the Kotlin
 *      `.name.lowercase()` convention and the SQL enum label would
 *      reject inserts with a cryptic cast error; this test surfaces
 *      it as a clear assertion failure on first run.
 *
 * Each test runs inside `transaction {}` inside `withDb {}` so the
 * DB state is reset between tests via the `DELETE` block in
 * `@BeforeTest`.
 */
class ExperimentationRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_experimentation_test")
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
    private val flagAssignmentRepository = FlagAssignmentRepositoryImpl()
    private val experimentRepository = ExperimentRepositoryImpl()
    private val conversionGoalRepository = ConversionGoalRepositoryImpl()
    private val analysisReportRepository = AnalysisReportRepositoryImpl()
    private val rolloutPolicyEventRepository = RolloutPolicyEventRepositoryImpl()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }

        if (!schemaInitialized) {
            runBlocking {
                FlywayMigration(pool).migrate(listOf(ExperimentationMigration()))
            }
            schemaInitialized = true
        }

        withDb {
            transaction {
                // Child-first deletes so FK cascades don't fight us.
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
    }

    private fun withDb(block: suspend () -> Unit) {
        runBlocking {
            val manager = pool.connection()
            try {
                withContext(manager.asCoroutineContext()) {
                    block()
                }
            } finally {
                withContext(NonCancellable) {
                    manager.release()
                }
            }
        }
    }

    private suspend fun <T> withConnection(block: suspend () -> T): T {
        val manager = pool.connection()
        return try {
            withContext(manager.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }

    // -----------------------------------------------------------------
    // Test fixture builders
    // -----------------------------------------------------------------

    private fun variationsJson() = buildJsonArray {
        add(buildJsonObject {
            put("key", "control"); put("name", "Control"); put("description", ""); put("value", false)
        })
        add(buildJsonObject {
            put("key", "treatment"); put("name", "Treatment"); put("description", ""); put("value", true)
        })
    }

    private suspend fun insertFlag(key: String = "test-flag"): FeatureFlag =
        flagRepository.add(
            FeatureFlag(
                key = key,
                name = "Test Flag",
                description = "",
                type = FlagType.BOOLEAN,
                status = FlagStatus.ENABLED,
                variations = variationsJson(),
                defaultVariationKey = "control",
                targetingRules = null,
            )
        )

    private suspend fun insertExperiment(
        flag: FeatureFlag,
        rolloutPolicy: RolloutPolicy? = null,
        analysisMethod: AnalysisMethod = AnalysisMethod.FREQUENTIST,
        bayesianPrior: BayesianPrior? = null,
    ): Experiment {
        val exp = Experiment(
            featureFlagId = flag.id,
            controlVariationKey = "control",
            name = "Test Experiment",
            description = "",
            hypothesis = "treatment > control",
            status = ExperimentStatus.DRAFT,
            rolloutPolicy = rolloutPolicy?.let {
                json.encodeToJsonElement(RolloutPolicy.serializer(), it)
            },
            analysisMethod = analysisMethod,
            bayesianPrior = bayesianPrior?.let {
                json.encodeToJsonElement(BayesianPrior.serializer(), it)
            },
        )
        return experimentRepository.add(exp)
    }

    // -----------------------------------------------------------------
    // Migration smoke test
    // -----------------------------------------------------------------

    @Test
    fun `V1 through V7 migrations apply cleanly and create every expected enum`() = withDb {
        // Flyway already ran by setup(). Query pg_type for each enum
        // defined by V1/V2/V3 and assert every expected label is
        // present — catches label-name drift between Kotlin enums
        // and SQL declarations.
        val raw = mutableMapOf<String, MutableList<String>>()
        connection().useStatement(
            """
            SELECT t.typname AS enum_name, e.enumlabel AS label
            FROM pg_type t
            JOIN pg_enum e ON t.oid = e.enumtypid
            JOIN pg_namespace n ON n.oid = t.typnamespace
            WHERE n.nspname = 'experimentation'
            ORDER BY enum_name, e.enumsortorder
            """.trimIndent()
        ) { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                val name = rs.getString("enum_name")
                val label = rs.getString("label")
                raw.getOrPut(name) { mutableListOf() }.add(label)
            }
        }
        // Widen to `Map<String, List<String>>` so `assertEquals`'s
        // @OnlyInputTypes check accepts the immutable List expected
        // values below.
        val enumLabels: Map<String, List<String>> = raw.mapValues { it.value.toList() }

        // V1 enums
        assertEquals(
            listOf("boolean", "percentage", "string", "json"),
            enumLabels["flag_type"], "flag_type labels mismatch",
        )
        assertEquals(
            listOf("draft", "enabled", "disabled", "archived"),
            enumLabels["flag_status"], "flag_status labels mismatch",
        )
        assertEquals(
            listOf("draft", "running", "paused", "completed", "archived"),
            enumLabels["experiment_status"], "experiment_status labels mismatch",
        )
        assertEquals(
            listOf("unique_conversion", "event_count", "session_duration"),
            enumLabels["goal_metric_type"], "goal_metric_type labels mismatch",
        )
        // V2 enums — Phase 1 (roles) + Phase 2 (policy events)
        assertEquals(
            listOf("primary", "secondary", "guardrail"),
            enumLabels["conversion_goal_role"], "conversion_goal_role labels mismatch",
        )
        assertEquals(
            listOf("advanced", "held", "halted", "completed"),
            enumLabels["rollout_policy_action"], "rollout_policy_action labels mismatch",
        )
        // V3 enums — Phase 4 (Bayesian)
        assertEquals(
            listOf("frequentist", "bayesian"),
            enumLabels["analysis_method"], "analysis_method labels mismatch",
        )
    }

    @Test
    fun `Kotlin enum name-lowercase matches every Postgres enum label`() = withDb {
        // Regression guard: if someone renames a Kotlin enum constant
        // without updating the migration (or vice versa), this
        // assertion catches the drift.
        val goalMetricLabels = GoalMetricType.entries.map { it.name.lowercase() }
        assertEquals(
            setOf("unique_conversion", "event_count", "session_duration"), goalMetricLabels.toSet(),
        )

        val roleLabels = ConversionGoalRole.entries.map { it.name.lowercase() }
        assertEquals(
            setOf("primary", "secondary", "guardrail"), roleLabels.toSet(),
        )

        val actionLabels = RolloutPolicyAction.entries.map { it.name.lowercase() }
        assertEquals(
            setOf("advanced", "held", "halted", "completed"), actionLabels.toSet(),
        )

        val methodLabels = AnalysisMethod.entries.map { it.name.lowercase() }
        assertEquals(
            setOf("frequentist", "bayesian"), methodLabels.toSet(),
        )
    }

    @Test
    fun `experiment_results table has Bayesian and CUPED columns from V3`() = withDb {
        // The V3 migration alters experiment_results with four new
        // columns. A column-name typo in the ALTER would silently
        // leave the table untouched and surface much later at
        // aggregation time. Catch it here.
        val columnNames = mutableSetOf<String>()
        connection().useStatement(
            """
            SELECT column_name FROM information_schema.columns
            WHERE table_schema = 'experimentation'
              AND table_name = 'experiment_results'
            """.trimIndent()
        ) { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) columnNames.add(rs.getString("column_name"))
        }
        for (expected in listOf(
            "probability_beats_control", "expected_loss",
            "adjusted_mean", "adjusted_variance",
        )) {
            assertTrue(expected in columnNames,
                "expected experiment_results.$expected to exist after V3, got columns=$columnNames")
        }
    }

    // -----------------------------------------------------------------
    // FeatureFlagRepository
    // -----------------------------------------------------------------

    @Test
    fun `feature flag insert and read round-trips through real PG`() = withDb {
        val saved = transaction { insertFlag("rt-flag") }
        assertNotEquals(UUID.NIL, saved.id)
        val loaded = flagRepository.getByKey("rt-flag")
        assertNotNull(loaded, "flag should exist after insert")
        assertEquals("rt-flag", loaded.key)
        assertEquals(FlagType.BOOLEAN, loaded.type)
        assertEquals(FlagStatus.ENABLED, loaded.status)
    }

    @Test
    fun `evaluation persists one installation assignment and emits one transition event`() = withDb {
        val flag = transaction { insertFlag("service-assignment") }
        val principalId = UUID.random()
        val cacheSupport = FlagCacheTestSupport()
        provides<CacheManager>(singleton = true) { cacheSupport.cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { cacheSupport.requestCacheSerializer }

        val analyticsClient = mockk<ServerAnalyticsClient>(relaxed = true)
        val pubSubService = mockk<PubSubService>()
        every {
            pubSubService.subscribe(any(), any<kotlinx.serialization.DeserializationStrategy<Any>>())
        } returns emptyFlow()

        val service = FeatureFlagServiceImpl(
            flagRepository = flagRepository,
            experimentRepository = experimentRepository,
            assignmentRepository = AssignmentRepositoryImpl(),
            flagAssignmentRepository = flagAssignmentRepository,
            analyticsClient = analyticsClient,
            segmentService = mockk<SegmentService>(relaxed = true),
            profileService = mockk<ProfileService>(relaxed = true),
            pubSubService = pubSubService,
            experimentService = mockk<ExperimentService>(relaxed = true),
            json = json,
            errorCapture = ErrorCapture.Noop,
        )

        try {
            cacheSupport.withFlagCache {
                assertEquals("control", service.evaluate(flag.key, null, "installation-1", null).variationKey)
                assertEquals(
                    "control",
                    service.evaluate(flag.key, principalId, "installation-1", null).variationKey,
                )
            }
        } finally {
            service.shutdown()
        }

        val distribution = flagAssignmentRepository.getDistribution(flag.id)
        assertEquals(1L, distribution.single().assignmentCount)
        assertEquals("control", distribution.single().variationKey)

        connection().useStatement(
            """
            select principal_id::text
            from experimentation.flag_assignments
            where flag_id = ? and installation_id = ?
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, flag.id.toJavaUuid())
            statement.setString(2, "installation-1")
            val result = statement.executeQuery()
            assertTrue(result.next())
            assertEquals(principalId.toString(), result.getString("principal_id"))
            assertFalse(result.next(), "the installation must have exactly one assignment row")
        }

        coVerify(exactly = 1) {
            analyticsClient.captureForSubject(
                match { event ->
                    event.type == EventType.Assignment &&
                        event.element?.id == flag.id.toString() &&
                        event.element?.type == "feature_flag"
                },
                null,
                "installation-1",
                null,
            )
        }
    }

    @Test
    fun `flag assignment time changes only when variation changes`() = withDb {
        val flag = transaction { insertFlag("assignment-state") }
        val firstAssigned = java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z")
        val repeatedEvaluation = firstAssigned.plusHours(1)
        val changedAssignment = firstAssigned.plusHours(2)

        assertEquals(true, transaction {
            flagAssignmentRepository.record(
                flag.id,
                "installation-1",
                null,
                "control",
                firstAssigned,
                flag.modified,
            )
        })
        assertNull(transaction {
            flagAssignmentRepository.record(
                flag.id,
                "installation-1",
                null,
                "control",
                repeatedEvaluation,
                flag.modified,
            )
        })

        connection().useStatement(
            "select variation_key, assigned_at from experimentation.flag_assignments where flag_id = ?"
        ) { statement ->
            statement.setObject(1, flag.id.toJavaUuid())
            val result = statement.executeQuery()
            assertTrue(result.next())
            assertEquals("control", result.getString("variation_key"))
            assertEquals(firstAssigned, result.getObject("assigned_at", java.time.OffsetDateTime::class.java))
        }

        assertEquals(true, transaction {
            flagAssignmentRepository.record(
                flag.id,
                "installation-1",
                null,
                "treatment",
                changedAssignment,
                flag.modified,
            )
        })
        assertEquals(
            listOf("treatment" to 1L),
            flagAssignmentRepository.getDistribution(flag.id).map { it.variationKey to it.assignmentCount },
        )
    }

    @Test
    fun `flag assignment rejects stale evaluation and stale configuration writes`() = withDb {
        val flag = transaction { insertFlag("stale-assignment") }
        val newer = java.time.OffsetDateTime.parse("2026-01-01T02:00:00Z")
        val older = newer.minusHours(1)
        val principalId = UUID.random()

        assertEquals(true, transaction {
            flagAssignmentRepository.record(
                flag.id,
                "installation-1",
                principalId,
                "treatment",
                newer,
                flag.modified,
            )
        })
        assertNull(transaction {
            flagAssignmentRepository.record(
                flag.id,
                "installation-1",
                principalId,
                "control",
                older,
                flag.modified,
            )
        })

        val updated = transaction { flagRepository.update(flag.copy(name = "Updated")) }
        assertNull(transaction {
            flagAssignmentRepository.record(
                updated.id,
                "installation-2",
                null,
                "control",
                newer.plusHours(1),
                flag.modified,
            )
        })
    }

    @Test
    fun `logging in promotes anonymous flag assignment without duplicate row`() = withDb {
        val flag = transaction { insertFlag("assignment-login") }
        val anonymousAssignedAt = java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z")
        val loginAt = anonymousAssignedAt.plusHours(1)
        val principalId = UUID.random()

        assertEquals(true, transaction {
            flagAssignmentRepository.record(
                flag.id,
                "installation-1",
                null,
                "control",
                anonymousAssignedAt,
                flag.modified,
            )
        })
        assertEquals(false, transaction {
            flagAssignmentRepository.record(
                flag.id,
                "installation-1",
                principalId,
                "control",
                loginAt,
                flag.modified,
            )
        })

        connection().useStatement(
            """
                select principal_id, installation_id, variation_key, assigned_at
                from experimentation.flag_assignments
                where flag_id = ?
            """.trimIndent()
        ) { statement ->
            statement.setObject(1, flag.id.toJavaUuid())
            val result = statement.executeQuery()
            assertTrue(result.next())
            assertEquals(principalId.toJavaUuid(), result.getObject("principal_id", java.util.UUID::class.java))
            assertEquals("installation-1", result.getString("installation_id"))
            assertEquals("control", result.getString("variation_key"))
            assertEquals(
                anonymousAssignedAt,
                result.getObject("assigned_at", java.time.OffsetDateTime::class.java),
            )
            assertFalse(result.next(), "principal ownership must not create a second assignment row")
        }
    }

    @Test
    fun `owned installation assignment rejects anonymous and different principal updates`() = withDb {
        val flag = transaction { insertFlag("assignment-owner") }
        val assignedAt = java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z")
        val owner = UUID.random()

        assertEquals(true, transaction {
            flagAssignmentRepository.record(
                flag.id,
                "installation-1",
                owner,
                "control",
                assignedAt,
                flag.modified,
            )
        })
        assertNull(transaction {
            flagAssignmentRepository.record(
                flag.id,
                "installation-1",
                null,
                "treatment",
                assignedAt.plusHours(1),
                flag.modified,
            )
        })
        assertNull(transaction {
            flagAssignmentRepository.record(
                flag.id,
                "installation-1",
                UUID.random(),
                "treatment",
                assignedAt.plusHours(2),
                flag.modified,
            )
        })
        assertEquals(true, transaction {
            flagAssignmentRepository.record(
                flag.id,
                "installation-1",
                owner,
                "treatment",
                assignedAt.plusHours(3),
                flag.modified,
            )
        })

        connection().useStatement(
            """
                select principal_id, variation_key, assigned_at, count(*) over () as row_count
                from experimentation.flag_assignments
                where flag_id = ? and installation_id = ?
            """.trimIndent()
        ) { statement ->
            statement.setObject(1, flag.id.toJavaUuid())
            statement.setString(2, "installation-1")
            val result = statement.executeQuery()
            assertTrue(result.next())
            assertEquals(owner.toJavaUuid(), result.getObject("principal_id", java.util.UUID::class.java))
            assertEquals("treatment", result.getString("variation_key"))
            assertEquals(1L, result.getLong("row_count"))
            assertFalse(result.next())
        }
    }

    // -----------------------------------------------------------------
    // ExperimentRepository — rolloutPolicy, analysisMethod, bayesianPrior
    // round-trip through JSONB / enum columns
    // -----------------------------------------------------------------

    @Test
    fun `experiment with no rollout policy round-trips with null JSONB columns`() = withDb {
        val flag = transaction { insertFlag() }
        val saved = transaction { insertExperiment(flag) }
        val loaded = experimentRepository.getById(saved.id)
        assertNotNull(loaded)
        assertNull(loaded.rolloutPolicy, "rolloutPolicy should be null when not set")
        assertNull(loaded.bayesianPrior, "bayesianPrior should be null when not set")
        assertEquals(AnalysisMethod.FREQUENTIST, loaded.analysisMethod,
            "analysisMethod should default to FREQUENTIST")
    }

    @Test
    fun `experiment with full rollout policy round-trips through JSONB`() = withDb {
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
            guardrailThreshold = 0.93,
            guardrailMinRegressionPercent = 0.2,
            haltOnGuardrail = true,
        )
        val saved = transaction { insertExperiment(flag, rolloutPolicy = policy) }
        val loaded = experimentRepository.getById(saved.id)
        assertNotNull(loaded)
        val loadedPolicyElement = assertNotNull(loaded.rolloutPolicy,
            "rolloutPolicy JSONB column must be populated after insert")
        val loadedPolicy = json.decodeFromJsonElement(RolloutPolicy.serializer(), loadedPolicyElement)
        assertEquals(policy, loadedPolicy,
            "rolloutPolicy must round-trip through PG JSONB byte-identically")
    }

    @Test
    fun `experiment with BAYESIAN analysis method and prior round-trips`() = withDb {
        val flag = transaction { insertFlag() }
        val prior = BayesianPrior(
            betaPriorAlpha = 10.0,
            betaPriorBeta = 5.0,
            normalPriorMean = 0.5,
            normalPriorVariance = 1.0,
        )
        val saved = transaction {
            insertExperiment(
                flag,
                analysisMethod = AnalysisMethod.BAYESIAN,
                bayesianPrior = prior,
            )
        }
        val loaded = experimentRepository.getById(saved.id)
        assertNotNull(loaded)
        assertEquals(AnalysisMethod.BAYESIAN, loaded.analysisMethod,
            "analysis_method enum must round-trip as BAYESIAN")
        val loadedPrior = json.decodeFromJsonElement(
            BayesianPrior.serializer(),
            assertNotNull(loaded.bayesianPrior, "bayesianPrior JSONB column must be populated"),
        )
        assertEquals(prior, loadedPrior)
    }

    @Test
    fun `experiment update preserves rollout policy and analysis method`() = withDb {
        val flag = transaction { insertFlag() }
        val policy = RolloutPolicy(
            mode = RolloutPolicyMode.ADAPTIVE_CONTINUOUS,
            treatmentVariationKey = "treatment",
            incrementPercent = 5.0,
        )
        val saved = transaction {
            insertExperiment(
                flag,
                rolloutPolicy = policy,
                analysisMethod = AnalysisMethod.BAYESIAN,
            )
        }
        // Update — change name, keep policy/method. Tests the
        // update SQL's jsonb casts and enum casts together.
        val updated = transaction {
            experimentRepository.update(saved.copy(name = "Renamed Experiment"))
        }
        assertEquals("Renamed Experiment", updated.name)
        assertEquals(AnalysisMethod.BAYESIAN, updated.analysisMethod)
        val policyRoundTripped = json.decodeFromJsonElement(
            RolloutPolicy.serializer(),
            assertNotNull(updated.rolloutPolicy),
        )
        assertEquals(policy, policyRoundTripped)
    }

    // -----------------------------------------------------------------
    // ConversionGoalRepository — role + cupedCovariate via JSONB
    // -----------------------------------------------------------------

    @Test
    fun `conversion goal insert with default PRIMARY role round-trips`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = transaction { insertExperiment(flag) }
        val goal = transaction {
            conversionGoalRepository.add(
                ConversionGoal(
                    experimentId = exp.id,
                    name = "Signups",
                    eventType = EventType.Interaction,
                    metricType = GoalMetricType.UNIQUE_CONVERSION,
                )
            )
        }
        assertEquals(ConversionGoalRole.PRIMARY, goal.role,
            "default role should be PRIMARY when not set")
        assertNull(goal.cupedCovariate)

        val loaded = conversionGoalRepository.getById(goal.id)
        assertNotNull(loaded)
        assertEquals(ConversionGoalRole.PRIMARY, loaded.role)
        assertEquals(EventType.Interaction, loaded.eventType)
    }

    @Test
    fun `conversion goal with GUARDRAIL role round-trips`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = transaction { insertExperiment(flag) }
        val goal = transaction {
            conversionGoalRepository.add(
                ConversionGoal(
                    experimentId = exp.id,
                    name = "Page load",
                    eventType = EventType.Impression,
                    metricType = GoalMetricType.EVENT_COUNT,
                    role = ConversionGoalRole.GUARDRAIL,
                )
            )
        }
        val loaded = assertNotNull(conversionGoalRepository.getById(goal.id))
        assertEquals(ConversionGoalRole.GUARDRAIL, loaded.role,
            "conversion_goal_role enum must round-trip as GUARDRAIL")
    }

    @Test
    fun `conversion goal with CUPED covariate round-trips as JSONB`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = transaction { insertExperiment(flag) }
        val covariate = CupedCovariate(
            eventType = EventType.Interaction,
            elementType = "click",
            elementId = "checkout",
            pagePath = "/checkout",
            lookbackWindow = "P30D",
        )
        val goal = transaction {
            conversionGoalRepository.add(
                ConversionGoal(
                    experimentId = exp.id,
                    name = "Interactions per user",
                    eventType = EventType.Interaction,
                    metricType = GoalMetricType.EVENT_COUNT,
                    cupedCovariate = json.encodeToJsonElement(CupedCovariate.serializer(), covariate),
                )
            )
        }
        val loaded = assertNotNull(conversionGoalRepository.getById(goal.id))
        val loadedCovariate = json.decodeFromJsonElement(
            CupedCovariate.serializer(),
            assertNotNull(loaded.cupedCovariate, "cuped_covariate JSONB column must be populated"),
        )
        assertEquals(covariate, loadedCovariate,
            "CupedCovariate must round-trip through PG JSONB byte-identically")
    }

    @Test
    fun `conversion goal update preserves role and covariate`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = transaction { insertExperiment(flag) }
        val covariate = CupedCovariate(lookbackWindow = "P14D")
        val original = transaction {
            conversionGoalRepository.add(
                ConversionGoal(
                    experimentId = exp.id,
                    name = "Original",
                    eventType = EventType.Interaction,
                    metricType = GoalMetricType.EVENT_COUNT,
                    role = ConversionGoalRole.SECONDARY,
                    cupedCovariate = json.encodeToJsonElement(CupedCovariate.serializer(), covariate),
                )
            )
        }
        val updated = transaction {
            conversionGoalRepository.update(original.copy(name = "Renamed"))
        }
        assertNotNull(updated)
        assertEquals("Renamed", updated.name)
        assertEquals(ConversionGoalRole.SECONDARY, updated.role,
            "role must survive the UPDATE")
        assertNotNull(updated.cupedCovariate,
            "cupedCovariate must survive the UPDATE")
    }

    // -----------------------------------------------------------------
    // RolloutPolicyEventRepository — audit trail round-trips with
    // the rollout_policy_action enum cast and two JSONB weight maps
    // -----------------------------------------------------------------

    @Test
    fun `rollout policy event insert round-trips every action variant`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = transaction { insertExperiment(flag) }

        val oldWeights = buildJsonObject { put("control", 100); put("treatment", 0) }
        val newWeights = buildJsonObject { put("control", 75); put("treatment", 25) }

        for (action in RolloutPolicyAction.entries) {
            val event = transaction {
                rolloutPolicyEventRepository.add(
                    RolloutPolicyEvent(
                        experimentId = exp.id,
                        action = action,
                        reason = "test $action",
                        oldWeights = oldWeights,
                        newWeights = newWeights,
                    )
                )
            }
            assertEquals(action, event.action,
                "rollout_policy_action enum must round-trip as $action")
            assertNotEquals(UUID.NIL, event.id)
        }

        // Read-back via getByExperimentId returns all events newest-first.
        val events = rolloutPolicyEventRepository.getByExperimentId(exp.id, 0L, 100)
        assertEquals(RolloutPolicyAction.entries.size, events.size,
            "all inserted events should be readable")
        val actions = events.map { it.action }.toSet()
        assertEquals(RolloutPolicyAction.entries.toSet(), actions,
            "every action enum variant should survive the JSONB + enum round-trip")
    }

    @Test
    fun `rollout policy event getLatest returns the most recent row`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = transaction { insertExperiment(flag) }
        val weights = buildJsonObject { put("control", 50); put("treatment", 50) }
        // Insert in separate transactions so the `default now()`
        // timestamps on `created` differ. Inserting both in one
        // transaction gives them identical timestamps and the
        // `ORDER BY created DESC LIMIT 1` is non-deterministic.
        transaction {
            rolloutPolicyEventRepository.add(
                RolloutPolicyEvent(
                    experimentId = exp.id, action = RolloutPolicyAction.HELD,
                    reason = "first", oldWeights = weights, newWeights = weights,
                )
            )
        }
        // Tiny sleep to guarantee strictly-later `now()`. PG's
        // `now()` is statement-precise at microseconds, so 5ms is
        // several orders of magnitude above the resolution.
        Thread.sleep(5)
        transaction {
            rolloutPolicyEventRepository.add(
                RolloutPolicyEvent(
                    experimentId = exp.id, action = RolloutPolicyAction.ADVANCED,
                    reason = "second", oldWeights = weights, newWeights = weights,
                )
            )
        }
        val latest = assertNotNull(rolloutPolicyEventRepository.getLatest(exp.id))
        assertEquals(RolloutPolicyAction.ADVANCED, latest.action)
        assertEquals("second", latest.reason)
    }

    @Test
    fun `rollout policy events cascade delete with experiment`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = transaction { insertExperiment(flag) }
        val weights = buildJsonObject { put("control", 100) }
        transaction {
            rolloutPolicyEventRepository.add(
                RolloutPolicyEvent(
                    experimentId = exp.id, action = RolloutPolicyAction.HELD,
                    reason = "cascade test", oldWeights = weights, newWeights = weights,
                )
            )
        }
        assertEquals(1, rolloutPolicyEventRepository.getByExperimentId(exp.id, 0L, 10).size)

        transaction { experimentRepository.deleteById(exp.id) }
        assertEquals(0, rolloutPolicyEventRepository.getByExperimentId(exp.id, 0L, 10).size,
            "FK cascade should remove rollout_policy_events when the experiment is deleted")
    }

    @Test
    fun `latest analysis revision query ignores later stale and wrong-type reports`() = withDb {
        val flag = transaction { insertFlag() }
        val experiment = transaction { insertExperiment(flag) }
        val current = transaction {
            analysisReportRepository.add(
                AnalysisReport(
                    experimentId = experiment.id,
                    summary = "current",
                    recommendation = "SHIP",
                    details = buildJsonObject {
                        put("controlVariationKey", "7")
                        put("experimentRevision", 7L)
                    },
                )
            )
        }
        Thread.sleep(5)
        val stale = transaction {
            analysisReportRepository.add(
                AnalysisReport(
                    experimentId = experiment.id,
                    summary = "late stale",
                    recommendation = "SHIP",
                    details = buildJsonObject {
                        put("controlVariationKey", "7")
                        put("experimentRevision", 6L)
                    },
                )
            )
        }
        Thread.sleep(5)
        val quotedRevision = transaction {
            analysisReportRepository.add(
                AnalysisReport(
                    experimentId = experiment.id,
                    summary = "quoted revision",
                    recommendation = "HALT",
                    details = buildJsonObject {
                        put("controlVariationKey", "7")
                        put("experimentRevision", "7")
                    },
                )
            )
        }
        Thread.sleep(5)
        val numericControl = transaction {
            analysisReportRepository.add(
                AnalysisReport(
                    experimentId = experiment.id,
                    summary = "numeric control",
                    recommendation = "HALT",
                    details = buildJsonObject {
                        put("controlVariationKey", 7L)
                        put("experimentRevision", 7L)
                    },
                )
            )
        }

        assertEquals(numericControl.id, analysisReportRepository.getByExperimentId(experiment.id, 0L, 1).single().id)
        assertEquals(
            current.id,
            analysisReportRepository.getLatestForRevision(experiment.id, "7", 7L).single().id,
        )
        assertEquals(
            listOf(current.id, numericControl.id, quotedRevision.id, stale.id),
            analysisReportRepository.getByExperimentIdForRevision(
                experiment.id,
                "7",
                7L,
                0L,
                10,
            ).map { it.id },
        )
        val rolloutDetails = assertNotNull(
            readLatestAnalysisDetails(
                analysisReportRepository,
                experiment.copy(controlVariationKey = "7", analysisRevision = 7L),
            )
        )
        assertEquals("7", rolloutDetails.controlVariationKey)
        assertEquals(7L, rolloutDetails.experimentRevision)
    }

    @Test
    fun `current report pagination promotes only the newest current report`() = withDb {
        val flag = transaction { insertFlag() }
        val experiment = transaction { insertExperiment(flag) }
        suspend fun addReport(summary: String, revision: Long) = transaction {
            analysisReportRepository.add(
                AnalysisReport(
                    experimentId = experiment.id,
                    summary = summary,
                    recommendation = "HOLD",
                    details = buildJsonObject {
                        put("controlVariationKey", "control")
                        put("experimentRevision", revision)
                    },
                )
            )
        }

        val currentOld = addReport("current old", 7L)
        Thread.sleep(5)
        val staleMiddle = addReport("stale middle", 6L)
        Thread.sleep(5)
        val currentNew = addReport("current new", 7L)

        val firstPage = analysisReportRepository.getByExperimentIdForRevision(
            experiment.id, "control", 7L, 0L, 2,
        )
        val secondPage = analysisReportRepository.getByExperimentIdForRevision(
            experiment.id, "control", 7L, 2L, 2,
        )

        assertEquals(listOf(currentNew.id, staleMiddle.id), firstPage.map { it.id })
        assertEquals(listOf(currentOld.id), secondPage.map { it.id })
    }

    @Test
    fun `experiment definition lock serializes rollout with definition edits`() = runBlocking {
        val flag = withConnection { transaction { insertFlag() } }
        val experiment = withConnection { transaction { insertExperiment(flag) } }
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val rollout = async {
            withConnection {
                transaction {
                    assertNotNull(experimentRepository.getByIdForUpdate(experiment.id))
                    locked.complete(Unit)
                    release.await()
                }
            }
        }
        locked.await()
        val blockedEdit = runCatching {
            withConnection {
                transaction {
                    connection().useStatement("set local lock_timeout = '100ms'") { it.execute() }
                    experimentRepository.update(experiment.copy(name = "Edited definition"))
                }
            }
        }.exceptionOrNull()

        assertNotNull(blockedEdit, "definition edit must not cross the rollout row lock")
        assertTrue(
            generateSequence(blockedEdit) { it.cause }
                .mapNotNull { it.message }
                .any { it.contains("lock timeout") },
            blockedEdit.message,
        )
        release.complete(Unit)
        rollout.await()
        val updated = withConnection {
            transaction {
                experimentRepository.update(experiment.copy(name = "Edited definition"))
            }
        }
        assertEquals("Edited definition", updated.name)
        assertEquals(experiment.analysisRevision + 1, updated.analysisRevision)
    }
}
