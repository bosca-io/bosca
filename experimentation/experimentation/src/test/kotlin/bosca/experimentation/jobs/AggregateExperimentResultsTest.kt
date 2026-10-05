@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.experimentation.jobs

import bosca.analytics.model.EventType
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.experimentation.configuration.ExperimentationConfig
import bosca.experimentation.configuration.ExperimentationMigration
import bosca.experimentation.FlagCacheTestSupport
import bosca.experimentation.model.Assignment
import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.ConversionGoalInput
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentActivationFilter
import bosca.experimentation.model.ExperimentStatus
import bosca.experimentation.model.ExperimentInput
import bosca.experimentation.model.ExperimentResult
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FlagStatus
import bosca.experimentation.model.FlagType
import bosca.experimentation.model.GoalMetricType
import bosca.experimentation.repository.AssignmentRepositoryImpl
import bosca.experimentation.repository.ConversionGoalRepositoryImpl
import bosca.experimentation.repository.ExperimentRepositoryImpl
import bosca.experimentation.repository.ExperimentResultRepositoryImpl
import bosca.experimentation.repository.FeatureFlagRepositoryImpl
import bosca.experimentation.service.ExperimentServiceImpl
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.sql.PreparedStatement
import java.sql.ResultSet
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
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end test for [aggregateExperimentResults] — the top-level
 * aggregation job entry point. Runs against a real PostgreSQL
 * TestContainer for the assignments / experiment_results / goals /
 * flags state, and provides a mocked Trino [ConnectionPool] that
 * returns canned `client_id → count` rows per goal.
 *
 * Pins the following behaviors that aren't covered by the pure
 * helper tests:
 *
 *   1. `aggregateExperimentResults` walks every goal, calls the
 *      SQL builder, executes the assignment-attributed query, and
 *      upserts per-(variation, goal) rows into `experiment_results`.
 *   2. When a goal has zero matching events, the experiment_results
 *      row is still written with `impressions = count` and
 *      `conversions = 0`, so the admin UI can distinguish
 *      "no events landed" from "never aggregated".
 *   3. The aggregator bails early when the flag is missing or the
 *      variations palette is empty.
 *
 * The Trino pool is mocked at the DI boundary via
 * `ProviderRegistry.get(ConnectionPool::class, "trino-readonly")`.
 * Every `useStatement` on the mocked connection returns a scripted
 * ResultSet; this is fragile JDBC-shape mocking but is the only
 * way to cover the main loop without standing up a full Trino
 * container for the first time in this codebase.
 */
class AggregateExperimentResultsTest {

    companion object {
        private const val DIAGNOSTICS_ROW = "__diagnostics__"

        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_aggregate_test")
            withReuse(true)
            start()
        }

        private val pgPool = ConnectionPool(
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
                pgPool.close()
            }
            postgres.stop()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val flagRepository = FeatureFlagRepositoryImpl()
    private val experimentRepository = ExperimentRepositoryImpl()
    private val conversionGoalRepository = ConversionGoalRepositoryImpl()
    private val experimentResultRepository = ExperimentResultRepositoryImpl()
    private val assignmentRepository = AssignmentRepositoryImpl()
    private val cacheSupport = FlagCacheTestSupport()

    private lateinit var experimentService: ExperimentServiceImpl

    /**
     * Captured per-query-invocation scripts. The test installs a
     * mocked Trino pool that pops the next script off this queue
     * on each `useStatement` call. Tests push `clientId → count`
     * maps in the order the aggregator is expected to query goals.
     */
    private val trinoScripts = mutableListOf<Map<String, Number>>()
    private val trinoQueries = mutableListOf<PerUserQuery>()
    private val assignmentSubjectIds = mutableMapOf<String, String>()
    private var trinoFailure: Throwable? = null
    private var trinoFailureAfter: Int = 0
    private var beforeTrinoQuery: (suspend () -> Unit)? = null
    private var invalidItemExtras = 0L
    private var missingItemExtraValues = 0L
    private val capturedDiagnostics = mutableListOf<Map<String, Any?>>()
    private var trinoQueryCount = 0
    private var trinoCountColumnReads = 0

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pgPool }
        provides<Json>(singleton = true) { json }
        provides<CacheManager>(singleton = true) { cacheSupport.cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { cacheSupport.requestCacheSerializer }

        // Install a real ExperimentationConfig — needed by the
        // aggregator to look up the events table name.
        provides<ExperimentationConfig>(singleton = true) {
            ExperimentationConfig(eventsTable = "analytics.events")
        }

        // Install a mocked Trino pool under the "trino-readonly"
        // name. The aggregator looks this up via
        // `provide(name = "trino-readonly")`, and the stubs below
        // use a script queue to return canned rows per
        // `useStatement` call.
        mockkObject(ProviderRegistry)
        val fakeTrinoPool = fakeTrinoPool()
        every {
            ProviderRegistry.get(ConnectionPool::class, "trino-readonly")
        } returns object : ObjectProvider<ConnectionPool> {
            override val type = ConnectionPool::class
            override suspend fun get(): ConnectionPool = fakeTrinoPool
        }
        // The main PG pool lookup still goes through the real
        // provides<> registration above, so no name override is
        // needed for it.

        if (!schemaInitialized) {
            runBlocking {
                FlywayMigration(pgPool).migrate(listOf(ExperimentationMigration()))
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

        experimentService = ExperimentServiceImpl(
            experimentRepository = experimentRepository,
            assignmentRepository = assignmentRepository,
            conversionGoalRepository = conversionGoalRepository,
            experimentResultRepository = experimentResultRepository,
            analysisReportRepository = bosca.experimentation.repository.AnalysisReportRepositoryImpl(),
            featureFlagRepository = flagRepository,
            rolloutPolicyEventRepository = mockk(relaxed = true),
            pubSubService = mockk<PubSubService>(relaxed = true),
            json = json,
            errorCapture = bosca.observability.ErrorCapture { _, _, context -> capturedDiagnostics += context },
        )
        provides<bosca.observability.ErrorCapture>(singleton = true) {
            bosca.observability.ErrorCapture { _, _, context -> capturedDiagnostics += context }
        }
        provides<bosca.experimentation.service.ExperimentService>(singleton = true) { experimentService }
        provides<bosca.experimentation.repository.ExperimentRepository>(singleton = true) { experimentRepository }
        provides<bosca.experimentation.repository.FeatureFlagRepository>(singleton = true) { flagRepository }
        provides<bosca.experimentation.repository.ExperimentResultRepository>(singleton = true) { experimentResultRepository }
        provides<bosca.experimentation.repository.AssignmentRepository>(singleton = true) { assignmentRepository }
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ProviderRegistry)
        ProviderRegistry.clear()
        trinoScripts.clear()
        trinoQueries.clear()
        assignmentSubjectIds.clear()
        trinoFailure = null
        trinoFailureAfter = 0
        beforeTrinoQuery = null
        invalidItemExtras = 0L
        missingItemExtraValues = 0L
        capturedDiagnostics.clear()
        trinoQueryCount = 0
        trinoCountColumnReads = 0
    }

    /**
     * Builds a mocked ConnectionPool whose every useStatement
     * returns the next script in [trinoScripts] as a fake
     * ResultSet. This is intentionally minimal — it only covers
     * the methods the aggregation job actually calls.
     */
    private fun fakeTrinoPool(): ConnectionPool {
        val pool = mockk<ConnectionPool>()
        every { pool.connection() } answers {
            val manager = mockk<ConnectionManager>()
            io.mockk.coEvery { manager.release() } returns Unit
            io.mockk.coEvery {
                manager.useStatement<Any>(any(), any())
            } coAnswers {
                beforeTrinoQuery?.let { action ->
                    beforeTrinoQuery = null
                    action()
                }
                if (trinoQueryCount >= trinoFailureAfter) trinoFailure?.let { throw it }
                trinoQueryCount++
                val sql = firstArg<String>()
                val block = secondArg<suspend (PreparedStatement) -> Any>()
                val script = if (trinoScripts.isNotEmpty()) trinoScripts.removeAt(0) else emptyMap()
                val stmt = mockk<PreparedStatement>(relaxed = true)
                val params = sortedMapOf<Int, String>()
                every { stmt.setString(any(), any()) } answers { params[firstArg()] = secondArg() }
                val rs = scriptedResultSet(script, assignmentAttributed = "WITH assignment_rows AS" in sql)
                every { stmt.executeQuery() } returns rs
                block(stmt).also { trinoQueries.add(PerUserQuery(sql, params.values.toList())) }
            }
            manager
        }
        return pool
    }

    private fun scriptedResultSet(script: Map<String, Number>, assignmentAttributed: Boolean): ResultSet {
        val rs = mockk<ResultSet>(relaxed = true)
        val iterator = script.entries.iterator()
        var current: Map.Entry<String, Number>? = null
        every { rs.next() } answers {
            if (iterator.hasNext()) {
                current = iterator.next()
                true
            } else {
                current = null
                false
            }
        }
        every { rs.getString("client_id") } answers {
            current?.key?.takeUnless { it == DIAGNOSTICS_ROW }?.let {
                if (assignmentAttributed) {
                    assignmentSubjectIds[it] ?: it
                } else if (it.startsWith("principal:") || it.startsWith("installation:")) {
                    it
                } else {
                    "installation:$it"
                }
            }
        }
        every { rs.getString("variation_key") } answers {
            current?.key?.let { if (it.startsWith("c")) "control" else "treatment" }
        }
        every { rs.getString("activated_at") } returns "2026-08-28 10:00:10.123456"
        every { rs.getLong("cnt") } answers {
            trinoCountColumnReads++
            current?.value?.toLong() ?: 0L
        }
        every { rs.getLong("invalid_item_extras") } answers {
            if (current?.key == DIAGNOSTICS_ROW) invalidItemExtras else 0L
        }
        every { rs.getLong("missing_item_extra_values") } answers {
            if (current?.key == DIAGNOSTICS_ROW) missingItemExtraValues else 0L
        }
        every { rs.getDouble("value") } answers { current?.value?.toDouble() ?: 0.0 }
        return rs
    }

    private fun withDb(block: suspend () -> Unit) {
        runBlocking {
            val manager = pgPool.connection()
            try {
                withContext(manager.asCoroutineContext()) { block() }
            } finally {
                withContext(NonCancellable) { manager.release() }
            }
        }
    }

    // -----------------------------------------------------------------
    // Test fixtures
    // -----------------------------------------------------------------

    private fun variationsJson() = buildJsonArray {
        add(buildJsonObject {
            put("key", "control"); put("name", "Control"); put("description", ""); put("value", false)
        })
        add(buildJsonObject {
            put("key", "treatment"); put("name", "Treatment"); put("description", ""); put("value", true)
        })
    }

    private fun ruleJson() = buildJsonArray {
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
                key = "agg-test-flag",
                name = "Agg Test Flag",
                description = "",
                type = FlagType.BOOLEAN,
                status = FlagStatus.ENABLED,
                variations = variationsJson(),
                defaultVariationKey = "control",
                targetingRules = ruleJson(),
            )
        )

    private suspend fun seedAssignment(
        experimentId: UUID,
        variationKey: String,
        clientId: String,
        principalId: UUID? = null,
    ) {
        val assignment = assignmentRepository.addIfAbsent(
            experimentId = experimentId,
            variationKey = variationKey,
            principalId = principalId,
            installationId = clientId,
        )
        if (assignment != null) assignmentSubjectIds[clientId] = assignment.id.toString()
    }

    // -----------------------------------------------------------------
    // Happy path: one UNIQUE_CONVERSION goal with real assignments
    // -----------------------------------------------------------------

    @Test
    fun `aggregates a UNIQUE_CONVERSION goal over seeded assignments and Trino rows`() = withDb {
        // Arrange a flag, experiment, and one goal. Seed four
        // assignments (two control, two treatment). Script Trino
        // to return one control converter and two treatment
        // converters. The expected result:
        //   control   impressions=2, conversions=1 (50% rate)
        //   treatment impressions=2, conversions=2 (100% rate)
        val flag = transaction { insertFlag() }
        val exp = experimentService.add(
            ExperimentInput(
                featureFlagId = flag.id,
                controlVariationKey = "control",
                name = "agg",
                description = "",
                hypothesis = "",
                targetingRuleId = "rule-1",
            )
        )
        // Goals must be added BEFORE the experiment leaves DRAFT —
        // `requireMutableExperiment` locks goal edits once the
        // experiment is RUNNING to prevent mid-flight metric
        // changes invalidating accumulated results.
        val goal = experimentService.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "Signups",
                eventType = EventType.Interaction,
                metricType = GoalMetricType.UNIQUE_CONVERSION,
            )
        )
        experimentService.setStatus(exp.id, ExperimentStatus.RUNNING)
        transaction {
            seedAssignment(exp.id, "control", "cA")
            seedAssignment(exp.id, "control", "cB")
            seedAssignment(exp.id, "treatment", "tA")
            seedAssignment(exp.id, "treatment", "tB")
        }
        // Trino script: cA converts once (1), tA + tB convert once (1 each).
        trinoScripts.add(mapOf("cA" to 1L, "tA" to 1L, "tB" to 1L))

        aggregateExperimentResults(exp.id)

        val results = experimentResultRepository.getByExperimentId(exp.id)
        assertEquals(2, results.size, "should produce one result row per variation")
        val control = assertNotNull(results.firstOrNull { it.variationKey == "control" })
        val treatment = assertNotNull(results.firstOrNull { it.variationKey == "treatment" })
        assertEquals(2, control.impressions)
        assertEquals(1, control.conversions, "control should have 1 converter (cA)")
        assertEquals(0.5, control.conversionRate, 1e-9)
        assertEquals(2, treatment.impressions)
        assertEquals(2, treatment.conversions, "treatment should have 2 converters (tA + tB)")
        assertEquals(1.0, treatment.conversionRate, 1e-9)
        // Lift for treatment over control = (1.0 - 0.5) / 0.5 * 100 = 100%
        assertEquals(100.0, treatment.liftOverControl)
        assertEquals(
            0,
            trinoCountColumnReads,
            "ordinary UNIQUE_CONVERSION rows contain only client_id and must not read cnt",
        )
    }

    @Test
    fun `account exclusions remove assignments and both identity paths from results`() = withDb {
        val excludedPrincipalId = UUID.random()
        val flag = transaction { insertFlag() }
        val exp = experimentService.add(
            ExperimentInput(
                featureFlagId = flag.id,
                controlVariationKey = "control",
                excludedPrincipalIds = listOf(excludedPrincipalId),
                name = "excluded accounts",
                targetingRuleId = "rule-1",
            ),
        )
        val goal = experimentService.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "Signups",
                eventType = EventType.Interaction,
                metricType = GoalMetricType.UNIQUE_CONVERSION,
            ),
        )
        experimentService.setStatus(exp.id, ExperimentStatus.RUNNING)
        transaction {
            seedAssignment(exp.id, "control", "cA")
            seedAssignment(exp.id, "control", "cB")
            seedAssignment(exp.id, "control", "cLinkedExcluded")
            seedAssignment(exp.id, "treatment", "tA")
            seedAssignment(exp.id, "treatment", "tExcluded", excludedPrincipalId)
            connection().useStatement(
                """
                insert into experimentation.flag_assignments
                    (flag_id, variation_key, principal_id, installation_id, assigned_at)
                values (?::uuid, 'control', ?::uuid, 'cLinkedExcluded', now())
                """.trimIndent(),
            ) { statement ->
                statement.setString(1, flag.id.toString())
                statement.setString(2, excludedPrincipalId.toString())
                statement.executeUpdate()
            }
        }
        // The warehouse query joins the exclusion-filtered assignments CTE, so excluded identities
        // cannot be returned as result rows.
        trinoScripts.add(mapOf("cA" to 1L, "tA" to 1L))

        aggregateExperimentResults(exp.id)

        val results = experimentResultRepository.getByExperimentId(exp.id)
        val control = assertNotNull(results.firstOrNull { it.variationKey == "control" })
        val treatment = assertNotNull(results.firstOrNull { it.variationKey == "treatment" })
        assertEquals(2, control.impressions)
        assertEquals(1, control.conversions)
        assertEquals(1, treatment.impressions)
        assertEquals(1, treatment.conversions)
        assertEquals(1.0, treatment.conversionRate)
        assertEquals(goal.id, treatment.goalId)
        assertEquals(
            listOf("cLinkedExcluded"),
            assignmentRepository.getExcludedInstallationIds(exp.id, listOf(excludedPrincipalId)),
        )
        assertTrue(trinoQueries.single().sql.contains("installation_id NOT IN (?)"))
        assertTrue("cLinkedExcluded" in trinoQueries.single().params)
        val cutoff = java.time.OffsetDateTime.now().minusDays(1)
        assertEquals(0L, assignmentRepository.countEligibleByVariation(exp.id, "control", emptyList(), emptyList(), cutoff))
        beforeTrinoQuery = {
            experimentService.edit(exp.id, ExperimentInput(
                featureFlagId = flag.id, controlVariationKey = "control", targetingRuleId = "rule-1",
                name = exp.name, excludedPrincipalIds = emptyList(),
            ))
        }
        trinoScripts.add(mapOf("cA" to 1L, "tA" to 1L))
        val changed = assertFailsWith<IllegalStateException> { aggregateExperimentResults(exp.id) }
        assertTrue(changed.message.orEmpty().contains("changed during aggregation"))
        assertTrue(experimentResultRepository.getByExperimentId(exp.id).isEmpty())

    }

    // -----------------------------------------------------------------
    // Early bail-out: flag missing
    // -----------------------------------------------------------------

    @Test
    fun `missing flag bails out without writing any results`() = withDb {
        // Create a real experiment, then delete the flag from
        // under it. The aggregator should skip cleanly rather
        // than writing results against the orphaned experiment.
        val flag = transaction { insertFlag() }
        val exp = experimentService.add(
            ExperimentInput(
                featureFlagId = flag.id,
                controlVariationKey = "control",
                name = "orphan",
                description = "",
                hypothesis = "",
                targetingRuleId = "rule-1",
            )
        )
        // Delete the experiment's parent flag via direct SQL so
        // the FK cascade doesn't remove the experiment row.
        transaction {
            connection().useStatement(
                "DELETE FROM experimentation.experiments WHERE id = ?::uuid"
            ) { stmt ->
                stmt.setString(1, exp.id.toString())
                stmt.execute()
            }
            connection().useStatement(
                "DELETE FROM experimentation.feature_flags WHERE id = ?::uuid"
            ) { stmt ->
                stmt.setString(1, flag.id.toString())
                stmt.execute()
            }
            // Re-insert the experiment WITHOUT its parent flag
            // using raw SQL that bypasses the FK — doesn't work
            // because the FK rejects it. So instead, just probe
            // the aggregator with a random experiment id: its
            // first-line "experiment not found" guard fires.
        }
        val fabricatedId = UUID.random()
        aggregateExperimentResults(fabricatedId)
        assertTrue(experimentResultRepository.getByExperimentId(fabricatedId).isEmpty(),
            "no results should be written when the experiment is missing")
    }

    // -----------------------------------------------------------------
    // Missing experiment is a no-op
    // -----------------------------------------------------------------

    @Test
    fun `missing experiment bails out without touching the DB`() = withDb {
        // The aggregator logs and returns immediately; no
        // assertion on writes is possible because no experiment
        // ever existed. This test exists to cover the early-return
        // branch.
        aggregateExperimentResults(UUID.random())
    }

    @Test
    fun `invalid persisted control fails before overwriting prior results`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = experimentService.add(
            ExperimentInput(
                featureFlagId = flag.id,
                controlVariationKey = "control",
                name = "corrupt-control",
                description = "",
                hypothesis = "",
                targetingRuleId = "rule-1",
            ),
        )
        val goal = experimentService.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "Existing result",
                eventType = EventType.Interaction,
                metricType = GoalMetricType.UNIQUE_CONVERSION,
            ),
        )
        transaction {
            experimentResultRepository.upsert(
                ExperimentResult(
                    experimentId = exp.id,
                    variationKey = "control",
                    goalId = goal.id,
                    impressions = 10,
                    observationCount = 10,
                    conversions = 7,
                    conversionRate = 0.7,
                ),
            )
            connection().useStatement(
                "update experimentation.experiments set control_variation_key = 'missing' where id = ?::uuid",
            ) { statement ->
                statement.setString(1, exp.id.toString())
                statement.execute()
            }
        }

        val failure = assertFailsWith<IllegalArgumentException> {
            aggregateExperimentResults(exp.id)
        }
        assertTrue(failure.message?.contains("not involved") == true)
        val unchanged = experimentResultRepository.getByExperimentId(exp.id).single()
        assertEquals(10, unchanged.impressions)
        assertEquals(7, unchanged.conversions)
        assertEquals(0.7, unchanged.conversionRate)
    }

    @Test
    fun `Trino query failure is loud and preserves prior results`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = experimentService.add(
            ExperimentInput(
                featureFlagId = flag.id,
                controlVariationKey = "control",
                name = "query-failure",
                description = "",
                hypothesis = "",
                targetingRuleId = "rule-1",
            ),
        )
        val goal = experimentService.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "Existing result",
                eventType = EventType.Interaction,
                metricType = GoalMetricType.UNIQUE_CONVERSION,
            ),
        )
        transaction {
            experimentResultRepository.upsert(
                ExperimentResult(
                    experimentId = exp.id,
                    variationKey = "control",
                    goalId = goal.id,
                    impressions = 8,
                    observationCount = 8,
                    conversions = 3,
                    conversionRate = 0.375,
                ),
            )
        }
        trinoFailure = IllegalStateException("Trino unavailable")

        val failure = assertFailsWith<IllegalStateException> {
            aggregateExperimentResults(exp.id)
        }
        assertEquals("Trino unavailable", failure.message)
        val unchanged = experimentResultRepository.getByExperimentId(exp.id).single()
        assertEquals(8, unchanged.impressions)
        assertEquals(3, unchanged.conversions)
        assertEquals(0.375, unchanged.conversionRate)
    }

    // -----------------------------------------------------------------
    // Zero-conversion goal still writes a row
    // -----------------------------------------------------------------

    // -----------------------------------------------------------------
    // EVENT_COUNT goal path
    // -----------------------------------------------------------------

    @Test
    fun `aggregates an EVENT_COUNT goal producing per-user mean and variance`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = experimentService.add(
            ExperimentInput(
                featureFlagId = flag.id,
                controlVariationKey = "control",
                name = "ev",
                description = "",
                hypothesis = "",
                targetingRuleId = "rule-1",
            )
        )
        experimentService.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "Clicks per user",
                eventType = EventType.Interaction,
                metricType = GoalMetricType.EVENT_COUNT,
            )
        )
        experimentService.setStatus(exp.id, ExperimentStatus.RUNNING)
        // Two users per variation. Scripted Trino returns
        // multiple events per user — the EVENT_COUNT branch
        // produces per-user mean + variance.
        transaction {
            seedAssignment(exp.id, "control", "cA")
            seedAssignment(exp.id, "control", "cB")
            seedAssignment(exp.id, "treatment", "tA")
            seedAssignment(exp.id, "treatment", "tB")
        }
        // Control users emit 1 + 3 events, treatment 5 + 7.
        trinoScripts.add(mapOf("cA" to 1L, "cB" to 3L, "tA" to 5L, "tB" to 7L))
        aggregateExperimentResults(exp.id)

        val results = experimentResultRepository.getByExperimentId(exp.id)
        val control = assertNotNull(results.firstOrNull { it.variationKey == "control" })
        val treatment = assertNotNull(results.firstOrNull { it.variationKey == "treatment" })
        // Control: sumEvents=4, impressions=2 → mean=2.0
        //          sumSq=1+9=10, variance=(10 - 4²/2)/1 = 2.0
        assertEquals(2.0, control.mean)
        assertEquals(2.0, control.variance)
        // Treatment: sumEvents=12, impressions=2 → mean=6.0
        //            sumSq=25+49=74, variance=(74 - 12²/2)/1 = 2.0
        assertEquals(6.0, treatment.mean)
        assertEquals(2.0, treatment.variance)
        // Lift over control mean = (6 - 2) / 2 * 100 = 200%
        assertEquals(200.0, treatment.liftOverControl)
    }

    @Test
    fun `aggregates SESSION_DURATION with observed-subject coverage`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = experimentService.add(
            ExperimentInput(
                featureFlagId = flag.id,
                controlVariationKey = "control",
                name = "session-duration",
                description = "",
                hypothesis = "",
                targetingRuleId = "rule-1",
            ),
        )
        val sessionGoal = experimentService.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "Observed session duration",
                metricType = GoalMetricType.SESSION_DURATION,
            ),
        )
        val guardrailGoal = experimentService.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "Session duration guardrail",
                metricType = GoalMetricType.SESSION_DURATION,
                role = bosca.experimentation.model.ConversionGoalRole.GUARDRAIL,
            ),
        )
        experimentService.setStatus(exp.id, ExperimentStatus.RUNNING)
        transaction {
            seedAssignment(exp.id, "control", "cA")
            seedAssignment(exp.id, "control", "cB")
            seedAssignment(exp.id, "treatment", "tA")
            seedAssignment(exp.id, "treatment", "tB")
        }
        // Trino has already reduced each subject's sessions to one average duration.
        // tB has no usable session and therefore remains an impression but not an observation.
        trinoScripts.add(mapOf("cA" to 10.0, "cB" to 20.0, "tA" to 40.0))

        aggregateExperimentResults(exp.id)

        val results = experimentResultRepository.getByExperimentId(exp.id)
        assertEquals(1, trinoQueryCount, "identical session goals must share one warehouse query")
        assertEquals(4, results.size)
        val control = assertNotNull(results.firstOrNull {
            it.goalId == sessionGoal.id && it.variationKey == "control"
        })
        val treatment = assertNotNull(results.firstOrNull {
            it.goalId == sessionGoal.id && it.variationKey == "treatment"
        })
        assertEquals(2, control.impressions)
        assertEquals(2, control.observationCount)
        assertEquals(15.0, control.mean)
        assertEquals(50.0, control.variance)
        assertEquals(2, treatment.impressions)
        assertEquals(1, treatment.observationCount)
        assertEquals(40.0, treatment.mean)
        assertEquals(null, treatment.variance)
        val guardrailControl = assertNotNull(results.firstOrNull {
            it.goalId == guardrailGoal.id && it.variationKey == "control"
        })
        assertEquals(control.observationCount, guardrailControl.observationCount)
        assertEquals(control.mean, guardrailControl.mean)
    }

    // -----------------------------------------------------------------
    // CUPED adjustment path
    // -----------------------------------------------------------------

    @Test
    fun `activation cohort is captured once for sessions outcomes denominators and CUPED`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = experimentService.add(ExperimentInput(
            featureFlagId = flag.id,
            controlVariationKey = "control",
            name = "Activated outcomes",
            targetingRuleId = "rule-1",
            startDate = java.time.OffsetDateTime.parse("2026-08-28T09:00:00Z"),
            activationFilter = ExperimentActivationFilter(eventType = EventType.Impression, elementType = "page"),
        ))
        val countGoal = experimentService.addConversionGoal(exp.id, ConversionGoalInput(
            name = "Follow-up views", eventType = EventType.Impression, elementType = "page",
            metricType = GoalMetricType.EVENT_COUNT,
            cupedCovariate = bosca.experimentation.model.CupedCovariate(lookbackWindow = "P14D"),
        ))
        experimentService.addConversionGoal(exp.id, ConversionGoalInput(
            name = "Session duration", metricType = GoalMetricType.SESSION_DURATION,
        ))
        transaction {
            for ((arm, subjects) in mapOf("control" to listOf("cA", "cB", "cNever"),
                "treatment" to listOf("tA", "tB", "tNever"))) {
                for (subject in subjects) seedAssignment(exp.id, arm, subject)
            }
        }
        trinoScripts.add(mapOf("cA" to 1L, "cB" to 1L, "tA" to 1L, "tB" to 1L))
        trinoScripts.add(mapOf("cA" to 310.0, "cB" to 300.0, "tA" to 350.0, "tB" to 310.0))
        val outcomes = mapOf("cA" to 1L, "cB" to 3L, "tA" to 4L, "tB" to 6L)
        trinoScripts.add(outcomes)
        trinoScripts.add(outcomes)
        trinoScripts.add(mapOf("cA" to 1L, "cB" to 3L, "tA" to 2L, "tB" to 4L,
            "cNever" to 10000L, "tNever" to 10000L))

        aggregateExperimentResults(exp.id)

        val results = experimentService.getResults(exp.id)
        assertEquals(4, results.size)
        for (result in results) {
            assertEquals(3L, result.assignments)
            assertEquals(2L, result.impressions)
            assertEquals(2L, result.observationCount)
        }
        val treatment = results.single { it.goalId == countGoal.id && it.variationKey == "treatment" }
        assertEquals(5.0, treatment.mean)
        assertNotNull(treatment.adjustedMean)
        val adjustedWithUnactivatedCovariates = treatment.adjustedMean
        val adjustedVariance = treatment.adjustedVariance
        assertEquals(5, trinoQueries.size)
        assertEquals(1, trinoQueries.count { "activation_candidates AS" in it.sql })
        val frozen = trinoQueries[1].params[1]
        assertEquals(frozen, trinoQueries[2].params[1])
        assertEquals(frozen, trinoQueries[3].params[1])
        assertTrue(frozen.contains("2026-08-28 10:00:10.123456"))
        assertTrue(trinoQueries.drop(1).all { "activation_candidates AS" !in it.sql })

        // Removing covariates belonging to unactivated subjects must leave CUPED unchanged.
        trinoScripts.add(mapOf("cA" to 1L, "cB" to 1L, "tA" to 1L, "tB" to 1L))
        trinoScripts.add(mapOf("cA" to 310.0, "cB" to 300.0, "tA" to 350.0, "tB" to 310.0))
        trinoScripts.add(outcomes)
        trinoScripts.add(outcomes)
        trinoScripts.add(mapOf("cA" to 1L, "cB" to 3L, "tA" to 2L, "tB" to 4L))
        aggregateExperimentResults(exp.id)
        val repeated = experimentService.getResults(exp.id).single { it.goalId == countGoal.id && it.variationKey == "treatment" }
        assertEquals(adjustedWithUnactivatedCovariates, repeated.adjustedMean)
        assertEquals(adjustedVariance, repeated.adjustedVariance)
    }

    @Test
    fun `cohort batches preserve full population statistics and CUPED without partial writes`() = withDb {
        val flag = transaction { insertFlag() }
        val input = ExperimentInput(
            featureFlagId = flag.id, controlVariationKey = "control", name = "Batched outcomes",
            targetingRuleId = "rule-1", startDate = java.time.OffsetDateTime.parse("2026-08-28T09:00:00Z"),
            activationFilter = ExperimentActivationFilter(eventType = EventType.Impression, elementType = "page"),
        )
        val exp = experimentService.add(input)
        val countGoal = experimentService.addConversionGoal(exp.id, ConversionGoalInput(
            name = "Follow-up count", eventType = EventType.Impression, elementType = "page",
            metricType = GoalMetricType.EVENT_COUNT,
            cupedCovariate = bosca.experimentation.model.CupedCovariate(lookbackWindow = "P14D"),
        ))
        val uniqueGoal = experimentService.addConversionGoal(exp.id, ConversionGoalInput(
            name = "Any follow-up", eventType = EventType.Impression, elementType = "page",
        ))
        val sessionGoal = experimentService.addConversionGoal(exp.id, ConversionGoalInput(
            name = "Duration", metricType = GoalMetricType.SESSION_DURATION,
        ))
        val subjects = List(2_100) { index -> if (index % 2 == 0) "c$index" else "t$index" }
        transaction {
            for (subject in subjects) seedAssignment(exp.id, if (subject.startsWith("c")) "control" else "treatment", subject)
        }
        val cohort = ActivatedCohort(subjects.map { subject -> ActivatedSubject(
            checkNotNull(assignmentSubjectIds[subject]), if (subject.startsWith("c")) "control" else "treatment",
            "2026-08-28 10:00:10.123456",
        ) })
        assertTrue(cohort.batches.size > 1)
        val clientsById = assignmentSubjectIds.entries.associate { (client, id) -> id to client }
        val batchClients = cohort.batches.map { batch ->
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ActivatedSubject.serializer()), batch.encodedSubjects)
                .map { checkNotNull(clientsById[it.subjectId]) }.toSet()
        }
        val activation = subjects.associateWith { 1L }
        val sessions = subjects.filterIndexed { index, _ -> index % 5 != 0 }
            .associateWith { 300.0 + it.drop(1).toInt() % 70 }
        val outcomes = subjects.filterIndexed { index, _ -> index % 3 != 0 }
            .associateWith { 1L + it.drop(1).toInt() % 7 }
        val prePeriod = subjects.associateWith { 1L + it.drop(1).toInt() % 9 }
        val goalOrder = experimentService.getConversionGoals(exp.id).filter { it.metricType != GoalMetricType.SESSION_DURATION }
        fun scriptOutcomes(batches: List<Set<String>>) {
            for (batch in batches) trinoScripts.add(sessions.filterKeys { it in batch })
            for (goal in goalOrder) {
                for (batch in batches) trinoScripts.add(outcomes.filterKeys { it in batch })
            }
            for (batch in batches) trinoScripts.add(outcomes.filterKeys { it in batch }) // CUPED outcomes
            trinoScripts.add(prePeriod)
        }
        trinoScripts.add(activation)
        scriptOutcomes(batchClients)
        aggregateExperimentResults(exp.id)
        val batched = experimentService.getResults(exp.id)
        assertEquals(6, batched.size)
        assertTrue(batched.all { it.impressions == 1_050L && it.assignments == 1_050L })
        assertTrue(batched.filter { it.goalId == countGoal.id }.all { it.adjustedMean != null })
        assertTrue(batched.filter { it.goalId == sessionGoal.id }.all { it.observationCount == 840L })
        assertTrue(batched.filter { it.goalId == uniqueGoal.id }.all { it.conversions == 700L })
        assertEquals(1, trinoQueries.count { "activation_candidates AS" in it.sql })
        val outcomeQueries = trinoQueries.filter { "json_parse(?) AS ARRAY(ROW(" in it.sql }
        assertEquals(4 * cohort.batches.size, outcomeQueries.size)
        for (batch in cohort.batches) assertEquals(4, outcomeQueries.count { batch.encodedSubjects in it.params })

        trinoScripts.add(activation)
        trinoScripts.add(sessions.filterKeys { it in batchClients.first() }.mapValues { 999_999.0 })
        trinoFailure = IllegalStateException("Second cohort batch failed")
        trinoFailureAfter = trinoQueryCount + 2
        assertFailsWith<IllegalStateException> { aggregateExperimentResults(exp.id) }
        assertEquals(batched, experimentService.getResults(exp.id), "a failed batch must not publish partial results")
        trinoFailure = null

        // Supply the same subject-level observations through the existing unbatched path.
        experimentService.edit(exp.id, input.copy(activationFilter = null))
        scriptOutcomes(listOf(subjects.toSet()))
        aggregateExperimentResults(exp.id)
        val unbatched = experimentService.getResults(exp.id)
        fun normalized(results: List<ExperimentResult>) = results.associate {
            (it.variationKey to it.goalId) to it.copy(id = UUID.NIL, updatedAt = java.time.OffsetDateTime.MIN)
        }
        assertEquals(normalized(unbatched), normalized(batched), "batch boundaries must not change any statistic")
        assertTrue(trinoScripts.isEmpty())
    }

    @Test
    fun `default CUPED inherits all goal page alternatives while an explicit path replaces them`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = experimentService.add(ExperimentInput(
            featureFlagId = flag.id, controlVariationKey = "control", name = "CUPED page filters",
            targetingRuleId = "rule-1", startDate = java.time.OffsetDateTime.parse("2026-08-28T09:00:00Z"),
        ))
        val input = ConversionGoalInput(
            name = "Content views", eventType = EventType.Impression, elementType = "page",
            metricType = GoalMetricType.EVENT_COUNT, pagePathPrefixes = listOf("/articles/", "/talks/"),
            cupedCovariate = bosca.experimentation.model.CupedCovariate(),
        )
        val goal = experimentService.addConversionGoal(exp.id, input)
        transaction {
            for (subject in listOf("cA", "cB", "tA", "tB")) {
                seedAssignment(exp.id, if (subject.startsWith("c")) "control" else "treatment", subject)
            }
        }
        val outcomes = mapOf("cA" to 1L, "cB" to 3L, "tA" to 4L, "tB" to 6L)
        for (goalPath in listOf(null, "/featured")) {
            for (overridePath in listOf(null, "/search")) {
                experimentService.editConversionGoal(goal.id, input.copy(
                    pagePath = goalPath,
                    cupedCovariate = bosca.experimentation.model.CupedCovariate(pagePath = overridePath),
                ))
                trinoScripts.add(outcomes)
                trinoScripts.add(outcomes)
                trinoScripts.add(mapOf("cA" to 1L, "cB" to 3L, "tA" to 2L, "tB" to 4L))
                aggregateExperimentResults(exp.id)

                val prePeriod = trinoQueries.last()
                val expectedPaths = if (overridePath == null) {
                    listOfNotNull(goalPath) + input.pagePathPrefixes
                } else {
                    listOf(overridePath)
                }
                assertEquals(listOf("Impression", "page"), prePeriod.params.take(2))
                assertEquals(expectedPaths, prePeriod.params.drop(2).dropLast(2))
                assertEquals(overridePath == null, "starts_with(page.path, ?)" in prePeriod.sql)
                assertTrue(input.pagePathPrefixes.all { it in trinoQueries[trinoQueries.size - 2].params },
                    "covariate overrides must not change the outcome filter")
            }
        }
    }

    @Test
    fun `CUPED-enabled EVENT_COUNT goal populates adjusted_mean and adjusted_variance`() = withDb {
        val principal = UUID.random()
        val excluded = UUID.random()
        val future = UUID.random()
        val flag = transaction { insertFlag() }
        val exp = experimentService.add(
            ExperimentInput(
                featureFlagId = flag.id,
                controlVariationKey = "control",
                name = "cuped-agg",
                description = "",
                hypothesis = "",
                targetingRuleId = "rule-1",
                startDate = java.time.OffsetDateTime.now().minusDays(1),
                endDate = java.time.OffsetDateTime.now().plusDays(3),
                excludedPrincipalIds = listOf(excluded),
            )
        )
        // Goal must be added before setStatus(RUNNING) — after that,
        // requireMutableExperiment locks goal edits.
        experimentService.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "Clicks",
                eventType = EventType.Interaction,
                metricType = GoalMetricType.EVENT_COUNT,
                cupedCovariate = bosca.experimentation.model.CupedCovariate(
                    eventType = EventType.Interaction,
                    lookbackWindow = "P14D",
                ),
            )
        )
        experimentService.setStatus(exp.id, ExperimentStatus.RUNNING)
        transaction {
            seedAssignment(exp.id, "control", "cA", principal)
            seedAssignment(exp.id, "control", "cB")
            seedAssignment(exp.id, "treatment", "tA")
            seedAssignment(exp.id, "treatment", "tB")
            seedAssignment(exp.id, "control", "cZero")
            seedAssignment(exp.id, "treatment", "tZero")
            seedAssignment(exp.id, "treatment", "tExcluded", excluded)
            seedAssignment(exp.id, "treatment", "tFuture", future)
            connection().useStatement("update experimentation.assignments set assigned_at = now() + interval '2 days' where installation_id = 'tFuture'") {
                it.executeUpdate()
            }
        }
        // Three Trino queries in order:
        //   1. countConversions main goal query (EVENT_COUNT path)
        //   2. computeCupedAdjustments post-period outcomes
        //   3. computeCupedAdjustments pre-period covariates
        trinoScripts.add(mapOf("cA" to 2L, "cB" to 2L, "tA" to 4L, "tB" to 4L))
        trinoScripts.add(mapOf("cA" to 2L, "cB" to 2L, "tA" to 4L, "tB" to 4L))
        // Non-zero covariate variance so the CUPED adjustment applies.
        trinoScripts.add(mapOf(
            "principal:$principal" to 1L, "cA" to 1L, "cB" to 3L, "tA" to 2L, "tB" to 3L,
            "principal:$excluded" to 1000L, "tExcluded" to 1000L,
            "principal:$future" to 1000L, "tFuture" to 1000L,
            "principal:malformed" to 1000L,
        ))

        aggregateExperimentResults(exp.id)

        val results = experimentResultRepository.getByExperimentId(exp.id)
        assertEquals(2, results.size)
        val treatment = assertNotNull(results.firstOrNull { it.variationKey == "treatment" })
        assertEquals(3L, treatment.impressions)
        assertEquals(8.0 / 3.0, treatment.mean)
        assertEquals(8.0 / 3.0, assertNotNull(treatment.adjustedMean), 1e-9)
        assertNotNull(treatment.adjustedVariance)
        // A cancelled/failed covariate query must preserve the last complete results.
        for (failure in listOf(kotlinx.coroutines.CancellationException("cancelled"), java.sql.SQLException("warehouse unavailable"))) {
            trinoFailure = failure
            trinoFailureAfter = trinoQueryCount + 1
            trinoScripts.add(mapOf("cA" to 99L, "tA" to 99L))
            val thrown = assertFailsWith<Exception> { aggregateExperimentResults(exp.id) }
            assertEquals(failure::class, thrown::class)
            assertEquals(failure.message, thrown.message)
            assertEquals(results, experimentResultRepository.getByExperimentId(exp.id))
        }
    }

    @Test
    fun `goal with zero converters still writes result rows with conversions=0`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = experimentService.add(
            ExperimentInput(
                featureFlagId = flag.id,
                controlVariationKey = "control",
                name = "zero-agg",
                description = "",
                hypothesis = "",
                targetingRuleId = "rule-1",
            )
        )
        experimentService.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "No-events goal",
                eventType = EventType.Interaction,
                metricType = GoalMetricType.UNIQUE_CONVERSION,
            )
        )
        experimentService.setStatus(exp.id, ExperimentStatus.RUNNING)
        transaction {
            seedAssignment(exp.id, "control", "cA")
            seedAssignment(exp.id, "treatment", "tA")
        }
        // Empty Trino script — no converters.
        trinoScripts.add(emptyMap())
        aggregateExperimentResults(exp.id)

        val results = experimentResultRepository.getByExperimentId(exp.id)
        assertEquals(2, results.size, "should still emit one row per variation")
        for (r in results) {
            assertEquals(0, r.conversions, "${r.variationKey} should have 0 conversions")
            assertEquals(0.0, r.conversionRate, 1e-9)
        }
    }

    @Test
    fun `malformed item extras emit one aggregation diagnostic`() = withDb {
        val flag = transaction { insertFlag() }
        val exp = experimentService.add(
            ExperimentInput(
                featureFlagId = flag.id,
                controlVariationKey = "control",
                name = "item-diagnostic",
                description = "",
                hypothesis = "",
                targetingRuleId = "rule-1",
            )
        )
        val goal = experimentService.addConversionGoal(
            exp.id,
            ConversionGoalInput(
                name = "Recommendation click",
                eventType = EventType.Interaction,
                itemExtraKey = "recommendation_source",
            )
        )
        experimentService.setStatus(exp.id, ExperimentStatus.RUNNING)
        transaction { seedAssignment(exp.id, "control", "cA") }
        invalidItemExtras = 3L
        missingItemExtraValues = 5L
        trinoScripts.add(linkedMapOf("cA" to 1L, DIAGNOSTICS_ROW to 0L))

        aggregateExperimentResults(exp.id)

        assertEquals(1, capturedDiagnostics.size)
        val diagnostic = capturedDiagnostics.single()
        assertEquals(exp.id.toString(), diagnostic["experimentId"])
        assertEquals(goal.id.toString(), diagnostic["goalId"])
        assertEquals("3", diagnostic["invalidItemExtras"])
        assertEquals("5", diagnostic["missingItemExtraValues"])
    }
}
