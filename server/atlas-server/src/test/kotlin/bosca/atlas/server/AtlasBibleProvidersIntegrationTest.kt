package bosca.atlas.server

import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Verifies the composed Bible-provider namespace with real persistence and both same-origin proxies. */
class AtlasBibleProvidersIntegrationTest {
    @Test fun `catalog registration audio compatibility refresh and source removal use the owning services`() {
        AtlasProcessFixture().use { fixture ->
            val (api, ui) = fixture.start("bible-catalog", mapOf("ATLAS_RUNNER_ENABLED" to "false"), "bosca.atlas.server.AtlasBibleFixtureKt")
            val standalone = fixture.startUi(api, fixture.newOrigin())
            fun JsonElement.at(vararg keys: String) = keys.fold(this) { value, key -> value.jsonObject.getValue(key) }
            fun login(identifier: String, password: String) = fixture.graphql(api, """mutation { security { login { password(identifier: "$identifier", password: "$password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
            val admin = login("admin", "atlas-admin-password")
            fixture.graphql(api, """mutation { security { signup { passwordV2(identifier: "bible-catalog-reader@example.test", password: "Reader-test-password", profile: {name: "Bible Reader", visibility: PUBLIC, attributes: []}) { principal { id } } } } }""")
            val reader = login("bible-catalog-reader@example.test", "Reader-test-password")
            val path = "/system/integrations/bible"
            fun page(origin: String, caller: String = admin): String {
                val response = fixture.request(origin, path, token = caller)
                assertEquals(200, response.statusCode(), response.body() + "\n" + fixture.logs())
                return response.body()
            }
            fun invoke(origin: String, method: String, args: List<JsonElement>, state: JsonObject? = null, caller: String = admin): JsonObject {
                val marker = Regex("<script type=\"application/json\" data-bml-state-key=\"(system-bible.model[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(page(origin)) ?: error("Missing Bible state")
                val response = fixture.request(origin, "/_bml/action", buildJsonObject {
                    put("page", path); put("path", path); put("stateKey", marker.groupValues[1]); put("state", state?.toString() ?: marker.groupValues[2]); put("method", method); put("args", JsonArray(args))
                }.toString(), caller)
                assertEquals(200, response.statusCode(), response.body())
                return Json.parseToJsonElement(Json.parseToJsonElement(response.body()).at("state").jsonPrimitive.content).jsonObject
            }
            fun ok(state: JsonObject) = assertFalse(state.at("feedback", "failed").jsonPrimitive.boolean, "$state\n${fixture.logs()}")
            fun sources(id: String) = fixture.graphql(api, """{ content { bibleProviders { sources(configurationId: "$id") { id kind version allowedUses metadataId variant } } } }""", admin).at("content", "bibleProviders", "sources").jsonArray
            fun denied(query: String) {
                val response = fixture.request(api, "/graphql", buildJsonObject { put("query", query) }.toString(), admin)
                assertTrue("errors" in Json.parseToJsonElement(response.body()).jsonObject, response.body())
            }
            for ((index, origin) in listOf(ui, standalone).withIndex()) {
                assertEquals(302, fixture.request(origin, path).statusCode())
                assertTrue("Administrator access is required" in page(origin, reader))
                var state = invoke(origin, "editConfiguration", listOf(JsonPrimitive(true)))
                val create = listOf(JsonPrimitive("Catalog fixture $index"), JsonPrimitive("API_BIBLE"), JsonPrimitive(true), JsonPrimitive("fixture-key"))
                assertTrue(invoke(origin, "saveConfiguration", create, state, reader).at("feedback", "failed").jsonPrimitive.boolean)
                state = invoke(origin, "saveConfiguration", create, state); ok(state)
                assertEquals("", state.at("draft", "apiKey").jsonPrimitive.content)
                val id = state.at("configurationId").jsonPrimitive.content
                assertFalse("fixture-key" in page(origin))
                state = invoke(origin, "loadCatalog", listOf(JsonPrimitive("TEXT"), JsonPrimitive(""), JsonPrimitive(0)), state)
                assertFalse(state.at("catalogFeedback", "failed").jsonPrimitive.boolean, state.toString())
                assertEquals(20, state.at("catalog").jsonArray.size)
                state = invoke(origin, "loadCatalog", listOf(JsonPrimitive("TEXT"), JsonPrimitive(""), JsonPrimitive(20)), state)
                assertEquals(1, state.at("catalog").jsonArray.size)
                assertEquals("edition-21", state.at("catalog").jsonArray.single().at("id").jsonPrimitive.content)
                state = invoke(origin, "loadCatalog", listOf(JsonPrimitive("TEXT"), JsonPrimitive("edition-1"), JsonPrimitive(0)), state)
                state = invoke(origin, "prepareRegistration", listOf(JsonPrimitive("edition-1")), state)
                val register = listOf(JsonPrimitive("reader"), JsonPrimitive("[]"), JsonPrimitive(""))
                assertTrue(invoke(origin, "register", register, state, reader).at("feedback", "failed").jsonPrimitive.boolean)
                state = invoke(origin, "register", register, state); ok(state)
                var text = sources(id).single()
                assertTrue(text.at("allowedUses").jsonArray.isEmpty())
                val sourceId = text.at("id").jsonPrimitive.content
                val firstVersion = text.at("version").jsonPrimitive.long
                state = invoke(origin, "loadCatalog", listOf(JsonPrimitive("AUDIO"), JsonPrimitive(""), JsonPrimitive(0)), state)
                state = invoke(origin, "prepareRegistration", listOf(JsonPrimitive("recording")), state)
                state = invoke(origin, "loadTargets", emptyList(), state)
                assertFalse(state.at("targetsFeedback", "failed").jsonPrimitive.boolean, state.toString())
                val target = state.at("targets").jsonArray.single()
                val key = listOf(target.at("metadataId"), target.at("version"), target.at("variant")).joinToString(":") { it.jsonPrimitive.content }
                state = invoke(origin, "register", listOf(JsonPrimitive(""), JsonPrimitive("[\"PLAYBACK\"]"), JsonPrimitive(key)), state); ok(state)
                assertEquals(2, sources(id).size)
                val audio = sources(id).single { it.at("kind").jsonPrimitive.content == "AUDIO" }
                assertEquals(listOf("PLAYBACK"), audio.at("allowedUses").jsonArray.map { it.jsonPrimitive.content })
                state = invoke(origin, "refreshSource", listOf(JsonPrimitive(sourceId)), state); ok(state)
                text = sources(id).single { it.at("id").jsonPrimitive.content == sourceId }
                assertTrue(text.at("version").jsonPrimitive.long > firstVersion)
                denied("""mutation { content { bibleProviders { removeSource(sourceId: "$sourceId", expectedVersion: $firstVersion) } } }""")
                denied("""mutation { content { bibleProviders { removeConfiguration(configurationId: "$id", purgeSources: false) } } }""")
                assertEquals(2, sources(id).size)
                state = invoke(origin, "prepareRemoveSource", listOf(audio.at("id")), state)
                state = invoke(origin, "removeSource", listOf(JsonPrimitive(true)), state); ok(state)
                assertEquals(1, sources(id).size)
                state = invoke(origin, "prepareRemoveConfiguration", emptyList(), state); ok(state)
                assertEquals(1, state.at("removingSources").jsonArray.size)
                assertTrue(invoke(origin, "removeConfiguration", listOf(JsonPrimitive(true), JsonPrimitive(false)), state).at("feedback", "failed").jsonPrimitive.boolean)
                assertTrue(invoke(origin, "removeConfiguration", listOf(JsonPrimitive(true), JsonPrimitive(true)), state, reader).at("feedback", "failed").jsonPrimitive.boolean)
                state = invoke(origin, "removeConfiguration", listOf(JsonPrimitive(true), JsonPrimitive(true)), state); ok(state)
                assertTrue(state.at("configurations").jsonArray.isEmpty())
            }
            if (System.getProperty("atlas.bible.browser") == "true") {
                val project = File(requireNotNull(System.getProperty("workspaceRoot")), "admin-support/system-ui")
                val log = File(fixture.directory, "bible-browser.log")
                val browser = ProcessBuilder("node", "src/test/browser/bible.mjs").directory(project).apply {
                    environment().putAll(mapOf("ATLAS_SYSTEM_TEST_URL" to ui, "ATLAS_BIBLE_STANDALONE_URL" to standalone, "ATLAS_SYSTEM_TEST_PASSWORD" to "atlas-admin-password"))
                }.redirectErrorStream(true).redirectOutput(log).start()
                try {
                    assertTrue(browser.waitFor(180, TimeUnit.SECONDS), "Bible browser checks timed out: ${log.readText()}")
                    assertEquals(0, browser.exitValue(), log.readText() + "\n" + fixture.logs())
                } finally {
                    if (browser.isAlive) { browser.destroy(); if (!browser.waitFor(5, TimeUnit.SECONDS)) browser.destroyForcibly() }
                }
            }
        }
    }

    @Test fun `Bible provider settings retain encrypted credentials and reject non administrator access`() {
        AtlasProcessFixture().use { fixture ->
            val (api, ui) = fixture.start("bible-providers", mapOf("ATLAS_RUNNER_ENABLED" to "false"))
            val standalone = fixture.startUi(api, fixture.newOrigin())
            fun JsonElement.at(vararg keys: String) = keys.fold(this) { value, key -> value.jsonObject.getValue(key) }
            fun login(identifier: String, password: String) = fixture.graphql(api, """mutation { security { login { password(identifier: "$identifier", password: "$password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
            val admin = login("admin", "atlas-admin-password")
            fixture.graphql(api, """mutation { security { signup { passwordV2(identifier: "bible-reader@example.test", password: "Reader-test-password", profile: {name: "Bible Reader", visibility: PUBLIC, attributes: []}) { principal { id } } } } }""")
            val reader = login("bible-reader@example.test", "Reader-test-password")
            for ((index, origin) in listOf(ui, standalone).withIndex()) {
                val query = "{ content { bibleProviders { providers configurations { id name provider enabled hasApiKey } } } }"
                assertTrue("API_BIBLE" in fixture.graphql(origin, query, admin).at("content", "bibleProviders", "providers").jsonArray.map { it.jsonPrimitive.content })
                val denied = fixture.request(origin, "/graphql", buildJsonObject { put("query", query) }.toString(), reader)
                assertTrue("errors" in Json.parseToJsonElement(denied.body()).jsonObject)
                val secret = "bible-credential-$index"
                val created = fixture.graphql(origin, """mutation { content { bibleProviders { saveConfiguration(input: {name: "Bible fixture $index", provider: "API_BIBLE", enabled: false, apiKey: "$secret"}) { id name provider enabled hasApiKey } } } }""", admin).at("content", "bibleProviders", "saveConfiguration")
                val id = created.at("id").jsonPrimitive.content
                assertTrue(created.at("hasApiKey").jsonPrimitive.boolean)
                assertFalse(created.at("enabled").jsonPrimitive.boolean)
                assertFalse(secret in fixture.graphql(origin, query, admin).toString())
                fixture.graphql(origin, """mutation { content { bibleProviders { saveConfiguration(input: {id: "$id", name: "Edited Bible fixture", provider: "API_BIBLE", enabled: false}) { id hasApiKey } } } }""", admin)
                val config = fixture.graphql(api, "{ configurations { all { id public value } } }", admin).at("configurations", "all").jsonArray.single { it.at("id").jsonPrimitive.content == id }
                assertEquals(secret, config.at("value", "apiKey").jsonPrimitive.content)
                assertFalse(config.at("public").jsonPrimitive.boolean)
                assertTrue(fixture.graphql(origin, """{ content { bibleProviders { sources(configurationId: "$id") { id } } } }""", admin).at("content", "bibleProviders", "sources").jsonArray.isEmpty())
                val remove = """mutation { content { bibleProviders { removeConfiguration(configurationId: "$id", purgeSources: false) } } }"""
                val deniedDelete = fixture.request(origin, "/graphql", buildJsonObject { put("query", remove) }.toString(), reader)
                assertTrue("errors" in Json.parseToJsonElement(deniedDelete.body()).jsonObject)
                fixture.graphql(origin, remove, admin)
                assertFalse(fixture.graphql(origin, query, admin).at("content", "bibleProviders", "configurations").jsonArray.any { it.at("id").jsonPrimitive.content == id })
            }
        }
    }
}
