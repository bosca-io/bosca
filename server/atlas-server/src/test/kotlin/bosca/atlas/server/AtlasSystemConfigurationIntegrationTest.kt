package bosca.atlas.server

import kotlinx.serialization.json.*
import kotlin.test.*

/** Uses isolated production hosts to verify configuration ACL retention and administrative utilities. */
class AtlasSystemConfigurationIntegrationTest {
    @Test fun `configuration edits preserve ACLs and utility mutations retain caller authorization`() {
        AtlasProcessFixture().use { fixture ->
            val (api, ui) = fixture.start("configuration", mapOf("ATLAS_RUNNER_ENABLED" to "false"))
            val standalone = fixture.startUi(api, fixture.newOrigin())
            fun JsonElement.at(vararg keys: String) = keys.fold(this) { value, key -> value.jsonObject.getValue(key) }
            fun login(identifier: String, password: String) = fixture.graphql(api, """mutation { security { login { password(identifier: "$identifier", password: "$password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
            val admin = login("admin", "atlas-admin-password")
            fixture.graphql(api, """mutation { security { signup { passwordV2(identifier: "configuration-reader@example.test", password: "Reader-test-password", profile: {name: "Configuration Reader", visibility: PUBLIC, attributes: []}) { principal { id } } } } }""")
            var reader = login("configuration-reader@example.test", "Reader-test-password")
            fun page(path: String, origin: String = ui, caller: String = admin): String {
                val response = fixture.request(origin, path, token = caller)
                assertEquals(200, response.statusCode(), response.body() + "\n" + fixture.logs())
                return response.body()
            }
            for (origin in listOf(ui, standalone)) for (path in listOf("/system/config", "/system/utilities")) {
                assertEquals(302, fixture.request(origin, path).statusCode())
                assertTrue("Bosca Atlas" in page(path, origin))
                if (path == "/system/utilities") assertTrue("Administrator access is required" in page(path, origin, reader))
                else assertTrue("No configurations yet" in page(path, origin, reader))
            }
            fun invoke(path: String, prefix: String, method: String, args: List<JsonElement>, caller: String = admin, state: JsonObject? = null): JsonObject {
                val marker = Regex("<script type=\"application/json\" data-bml-state-key=\"($prefix[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(page(path)) ?: error("Missing $prefix")
                val response = fixture.request(ui, "/_bml/action", buildJsonObject {
                    put("page", path); put("path", path); put("stateKey", marker.groupValues[1]); put("state", state?.toString() ?: marker.groupValues[2]); put("method", method); put("args", JsonArray(args))
                }.toString(), caller)
                assertEquals(200, response.statusCode(), response.body())
                return Json.parseToJsonElement(Json.parseToJsonElement(response.body()).at("state").jsonPrimitive.content).jsonObject
            }
            fun config(method: String, args: List<JsonElement>, caller: String = admin, state: JsonObject? = null) = invoke("/system/config", "system-configurations.model", method, args, caller, state)
            fun ok(state: JsonObject) = assertFalse(state.at("feedback", "failed").jsonPrimitive.boolean, "$state\n${fixture.logs()}")
            fun fields(value: String, public: Boolean = false) = listOf(JsonPrimitive("system.fixture"), JsonPrimitive("Configuration fixture"), JsonPrimitive(value), JsonPrimitive(public))
            val createDenied = config("save", listOf(JsonPrimitive("reader.unauthorized"), JsonPrimitive("Unauthorized"), JsonPrimitive("{}"), JsonPrimitive(false)), caller = reader)
            assertTrue(createDenied.at("feedback", "failed").jsonPrimitive.boolean)
            ok(config("save", fields("{\"nested\":[true,3]}")))
            val groupId = fixture.graphql(api, """mutation { security { groups { addGroup(name: "Configuration fixture readers", description: "ACL retention", groupType: PRINCIPAL) { id } } } }""", admin).at("security", "groups", "addGroup", "id").jsonPrimitive.content
            val configurationId = fixture.graphql(api, """{ configurations { configuration(key: "system.fixture") { id } } }""", admin).at("configurations", "configuration", "id").jsonPrimitive.content
            val entry = fixture.graphql(api, """mutation { configurations { setConfiguration(configuration: {key: "system.fixture", description: "With ACL", value: {nested: [true, 3]}, public: false, permissions: [{action: VIEW, entityId: "$configurationId", groupId: "$groupId"}]}) { id } } }""", admin).at("configurations", "setConfiguration")
            val originalId = entry.at("id").jsonPrimitive.content
            val editing = config("edit", listOf(JsonPrimitive("system.fixture")))
            ok(editing)
            val denied = config("save", fields("{\"forged\":true}"), reader, editing)
            assertTrue(denied.at("feedback", "failed").jsonPrimitive.boolean)
            val invalid = config("save", fields("{invalid"), state = editing)
            assertTrue(invalid.at("feedback", "failed").jsonPrimitive.boolean)
            assertEquals("{invalid", invalid.at("draft", "value").jsonPrimitive.content)
            ok(config("save", fields("[1,true,null]", true), state = editing))
            val saved = fixture.graphql(api, """{ configurations { configuration(key: "system.fixture") { id value public permissions { action group { id } } } } }""", admin).at("configurations", "configuration")
            assertEquals(originalId, saved.at("id").jsonPrimitive.content)
            assertEquals(Json.parseToJsonElement("[1,true,null]"), saved.at("value"))
            assertTrue(saved.at("public").jsonPrimitive.boolean)
            assertEquals(groupId, saved.at("permissions").jsonArray.single().at("group", "id").jsonPrimitive.content)
            assertEquals("VIEW", saved.at("permissions").jsonArray.single().at("action").jsonPrimitive.content)
            assertTrue("system.fixture" in page("/system/config", standalone))
            val readerId = fixture.graphql(api, "{ security { principals { current { id } } } }", reader).at("security", "principals", "current", "id").jsonPrimitive.content
            fixture.graphql(api, """mutation { security { addPrincipalGroup(groupId: "$groupId", principalId: "$readerId") } }""", admin)
            fixture.graphql(api, """mutation { configurations { setConfiguration(configuration: {key: "system.fixture", description: "Delegated configuration", value: [1, true, null], public: false, permissions: [{action: VIEW, entityId: "$configurationId", groupId: "$groupId"}, {action: LIST, entityId: "$configurationId", groupId: "$groupId"}, {action: EDIT, entityId: "$configurationId", groupId: "$groupId"}]}) { id } } }""", admin)
            reader = login("configuration-reader@example.test", "Reader-test-password")
            assertTrue("system.fixture" in page("/system/config", caller = reader))
            val delegatedEdit = config("edit", listOf(JsonPrimitive("system.fixture")), caller = reader)
            ok(delegatedEdit)
            ok(config("save", fields("[2,false]"), caller = reader, state = delegatedEdit))
            val delegated = fixture.graphql(api, """{ configurations { configuration(key: "system.fixture") { value permissions { action } } } }""", admin).at("configurations", "configuration")
            assertEquals(Json.parseToJsonElement("[2,false]"), delegated.at("value"))
            assertEquals(setOf("VIEW", "LIST", "EDIT"), delegated.at("permissions").jsonArray.map { it.at("action").jsonPrimitive.content }.toSet())
            val searchId = fixture.graphql(api, """{ configurations { configuration(key: "search") { id } } }""", admin).at("configurations", "configuration", "id").jsonPrimitive.content
            fixture.graphql(api, """mutation { configurations { setConfiguration(configuration: {key: "search", description: "Delegated search", value: {}, public: false, permissions: [{action: VIEW, entityId: "$searchId", groupId: "$groupId"}, {action: EDIT, entityId: "$searchId", groupId: "$groupId"}]}) { id } } }""", admin)
            assertTrue("Search configuration" in page("/system/search", caller = reader))
            ok(invoke("/system/search", "system-search.model", "save", listOf(JsonPrimitive("{\"expressions\":{}}")), caller = reader))
            val searchPermissions = fixture.graphql(api, """{ configurations { configuration(key: "search") { permissions { action } value } } }""", admin).at("configurations", "configuration")
            assertEquals(setOf("VIEW", "EDIT"), searchPermissions.at("permissions").jsonArray.map { it.at("action").jsonPrimitive.content }.toSet())
            assertEquals(Json.parseToJsonElement("{\"expressions\":{}}"), searchPermissions.at("value"))
            val cleared = fixture.graphql(api, """mutation { configurations { setConfiguration(configuration: {key: "search", description: "Search", value: {}, public: false, permissions: []}) { permissions { action } } } }""", admin).at("configurations", "setConfiguration", "permissions").jsonArray
            assertTrue(cleared.isEmpty())
            val filtered = config("filter", listOf(JsonPrimitive("no-such-key")))
            assertEquals("no-such-key", filtered.at("search").jsonPrimitive.content)
            for (operation in listOf("clear-cache", "clear-cdn-cache", "expire-all-jobs", "clear-job-locks")) {
                val args = listOf(JsonPrimitive(operation), JsonPrimitive(true))
                val rejected = invoke("/system/utilities", "system-utilities.model", "execute", args, reader)
                assertTrue(rejected.at("feedback", "failed").jsonPrimitive.boolean)
                val result = invoke("/system/utilities", "system-utilities.model", "execute", args)
                if (operation == "clear-cdn-cache") {
                    assertTrue(result.at("feedback", "failed").jsonPrimitive.boolean)
                    assertTrue("CDN is not configured" in result.at("feedback", "message").jsonPrimitive.content)
                } else ok(result)
            }
            ok(config("delete", listOf(JsonPrimitive("system.fixture"), JsonPrimitive(true))))
            assertEquals(JsonNull, fixture.graphql(api, """{ configurations { configuration(key: "system.fixture") { id } } }""", admin).at("configurations", "configuration"))
            fixture.graphql(api, """mutation { security { groups { deleteGroup(id: "$groupId") } } }""", admin)
        }
    }
}
