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

/** Runs the seeded metrics against Trino rows with sign-in, duplicate delivery, and bot agents. */
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

                    connection.createStatement().use { statement ->
                        statement.executeQuery(execute("daily-active-users")).use { rows ->
                            assertTrue(rows.next())
                            assertEquals(2L, rows.getLong("value"))
                        }
                        statement.executeQuery(execute("unique-sessions-per-day")).use { rows ->
                            assertTrue(rows.next())
                            assertEquals(3L, rows.getLong("value"))
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
                    id varchar, client_id varchar, type varchar, created timestamp,
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
            ) {
                val client = clientId?.let { "'$it'" } ?: "cast(null as varchar)"
                val user = userId?.let { "'$it'" } ?: "cast(null as varchar)"
                statement.execute("""
                    insert into memory.default.events values (
                        '$id', $client, '$type', localtimestamp,
                        row($user, row('$installationId'), '$sessionId', row('$agent')),
                        row('$elementId', '$elementType', '$extras')
                    )
                """.trimIndent())
            }
            insert("start-a", "start-a", "Session", null, "install-a", "session-a",
                "Mozilla/5.0", "session", "session-a", """{"start":"true"}""")
            insert("auth-a", "auth-a", "Interaction", "user-a", "install-a", "session-a",
                "Mozilla/5.0", "button", "sign-in")
            insert("start-b", "start-b", "Session", "user-a", "install-a", "session-b",
                "Mozilla/5.0", "session", "session-b", """{"start":"true"}""")
            insert("start-c", "start-c", "Session", null, "install-c", "session-c",
                "Mozilla/5.0", "session", "session-c", """{"start":"true"}""")
            insert("bot-start", "bot-start", "Session", null, "install-bot", "session-bot",
                "GoogleOther/1.0", "session", "session-bot", """{"start":"true"}""")
            insert("non-start", "non-start", "Session", null, "install-other", "session-other",
                "Mozilla/5.0", "session", "session-other", """{"start":"false"}""")
            insert("page-a", "page-a", "Impression", "user-a", "install-a", "session-a",
                "Mozilla/5.0", "page", "/article?utm_source=mail")
            insert("page-a-retry", "page-a", "Impression", "user-a", "install-a", "session-a",
                "Mozilla/5.0", "page", "/article?utm_source=mail")
            insert("page-b", "page-b", "Impression", "user-a", "install-a", "session-a",
                "Mozilla/5.0", "page", "/article?utm_campaign=social")
            insert("legacy-page", null, "Impression", null, "install-c", "session-c",
                "Mozilla/5.0", "page", "chapter:one")
            insert("bot-page", "bot-page", "Impression", null, "install-bot", "session-bot",
                "GoogleOther/1.0", "page", "chapter:bot")
        }
    }
}
