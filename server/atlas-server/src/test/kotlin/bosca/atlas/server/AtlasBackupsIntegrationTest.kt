package bosca.atlas.server

import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.test.*

/** Exercises backup UI actions against production hosts and the dedicated durable worker. */
class AtlasBackupsIntegrationTest {
    @Test fun `both hosts preserve backup options authorization and asynchronous execution`() {
        AtlasProcessFixture().use { fixture ->
            val (api, ui) = fixture.start("backups", mapOf("ATLAS_RUNNER_ENABLED" to "false"))
            val standalone = fixture.startUi(api, fixture.newOrigin())
            fun JsonElement.at(vararg keys: String) = keys.fold(this) { value, key -> value.jsonObject.getValue(key) }
            fun login(identifier: String, password: String) = fixture.graphql(api, """mutation { security { login { password(identifier: "$identifier", password: "$password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
            val admin = login("admin", "atlas-admin-password")
            fixture.graphql(api, """mutation { security { signup { passwordV2(identifier: "backup-reader@example.test", password: "Reader-test-password", profile: {name: "Backup Reader", visibility: PUBLIC, attributes: []}) { principal { id } } } } }""")
            val reader = login("backup-reader@example.test", "Reader-test-password")
            val sourceFile = fixture.seedBackupFile("Backup binary fixture")
            fun saveConfiguration(value: String) = fixture.graphql(api, """mutation { configurations { setConfiguration(configuration: {key: "backup.fixture", description: "Restore conflict fixture", value: "$value", public: false}) { id } } }""", admin)
            fun configurationValue(): String {
                fixture.graphql(api, "mutation { clearCache }", admin)
                return fixture.graphql(api, """{ configurations { configuration(key: "backup.fixture") { value } } }""", admin).at("configurations", "configuration", "value").jsonPrimitive.content
            }
            saveConfiguration("original")
            fun page(origin: String, caller: String = admin): String {
                val response = fixture.request(origin, "/system/backups", token = caller)
                assertEquals(200, response.statusCode(), response.body() + "\n" + fixture.logs())
                return response.body()
            }
            fun invoke(origin: String, method: String, args: List<JsonElement>, caller: String = admin): JsonObject {
                val marker = Regex("<script type=\"application/json\" data-bml-state-key=\"(system-backups.model[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(page(origin)) ?: error("Missing backups state")
                val response = fixture.request(origin, "/_bml/action", buildJsonObject {
                    put("page", "/system/backups"); put("path", "/system/backups"); put("stateKey", marker.groupValues[1]); put("state", marker.groupValues[2]); put("method", method); put("args", JsonArray(args))
                }.toString(), caller)
                assertEquals(200, response.statusCode(), response.body())
                return Json.parseToJsonElement(Json.parseToJsonElement(response.body()).at("state").jsonPrimitive.content).jsonObject
            }
            fun records() = fixture.graphql(api, "{ backups { list { id status error path includeFiles metadataId } } }", admin).at("backups", "list").jsonArray
            fun ok(state: JsonObject) = assertFalse(state.at("feedback", "failed").jsonPrimitive.boolean, "$state\n${fixture.logs()}")
            for (origin in listOf(ui, standalone)) {
                assertEquals(302, fixture.request(origin, "/system/backups").statusCode())
                assertTrue("No backups." in page(origin))
                assertTrue("Administrator access is required" in page(origin, reader))
                assertTrue(invoke(origin, "create", listOf(JsonPrimitive(false)), reader).at("feedback", "failed").jsonPrimitive.boolean)
            }
            assertTrue(records().isEmpty())
            ok(invoke(ui, "create", listOf(JsonPrimitive(false))))
            val backup = records().single()
            val id = backup.at("id").jsonPrimitive.content
            assertEquals("pending", backup.at("status").jsonPrimitive.content)
            assertFalse(backup.at("includeFiles").jsonPrimitive.boolean)
            assertTrue(id.take(8) in page(standalone))
            assertTrue(invoke(standalone, "restore", listOf(JsonPrimitive(id), JsonPrimitive("SKIP"), JsonPrimitive(true))).at("feedback", "failed").jsonPrimitive.boolean)
            for (origin in listOf(ui, standalone)) {
                assertTrue(invoke(origin, "restore", listOf(JsonPrimitive(id), JsonPrimitive("OVERWRITE"), JsonPrimitive(true)), reader).at("feedback", "failed").jsonPrimitive.boolean)
                assertTrue(invoke(origin, "delete", listOf(JsonPrimitive(id), JsonPrimitive(true)), reader).at("feedback", "failed").jsonPrimitive.boolean)
                assertTrue(invoke(origin, "delete", listOf(JsonPrimitive(id), JsonPrimitive(false))).at("feedback", "failed").jsonPrimitive.boolean)
            }
            fixture.start("backup-worker", mapOf(
                "ATLAS_API_ENABLED" to "false", "ATLAS_GIT_ENABLED" to "false", "ATLAS_ARTIFACTS_ENABLED" to "false",
                "ATLAS_UI_ENABLED" to "false", "ATLAS_RUNNER_QUEUES" to "backup", "ATLAS_RUNNER_LISTENERS_ENABLED" to "false", "ATLAS_SCHEDULER_ENABLED" to "false",
            ))
            fixture.await("backup completion") { records().single().at("status").jsonPrimitive.content in setOf("completed", "failed") }
            val completed = records().single()
            assertEquals("completed", completed.at("status").jsonPrimitive.content, "$completed\n${fixture.logs()}")
            assertNotEquals(JsonNull, completed.at("path"))
            assertNotEquals(JsonNull, completed.at("metadataId"))
            ZipFile(File(fixture.directory, "storage/${completed.at("path").jsonPrimitive.content}")).use { archive ->
                assertNotNull(archive.getEntry("manifest.json"))
                assertNotNull(archive.getEntry("data/metadata.jsonl"))
                assertTrue(archive.entries().asSequence().none { it.name.startsWith("files/") })
                val manifest = Json.parseToJsonElement(archive.getInputStream(archive.getEntry("manifest.json")).bufferedReader().use { it.readText() })
                assertTrue("metadata" in manifest.at("counts").jsonObject)
                assertFalse("prayers" in manifest.at("counts").jsonObject)
            }
            ok(invoke(standalone, "create", listOf(JsonPrimitive(true))))
            var withFiles: JsonElement? = null
            fixture.await("file-inclusive backup completion") {
                withFiles = records().firstOrNull { it.at("id").jsonPrimitive.content != id && it.at("status").jsonPrimitive.content in setOf("completed", "failed") }
                withFiles != null
            }
            val fileBackup = requireNotNull(withFiles)
            assertEquals("completed", fileBackup.at("status").jsonPrimitive.content, "$fileBackup\n${fixture.logs()}")
            val fileBackupPath = File(fixture.directory, "storage/${fileBackup.at("path").jsonPrimitive.content}")
            ZipFile(fileBackupPath).use { archive ->
                val entry = requireNotNull(archive.getEntry("files/${sourceFile.relativeTo(File(fixture.directory, "storage")).invariantSeparatorsPath}"))
                assertEquals("Backup binary fixture", archive.getInputStream(entry).bufferedReader().use { it.readText() })
            }
            saveConfiguration("changed")
            fun restoreHistory() = fixture.graphql(api, "{ scheduler { history(offset: 0, limit: 100) { id name status definition errorMessage } } }", admin).at("scheduler", "history").jsonArray.filter { it.at("name").jsonPrimitive.content == "restore-system" }
            for ((index, strategy) in listOf("SKIP", "OVERWRITE", "FAIL").withIndex()) {
                val previous = restoreHistory().map { it.at("id") }.toSet()
                ok(invoke(if (index % 2 == 0) ui else standalone, "restore", listOf(JsonPrimitive(id), JsonPrimitive(strategy), JsonPrimitive(true))))
                var execution: JsonElement? = null
                fixture.await("$strategy restore execution") {
                    execution = restoreHistory().firstOrNull { it.at("id") !in previous && it.at("status").jsonPrimitive.content in setOf("COMPLETED", "FAILED") }
                    execution != null
                }
                val result = requireNotNull(execution)
                assertEquals(strategy, result.at("definition").jsonObject["conflictStrategy"]?.jsonPrimitive?.content ?: "SKIP")
                assertEquals(if (strategy == "FAIL") "FAILED" else "COMPLETED", result.at("status").jsonPrimitive.content, "$result\n${fixture.logs()}")
                assertEquals(if (strategy == "SKIP") "changed" else "original", configurationValue())
            }
            if (System.getProperty("atlas.backups.browser") == "true") {
                val project = File(requireNotNull(System.getProperty("workspaceRoot")), "admin-support/system-ui")
                val log = File(fixture.directory, "backups-browser.log")
                val browser = ProcessBuilder("node", "src/test/browser/backups.mjs").directory(project).apply {
                    environment().putAll(mapOf("ATLAS_SYSTEM_TEST_URL" to ui, "ATLAS_BACKUPS_STANDALONE_URL" to standalone, "ATLAS_SYSTEM_TEST_PASSWORD" to "atlas-admin-password"))
                }.redirectErrorStream(true).redirectOutput(log).start()
                try {
                    assertTrue(browser.waitFor(180, TimeUnit.SECONDS), "Backup browser checks timed out: ${log.readText()}")
                    assertEquals(0, browser.exitValue(), log.readText() + "\n" + fixture.logs())
                } finally {
                    if (browser.isAlive) { browser.destroy(); if (!browser.waitFor(5, TimeUnit.SECONDS)) browser.destroyForcibly() }
                }
            }
            ok(invoke(standalone, "delete", listOf(JsonPrimitive(id), JsonPrimitive(true))))
            ok(invoke(ui, "delete", listOf(JsonPrimitive(fileBackup.at("id").jsonPrimitive.content), JsonPrimitive(true))))
            assertFalse(fileBackupPath.exists())
            assertTrue(records().isEmpty())
        }
    }
}
