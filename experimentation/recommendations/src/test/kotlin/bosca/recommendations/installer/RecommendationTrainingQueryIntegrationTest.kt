package bosca.recommendations.installer

import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Execute the actual installer SQL against Trino's nested analytics rows and PostgreSQL identities. */
class RecommendationTrainingQueryIntegrationTest {
    @Test
    fun `training queries preserve exposure quality identity and snapshot boundaries`() {
        assumeTrue("Docker is required for the training-query integration test", DockerClientFactory.instance().isDockerAvailable)
        Network.newNetwork().use { network ->
            PostgreSQLContainer("pgvector/pgvector:pg17")
                .withDatabaseName("training_test").withUsername("training").withPassword("training")
                .withNetwork(network).withNetworkAliases("training-postgres").use { postgres ->
                    postgres.start()
                    DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                        connection.createStatement().use { statement ->
                            statement.execute("create table principals(id uuid, primary_profile_id uuid, deleted_at timestamptz)")
                            statement.execute("create table profiles(id uuid, principal uuid, created timestamptz, deleted_at timestamptz)")
                            statement.execute("insert into principals values ('$PRINCIPAL', '$PROFILE', null)")
                            statement.execute("insert into profiles values ('$PROFILE', '$PRINCIPAL', '2026-01-01', null)")
                            statement.execute("create table profile_ratings(profile_id uuid, metadata_id uuid, rating int, created timestamptz)")
                            statement.execute("create schema recommendations")
                            statement.execute("create table recommendations.strategies(id uuid, status text)")
                            statement.execute("insert into recommendations.strategies values ('$PRINCIPAL', 'active'), ('$DELETED_PROFILE', 'inactive')")
                            statement.execute("create table recommendations.co_engagements(source_metadata_id uuid, co_engaged_metadata_id uuid, strategy_id uuid, score double precision, created timestamptz)")
                            statement.execute("""
                                insert into recommendations.co_engagements values
                                ('$GUIDE', '$CONTENT', '$PRINCIPAL', 3, '2026-09-04'),
                                ('$GUIDE', '$CONTENT', '$PRINCIPAL', 5, '2026-09-04'),
                                ('$GUIDE', '$SECOND_CONTENT', '$PRINCIPAL', 9, '2026-09-06'),
                                ('$GUIDE', '$CONTENT', '$DELETED_PROFILE', 99, '2026-09-04')
                            """.trimIndent())
                            statement.execute("create table recommendations.cohort_co_engagements(source_metadata_id uuid, co_engaged_metadata_id uuid, strategy_id uuid, cohort_key text, score double precision, created timestamptz)")
                            statement.execute("insert into recommendations.cohort_co_engagements values ('$GUIDE', '$CONTENT', '$PRINCIPAL', 'readers', 7, '2026-09-04')")
                            statement.execute("create table recommendations.profile_cohort(user_id uuid, cohort_key text)")
                            statement.execute("insert into recommendations.profile_cohort values ('$PROFILE', 'readers')")
                            statement.execute("create table recommendations.dismissals(profile_id uuid, metadata_id uuid, created timestamptz)")
                            statement.execute("insert into profile_ratings values ('$PROFILE', '$CONTENT', 5, '2026-09-04'), ('$PROFILE', '$CONTENT', 1, '2026-09-06')")
                            statement.execute("insert into recommendations.dismissals values ('$PROFILE', '$CONTENT', '2026-09-05 11:00:00Z')")
                            statement.execute("create table guide_steps(id bigint, metadata_id uuid, version int, step_metadata_id uuid)")
                            statement.execute("create table profile_guide_progress(profile_id uuid, metadata_id uuid, version int, completed_step_ids bigint[], modified timestamptz, started timestamptz default '2026-09-01')")
                            statement.execute("create table profile_guide_history(id bigint, profile_id uuid, metadata_id uuid, version int, completed timestamptz)")
                            statement.execute("insert into profiles values ('$DELETED_PROFILE', null, '2026-01-01', '2026-09-01')")
                            statement.execute("""
                                insert into guide_steps values
                                (11, '$GUIDE', 1, '$CONTENT'), (12, '$GUIDE', 1, '$SECOND_CONTENT'),
                                (13, '$GUIDE', 1, '$THIRD_CONTENT'),
                                (21, '$GUIDE', 2, '$CONTENT'), (22, '$GUIDE', 2, '$SECOND_CONTENT'),
                                (31, '$GUIDE', 3, '$THIRD_CONTENT')
                            """.trimIndent())
                            statement.execute("""
                                insert into profile_guide_progress(profile_id, metadata_id, version, completed_step_ids, modified) values
                                ('$PROFILE', '$GUIDE', 1, ARRAY[11,12,999]::bigint[], '2026-09-05 11:00:00Z'),
                                ('$PROFILE', '$GUIDE', 2, ARRAY[]::bigint[], '2026-09-05'),
                                ('$PROFILE', '$GUIDE', 1, ARRAY[13]::bigint[], '2026-09-06'),
                                ('$PROFILE', '$GUIDE', 1, ARRAY[13]::bigint[], '2024-01-01'),
                                ('$DELETED_PROFILE', '$GUIDE', 1, ARRAY[13]::bigint[], '2026-09-05')
                            """.trimIndent())
                            statement.execute("""
                                insert into profile_guide_history values
                                (1, '$PROFILE', '$GUIDE', 2, '2026-09-04 18:00:00-05:00'),
                                (2, '$PROFILE', '$GUIDE', 2, '2026-09-06'),
                                (3, '$PROFILE', '$GUIDE', 2, null),
                                (4, '$PROFILE', '$GUIDE', 2, '2024-01-01'),
                                (5, '$DELETED_PROFILE', '$GUIDE', 2, '2026-09-05')
                            """.trimIndent())
                        }
                    }
                    GenericContainer("trinodb/trino:479")
                        .withNetwork(network).withExposedPorts(8080)
                        .withCopyToContainer(Transferable.of("""
                            connector.name=postgresql
                            connection-url=jdbc:postgresql://training-postgres:5432/training_test
                            connection-user=training
                            connection-password=training
                            postgresql.array-mapping=AS_ARRAY
                        """.trimIndent().toByteArray()), "/etc/trino/catalog/bosca.properties")
                        .withCopyToContainer(Transferable.of("connector.name=memory\n".toByteArray()), "/etc/trino/catalog/memory.properties")
                        .waitingFor(Wait.forHttp("/v1/info").forStatusCode(200))
                        .withStartupTimeout(Duration.ofMinutes(2)).use { trino ->
                            trino.start()
                            val properties = Properties().apply { setProperty("user", "training-test") }
                            DriverManager.getConnection("jdbc:trino://${trino.host}:${trino.getMappedPort(8080)}", properties).use { connection ->
                                connection.unwrap(io.trino.jdbc.TrinoConnection::class.java).setTimeZoneId("America/Chicago")
                                waitForTrino(connection)
                                createEvents(connection)
                                val sql = RecommendationsInstaller.trainingInteractionsQuery("memory.default.events")
                                val rows = execute(connection, sql)
                                assertEquals(6, rows.size)
                                assertTrue(rows.none { it["event_id"] == "crawler" })
                                assertTrue(rows.all { it["user_id"] == PROFILE })
                                val depth = rows.single { it["event_id"] == "depth" }
                                assertNull(depth["content_id"])
                                assertEquals("/article", depth["page_id"])
                                assertEquals("90.0", depth["depth_percent"])
                                val impression = rows.single { it["event_id"] == "seen" }
                                assertEquals("1000.0", impression["visible_ms"])
                                assertEquals("0.5", impression["visibility_threshold"])
                                assertEquals(GUIDE, impression["recommendation_source_id"])
                                assertEquals("reading", impression["recommendation_context"])
                                assertEquals("17", impression["recommendation_model_version"])
                                assertEquals("request-17", impression["recommendation_request_id"])
                                assertNull(rows.first { it["event_id"] == "page" }["recommendation_context"])
                                assertNull(rows.first { it["event_id"] == "page" }["view_percent"])
                                // Event IDs survive pagination and preserve duplicates for trainer deduplication.
                                assertEquals(2, rows.count { it["event_id"] == "page" })
                                val paged = execute(connection, sql, 0, 2) + execute(connection, sql, 2, 2) + execute(connection, sql, 4, 2)
                                assertEquals(rows, paged)
                                val behaviorSql = RecommendationsInstaller.trainingBehaviorQuery()
                                val behavior = execute(connection, behaviorSql)
                                assertEquals(3, behavior.size)
                                assertEquals("5.0", behavior.single { it["kind"] == "global" }["score"])
                                assertEquals("7.0", behavior.single { it["kind"] == "cohort" }["score"])
                                assertEquals(PROFILE, behavior.single { it["kind"] == "membership" }["user_id"])
                                assertEquals(behavior, execute(connection, behaviorSql, 0, 1) + execute(connection, behaviorSql, 1, 2))
                                val guideEvent = rows.single { it["event_id"] == "guide-step" }
                                assertEquals(GUIDE, guideEvent["guide_id"])
                                assertEquals("1", guideEvent["guide_version"])
                                assertEquals("2026-09-01T00:00:00Z", guideEvent["guide_started"])
                                val feedback = execute(connection, RecommendationsInstaller.trainingFeedbackQuery())
                                assertEquals(2, feedback.size)
                                assertEquals(setOf("rating", "dismissal"), feedback.map { it["feedback_source"] }.toSet())
                                assertTrue(feedback.all { it["feedback_created"] != null })
                                assertEquals(setOf("1.0", "0.0"), feedback.map { it["feedback_label"] }.toSet())
                                val guideSql = RecommendationsInstaller.trainingGuideCompletionsQuery()
                                val guides = execute(connection, guideSql)
                                assertEquals(6, guides.size)
                                assertTrue(guides.all { it["user_id"] == PROFILE })
                                assertTrue(guides.all { it["interaction_type"] == "Completion" && it["view_percent"] == null })
                                assertTrue(guides.all { it["app_id"] == "bosca-guide-state" })
                                assertTrue(guides.all { it["session_id"] == null && it["page_id"] == null })
                                assertEquals(6, guides.map { it["event_id"] }.toSet().size)
                                assertEquals(1, guides.count { it["element_type"] == "guide_progress" })
                                assertEquals(1, guides.count { it["element_type"] == "guide" })
                                assertEquals(4, guides.count { it["element_type"] == "guide_step" })
                                assertTrue(guides.none { it["content_id"] == THIRD_CONTENT })
                                val complete = guides.single { it["element_type"] == "guide" }
                                assertEquals(GUIDE, complete["content_id"])
                                assertEquals(Instant.parse("2026-09-04T23:00:00Z"), Instant.parse(complete["interaction_created"]))
                                val progress = guides.single { it["element_type"] == "guide_progress" }
                                assertEquals(GUIDE, progress["content_id"])
                                assertEquals(Instant.parse("2026-09-05T11:00:00Z"), Instant.parse(progress["interaction_created"]))
                                assertEquals(guides, (0..6 step 2).flatMap { execute(connection, guideSql, it, 2) })
                                assertTrendingRetainsFullPool(connection)
                            }
                        }
                }
        }
    }

    private fun assertTrendingRetainsFullPool(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("""
                create table memory.default.trending_events as
                select localtimestamp as created, 'Interaction' as type,
                    cast(row(row('Mozilla/5.0')) as row(browser row(agent varchar))) as context,
                    cast(row('button', array[row(format('content-%03d', n), 'article', bigint '0', double '0')])
                        as row(type varchar, content array(row(id varchar, type varchar, "index" bigint, percent double)))) as element
                from unnest(sequence(1, 75)) as content(n)
                cross join unnest(sequence(1, 76 - n)) as engagement(i)
            """.trimIndent())
            statement.execute("""
                insert into memory.default.trending_events
                select localtimestamp, 'Interaction',
                    cast(row(row('GoogleOther/1.0')) as row(browser row(agent varchar))),
                    cast(row('button', array[row('content-001', 'article', bigint '0', double '0')])
                        as row(type varchar, content array(row(id varchar, type varchar, "index" bigint, percent double))))
            """.trimIndent())
            statement.executeQuery(RecommendationsInstaller.trendingContentQuery("memory.default.trending_events")).use { rows ->
                val candidates = buildList {
                    while (rows.next()) add(rows.getString("metadata_id") to rows.getDouble("score"))
                }
                assertEquals(75, candidates.size)
                assertEquals("content-001" to 75.0, candidates.first())
                assertEquals("content-075" to 1.0, candidates.last())
            }
        }
    }

    private fun createEvents(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("create schema if not exists memory.default")
            statement.execute("""
                create table memory.default.events (
                    created timestamp, client_id varchar, type varchar,
                    context row(app_id varchar, user_id varchar, session_id varchar,
                        browser row(agent varchar)),
                    page row(path varchar),
                    element row(id varchar, type varchar,
                        content array(row(id varchar, type varchar, "index" bigint, percent double)), extras varchar)
                )
            """.trimIndent())
            val content = "ARRAY[ROW('$CONTENT', 'article', CAST(NULL AS BIGINT), CAST(NULL AS DOUBLE))]"
            val empty = "CAST(ARRAY[] AS ARRAY(ROW(id VARCHAR, type VARCHAR, \"index\" BIGINT, percent DOUBLE)))"
            val context = "ROW('app', '$PRINCIPAL', 'session', ROW('Mozilla/5.0'))"
            fun insert(id: String, type: String, elementType: String, contents: String = content,
                       time: String = "10:00:00", extras: String = "{}", userContext: String = context) {
                statement.execute("""
                    insert into memory.default.events values (
                        TIMESTAMP '2026-09-05 $time', '$id', '$type', $userContext, ROW('/next'),
                        ROW('/article', '$elementType', $contents, '$extras'))
                """.trimIndent())
            }
            insert("page", "Impression", "page")
            insert("page", "Impression", "page")
            insert("depth", "Interaction", "scroll_max_depth", empty, extras = "{\"max_depth_percent\":\"90\"}")
            insert("seen", "Impression", "article", extras = """{"visible_ms":"1000","visibility_threshold":"0.5","recommendation_source_id":"$GUIDE","recommendation_context":"reading","recommendation_model_version":"17","recommendation_request_id":"request-17"}""")
            insert("complete", "Completion", "article", userContext = "ROW('app', '$PROFILE', 'session', ROW('Mozilla/5.0'))")
            insert("guide-step", "Completion", "guide_step",
                extras = "{\"guide_id\":\"$GUIDE\",\"guide_version\":1,\"guide_started\":\"2026-09-01T00:00:00Z\"}",
                userContext = "ROW('bosca-guide-completions', '$PROFILE', 'guide-run', ROW('Mozilla/5.0'))")
            insert("crawler", "Interaction", "click",
                userContext = "ROW('app', '$PRINCIPAL', 'session', ROW('GoogleOther/1.0'))")
            insert("future", "Interaction", "click", time = "13:00:00")
            insert("unknown", "Interaction", "click",
                userContext = "ROW('app', 'unknown-user', 'session', ROW('Mozilla/5.0'))")
        }
    }

    private fun waitForTrino(connection: Connection) {
        repeat(60) {
            try {
                connection.createStatement().use { it.execute("show catalogs") }
                return
            } catch (error: SQLException) {
                if (!error.message.orEmpty().contains("still initializing")) throw error
                Thread.sleep(500)
            }
        }
        error("Trino did not finish initialization")
    }

    private fun execute(connection: Connection, sql: String, offset: Int = 0, limit: Int = 100): List<Map<String, String?>> {
        val bound = sql.replace(":asOf", "?").replace(":offset", "?").replace(":limit", "?")
        return connection.prepareStatement(bound).use { statement ->
            statement.setString(1, "2026-09-05T12:00:00Z")
            statement.setInt(2, offset)
            statement.setInt(3, limit)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) {
                        add((1..results.metaData.columnCount).associate { column ->
                            results.metaData.getColumnLabel(column) to results.getString(column)
                        })
                    }
                }
            }
        }
    }

    companion object {
        private const val PRINCIPAL = "11111111-1111-1111-1111-111111111111"
        private const val PROFILE = "22222222-2222-2222-2222-222222222222"
        private const val CONTENT = "33333333-3333-3333-3333-333333333333"
        private const val GUIDE = "44444444-4444-4444-4444-444444444444"
        private const val SECOND_CONTENT = "55555555-5555-5555-5555-555555555555"
        private const val THIRD_CONTENT = "66666666-6666-6666-6666-666666666666"
        private const val DELETED_PROFILE = "77777777-7777-7777-7777-777777777777"
    }
}
