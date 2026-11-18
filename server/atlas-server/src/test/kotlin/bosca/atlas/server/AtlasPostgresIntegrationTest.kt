package bosca.atlas.server

import kotlinx.serialization.json.*
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Dedicated pgvector server preloads statement statistics for read/maintenance acceptance. */
class AtlasPostgresIntegrationTest {
    @Test fun `PostgreSQL diagnostics details and maintenance work in both Atlas hosts`() {
        PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres")).apply {
            withDatabaseName("atlas_postgres"); withUsername("atlas_test"); withPassword("atlas_test")
            withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements")
        }.use { database ->
            database.start()
            AtlasProcessFixture(database.jdbcUrl, database.username, database.password).use { fixture ->
                fixture.databaseConnection().use { connection -> connection.createStatement().use { statement ->
                    statement.execute("create extension if not exists pg_stat_statements")
                    statement.execute("create table public.system_ui_console_probe(id int primary key, value text not null)")
                    statement.execute("insert into public.system_ui_console_probe values (1, 'diagnostic fixture'), (2, 'other row')")
                } }
                val (api, ui) = fixture.start("postgres", mapOf("ATLAS_RUNNER_ENABLED" to "false"))
                val standalone = fixture.startUi(api, fixture.newOrigin())
                fun JsonElement.at(vararg keys: String) = keys.fold(this) { value, key -> value.jsonObject.getValue(key) }
                fun login(identifier: String, password: String) = fixture.graphql(api, """mutation { security { login { password(identifier:"$identifier",password:"$password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
                val admin = login("admin", "atlas-admin-password")
                fixture.graphql(api, """mutation { security { signup { passwordV2(identifier:"postgres-reader@example.test",password:"Reader-test-password",profile:{name:"PostgreSQL Reader",visibility:PUBLIC,attributes:[]}) { principal { id } } } } }""")
                val reader = login("postgres-reader@example.test", "Reader-test-password")
                fun page(origin: String, caller: String = admin) = fixture.request(origin, "/system/postgres", token = caller).also { assertEquals(200, it.statusCode(), it.body() + fixture.logs()) }.body()
                fun invoke(origin: String, method: String, args: List<JsonElement>, caller: String = admin, previous: JsonObject? = null): JsonObject {
                    val marker = Regex("<script type=\"application/json\" data-bml-state-key=\"(system-postgres.model[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(page(origin)) ?: error("Missing PostgreSQL state")
                    val response = fixture.request(origin, "/_bml/action", buildJsonObject {
                        put("page", "/system/postgres"); put("path", "/system/postgres"); put("stateKey", marker.groupValues[1]); put("state", previous?.toString() ?: marker.groupValues[2]); put("method", method); put("args", JsonArray(args))
                    }.toString(), caller)
                    assertEquals(200, response.statusCode(), response.body())
                    return Json.parseToJsonElement(Json.parseToJsonElement(response.body()).at("state").jsonPrimitive.content).jsonObject
                }
                fun args(vararg values: Any) = values.map { if (it is Boolean) JsonPrimitive(it) else JsonPrimitive(it.toString()) }
                fun ok(state: JsonObject) = assertFalse(state.at("feedback", "failed").jsonPrimitive.boolean, "$state\n${fixture.logs()}")
                fun active(pid: Int): Boolean = fixture.graphql(api, "{ postgresAdmin { activeQueries { pid state query } } }", admin).at("postgresAdmin", "activeQueries").jsonArray.any { it.at("pid").jsonPrimitive.int == pid && it.at("state").jsonPrimitive.content == "active" && "pg_sleep" in it.at("query").jsonPrimitive.content }
                fun resetTime(): Instant = fixture.databaseConnection().use { connection -> connection.createStatement().use { statement -> statement.executeQuery("select stats_reset from pg_stat_statements_info").use { result -> check(result.next()); result.getTimestamp(1).toInstant() } } }
                for (origin in listOf(ui, standalone)) {
                    assertEquals(302, fixture.request(origin, "/system/postgres").statusCode())
                    assertTrue("Administrator access is required" in page(origin, reader))
                    assertTrue("PostgreSQL diagnostics" in page(origin))
                    val tables = invoke(origin, "loadTables", args("public")); ok(tables)
                    assertTrue(tables.at("tables").jsonArray.any { it.at("tableName").jsonPrimitive.content == "system_ui_console_probe" })
                    assertTrue(invoke(origin, "loadTables", args("public"), reader).at("feedback", "failed").jsonPrimitive.boolean)
                    val detail = invoke(origin, "openTable", args("public", "system_ui_console_probe"), previous = tables)
                    assertEquals("system_ui_console_probe", detail.at("tableSelectionName").jsonPrimitive.content)
                    assertTrue(invoke(origin, "analyzeTable", args("public", "system_ui_console_probe", false), previous = detail).at("feedback", "failed").jsonPrimitive.boolean)
                    assertTrue(invoke(origin, "analyzeTable", args("public", "system_ui_console_probe", true), reader, detail).at("feedback", "failed").jsonPrimitive.boolean)
                    val analyzed = invoke(origin, "analyzeTable", args("public", "system_ui_console_probe", true), previous = detail); ok(analyzed)
                    assertTrue(analyzed.at("tables").jsonArray.single { it.at("tableName").jsonPrimitive.content == "system_ui_console_probe" }.at("lastAnalyze") is JsonPrimitive)
                    ok(invoke(origin, "loadBloated", args("5"))); ok(invoke(origin, "loadIO", args("public")))
                    val indexes = invoke(origin, "loadIndexes", args("public")); ok(indexes)
                    assertTrue(indexes.at("indexes").jsonArray.any { it.at("tableName").jsonPrimitive.content == "system_ui_console_probe" })
                    for (tab in listOf("Overview", "Cluster", "Tables", "Indexes", "Queries", "Locks", "Settings")) ok(invoke(origin, "chooseTab", args(tab)))
                    for (order in listOf("total", "mean")) { val queries = invoke(origin, "loadQueries", args("30", order)); ok(queries); assertTrue(queries.at("slowQueries").jsonArray.isNotEmpty()) }
                    val settings = invoke(origin, "loadSettings", args("max_connections")); ok(settings)
                    assertTrue(settings.at("settings").jsonArray.any { it.at("name").jsonPrimitive.content == "max_connections" })
                    AtlasPostgresSession(fixture.databaseConnection()).use { session ->
                        fixture.await("owned cancel query is active") { active(session.pid) }
                        assertTrue(invoke(origin, "cancelQuery", args(session.pid, true), reader).at("feedback", "failed").jsonPrimitive.boolean)
                        assertTrue(active(session.pid)); ok(invoke(origin, "cancelQuery", args(session.pid, true)))
                        assertEquals("57014", session.result())
                    }
                    AtlasPostgresSession(fixture.databaseConnection()).use { session ->
                        fixture.await("owned terminate query is active") { active(session.pid) }
                        ok(invoke(origin, "terminateBackend", args(session.pid, true)))
                        assertTrue(session.result() in setOf("57P01", "08006"))
                    }
                    val before = resetTime()
                    assertTrue(invoke(origin, "resetStatements", args(true), reader).at("feedback", "failed").jsonPrimitive.boolean)
                    ok(invoke(origin, "resetStatements", args(true))); assertTrue(resetTime().isAfter(before))
                }
                if (System.getProperty("atlas.postgres.browser") == "true") {
                    val sessions = List(4) { AtlasPostgresSession(fixture.databaseConnection()) }
                    try {
                        for (session in sessions) fixture.await("browser-owned query ${session.pid} is active") { active(session.pid) }
                        val log = File(fixture.directory, "postgres-browser.log")
                        val browser = ProcessBuilder("node", "src/test/browser/postgres.mjs").directory(File(requireNotNull(System.getProperty("workspaceRoot")), "admin-support/system-ui")).apply {
                            environment().putAll(mapOf("ATLAS_SYSTEM_TEST_URL" to ui, "ATLAS_POSTGRES_STANDALONE_URL" to standalone, "ATLAS_SYSTEM_TEST_PASSWORD" to "atlas-admin-password", "ATLAS_POSTGRES_PROBE_PIDS" to sessions.joinToString(",") { it.pid.toString() }))
                        }.redirectErrorStream(true).redirectOutput(log).start()
                        try { assertTrue(browser.waitFor(240, TimeUnit.SECONDS), "PostgreSQL browser timed out"); assertEquals(0, browser.exitValue(), log.readText() + fixture.logs()) }
                        finally { if (browser.isAlive) { browser.destroy(); if (!browser.waitFor(5, TimeUnit.SECONDS)) browser.destroyForcibly() } }
                        assertEquals("57014", sessions[0].result()); assertEquals("57014", sessions[2].result())
                        for (index in listOf(1, 3)) assertTrue(sessions[index].result() in setOf("57P01", "08006"))
                    } finally { sessions.forEach(AutoCloseable::close) }
                }
            }
        }
    }
}
