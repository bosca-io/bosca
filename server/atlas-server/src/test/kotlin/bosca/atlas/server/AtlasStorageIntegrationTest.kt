package bosca.atlas.server

import kotlinx.serialization.json.*
import kotlin.test.*

/** Real storage/model persistence, indexing-preview wiring and index-worker execution. */
class AtlasStorageIntegrationTest {
    @Test fun `System storage and search use the composed services and execute reindex jobs`() {
        AtlasProcessFixture().use { fixture ->
            val (api, ui) = fixture.start("storage", mapOf("ATLAS_RUNNER_QUEUES" to "index", "ATLAS_RUNNER_LISTENERS_ENABLED" to "false", "ATLAS_SCHEDULER_ENABLED" to "false"))
            val standalone = fixture.startUi(api, fixture.newOrigin())
            fun JsonElement.at(vararg keys: String) = keys.fold(this) { value, key -> value.jsonObject.getValue(key) }
            fun login(identifier: String, password: String) = fixture.graphql(api, """mutation { security { login { password(identifier: "$identifier", password: "$password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
            val admin = login("admin", "atlas-admin-password")
            val packages = fixture.graphql(api, "{ packages { all { key } history { key } } }", admin).at("packages")
            assertTrue(packages.at("all").jsonArray.any { it.at("key").jsonPrimitive.content == "ai" })
            assertFalse(packages.at("history").jsonArray.any {
                val key = it.at("key").jsonPrimitive.content
                key == "ai" || key.startsWith("ai:")
            }, "Registering AI services must not install packages outside the configured list")
            fixture.graphql(api, """mutation { security { signup { passwordV2(identifier: "storage-reader@example.test", password: "Reader-test-password", profile: {name: "Storage Reader", visibility: PUBLIC, attributes: []}) { principal { id } } } } }""")
            val reader = login("storage-reader@example.test", "Reader-test-password")
            val metadataId = fixture.seedSearchMetadata()
            val modelId = fixture.graphql(api, """mutation { models { add(model: {key: "storage-fixture", type: "test", name: "Storage fixture model", description: "No external calls", configuration: {dimension: 16}}) { id } } }""", admin).at("models", "add", "id").jsonPrimitive.content
            fun page(path: String, origin: String = ui, caller: String = admin): String {
                val response = fixture.request(origin, path, token = caller)
                assertEquals(200, response.statusCode(), response.body() + "\n" + fixture.logs())
                return response.body()
            }
            fun action(path: String, prefix: String, method: String, args: List<JsonElement>, caller: String = admin, state: JsonObject? = null): JsonObject {
                val marker = Regex("<script type=\"application/json\" data-bml-state-key=\"($prefix[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(page(path)) ?: error("Missing $prefix")
                val response = fixture.request(ui, "/_bml/action", buildJsonObject {
                    put("page", path); put("path", path); put("stateKey", marker.groupValues[1]); put("state", state?.toString() ?: marker.groupValues[2]); put("method", method); put("args", JsonArray(args))
                }.toString(), caller)
                assertEquals(200, response.statusCode(), response.body())
                return Json.parseToJsonElement(Json.parseToJsonElement(response.body()).at("state").jsonPrimitive.content).jsonObject
            }
            fun storage(method: String, args: List<JsonElement>, caller: String = admin, state: JsonObject? = null) = action("/system/storage", "system-storage.model", method, args, caller, state)
            fun search(method: String, args: List<JsonElement>, caller: String = admin) = action("/system/search", "system-search.model", method, args, caller)
            fun ok(state: JsonObject) = assertFalse(state.at("feedback", "failed").jsonPrimitive.boolean, "$state\n${fixture.logs()}")
            for (origin in listOf(ui, standalone)) {
                for (path in listOf("/system/storage", "/system/search")) {
                    assertEquals(302, fixture.request(origin, path).statusCode())
                    assertTrue("Bosca Atlas" in page(path, origin))
                }
                assertTrue("Administrator access is required" in page("/system/storage", origin, reader))
            }
            val modelValues = """[{"key":"association","modelId":"$modelId","configuration":"{\"dimension\":16}"}]"""
            fun fields(name: String, type: String = "VECTOR") = listOf(JsonPrimitive(name), JsonPrimitive("Storage integration"), JsonPrimitive(type), JsonPrimitive("{\"purpose\":\"integration\"}"), JsonPrimitive(modelValues))
            val denied = storage("save", fields("Forbidden storage"), reader)
            assertTrue(denied.at("feedback", "failed").jsonPrimitive.boolean)
            ok(storage("save", fields("Storage integration")))
            val system = fixture.graphql(api, "{ storageSystems { all { id name type configuration models { modelId configuration model { id name } } } } }", admin).at("storageSystems", "all").jsonArray.single { it.at("name").jsonPrimitive.content == "Storage integration" }
            val systemId = system.at("id").jsonPrimitive.content
            assertEquals(modelId, system.at("models").jsonArray.single().at("modelId").jsonPrimitive.content)
            assertEquals("Storage fixture model", system.at("models").jsonArray.single().at("model", "name").jsonPrimitive.content)
            assertEquals(16, system.at("models").jsonArray.single().at("configuration", "dimension").jsonPrimitive.int)
            fixture.graphql(api, """mutation { packages { install(key: "search-index", version: "1.0.6") } }""", admin)
            val editing = storage("edit", listOf(JsonPrimitive(systemId)))
            ok(editing)
            ok(storage("save", fields("Storage edited", "SUPPLEMENTARY"), state = editing))
            assertTrue("Storage edited" in page("/system/storage", standalone))
            val preview = search("preview", listOf(JsonPrimitive(metadataId)))
            ok(preview)
            assertEquals("Search preview fixture", preview.at("preview", "metadata", "name").jsonPrimitive.content)
            assertTrue(search("preview", listOf(JsonPrimitive(metadataId)), reader).at("feedback", "failed").jsonPrimitive.boolean)
            val configuration = fixture.graphql(api, """{ configurations { configuration(key: "search") { value } } }""", admin).at("configurations", "configuration", "value")
            ok(search("save", listOf(JsonPrimitive(configuration.toString()))))
            val invalid = search("save", listOf(JsonPrimitive("{invalid")))
            assertTrue(invalid.at("feedback", "failed").jsonPrimitive.boolean)
            assertEquals("{invalid", invalid.at("configuration").jsonPrimitive.content)
            // This fixture consumes only the index queue, proving explicit reindex dispatch executes there.
            val reindexArgs = listOf(JsonPrimitive("Admin Search Index"), JsonPrimitive(true), JsonPrimitive(true), JsonPrimitive(false), JsonPrimitive(false), JsonPrimitive(true))
            assertTrue(storage("reindex", reindexArgs, reader).at("feedback", "failed").jsonPrimitive.boolean)
            ok(storage("reindex", reindexArgs))
            val indexUid = fixture.searchIndexUid("administration")
            var lastDocuments = ""
            fixture.await("metadata reindex appears in Meilisearch", observation = { lastDocuments }) {
                val result = fixture.request(api, "/graphql", buildJsonObject { put("query", "{ meilisearchAdmin { index(uid: \"$indexUid\") { documents(offset: 0, limit: 100) } } }") }.toString(), admin)
                lastDocuments = result.body()
                val body = Json.parseToJsonElement(lastDocuments).jsonObject
                body["errors"] == null && body["data"]?.toString()?.contains(metadataId) == true
            }
            ok(search("reindex", listOf(JsonPrimitive(false), JsonPrimitive(true))))
            ok(search("reindex", listOf(JsonPrimitive(true), JsonPrimitive(true))))
            ok(storage("delete", listOf(JsonPrimitive(systemId), JsonPrimitive(true))))
            fixture.graphql(api, """mutation { models { delete(id: "$modelId") } }""", admin)
            assertFalse("Storage edited" in page("/system/storage"))
            assertTrue("Atlas job consumer started: index" in fixture.directory.resolve("storage.log").readText())
        }
    }
}
