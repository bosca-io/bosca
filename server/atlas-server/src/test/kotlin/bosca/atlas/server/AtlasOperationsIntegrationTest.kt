package bosca.atlas.server

import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Verifies scheduler, execution history and package actions through the production composition. */
class AtlasOperationsIntegrationTest {
    private fun JsonElement.at(vararg keys: String) = keys.fold(this) { value, key -> value.jsonObject.getValue(key) }
    private fun args(vararg values: Any) = values.map { value -> when (value) { is Boolean -> JsonPrimitive(value); is Number -> JsonPrimitive(value); else -> JsonPrimitive(value.toString()) } }
    private fun login(fixture: AtlasProcessFixture, api: String, identifier: String = "admin", password: String = "atlas-admin-password") = fixture.graphql(api, """mutation { security { login { password(identifier: "$identifier", password: "$password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
    private fun page(fixture: AtlasProcessFixture, origin: String, path: String, token: String): String {
        val response = fixture.request(origin, path, token = token)
        assertEquals(200, response.statusCode(), response.body() + "\n" + fixture.logs())
        return response.body()
    }
    private fun invoke(fixture: AtlasProcessFixture, origin: String, path: String, prefix: String, method: String, args: List<JsonElement>, token: String, caller: String = token, initial: JsonObject? = null): JsonObject {
        val marker = Regex("<script type=\"application/json\" data-bml-state-key=\"($prefix.model[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(page(fixture, origin, path, token)) ?: error("Missing $prefix state")
        val response = fixture.request(origin, "/_bml/action", buildJsonObject {
            put("page", if (prefix == "system-schedule") "/system/scheduler/{id}" else path.substringBefore('?')); put("path", path.substringBefore('?')); put("stateKey", marker.groupValues[1]); put("state", initial?.toString() ?: marker.groupValues[2]); put("method", method); put("args", JsonArray(args))
        }.toString(), caller)
        assertEquals(200, response.statusCode(), response.body())
        return Json.parseToJsonElement(Json.parseToJsonElement(response.body()).at("state").jsonPrimitive.content).jsonObject
    }
    private fun ok(state: JsonObject) = assertFalse(state.at("feedback", "failed").jsonPrimitive.boolean, state.toString())
    private fun reader(fixture: AtlasProcessFixture, api: String): String {
        fixture.graphql(api, """mutation { security { signup { passwordV2(identifier: "operations-reader@example.test", password: "Reader-test-password", profile: {name: "Operations Reader", visibility: PUBLIC, attributes: []}) { principal { id } } } } }""")
        return login(fixture, api, "operations-reader@example.test", "Reader-test-password")
    }

    @Test fun `manual and timed schedules preserve fields history authorization principal state and worker execution`() {
        AtlasProcessFixture().use { fixture ->
            val (api, ui) = fixture.start("operations", mapOf("ATLAS_RUNNER_ENABLED" to "false"))
            val standalone = fixture.startUi(api, fixture.newOrigin())
            val admin = login(fixture, api); val reader = reader(fixture, api)
            val adminId = fixture.graphql(api, "{ security { principals { current { id } } } }", admin).at("security", "principals", "current", "id").jsonPrimitive.content
            fun job(id: String) = fixture.graphql(api, """{ scheduler { job(id: "$id") { id name jobParameters cronExpression enabled allowConcurrent catchUp maxCatchUp principalState executionPrincipalId nextRunAt history(limit: 100) { id status } historyCount } } }""", admin).at("scheduler", "job")
            val schedules = mutableListOf<String>()
            for ((index, origin) in listOf(ui, standalone).withIndex()) {
                val path = "/system/scheduler/new"
                assertEquals(302, fixture.request(origin, path).statusCode())
                assertTrue("Administrator access is required" in page(fixture, origin, path, reader))
                val fields = args("API operations $index", "Manual fixture", "purge-job-history", "{\"retentionDays\":41}", "", true, true, true, "3")
                assertTrue(invoke(fixture, origin, path, "system-schedule", "save", fields, admin, reader).at("feedback", "failed").jsonPrimitive.boolean)
                val created = invoke(fixture, origin, path, "system-schedule", "save", fields, admin); ok(created)
                val id = created.at("feedback", "nextHref").jsonPrimitive.content.substringAfterLast('/')
                schedules += id
                val manual = job(id)
                assertEquals(JsonNull, manual.at("cronExpression")); assertEquals(JsonNull, manual.at("nextRunAt"))
                assertTrue(manual.at("enabled").jsonPrimitive.boolean)
                assertTrue(manual.at("allowConcurrent").jsonPrimitive.boolean)
                assertEquals(3, manual.at("maxCatchUp").jsonPrimitive.int)
                val detail = "/system/scheduler/$id"
                val preview = invoke(fixture, origin, detail, "system-schedule", "inspect", args("Unsaved", "Description", "purge-job-history", "{\"retentionDays\":43}", "0 2 * * *", false, true, true, "4", true), admin)
                ok(preview); assertEquals(5, preview.at("cronCheck", "nextRuns").jsonArray.size)
                assertEquals("API operations $index", job(id).at("name").jsonPrimitive.content)
                ok(invoke(fixture, origin, detail, "system-schedule", "save", args("Timed fixture $index", "Timed", "purge-job-history", "{\"retentionDays\":43}", "0 2 * * *", false, true, true, "4"), admin))
                assertNotEquals(JsonNull, job(id).at("nextRunAt"))
                ok(invoke(fixture, origin, detail, "system-schedule", "save", fields, admin))
                assertEquals(JsonNull, job(id).at("nextRunAt"))
                assertTrue(invoke(fixture, origin, detail, "system-schedule", "action", args("trigger", true), admin, reader).at("feedback", "failed").jsonPrimitive.boolean)
                val triggered = invoke(fixture, origin, detail, "system-schedule", "action", args("trigger", true), admin); ok(triggered)
                val historyId = triggered.at("history").jsonArray.single().at("id").jsonPrimitive.content
                assertEquals("PENDING", job(id).at("history").jsonArray.single().at("status").jsonPrimitive.content)
                assertTrue(invoke(fixture, origin, detail, "system-schedule", "cancel", args(historyId, true), admin, reader).at("feedback", "failed").jsonPrimitive.boolean)
                ok(invoke(fixture, origin, detail, "system-schedule", "cancel", args(historyId, true), admin))
                assertEquals("CANCELLED", job(id).at("history").jsonArray.single().at("status").jsonPrimitive.content)
                for (iteration in 1..26) fixture.graphql(api, """mutation { scheduler { trigger(id: "$id") { id } } }""", admin)
                val secondPage = invoke(fixture, origin, detail, "system-schedule", "loadHistory", args("PENDING", "SCHEDULER", 25), admin)
                ok(secondPage); assertEquals(26, secondPage.at("historyCount").jsonPrimitive.int); assertEquals(1, secondPage.at("history").jsonArray.size)
                assertTrue(invoke(fixture, origin, detail, "system-schedule", "action", args("delete", true), admin, reader).at("feedback", "failed").jsonPrimitive.boolean)
            }
            val required = fixture.graphql(api, """mutation { scheduler { create(input: {name: "Principal fixture", jobName: "purge-job-history", jobParameters: {}, requiresPrincipal: true}) { id principalState enabled } } }""", admin).at("scheduler", "create")
            val requiredId = required.at("id").jsonPrimitive.content
            assertEquals("NEEDS_PRINCIPAL", required.at("principalState").jsonPrimitive.content)
            assertFalse(required.at("enabled").jsonPrimitive.boolean)
            val principalPath = "/system/scheduler/$requiredId"
            ok(invoke(fixture, ui, principalPath, "system-schedule", "assign", args(adminId, false, true), admin))
            assertEquals("ACTIVE", job(requiredId).at("principalState").jsonPrimitive.content)
            ok(invoke(fixture, standalone, principalPath, "system-schedule", "assign", args("", true, true), admin))
            assertEquals("NEEDS_PRINCIPAL", job(requiredId).at("principalState").jsonPrimitive.content)
            if (System.getProperty("atlas.operations.browser") == "true") browser(fixture, "scheduler", ui, standalone, mapOf("ATLAS_SCHEDULE_FIXTURE_ID" to schedules.first(), "ATLAS_PRINCIPAL_SCHEDULE_ID" to requiredId, "ATLAS_OPERATIONS_ADMIN_ID" to adminId))
            fixture.start("operations-worker", mapOf("ATLAS_API_ENABLED" to "false", "ATLAS_GIT_ENABLED" to "false", "ATLAS_ARTIFACTS_ENABLED" to "false", "ATLAS_UI_ENABLED" to "false", "ATLAS_RUNNER_QUEUES" to "common", "ATLAS_RUNNER_LISTENERS_ENABLED" to "false", "ATLAS_SCHEDULER_ENABLED" to "false"))
            fixture.await("manual maintenance execution") { schedules.all { id -> job(id).at("history").jsonArray.count { it.at("status").jsonPrimitive.content == "COMPLETED" } >= 26 } }
            for (id in schedules + requiredId) {
                ok(invoke(fixture, standalone, "/system/scheduler/$id", "system-schedule", "action", args("delete", true), admin))
                assertEquals(JsonNull, job(id))
            }
        }
    }

    @Test fun `packages use real installers authoritative status confirmations and denied replay in both hosts`() {
        AtlasProcessFixture().use { fixture ->
            val (api, ui) = fixture.start("package-operations", mapOf("ATLAS_RUNNER_ENABLED" to "false"), excludedPackages = setOf("root-collection-installer", "bible"))
            val standalone = fixture.startUi(api, fixture.newOrigin())
            val admin = login(fixture, api); val reader = reader(fixture, api)
            for (origin in listOf(ui, standalone)) {
                assertEquals(302, fixture.request(origin, "/system/packages").statusCode())
                assertTrue("Administrator access is required" in page(fixture, origin, "/system/packages", reader))
                assertTrue(invoke(fixture, origin, "/system/packages", "system-packages", "install", args("bible", "1.0.0", "", "", true), admin, reader).at("feedback", "failed").jsonPrimitive.boolean)
                assertTrue(invoke(fixture, origin, "/system/packages", "system-packages", "install", args("bible", "1.0.0", "", "", false), admin).at("feedback", "failed").jsonPrimitive.boolean)
            }
            if (System.getProperty("atlas.operations.browser") == "true") browser(fixture, "packages", ui, standalone)
            else {
                ok(invoke(fixture, ui, "/system/packages", "system-packages", "install", args("root-collection-installer", "1.0.0", "root-collection-installer", "1.0.0", true), admin))
                ok(invoke(fixture, standalone, "/system/packages", "system-packages", "install", args("bible", "1.0.0", "", "", true), admin))
            }
            val status = fixture.graphql(api, "{ packages { all { key versions { version installed { name version } } } history { key version } } }", admin).at("packages")
            for (key in listOf("root-collection-installer", "bible")) {
                val pkg = status.at("all").jsonArray.single { it.at("key").jsonPrimitive.content == key }
                assertEquals(1, pkg.at("versions").jsonArray.single().at("installed").jsonArray.size)
            }
            assertTrue(status.at("history").jsonArray.any { it.at("key").jsonPrimitive.content == "bible" })
            val already = invoke(fixture, ui, "/system/packages", "system-packages", "install", args("root-collection-installer", "1.0.0", "root-collection-installer", "1.0.0", true), admin)
            ok(already); assertEquals("Installer already installed.", already.at("feedback", "message").jsonPrimitive.content)
        }
    }

    private fun browser(fixture: AtlasProcessFixture, kind: String, ui: String, standalone: String, extra: Map<String, String> = emptyMap()) {
        val project = File(requireNotNull(System.getProperty("workspaceRoot")), "admin-support/system-ui")
        val log = File(fixture.directory, "$kind-browser.log")
        val process = ProcessBuilder("node", "src/test/browser/operations.mjs").directory(project).apply {
            environment().putAll(mapOf("ATLAS_SYSTEM_TEST_URL" to ui, "ATLAS_OPERATIONS_STANDALONE_URL" to standalone, "ATLAS_SYSTEM_TEST_PASSWORD" to "atlas-admin-password", "ATLAS_OPERATIONS_KIND" to kind) + extra)
        }.redirectErrorStream(true).redirectOutput(log).start()
        try {
            assertTrue(process.waitFor(240, TimeUnit.SECONDS), "Operations browser checks timed out: ${log.readText()}")
            assertEquals(0, process.exitValue(), log.readText() + "\n" + fixture.logs())
        } finally {
            if (process.isAlive) { process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly() }
        }
    }
}
