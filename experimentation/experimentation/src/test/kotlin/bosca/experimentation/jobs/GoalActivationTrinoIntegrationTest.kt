@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.experimentation.jobs

import bosca.analytics.model.EventType
import bosca.db.ConnectionPool
import bosca.db.ConnectionManager
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.observability.ErrorCapture
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.sql.PreparedStatement
import bosca.experimentation.configuration.ExperimentationConfig
import bosca.experimentation.model.*
import bosca.serialization.UUID
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.images.builder.Transferable
import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.Properties
import kotlin.test.*

class GoalActivationTrinoIntegrationTest {
    @Test
    fun `frozen per-variation cohorts enforce scope ordering deduplication and exact destinations`() {
        assumeTrue("Docker is required for Trino integration", DockerClientFactory.instance().isDockerAvailable)
        val network = Network.newNetwork()
        val postgres = PostgreSQLContainer("pgvector/pgvector:pg17").withNetwork(network).withNetworkAliases("goal-postgres")
        val trino = GenericContainer("trinodb/trino:479").withNetwork(network).withExposedPorts(8080)
            .withCopyToContainer(Transferable.of("connector.name=memory\n".toByteArray()), "/etc/trino/catalog/memory.properties")
        try {
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { pg ->
                pg.createStatement().use { it.execute("CREATE TABLE metadata(id UUID PRIMARY KEY, attributes JSONB)") }
                pg.createStatement().use { it.execute("INSERT INTO metadata VALUES ('$target', '{\"type\":\"Article\"}'), ('$unsupported', '{\"type\":\"Unknown\"}')") }
            }
            trino.withCopyToContainer(Transferable.of("""
                connector.name=postgresql
                connection-url=jdbc:postgresql://goal-postgres:5432/${postgres.databaseName}
                connection-user=${postgres.username}
                connection-password=${postgres.password}
            """.trimIndent().toByteArray()), "/etc/trino/catalog/bosca.properties")
            trino.start()
            DriverManager.getConnection("jdbc:trino://${trino.host}:${trino.getMappedPort(8080)}", Properties().apply { setProperty("user", "goal-test") }).use { c ->
                val deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos()
                while (true) {
                    try { c.createStatement().use { it.execute("SELECT 1") }; break }
                    catch (e: java.sql.SQLException) {
                        if (e.message?.contains("still initializing") != true || System.nanoTime() >= deadline) throw e
                        Thread.sleep(200)
                    }
                }
                c.createStatement().use { it.execute("CREATE SCHEMA IF NOT EXISTS memory.default") }
                c.createStatement().use { it.execute("""
                    CREATE TABLE memory.default.assignments(id UUID, experiment_id UUID, principal_id UUID,
                      installation_id VARCHAR, variation_key VARCHAR, assigned_at TIMESTAMP WITH TIME ZONE)
                """.trimIndent()) }
                c.createStatement().use { it.execute("""
                    CREATE TABLE memory.default.events(id UUID, client_id VARCHAR, created TIMESTAMP, type VARCHAR,
                      context ROW(user_id VARCHAR, device ROW(installation_id VARCHAR), session_id VARCHAR, app_id VARCHAR),
                      element ROW(id VARCHAR, type VARCHAR, content ARRAY(ROW(id VARCHAR)), extras VARCHAR), page ROW(path VARCHAR))
                """.trimIndent()) }
                val arms = linkedMapOf("good" to "Mid", "never" to "Mid", "wrong" to "Mid", "both" to "Mid", "bottom" to "Bottom", "none" to "None", "foreign" to "Mid", "excluded" to "Mid", "linked" to "Mid")
                val ids = arms.mapValues { UUID.random().toString() }
                arms.forEach { (device, arm) ->
                    val principal = if (device == "excluded") "CAST('$excluded' AS UUID)" else "CAST(NULL AS UUID)"
                    c.createStatement().use { it.execute("INSERT INTO memory.default.assignments VALUES (UUID '${ids.getValue(device)}', UUID '$experiment', $principal, '$device', '$arm', TIMESTAMP '2026-09-01 10:00:00 UTC')") }
                    event(c, device, "10:01:00", "Impression", "/articles/start", "page", path = "/articles/start")
                }
                event(c, "good", "09:59:00", "Impression", "article-inline-rec-before")
                event(c, "good", "10:01:00", "Impression", "article-inline-rec-same-time")
                for (device in listOf("good", "both", "excluded", "linked")) {
                    event(c, device, "10:02:00", "Impression", "article-inline-rec-target", key = "$device-exposure")
                }
                event(c, "good", "10:02:00", "Impression", "article-inline-rec-target", key = "good-exposure")
                for (device in listOf("wrong", "both", "bottom", "none")) event(c, device, "10:02:01", "Impression", "article-footer-rec-target")
                event(c, "foreign", "10:02:00", "Impression", "article-inline-rec-target", app = "studio")
                event(c, "never", "10:02:00", "Impression", "article-inline-heading")
                event(c, "never", "10:02:00", "Impression", "article-inline-rec-empty", content = null)
                event(c, "never", "10:02:00", "Impression", "article-inline-rec-invalid", content = "invalid-uuid")
                event(c, "never", "10:02:00", "Impression", "article-inline-rec-target", visible = 500)
                event(c, "never", "10:02:00", "Impression", "article-inline-rec-target", session = "other")
                event(c, "never", "10:20:00", "Impression", "article-inline-rec-target")
                event(c, "good", "10:02:00", "Interaction", "article-inline-rec-target")
                event(c, "good", "10:03:00", "Interaction", "article-inline-rec-target", key = "click")
                event(c, "good", "10:03:00", "Interaction", "article-inline-rec-target", key = "click")
                event(c, "good", "10:03:10", "Interaction", "article-inline-heading")
                event(c, "good", "10:03:11", "Interaction", "article-inline-rec-empty", content = null)
                event(c, "good", "10:03:12", "Interaction", "article-inline-rec-invalid", content = "invalid-uuid")
                event(c, "good", "10:04:00", "Impression", "/articles/target", "page", path = "/articles/target/")
                event(c, "both", "10:03:00", "Interaction", "article-inline-rec-target")
                event(c, "both", "10:04:00", "Impression", "/articles/other", "page", path = "/articles/other")
                event(c, "both", "10:04:01", "Impression", "/articles/target", "page", path = "/articles/target", session = "different")
                event(c, "both", "10:04:02", "Impression", "/articles/target", "page", path = "/articles/target", app = "studio")
                event(c, "both", "10:09:00", "Impression", "/articles/target", "page", path = "/articles/target")
                event(c, "good", "10:05:00", "Interaction", "article-inline-rec-missing", content = UUID.random().toString())
                event(c, "good", "10:05:01", "Interaction", "article-inline-rec-unsupported", content = unsupported)
                event(c, "good", "10:05:02", "Interaction", "article-inline-rec-/", content = target)
                event(c, "good", "10:05:03", "Interaction", "article-inline-rec-target", session = null)
                event(c, "good", "10:35:00", "Interaction", "article-inline-rec-late")
                val eligibility = buildActivatedCohortQuery(config.safeEventsTable, config.safeAssignmentsTable, experiment,
                    ExperimentActivationFilter(EventType.Impression, "page", pagePath = "/articles/start", applicationIds = listOf("site")),
                    start, cutoff, listOf(excluded), listOf("linked"), captureContext = true)
                val eligible = rows(c, eligibility).map { ActivatedSubject(it.getValue("client_id"), it.getValue("variation_key"), it.getValue("activated_at"), it["app_id"], it["session_id"]) }
                assertEquals(7, eligible.size)
                val cohort = ActivatedCohort(eligible).batches.single()
                val inline = ExperimentActivationFilter(eventType = EventType.Impression, elementIdContains = "article-inline-rec-", recommendationCard = true)
                val activation = GoalActivation(variationRules = listOf(GoalActivationRule("Mid", GoalActivationMode.EVENT, inline), GoalActivationRule("None", GoalActivationMode.NOT_APPLICABLE)), sameSession = true, maxDelaySeconds = 300)
                val matches = rows(c, buildGoalActivationQuery(config.safeEventsTable, config.safeAssignmentsTable, experiment, inline, activation, cohort, start, cutoff, listOf(excluded), listOf("linked")))
                val active = matches.filter { it["activated_at"] != null }
                assertEquals(setOf(ids.getValue("good"), ids.getValue("both")), active.map { it.getValue("subject_id") }.toSet())
                assertEquals("1", active.first { it["subject_id"] == ids["good"] }["event_count"])
                assertEquals("1", active.first { it["subject_id"] == ids["good"] }["duplicates"])
                assertEquals("1", matches.first { it["subject_id"] == ids["foreign"] }["outside_application"])
                val activated = ActivatedCohort(active.map { ActivatedSubject(it.getValue("subject_id"), it.getValue("variation_key"), it.getValue("activated_at"), it["app_id"], it["session_id"]) }).batches.single()
                val goal = ConversionGoal(experimentId = experiment, name = "Cards", eventType = EventType.Interaction,
                    outcomeScope = GoalOutcomeScope(recommendationCard = true, attributeRecommendationDestination = true))
                val conversions = rows(c, checkNotNull(buildConversionCountQuery(config.safeEventsTable, config.safeAssignmentsTable, experiment, goal, start, cutoff, listOf(excluded), listOf("linked"), activated)))
                assertEquals(2, conversions.size)
                val destinations = rows(c, buildDestinationAttributionQuery(config, experiment, goal, activated, start, cutoff, listOf(excluded), listOf("linked"))).single()
                assertEquals("2", destinations["engaged_subjects"])
                assertEquals("1", destinations["attributed_subjects"])
                assertEquals("1", destinations["attributed_interactions"])
                assertEquals("6", destinations["interactions"], "Cards without a target do not count as engagement")
                for (diagnostic in listOf("unresolved_targets", "unsupported_types", "missing_slugs", "missing_sessions", "unmatched_destinations")) assertEquals("1", destinations[diagnostic], diagnostic)
                val postSession = rows(c, buildSessionDurationQuery(config.safeEventsTable, config.safeAssignmentsTable, experiment, start, cutoff,
                    listOf(excluded), listOf("linked"), activated, GoalOutcomeScope(), strictlyAfterActivation = true))
                assertEquals(2, postSession.size)
                assertTrue(postSession.all { it.getValue("value").toDouble() >= 300 })
                withTrino(c) {
                    val variations = listOf("Mid", "Bottom", "None", "Unset").map { Variation(key = it, name = it, value = JsonPrimitive(it)) }
                    val bottomFilter = inline.copy(elementIdContains = "article-footer-rec-")
                    val definition = activation.copy(variationRules = activation.variationRules + GoalActivationRule("Bottom", GoalActivationMode.EVENT, bottomFilter))
                    val configuredGoal = goal.copy(activation = definition)
                    val frozen = ActivatedCohort(eligible)
                    val counts = eligible.groupingBy { it.variationKey }.eachCount().mapValues { it.value.toLong() }
                    val overall = countConversions(experiment, variations, listOf(configuredGoal), config, start, cutoff,
                        setOf(excluded), setOf("linked"), frozen, ErrorCapture { _, _, _ -> })
                    val summaries = aggregateGoalPopulationAnalysis(experiment, configuredGoal, variations, config, start, cutoff,
                        frozen, counts, overall, setOf(excluded), setOf("linked"), ErrorCapture { _, _, _ -> })
                    val mid = summaries.getValue("Mid")
                    assertEquals(5, mid.overall.subjects)
                    assertEquals(2, mid.activated?.subjects)
                    assertEquals(3, mid.notActivated?.subjects)
                    assertEquals(2, mid.activated?.conversions)
                    assertEquals(0, mid.notActivated?.conversions)
                    assertEquals(PlacementIntegrity(1, 1, 1, 2), mid.placement)
                    assertEquals(2, mid.exposureEvents)
                    assertEquals(1.0, mid.exposureIntensity?.p99)
                    assertEquals(.4, mid.reach)
                    assertEquals(1, mid.attribution?.attributedSubjects)
                    assertEquals(GoalDiagnostics(1, 1, 1, 1, 0), mid.diagnostics)
                    assertEquals(0, summaries.getValue("Bottom").attribution?.engagedSubjects)
                    assertTrue(summaries.getValue("None").notApplicable)
                    assertNull(summaries.getValue("None").activated)
                    assertEquals(PlacementIntegrity(0, 1, 0, 0), summaries.getValue("None").placement)
                    assertFalse(summaries.getValue("Unset").activationConfigured)
                    assertFalse(summaries.getValue("Unset").notApplicable)
                    assertNull(summaries.getValue("Unset").reach)
                    assertTrue(summaries.values.all { it.cutoff == cutoff.toString() })
                    val empty = aggregateGoalPopulationAnalysis(experiment, configuredGoal, variations, config, start, cutoff,
                        ActivatedCohort(emptyList()), emptyMap(), emptyMap(), emptySet(), emptySet(), ErrorCapture { _, _, _ -> })
                    assertEquals(0, empty.getValue("Mid").activated?.subjects)
                    assertNull(empty.getValue("Mid").reach)
                    assertNull(empty.getValue("Mid").exposureIntensity)
                    val ordinary = aggregateGoalPopulationAnalysis(experiment, goal.copy(outcomeScope = null), variations, config, start, cutoff,
                        null, counts, overall, emptySet(), emptySet(), ErrorCapture { _, _, _ -> })
                    assertNull(ordinary.getValue("Mid").placement)
                    assertNull(ordinary.getValue("Mid").activated)
                    val assignmentCohort = queryAssignmentCohort(config, experiment, cutoff, setOf(excluded), setOf("linked"))
                    assertEquals(7, assignmentCohort.subjects.size)
                    assertTrue(assignmentCohort.subjects.all { it.activatedAt.startsWith("2026-09-01 10:00:00") })
                    val assignmentMatches = rows(c, buildGoalActivationQuery(config.safeEventsTable,
                        config.safeAssignmentsTable, experiment, inline.copy(applicationIds = listOf("site")),
                        GoalActivation(), assignmentCohort.batches.single(), start, cutoff,
                        setOf(excluded), setOf("linked")))
                    assertTrue(assignmentMatches.any { it["subject_id"] == ids["good"] && it["activated_at"] != null })
                    assertEquals("1", assignmentMatches.first { it["subject_id"] == ids["foreign"] }["outside_application"])
                }
            }
        } finally { trino.stop(); postgres.stop(); network.close() }
    }

    /** Exercise the ordinary DI/JDBC query path against the live Trino connection. */
    private fun withTrino(connection: Connection, block: suspend () -> Unit) = runBlocking {
        val pool = mockk<ConnectionPool>()
        val manager = mockk<ConnectionManager>()
        every { pool.connection() } returns manager
        coEvery { manager.release() } returns Unit
        coEvery { manager.useStatement<Any>(any(), any()) } coAnswers {
            connection.prepareStatement(firstArg()).use { statement -> secondArg<suspend (PreparedStatement) -> Any>()(statement) }
        }
        ProviderRegistry.clear()
        provides<ConnectionPool>(name = "trino-readonly", singleton = true) { pool }
        try { block() } finally { ProviderRegistry.clear() }
    }

    private fun rows(c: Connection, query: PerUserQuery): List<Map<String, String>> = c.prepareStatement(query.sql).use { statement ->
        query.params.forEachIndexed { i, value -> statement.setString(i + 1, value) }
        statement.executeQuery().use { rs -> buildList {
            while (rs.next()) add(buildMap { for (i in 1..rs.metaData.columnCount) rs.getString(i)?.let { put(rs.metaData.getColumnLabel(i), it) } })
        } }
    }

    private fun event(c: Connection, device: String, time: String, type: String, element: String, elementType: String = "card",
        path: String = "/articles/start", app: String = "site", session: String? = "session", content: String? = target,
        visible: Int = 1000, key: String = UUID.random().toString()) {
        val sid = session?.let { "'$it'" } ?: "CAST(NULL AS VARCHAR)"
        val references = content?.let { "ARRAY[ROW('$it')]" } ?: "CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR)))"
        c.createStatement().use { it.execute("""
            INSERT INTO memory.default.events VALUES (uuid(), '$key', TIMESTAMP '2026-09-01 $time', '$type',
              ROW(CAST(NULL AS VARCHAR), ROW('$device'), $sid, '$app'),
              ROW('$element', '$elementType', $references, '{"visibility_threshold":"0.5","visible_ms":"$visible"}'), ROW('$path'))
        """.trimIndent()) }
    }

    companion object {
        private val experiment = UUID.random()
        private val excluded = UUID.random()
        private val target = UUID.random().toString()
        private val unsupported = UUID.random().toString()
        private val start = OffsetDateTime.parse("2026-09-01T10:00:00Z")
        private val cutoff = OffsetDateTime.parse("2026-09-01T10:30:00Z")
        private val config = ExperimentationConfig(eventsTable = "memory.default.events", assignmentsTable = "memory.default.assignments")
    }
}
