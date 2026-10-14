package bosca.analytics.installer

import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryInput
import bosca.analytics.service.AnalyticsQueryService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs the seeded metrics against Trino rows with sign-in, duplicate delivery, bot, and crawler agents. */
class AnalyticsQuerySqlIntegrationTest {
    @Test
    fun `seeded event metrics count people sessions and page impressions without obvious bots`() {
        assumeTrue("Docker is required for the Trino query test", DockerClientFactory.instance().isDockerAvailable)
        val queries = seededQueries()
        GenericContainer("trinodb/trino:479")
            .withExposedPorts(8080)
            .withCopyToContainer(
                Transferable.of("connector.name=memory\n".toByteArray()),
                "/etc/trino/catalog/memory.properties",
            )
            .waitingFor(Wait.forHttp("/v1/info").forStatusCode(200))
            .use { trino ->
                trino.start()
                val properties = Properties().apply { setProperty("user", "analytics-query-test") }
                DriverManager.getConnection(
                    "jdbc:trino://${trino.host}:${trino.getMappedPort(8080)}", properties,
                ).use { connection ->
                    waitForTrino(connection)
                    createEvents(connection)
                    fun execute(key: String) = queries.getValue(key).query
                        .replace("warehouse.bosca.events", "memory.default.events")
                        .replace(":startDate", "current_date")
                        .replace(":endDate", "current_date")

                    connection.createStatement().use { statement ->
                        // user-a (signed-out start linked through install-a), install-c, and install-d.
                        statement.executeQuery(execute("daily-active-users")).use { rows ->
                            assertTrue(rows.next())
                            assertEquals(3L, rows.getLong("value"))
                        }
                        statement.executeQuery(execute("unique-sessions-per-day")).use { rows ->
                            assertTrue(rows.next())
                            assertEquals(4L, rows.getLong("value"))
                        }
                        // session-a spans 30s, session-c's 400s gap is capped at 300s, and heartbeats add nothing.
                        statement.executeQuery(execute("monthly-users-and-engagement")).use { rows ->
                            assertTrue(rows.next())
                            assertEquals(3L, rows.getLong("active_users"))
                            assertEquals(110.0, rows.getDouble("avg_engagement_seconds"))
                            assertEquals(1.83, rows.getDouble("avg_engagement_minutes"))
                        }
                        statement.executeQuery(execute("top-50-page-impressions")).use { rows ->
                            val impressions = buildMap {
                                while (rows.next()) put(rows.getString("page_id"), rows.getLong("impressions"))
                            }
                            assertEquals(mapOf("/article" to 2L, "chapter:one" to 1L), impressions)
                        }
                    }
                }
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

    private fun seededQueries(): Map<String, AnalyticsQueryInput> = runBlocking {
        val service = mockk<AnalyticsQueryService>(relaxed = true)
        val security = mockk<SecurityService>(relaxed = true)
        val inputs = mutableListOf<AnalyticsQueryInput>()
        coEvery { service.getQueries(0, Int.MAX_VALUE) } returns emptyList()
        coEvery { service.addQuery(any()) } answers {
            firstArg<AnalyticsQueryInput>().also { inputs += it }.let {
                AnalyticsQuery(UUID.random(), it.key, it.name, it.description, it.query)
            }
        }
        coEvery { security.getGroupByName("administrators", GroupType.SYSTEM) } returns
            Group(UUID.random(), "administrators", "Administrators", GroupType.SYSTEM)
        QueryInstaller(service, security).install(
            mockk<PackageInstallation>(relaxed = true),
            mockk<PackageInstallationVersion>(relaxed = true),
        )
        inputs.associateBy { it.key }
    }

    private fun createEvents(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("create schema if not exists memory.default")
            statement.execute("""
                create table memory.default.events (
                    id varchar, client_id varchar, type varchar, created timestamp(6),
                    context row(user_id varchar, device row(installation_id varchar),
                        session_id varchar, browser row(agent varchar)),
                    element row(id varchar, type varchar, extras varchar)
                )
            """.trimIndent())
            fun insert(
                id: String,
                clientId: String?,
                type: String,
                userId: String?,
                installationId: String,
                sessionId: String,
                agent: String,
                elementType: String,
                elementId: String,
                extras: String = "{}",
                offsetSeconds: Int = 0,
            ) {
                val client = clientId?.let { "'$it'" } ?: "cast(null as varchar)"
                val user = userId?.let { "'$it'" } ?: "cast(null as varchar)"
                statement.execute("""
                    insert into memory.default.events values (
                        '$id', $client, '$type',
                        date_trunc('day', localtimestamp) + interval '1' hour + interval '$offsetSeconds' second,
                        row($user, row('$installationId'), '$sessionId', row('$agent')),
                        row('$elementId', '$elementType', '$extras')
                    )
                """.trimIndent())
            }
            insert("start-a", "start-a", "Session", null, "install-a", "session-a",
                "Mozilla/5.0", "session", "session-a", """{"start":"true"}""")
            insert("auth-a", "auth-a", "Interaction", "user-a", "install-a", "session-a",
                "Mozilla/5.0", "button", "sign-in", offsetSeconds = 10)
            insert("start-b", "start-b", "Session", "user-a", "install-a", "session-b",
                "Mozilla/5.0", "session", "session-b", """{"start":"true"}""")
            insert("start-c", "start-c", "Session", null, "install-c", "session-c",
                "Mozilla/5.0", "session", "session-c", """{"start":"true"}""")
            insert("bot-start", "bot-start", "Session", null, "install-bot", "session-bot",
                "GoogleOther/1.0", "session", "session-bot", """{"start":"true"}""")
            insert("non-start", "non-start", "Session", null, "install-other", "session-other",
                "Mozilla/5.0", "session", "session-other", """{"start":"false"}""")
            insert("page-a", "page-a", "Impression", "user-a", "install-a", "session-a",
                "Mozilla/5.0", "page", "/article?utm_source=mail", offsetSeconds = 20)
            insert("page-a-retry", "page-a", "Impression", "user-a", "install-a", "session-a",
                "Mozilla/5.0", "page", "/article?utm_source=mail", offsetSeconds = 20)
            insert("page-b", "page-b", "Impression", "user-a", "install-a", "session-a",
                "Mozilla/5.0", "page", "/article?utm_campaign=social", offsetSeconds = 30)
            insert("legacy-page", null, "Impression", null, "install-c", "session-c",
                "Mozilla/5.0", "page", "chapter:one", offsetSeconds = 400)
            insert("start-d", "start-d", "Session", null, "install-d", "session-d",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
                "session", "session-d", """{"start":"true"}""")
            insert("heartbeat-d", "heartbeat-d", "Heartbeat", null, "install-d", "session-d",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
                "session", "session-d", offsetSeconds = 100)
            insert("crawler-start", "crawler-start", "Session", null, "install-crawler", "session-crawler",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.7390.37 Safari/537.36",
                "session", "session-crawler", """{"start":"true"}""")
            insert("bot-page", "bot-page", "Impression", null, "install-bot", "session-bot",
                "GoogleOther/1.0", "page", "chapter:bot")
        }
    }
}
