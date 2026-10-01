package bosca.experimentation.jobs

import bosca.analytics.model.EventType
import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.ExperimentActivationFilter
import bosca.experimentation.model.GoalMetricType
import bosca.serialization.UUID
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.images.builder.Transferable
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

/** Real Trino/PostgreSQL proof for experiment-bounded session attribution. */
class SessionDurationTrinoIntegrationTest {
    @Test
    fun `session query merges assignment identities and splits stale session ids`() {
        assumeTrue("Docker not available -- skipping Trino session integration test", isDockerAvailable())
        val network = Network.newNetwork()
        val postgres = PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("session_duration_test")
            .withUsername("session_test")
            .withPassword("session_test")
            .withNetwork(network)
            .withNetworkAliases("session-postgres")
        var trino: GenericContainer<*>? = null
        try {
            postgres.start()
            createAssignments(postgres.jdbcUrl, postgres.username, postgres.password)
            trino = GenericContainer("trinodb/trino:479")
                .withNetwork(network)
                .withExposedPorts(8080)
                .withCopyToContainer(
                    Transferable.of(
                        """
                        connector.name=postgresql
                        connection-url=jdbc:postgresql://session-postgres:5432/session_duration_test
                        connection-user=session_test
                        connection-password=session_test
                        """.trimIndent().toByteArray(),
                    ),
                    "/etc/trino/catalog/bosca.properties",
                )
                .withCopyToContainer(
                    Transferable.of("connector.name=memory\n".toByteArray()),
                    "/etc/trino/catalog/memory.properties",
                )
            trino.start()

            val connectionProperties = Properties().apply { setProperty("user", "session-test") }
            DriverManager.getConnection(
                "jdbc:trino://${trino.host}:${trino.getMappedPort(8080)}",
                connectionProperties,
            ).use { connection ->
                waitForTrino(connection)
                connection.createStatement().use { statement ->
                    statement.execute("CREATE SCHEMA IF NOT EXISTS memory.default")
                    statement.execute(
                        """
                        CREATE TABLE memory.default.events (
                            id UUID,
                            client_id VARCHAR,
                            created TIMESTAMP,
                            type VARCHAR,
                            context ROW(
                                user_id VARCHAR,
                                device ROW(installation_id VARCHAR),
                                session_id VARCHAR
                            ),
                            element ROW(id VARCHAR, type VARCHAR, content ARRAY(ROW(id VARCHAR)), extras VARCHAR)
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO memory.default.events (id, client_id, created, type, context, element)
                        SELECT uuid(), CAST(NULL AS VARCHAR), created, type, context, element
                        FROM (VALUES
                            (TIMESTAMP '2026-08-28 10:00:00', 'Session', ROW('11111111-1111-1111-1111-111111111111', ROW(CAST(NULL AS VARCHAR)), 'control-session'), ROW('page', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR))),
                            (TIMESTAMP '2026-08-28 10:00:05', 'Session', ROW('11111111-1111-1111-1111-111111111111', ROW(CAST(NULL AS VARCHAR)), 'control-session'), ROW('page', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR))),
                            (TIMESTAMP '2026-08-28 10:00:10', 'Session', ROW(CAST(NULL AS VARCHAR), ROW('control-installation'), 'control-session'), ROW('page', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR))),
                            (TIMESTAMP '2026-08-28 10:00:15', 'Session', ROW('11111111-1111-1111-1111-111111111111', ROW(CAST(NULL AS VARCHAR)), 'control-session'), ROW('page', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR))),
                            (TIMESTAMP '2026-08-28 10:30:00', 'Session', ROW('11111111-1111-1111-1111-111111111111', ROW(CAST(NULL AS VARCHAR)), 'control-session'), ROW('page', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR))),
                            (TIMESTAMP '2026-08-28 10:01:00', 'Session', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), 'treatment-session-1'), ROW('page', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR))),
                            (TIMESTAMP '2026-08-28 10:01:05', 'Session', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), 'treatment-session-1'), ROW('page', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR))),
                            (TIMESTAMP '2026-08-28 10:01:17', 'Session', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), 'treatment-session-1'), ROW('page', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR))),
                            (TIMESTAMP '2026-08-28 10:01:30', 'Interaction', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), 'treatment-session-1'), ROW('scroll_depth', 'scroll_depth', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), '{"depth_percent":"75"}')),
                            (TIMESTAMP '2026-08-28 10:02:00', 'Session', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), 'treatment-session-2'), ROW('page', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR))),
                            (TIMESTAMP '2026-08-28 10:02:00', 'Interaction', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), 'treatment-session-2'), ROW('scroll_depth', 'scroll_depth', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), '{"depth_percent":"25"}')),
                            (TIMESTAMP '2026-08-28 10:05:00', 'Assignment', ROW('11111111-1111-1111-1111-111111111111', ROW(CAST(NULL AS VARCHAR)), 'server-assignment-session'), ROW('feature_flag', 'feature_flag', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR))),
                            (TIMESTAMP '2026-08-28 10:03:00', 'Session', ROW(CAST(NULL AS VARCHAR), ROW('unassigned-installation'), 'unrelated-session'), ROW('page', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR))),
                            (TIMESTAMP '2026-08-28 10:13:00', 'Session', ROW(CAST(NULL AS VARCHAR), ROW('unassigned-installation'), 'unrelated-session'), ROW('page', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR))),
                            (TIMESTAMP '2026-08-28 10:00:30', 'Interaction', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), CAST(NULL AS VARCHAR)), ROW('metadata', 'metadata', ARRAY[ROW('content-1')], '{"recommendation_source":" premium "}')),
                            (TIMESTAMP '2026-08-28 10:20:00', 'Interaction', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), CAST(NULL AS VARCHAR)), ROW('metadata', 'metadata', ARRAY[ROW('content-1')], '{"recommendation_source":" premium "}')),
                            (TIMESTAMP '2026-08-28 10:20:01', 'Interaction', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), CAST(NULL AS VARCHAR)), ROW('metadata', 'metadata', ARRAY[ROW('content-1')], '{"recommendation_source":""}')),
                            (TIMESTAMP '2026-08-28 10:20:02', 'Interaction', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), CAST(NULL AS VARCHAR)), ROW('metadata', 'metadata', ARRAY[ROW('content-1')], '{')),
                            (TIMESTAMP '2026-08-28 10:20:03', 'Interaction', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), CAST(NULL AS VARCHAR)), ROW('metadata', 'metadata', ARRAY[ROW('content-1')], '"recommendations"')),
                            (TIMESTAMP '2026-08-28 10:20:04', 'Interaction', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), CAST(NULL AS VARCHAR)), ROW('metadata', 'metadata', ARRAY[ROW('content-1')], '[]')),
                            (TIMESTAMP '2026-08-28 10:20:05', 'Interaction', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), CAST(NULL AS VARCHAR)), ROW('metadata', 'metadata', ARRAY[ROW('content-1')], '{"other":"value"}')),
                            (TIMESTAMP '2026-08-28 10:20:06', 'Interaction', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), CAST(NULL AS VARCHAR)), ROW('metadata', 'metadata', ARRAY[ROW('content-1')], 'null')),
                            (TIMESTAMP '2026-08-28 10:20:07', 'Interaction', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), CAST(NULL AS VARCHAR)), ROW('metadata', 'metadata', ARRAY[ROW('content-1')], CAST(NULL AS VARCHAR))),
                            (TIMESTAMP '2026-08-28 10:20:08', 'Interaction', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), CAST(NULL AS VARCHAR)), ROW('metadata', 'metadata', ARRAY[ROW('content-1')], '{"recommendation_source":{"tier":"premium"}}')),
                            (TIMESTAMP '2026-08-28 10:20:09', 'Interaction', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), CAST(NULL AS VARCHAR)), ROW('metadata', 'metadata', ARRAY[ROW('content-1')], '{"recommendation_source":["premium"]}')),
                            (TIMESTAMP '2026-08-28 10:20:10', 'Impression', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), CAST(NULL AS VARCHAR)), ROW('recommendation_item', 'recommendation_item', ARRAY[ROW('content-1')], '{"recommendation_source":" premium "}')),
                            (TIMESTAMP '2026-08-28 10:20:11', 'Impression', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), CAST(NULL AS VARCHAR)), ROW('page', 'page', ARRAY[ROW('content-1')], '{"recommendation_source":" premium "}')),
                            (TIMESTAMP '2026-08-28 10:20:12', 'Interaction', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), CAST(NULL AS VARCHAR)), ROW('scroll_depth', 'scroll_depth', ARRAY[ROW('content-1')], '{"depth_percent":"75","recommendation_source":" premium "}')),
                            (TIMESTAMP '2026-08-28 10:20:13', 'Interaction', ROW(CAST(NULL AS VARCHAR), ROW('diagnostic-only-installation'), CAST(NULL AS VARCHAR)), ROW('metadata', 'metadata', ARRAY[ROW('content-2')], '{"other":"value"}')),
                            (TIMESTAMP '2026-08-28 10:20:14', 'Interaction', ROW('11111111-1111-1111-1111-111111111111', ROW(CAST(NULL AS VARCHAR)), CAST(NULL AS VARCHAR)), ROW('metadata', 'metadata', ARRAY[ROW('content-1')], '{"recommendation_source":" premium "}')),
                            (TIMESTAMP '2026-08-28 10:20:15', 'Interaction', ROW(CAST(NULL AS VARCHAR), ROW('control-installation'), CAST(NULL AS VARCHAR)), ROW('metadata', 'metadata', ARRAY[ROW('content-1')], '{"recommendation_source":" premium "}'))
                        ) AS source(created, type, context, element)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO memory.default.events (id, client_id, created, type, context, element) VALUES
                            (uuid(), 'retried-event', TIMESTAMP '2026-08-28 11:30:00', 'Interaction', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), 'retry-session'), ROW('button', 'button', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR))),
                            (uuid(), 'retried-event', TIMESTAMP '2026-08-28 11:30:00', 'Interaction', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), 'retry-session'), ROW('button', 'button', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR)))
                        """.trimIndent(),
                    )
                }

                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE memory.default.activation_events (
                            id UUID,
                            client_id VARCHAR,
                            created TIMESTAMP,
                            type VARCHAR,
                            context ROW(
                                user_id VARCHAR,
                                device ROW(installation_id VARCHAR),
                                session_id VARCHAR
                            ),
                            element ROW(id VARCHAR, type VARCHAR, content ARRAY(ROW(id VARCHAR)), extras VARCHAR),
                            page ROW(path VARCHAR)
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO memory.default.activation_events (id, client_id, created, type, context, element, page)
                        SELECT uuid(), CAST(NULL AS VARCHAR), created, type, context, element, page
                        FROM (VALUES
                            (TIMESTAMP '2026-08-28 10:00:06', 'Impression', ROW('11111111-1111-1111-1111-111111111111', ROW(CAST(NULL AS VARCHAR)), 'control-session'), ROW('/talks/before', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR)), ROW('/talks/before')),
                            (TIMESTAMP '2026-08-28 10:00:10', 'Impression', ROW('11111111-1111-1111-1111-111111111111', ROW(CAST(NULL AS VARCHAR)), 'control-session'), ROW('/articles/first', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR)), ROW('/articles/first')),
                            (TIMESTAMP '2026-08-28 10:00:20', 'Impression', ROW(CAST(NULL AS VARCHAR), ROW('control-installation'), 'control-session'), ROW('/talks/next', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR)), ROW('/talks/next')),
                            (TIMESTAMP '2026-08-28 10:01:10', 'Impression', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), 'treatment-session-1'), ROW('/articles/first', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR)), ROW('/articles/first')),
                            (TIMESTAMP '2026-08-28 10:01:20', 'Impression', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), 'treatment-session-1'), ROW('/search', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR)), ROW('/search')),
                            (TIMESTAMP '2026-08-28 10:01:30', 'Impression', ROW(CAST(NULL AS VARCHAR), ROW('22222222-2222-2222-2222-222222222222'), 'treatment-session-1'), ROW('/studies/next', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR)), ROW('/studies/next')),
                            (TIMESTAMP '2026-08-28 10:02:00', 'Impression', ROW(CAST(NULL AS VARCHAR), ROW('unassigned-installation'), 'unrelated-session'), ROW('/articles/unassigned', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR)), ROW('/articles/unassigned'))
                        ) AS source(created, type, context, element, page)
                        """.trimIndent(),
                    )
                }

                val query = buildSessionDurationQuery(
                    eventsTable = "memory.default.events",
                    assignmentsTable = "bosca.experimentation.assignments",
                    experimentId = EXPERIMENT_ID,
                    startDate = OffsetDateTime.parse("2026-08-28T09:00:00Z"),
                    endDate = OffsetDateTime.parse("2026-08-28T11:00:00Z"),
                )
                val values = linkedMapOf<String, Double>()
                connection.prepareStatement(query.sql).use { statement ->
                    query.params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                    statement.executeQuery().use { rows ->
                        while (rows.next()) values[rows.getString("variation_key")] = rows.getDouble("value")
                    }
                }
                assertEquals(setOf("control", "treatment"), values.keys)
                assertEquals(
                    305.0,
                    values.getValue("control"),
                    0.000_001,
                    "a reused id is split after five minutes and every derived session includes its timeout tail",
                )
                assertEquals(
                    312.5,
                    values.getValue("treatment"),
                    0.000_001,
                    "the timeout extends each session five minutes beyond its final activity event",
                )

                val retriedEventQuery = checkNotNull(
                    buildConversionCountQuery(
                        eventsTable = "memory.default.events",
                        assignmentsTable = "bosca.experimentation.assignments",
                        experimentId = EXPERIMENT_ID,
                        goal = ConversionGoal(
                            experimentId = EXPERIMENT_ID,
                            name = "Retried interactions",
                            eventType = EventType.Interaction,
                            metricType = GoalMetricType.EVENT_COUNT,
                        ),
                        startDate = OffsetDateTime.parse("2026-08-28T11:00:00Z"),
                        endDate = OffsetDateTime.parse("2026-08-28T12:00:00Z"),
                    ),
                )
                connection.prepareStatement(retriedEventQuery.sql).use { statement ->
                    retriedEventQuery.params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                    statement.executeQuery().use { rows ->
                        kotlin.test.assertTrue(rows.next())
                        assertEquals("treatment", rows.getString("variation_key"))
                        assertEquals(1L, rows.getLong("cnt"), "a retried browser event must count once")
                        kotlin.test.assertFalse(rows.next())
                    }
                }

                val excludedQuery = buildSessionDurationQuery(
                    eventsTable = "memory.default.events",
                    assignmentsTable = "bosca.experimentation.assignments",
                    experimentId = EXPERIMENT_ID,
                    startDate = OffsetDateTime.parse("2026-08-28T09:00:00Z"),
                    endDate = OffsetDateTime.parse("2026-08-28T11:00:00Z"),
                    excludedPrincipalIds = listOf(
                        UUID.parse("11111111-1111-1111-1111-111111111111"),
                    ),
                )
                val valuesWithControlAccountExcluded = linkedMapOf<String, Double>()
                connection.prepareStatement(excludedQuery.sql).use { statement ->
                    excludedQuery.params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            valuesWithControlAccountExcluded[rows.getString("variation_key")] = rows.getDouble("value")
                        }
                    }
                }
                assertEquals(
                    mapOf("treatment" to 312.5),
                    valuesWithControlAccountExcluded,
                    "excluding a principal must also exclude activity from that assignment's installation id",
                )

                val linkedInstallationExcludedQuery = buildSessionDurationQuery(
                    eventsTable = "memory.default.events",
                    assignmentsTable = "bosca.experimentation.assignments",
                    experimentId = EXPERIMENT_ID,
                    startDate = OffsetDateTime.parse("2026-08-28T09:00:00Z"),
                    endDate = OffsetDateTime.parse("2026-08-28T11:00:00Z"),
                    excludedInstallationIds = listOf("22222222-2222-2222-2222-222222222222"),
                )
                val valuesWithTreatmentInstallationExcluded = linkedMapOf<String, Double>()
                connection.prepareStatement(linkedInstallationExcludedQuery.sql).use { statement ->
                    linkedInstallationExcludedQuery.params.forEachIndexed { index, value ->
                        statement.setString(index + 1, value)
                    }
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            valuesWithTreatmentInstallationExcluded[rows.getString("variation_key")] =
                                rows.getDouble("value")
                        }
                    }
                }
                assertEquals(
                    mapOf("control" to 305.0),
                    valuesWithTreatmentInstallationExcluded,
                    "an installation linked to an excluded principal must be absent from session outcomes",
                )

                val itemQuery = checkNotNull(
                    buildConversionCountQuery(
                        eventsTable = "memory.default.events",
                        assignmentsTable = "bosca.experimentation.assignments",
                        experimentId = EXPERIMENT_ID,
                        goal = ConversionGoal(
                            experimentId = EXPERIMENT_ID,
                            name = "Exact recommendation source",
                            itemExtraKey = "recommendation_source",
                            itemExtraValue = " premium ",
                        ),
                        startDate = OffsetDateTime.parse("2026-08-28T09:59:00Z"),
                        endDate = OffsetDateTime.parse("2026-08-28T10:21:00Z"),
                    ),
                )
                connection.prepareStatement(itemQuery.sql).use { statement ->
                    itemQuery.params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                    statement.executeQuery().use { rows ->
                        val outcomes = mutableMapOf<String, Pair<String, Long>>()
                        var invalidExtras = -1L
                        var missingValues = -1L
                        while (rows.next()) {
                            val clientId = rows.getString("client_id")
                            if (clientId == null) {
                                invalidExtras = rows.getLong("invalid_item_extras")
                                missingValues = rows.getLong("missing_item_extra_values")
                            } else {
                                outcomes[clientId] = rows.getString("variation_key") to rows.getLong("cnt")
                                assertEquals(0L, rows.getLong("invalid_item_extras"))
                                assertEquals(0L, rows.getLong("missing_item_extra_values"))
                            }
                        }
                        assertEquals(
                            mapOf(
                                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1" to ("control" to 2L),
                                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2" to ("treatment" to 2L),
                            ),
                            outcomes,
                            "assignment identity paths must merge; pre-assignment and unassigned events must be omitted",
                        )
                        assertEquals(5L, invalidExtras)
                        assertEquals(3L, missingValues)
                    }
                }

                // Exercise each production conversion branch through a real cross-catalog join.
                for (metric in listOf(
                    bosca.experimentation.model.GoalMetricType.UNIQUE_CONVERSION,
                    bosca.experimentation.model.GoalMetricType.EVENT_COUNT,
                )) {
                    for (excluded in listOf(emptyList(), listOf(UUID.parse("11111111-1111-1111-1111-111111111111")))) {
                        val conversion = checkNotNull(buildConversionCountQuery(
                            eventsTable = "memory.default.events",
                            assignmentsTable = "bosca.experimentation.assignments",
                            experimentId = EXPERIMENT_ID,
                            goal = ConversionGoal(
                                experimentId = EXPERIMENT_ID,
                                name = "Interactions",
                                eventType = bosca.analytics.model.EventType.Interaction,
                                metricType = metric,
                            ),
                            startDate = OffsetDateTime.parse("2026-08-28T09:00:00Z"),
                            endDate = OffsetDateTime.parse("2026-08-28T11:00:00Z"),
                            excludedPrincipalIds = excluded,
                        ))
                        connection.prepareStatement(conversion.sql).use { statement ->
                            conversion.params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                            statement.executeQuery().use { rows ->
                                val actual = mutableMapOf<String, Long>()
                                while (rows.next()) {
                                    actual[rows.getString("variation_key")] =
                                        if (metric == bosca.experimentation.model.GoalMetricType.EVENT_COUNT) rows.getLong("cnt") else 1L
                                }
                                val expected = mutableMapOf("treatment" to
                                    if (metric == bosca.experimentation.model.GoalMetricType.EVENT_COUNT) 10L else 1L)
                                if (excluded.isEmpty()) expected["control"] =
                                    if (metric == bosca.experimentation.model.GoalMetricType.EVENT_COUNT) 2L else 1L
                                assertEquals(expected, actual, "metric=$metric excluded=$excluded")
                            }
                        }
                    }
                }

                DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { pg ->
                    pg.createStatement().use { statement ->
                        statement.execute("""
                            INSERT INTO experimentation.assignments VALUES (
                                'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4', '$EXPERIMENT_ID', 'control',
                                NULL, 'late-activation', TIMESTAMPTZ '2026-08-28 10:00:05+00'
                            )
                        """.trimIndent())
                    }
                }
                val activationFilter = ExperimentActivationFilter(
                    eventType = EventType.Impression,
                    elementType = "page",
                    pagePathPrefixes = listOf("/articles/"),
                )
                val activated = buildActivatedCohortQuery(
                    eventsTable = "memory.default.activation_events",
                    assignmentsTable = "bosca.experimentation.assignments",
                    experimentId = EXPERIMENT_ID,
                    activationFilter = activationFilter,
                    startDate = OffsetDateTime.parse("2026-08-28T09:00:00Z"),
                    endDate = OffsetDateTime.parse("2026-08-28T11:00:00Z"),
                )
                fun captureCohort(): ActivatedCohort = connection.prepareStatement(activated.sql).use { statement ->
                    activated.params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                    statement.executeQuery().use { rows ->
                        ActivatedCohort(buildList {
                            while (rows.next()) add(ActivatedSubject(
                                rows.getString("client_id"), rows.getString("variation_key"), rows.getString("activated_at"),
                            ))
                        })
                    }
                }
                val cohort = captureCohort()
                assertEquals(mapOf("control" to 1L, "treatment" to 1L), cohort.countsByVariation)

                // All event timestamps precede the aggregation cutoff, but these rows arrive
                // after the denominator was captured: one new subject and an earlier activation.
                connection.createStatement().use { statement ->
                    statement.execute("""
                        INSERT INTO memory.default.activation_events (id, client_id, created, type, context, element, page)
                        SELECT uuid(), CAST(NULL AS VARCHAR), created, type, context, element, page
                        FROM (VALUES
                            (TIMESTAMP '2026-08-28 10:00:05.500', 'Impression', ROW('11111111-1111-1111-1111-111111111111', ROW(CAST(NULL AS VARCHAR)), 'control-session'), ROW('/articles/earlier', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR)), ROW('/articles/earlier')),
                            (TIMESTAMP '2026-08-28 10:00:10', 'Impression', ROW(CAST(NULL AS VARCHAR), ROW('late-activation'), 'late-session'), ROW('/articles/late', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR)), ROW('/articles/late')),
                            (TIMESTAMP '2026-08-28 10:00:20', 'Impression', ROW(CAST(NULL AS VARCHAR), ROW('late-activation'), 'late-session'), ROW('/talks/late', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR)), ROW('/talks/late'))
                        ) AS source(created, type, context, element, page)
                    """.trimIndent())
                }
                assertEquals(mapOf("control" to 2L, "treatment" to 1L), captureCohort().countsByVariation,
                    "the next aggregation may discover late activations")

                val frozenSubjects = kotlinx.serialization.json.Json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(ActivatedSubject.serializer()),
                    cohort.batches.single().encodedSubjects,
                )
                val largeCohort = ActivatedCohort(
                    listOf(frozenSubjects.first()) + List(9_000) { index ->
                        ActivatedSubject(UUID.random().toString(), "unused-$index", "2026-08-28 10:00:10.123456")
                    } + frozenSubjects.drop(1),
                )
                kotlin.test.assertTrue(largeCohort.batches.size > 1)
                for (testedCohort in listOf(cohort, largeCohort, ActivatedCohort(emptyList()))) {
                    for (metric in listOf(GoalMetricType.EVENT_COUNT, GoalMetricType.UNIQUE_CONVERSION)) {
                        val counts = mutableMapOf<String, Long>()
                        for (batch in testedCohort.batches) {
                            val followUps = checkNotNull(buildConversionCountQuery(
                                eventsTable = "memory.default.activation_events",
                                assignmentsTable = "bosca.experimentation.assignments",
                                experimentId = EXPERIMENT_ID,
                                goal = ConversionGoal(
                                    experimentId = EXPERIMENT_ID,
                                    name = "Follow-up content page",
                                    eventType = EventType.Impression,
                                    elementType = "page",
                                    pagePathPrefixes = listOf("/articles/", "/talks/", "/studies/"),
                                    metricType = metric,
                                ),
                                startDate = OffsetDateTime.parse("2026-08-28T09:00:00Z"),
                                endDate = OffsetDateTime.parse("2026-08-28T11:00:00Z"),
                                activatedCohort = batch,
                            ))
                            connection.prepareStatement(followUps.sql).use { statement ->
                                followUps.params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                                statement.executeQuery().use { rows ->
                                    while (rows.next()) counts.merge(rows.getString("variation_key"),
                                        if (metric == GoalMetricType.EVENT_COUNT) rows.getLong("cnt") else 1L, Long::plus)
                                }
                            }
                        }
                        val expected = if (testedCohort.subjectIds.isEmpty()) emptyMap() else mapOf("control" to 1L, "treatment" to 1L)
                        assertEquals(expected, counts, "batches must preserve $metric eligibility and activation boundaries")
                    }

                    val durations = mutableMapOf<String, Double>()
                    for (batch in testedCohort.batches) {
                        val sessions = buildSessionDurationQuery(
                            eventsTable = "memory.default.activation_events",
                            assignmentsTable = "bosca.experimentation.assignments",
                            experimentId = EXPERIMENT_ID,
                            startDate = OffsetDateTime.parse("2026-08-28T09:00:00Z"),
                            endDate = OffsetDateTime.parse("2026-08-28T11:00:00Z"),
                            activatedCohort = batch,
                        )
                        connection.prepareStatement(sessions.sql).use { statement ->
                            sessions.params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                            statement.executeQuery().use { rows ->
                                while (rows.next()) {
                                    val subjectId = rows.getString("client_id")
                                    kotlin.test.assertFalse(subjectId in durations, "batches must be disjoint")
                                    durations[subjectId] = rows.getDouble("value")
                                }
                            }
                        }
                    }
                    val expected = if (testedCohort.subjectIds.isEmpty()) emptyMap() else mapOf(
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1" to 310.0,
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2" to 320.0,
                    )
                    assertEquals(expected, durations, "session durations must keep the captured activation boundaries")
                }

                for (exactPath in listOf(null, "/search", "/articles/first")) {
                    val covariateQuery = buildPerUserEventCountsQuery(
                        eventsTable = "memory.default.activation_events",
                        eventType = EventType.Impression,
                        elementType = "page",
                        elementId = null,
                        pagePath = exactPath,
                        pagePathPrefixes = listOf("/articles/", "/talks/"),
                        windowStart = OffsetDateTime.parse("2026-08-28T09:00:00Z"),
                        windowEnd = OffsetDateTime.parse("2026-08-28T11:00:00Z"),
                    )
                    connection.prepareStatement(covariateQuery.sql).use { statement ->
                        covariateQuery.params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                        statement.executeQuery().use { rows ->
                            val counts = buildMap {
                                while (rows.next()) put(rows.getString("client_id"), rows.getLong("cnt"))
                            }
                            assertEquals(mapOf(
                                "principal:11111111-1111-1111-1111-111111111111" to 3L,
                                "installation:control-installation" to 1L,
                                "installation:22222222-2222-2222-2222-222222222222" to if (exactPath == "/search") 2L else 1L,
                                "installation:unassigned-installation" to 1L,
                                "installation:late-activation" to 2L,
                            ), counts, "CUPED page alternatives must exclude other routes without double-counting")
                        }
                    }
                }

                val presenceQuery = buildPerUserEventCountsQuery(
                    eventsTable = "memory.default.events",
                    eventType = null,
                    elementType = null,
                    elementId = null,
                    pagePath = null,
                    itemExtraKey = "recommendation_source",
                    windowStart = OffsetDateTime.parse("2026-08-28T10:19:00Z"),
                    windowEnd = OffsetDateTime.parse("2026-08-28T10:21:00Z"),
                )
                connection.prepareStatement(presenceQuery.sql).use { statement ->
                    presenceQuery.params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                    statement.executeQuery().use { rows ->
                        assertEquals(
                            mapOf(
                                "principal:11111111-1111-1111-1111-111111111111" to 1L,
                                "installation:control-installation" to 1L,
                                "installation:22222222-2222-2222-2222-222222222222" to 3L,
                            ),
                            buildMap {
                                while (rows.next()) put(rows.getString("client_id"), rows.getLong("cnt"))
                            },
                            "scalar interactions and page impressions count, passive impressions do not",
                        )
                    }
                }
                val sessionCases = listOf(
                    listOf(0L) to 300.0,
                    listOf(0L, 20_000L) to 320.0,
                    listOf(0L, 300_000L) to 300.0,
                    listOf(0L, 299_999L) to 599.999,
                    listOf(0L, 7_200_000L) to 300.0,
                    listOf(0L, 0L) to 300.0,
                )
                for ((index, scenario) in sessionCases.withIndex()) {
                    val (offsets, expectedSeconds) = scenario
                    val start = OffsetDateTime.parse("2026-08-29T12:00:00Z").plusDays(index.toLong())
                    connection.prepareStatement("""
                        INSERT INTO memory.default.events (id, client_id, created, type, context, element) VALUES (
                            uuid(), CAST(NULL AS VARCHAR), CAST(? AS TIMESTAMP), 'Session',
                            ROW('11111111-1111-1111-1111-111111111111', ROW(CAST(NULL AS VARCHAR)), ?),
                            ROW('page', 'page', CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR))), CAST(NULL AS VARCHAR))
                        )
                    """.trimIndent()).use { statement ->
                        for (offset in offsets) {
                            statement.setString(1, start.plusNanos(offset * 1_000_000).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")))
                            statement.setString(2, "edge-session-$index")
                            statement.executeUpdate()
                        }
                    }
                    val edgeQuery = buildSessionDurationQuery(
                        "memory.default.events", "bosca.experimentation.assignments", EXPERIMENT_ID,
                        start, start.plusHours(4),
                    )
                    connection.prepareStatement(edgeQuery.sql).use { statement ->
                        edgeQuery.params.forEachIndexed { paramIndex, value -> statement.setString(paramIndex + 1, value) }
                        statement.executeQuery().use { rows ->
                            kotlin.test.assertTrue(rows.next())
                            assertEquals(expectedSeconds, rows.getDouble("value"), 1e-6, "event offsets in milliseconds: $offsets")
                            kotlin.test.assertFalse(rows.next())
                        }
                    }
                }

                assertPlaybackSessions(connection)

            }
        } finally {
            trino?.stop()
            postgres.stop()
            network.close()
        }
    }

    private data class PlaybackEvent(
        val seconds: Long,
        val id: String = "play",
        val type: String = "Completion",
        val elementType: String = "button",
        val position: String = "0",
        val duration: String = "1200",
        val audio: Boolean = false,
        val session: String = "playback-session",
        val device: String = "control-installation",
        val content: String = "content-1",
        val extra: String = "",
        val rawExtras: String? = null,
    )

    private fun assertPlaybackSessions(connection: java.sql.Connection) {
        val play = PlaybackEvent(0)
        val pause = PlaybackEvent(1200, id = "pause", type = "Interaction", position = "1200")
        val click = PlaybackEvent(7200, id = "click", type = "Interaction")
        val progress = PlaybackEvent(0, id = "playback-1", type = "Impression", elementType = "media_playback",
            extra = ",\"observedSeconds\":\"0\"")
        val cases = listOf(
            Triple("progress confirms twenty minutes without user input", (0L..40L).map { index ->
                progress.copy(seconds = index * 30, position = (index * 30).toString(),
                    extra = ",\"observedSeconds\":\"${if (index == 0L) 0 else 30}\"")
            }, 1500.0),
            Triple("confirmed background playback bridges timer suspension and client rollover", listOf(
                progress,
                progress.copy(seconds = 1200, position = "1200", session = "rotated", extra = ",\"observedSeconds\":\"1200\""),
            ), 1500.0),
            Triple("stopped progress does not assume remaining media was played", listOf(progress), 300.0),
            Triple("implicit completion carries final confirmed playback", listOf(
                progress, progress.copy(seconds = 20, position = "20", type = "Completion", extra = ",\"observedSeconds\":\"20\",\"state\":\"pagehide\""),
            ), 320.0),
            Triple("closing a long-paused player does not create a phantom session", listOf(
                progress, progress.copy(seconds = 20, position = "20", extra = ",\"observedSeconds\":\"20\",\"state\":\"paused\""),
                progress.copy(seconds = 7200, position = "20", type = "Completion", extra = ",\"observedSeconds\":\"0\",\"state\":\"pagehide\""),
            ), 320.0),
            Triple("unconfirmed huge progress gap still splits", listOf(progress, progress.copy(seconds = 7200)), 300.0),
            Triple("playback cannot claim more observed time than the event interval", listOf(
                progress, progress.copy(seconds = 7200, extra = ",\"observedSeconds\":\"999999\""),
            ), 300.0),
            Triple("same-time pause does not erase confirmed progress", listOf(
                progress,
                progress.copy(seconds = 1200, position = "1200", extra = ",\"observedSeconds\":\"1200\""),
                progress.copy(seconds = 1200, position = "1200", extra = ",\"observedSeconds\":\"0\",\"state\":\"paused\""),
            ), 1500.0),
            Triple("video plays for twenty minutes", listOf(play, pause.copy(id = "player", type = "Completion", elementType = "media")), 1500.0),
            Triple("audio with no stop uses remaining duration", listOf(play.copy(audio = true)), 1500.0),
            Triple("resumed audio starts at the recorded position", listOf(play.copy(audio = true, position = "900")), 600.0),
            Triple("pause cuts the playback allowance short", listOf(play, pause.copy(seconds = 120, position = "120")), 420.0),
            Triple("ordinary activity cannot shorten playback", listOf(play, click.copy(seconds = 600), pause), 1500.0),
            Triple("a later visit still splits", listOf(play, click), 900.0),
            Triple("exact expiry starts another session", listOf(play, click.copy(seconds = 1500)), 900.0),
            Triple("client session rollover during playback merges", listOf(play, click.copy(seconds = 600, type = "Session", session = "renewed"), pause.copy(session = "renewed")), 1500.0),
            Triple("playback cannot bridge a different device", listOf(play, click.copy(seconds = 600, device = "other-device")), 900.0),
            Triple("a different item's pause does not stop playback", listOf(play, pause.copy(seconds = 120, content = "other-item")), 1500.0),
            Triple("overlapping players are not added together", listOf(
                play,
                play.copy(seconds = 60, content = "content-2", duration = "1800"),
                pause.copy(seconds = 600),
                pause.copy(seconds = 900, content = "content-2"),
            ), 1200.0),
            Triple("audio and video on the same content have independent playback state", listOf(
                play,
                play.copy(seconds = 60, audio = true, duration = "1800"),
                pause.copy(seconds = 600),
                pause.copy(seconds = 900, audio = true),
            ), 1200.0),
            Triple("seeking forward does not count skipped media", listOf(
                play.copy(duration = "3600"),
                play.copy(seconds = 120, id = "seek", type = "Interaction", position = "3000", duration = "3600"),
                play.copy(seconds = 125, id = "seek", position = "3000", duration = "3600"),
                pause.copy(seconds = 725, position = "3600", duration = "3600"),
            ), 1025.0),
            Triple("seeking while paused does not restart playback", listOf(
                play, pause.copy(seconds = 120, position = "120"),
                play.copy(seconds = 600, id = "seek", type = "Interaction", position = "900"),
                play.copy(seconds = 601, id = "seek", position = "900"),
            ), 360.5),
            Triple("a player error ends the allowance", listOf(play, play.copy(seconds = 120, extra = ",\"error\":\"true\"")), 420.0),
            Triple("failed playback does not extend", listOf(play.copy(extra = ",\"error\":\"true\"")), 300.0),
            Triple("invalid duration does not extend", listOf(play.copy(duration = "NaN")), 300.0),
            Triple("malformed extras do not extend", listOf(play.copy(rawExtras = "{")), 300.0),
            Triple("empty content identity does not extend", listOf(play.copy(content = "")), 300.0),
            Triple("negative duration does not extend", listOf(play.copy(duration = "-1200")), 300.0),
            Triple("invalid rate does not extend", listOf(play.copy(extra = ",\"playbackRate\":\"invalid\"")), 300.0),
            Triple("position beyond duration does not extend", listOf(play.copy(position = "1500")), 300.0),
            Triple("zero playback rate does not extend", listOf(play.copy(extra = ",\"playbackRate\":\"0\"")), 300.0),
            Triple("known playback rate scales remaining time", listOf(play.copy(extra = ",\"playbackRate\":\"2\"")), 900.0),
        )
        for ((index, scenario) in cases.withIndex()) {
            val (name, events, expected) = scenario
            val start = OffsetDateTime.parse("2027-01-01T12:00:00Z").plusDays(index.toLong())
            connection.prepareStatement("""
                INSERT INTO memory.default.events (id, client_id, created, type, context, element) VALUES (
                    uuid(), CAST(NULL AS VARCHAR), CAST(? AS TIMESTAMP), ?,
                    ROW('11111111-1111-1111-1111-111111111111', ROW(?), ?),
                    ROW(?, ?, ARRAY[ROW(?)], ?)
                )
            """.trimIndent()).use { statement ->
                for (event in events) {
                    statement.setString(1, start.plusSeconds(event.seconds).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")))
                    statement.setString(2, event.type)
                    statement.setString(3, event.device)
                    statement.setString(4, event.session)
                    statement.setString(5, event.id)
                    statement.setString(6, event.elementType)
                    statement.setString(7, event.content)
                    statement.setString(8, event.rawExtras ?: """{"audio":"${event.audio}","currentTime":"${event.position}","duration":"${event.duration}"${event.extra}}""")
                    statement.executeUpdate()
                }
            }
            val built = buildSessionDurationQuery(
                "memory.default.events", "bosca.experimentation.assignments", EXPERIMENT_ID, start, start.plusHours(4),
            )
            connection.prepareStatement(built.sql).use { statement ->
                built.params.forEachIndexed { paramIndex, value -> statement.setString(paramIndex + 1, value) }
                statement.executeQuery().use { rows ->
                    kotlin.test.assertTrue(rows.next(), name)
                    assertEquals(expected, rows.getDouble("value"), 1e-6, name)
                    kotlin.test.assertFalse(rows.next(), name)
                }
            }
            if (name == "progress confirms twenty minutes without user input") {
                for (elementType in listOf(null, "media_playback")) {
                    val goal = ConversionGoal(
                        experimentId = EXPERIMENT_ID, name = "Impression metric",
                        eventType = bosca.analytics.model.EventType.Impression, elementType = elementType,
                        metricType = bosca.experimentation.model.GoalMetricType.EVENT_COUNT,
                    )
                    val conversion = checkNotNull(buildConversionCountQuery(
                        "memory.default.events", "bosca.experimentation.assignments", EXPERIMENT_ID,
                        goal, start, start.plusHours(4),
                    ))
                    val covariate = buildPerUserEventCountsQuery(
                        eventsTable = "memory.default.events", eventType = goal.eventType, elementType = elementType,
                        elementId = null, pagePath = null, windowStart = start, windowEnd = start.plusHours(4),
                    )
                    for (query in listOf(conversion, covariate)) {
                        connection.prepareStatement(query.sql).use { statement ->
                            query.params.forEachIndexed { paramIndex, value -> statement.setString(paramIndex + 1, value) }
                            statement.executeQuery().use { rows ->
                                var count = 0L
                                while (rows.next()) if (rows.getString("client_id") != null) count += rows.getLong("cnt")
                                assertEquals(if (elementType == null) 0L else 41L, count,
                                    "playback progress must be selected explicitly instead of inflating broad impression metrics")
                            }
                        }
                    }
                }
            }
            if (name == "video plays for twenty minutes") {
                val partial = buildSessionDurationQuery(
                    "memory.default.events", "bosca.experimentation.assignments", EXPERIMENT_ID, start, start.plusSeconds(600),
                )
                connection.prepareStatement(partial.sql).use { statement ->
                    partial.params.forEachIndexed { paramIndex, value -> statement.setString(paramIndex + 1, value) }
                    statement.executeQuery().use { rows ->
                        kotlin.test.assertTrue(rows.next())
                        assertEquals(900.0, rows.getDouble("value"), 1e-6, "playback cannot project beyond the aggregation cutoff")
                    }
                }
            }
        }
    }

    private fun createAssignments(jdbcUrl: String, username: String, password: String) {
        DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA experimentation")
                statement.execute(
                    """
                    CREATE TABLE experimentation.assignments (
                        id UUID PRIMARY KEY,
                        experiment_id UUID NOT NULL,
                        variation_key VARCHAR NOT NULL,
                        principal_id UUID,
                        installation_id VARCHAR,
                        assigned_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO experimentation.assignments (
                        id, experiment_id, variation_key, principal_id, installation_id, assigned_at
                    ) VALUES
                        ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', '$EXPERIMENT_ID', 'control', '11111111-1111-1111-1111-111111111111', 'control-installation', '2026-08-28 10:00:05+00'),
                        ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', '$EXPERIMENT_ID', 'treatment', NULL, '22222222-2222-2222-2222-222222222222', '2026-08-28 10:01:05+00'),
                        ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3', '99999999-9999-9999-9999-999999999999', 'other', NULL, 'unassigned-installation', '2026-08-28 09:00:00+00')
                    """.trimIndent(),
                )
            }
        }
    }

    private fun waitForTrino(connection: java.sql.Connection) {
        var lastFailure: SQLException? = null
        repeat(120) {
            try {
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT 1").use { rows -> check(rows.next()) }
                }
                return
            } catch (failure: SQLException) {
                lastFailure = failure
                Thread.sleep(250)
            }
        }
        throw lastFailure ?: IllegalStateException("Trino did not become ready")
    }

    private fun isDockerAvailable(): Boolean = try {
        DockerClientFactory.instance().isDockerAvailable
    } catch (_: Throwable) {
        false
    }

    private companion object {
        val EXPERIMENT_ID: UUID = UUID.parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    }
}
