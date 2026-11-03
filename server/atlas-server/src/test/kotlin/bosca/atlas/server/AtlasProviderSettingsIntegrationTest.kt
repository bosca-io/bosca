package bosca.atlas.server

import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Checks Mux and push configuration through real Atlas hosts without contacting delivery providers. */
class AtlasProviderSettingsIntegrationTest {
    @Test fun `Mux and push forms retain authorized credentials ACLs and complete provider settings`() {
        AtlasProcessFixture().use { fixture ->
            val (api, ui) = fixture.start("provider-settings", mapOf("ATLAS_RUNNER_ENABLED" to "false"))
            val standalone = fixture.startUi(api, fixture.newOrigin())
            fun JsonElement.at(vararg keys: String) = keys.fold(this) { value, key -> value.jsonObject.getValue(key) }
            fun login(identifier: String, password: String) = fixture.graphql(api, """mutation { security { login { password(identifier: "$identifier", password: "$password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
            val admin = login("admin", "atlas-admin-password")
            fixture.graphql(api, """mutation { security { signup { passwordV2(identifier: "provider-reader@example.test", password: "Reader-test-password", profile: {name: "Provider Reader", visibility: PUBLIC, attributes: []}) { principal { id } } } } }""")
            var reader = login("provider-reader@example.test", "Reader-test-password")
            val group = fixture.graphql(api, """mutation { security { groups { addGroup(name: "Provider settings editors", description: "Delegated settings", groupType: PRINCIPAL) { id } } } }""", admin).at("security", "groups", "addGroup", "id").jsonPrimitive.content
            val values = mapOf(
                "mux" to """{tokenId: "fixture-mux-id", tokenSecret: "fixture-mux-secret", playbackPolicy: "public", streamBaseUrl: "https://video.example.test"}""",
                "push" to """{enabled: true, fcm: {serviceAccountJson: "{\"project_id\":\"fixture-fcm-secret\"}"}, apns: {teamId: "team", keyId: "key", bundleId: "app", privateKey: "fixture-apns-secret", sandbox: true}}""",
            )
            for ((key, value) in values) {
                val id = fixture.graphql(api, """mutation { configurations { setConfiguration(configuration: {key: "$key", description: "Provider fixture", value: $value, public: false}) { id } } }""", admin).at("configurations", "setConfiguration", "id").jsonPrimitive.content
                fixture.graphql(api, """mutation { configurations { setConfiguration(configuration: {key: "$key", description: "Provider fixture", value: $value, public: false, permissions: [{action: VIEW, entityId: "$id", groupId: "$group"}, {action: EDIT, entityId: "$id", groupId: "$group"}]}) { id } } }""", admin)
            }
            fun read(key: String) = fixture.graphql(api, """{ configurations { configuration(key: "$key") { value public permissions { action group { id } } } } }""", admin).at("configurations", "configuration")
            fun page(origin: String, key: String, caller: String = admin): String {
                val response = fixture.request(origin, "/system/integrations/$key", token = caller)
                assertEquals(200, response.statusCode(), response.body() + "\n" + fixture.logs())
                return response.body()
            }
            fun state(html: String, key: String) = Regex("<script type=\"application/json\" data-bml-state-key=\"(system-$key.model[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html) ?: error("Missing $key state")
            fun invoke(origin: String, key: String, args: List<JsonElement>, caller: String = admin): JsonObject {
                val path = "/system/integrations/$key"
                val marker = state(page(origin, key), key)
                val response = fixture.request(origin, "/_bml/action", buildJsonObject {
                    put("page", path); put("path", path); put("stateKey", marker.groupValues[1]); put("state", marker.groupValues[2]); put("method", "save"); put("args", JsonArray(args))
                }.toString(), caller)
                assertEquals(200, response.statusCode(), response.body())
                return Json.parseToJsonElement(Json.parseToJsonElement(response.body()).at("state").jsonPrimitive.content).jsonObject
            }
            fun args(vararg values: Any) = values.map { if (it is Boolean) JsonPrimitive(it) else JsonPrimitive(it.toString()) }
            fun ok(state: JsonObject) = assertFalse(state.at("feedback", "failed").jsonPrimitive.boolean, "$state\n${fixture.logs()}")
            val mux = args("", "", false, false, "signed", "7200", "premium", "2160p", false, "fr", true)
            val push = args(true, true, "null", false, true, "edited-team", "key", "io.bosca.fixture", "", false, false)
            for (origin in listOf(ui, standalone)) for (key in values.keys) {
                assertEquals(302, fixture.request(origin, "/system/integrations/$key").statusCode())
                val html = page(origin, key)
                assertFalse(listOf("fixture-mux-id", "fixture-mux-secret", "fixture-fcm-secret", "fixture-apns-secret").any { it in html })
                assertTrue(invoke(origin, key, if (key == "mux") mux else push, reader).at("feedback", "failed").jsonPrimitive.boolean)
            }
            val principal = fixture.graphql(api, "{ security { principals { current { id } } } }", reader).at("security", "principals", "current", "id").jsonPrimitive.content
            fixture.graphql(api, """mutation { security { addPrincipalGroup(groupId: "$group", principalId: "$principal") } }""", admin)
            reader = login("provider-reader@example.test", "Reader-test-password")
            for (origin in listOf(ui, standalone)) {
                for (key in values.keys) assertTrue(Json.parseToJsonElement(state(page(origin, key, reader), key).groupValues[2]).at("loaded").jsonPrimitive.boolean)
                ok(invoke(origin, "mux", mux, reader)); ok(invoke(origin, "push", push, reader))
            }
            val muxValue = read("mux").at("value")
            assertEquals("fixture-mux-secret", muxValue.at("tokenSecret").jsonPrimitive.content)
            assertEquals("fixture-mux-id", muxValue.at("tokenId").jsonPrimitive.content)
            assertEquals("https://video.example.test", muxValue.at("streamBaseUrl").jsonPrimitive.content)
            assertEquals("premium", muxValue.at("videoQuality").jsonPrimitive.content)
            assertEquals("2160p", muxValue.at("maxResolutionTier").jsonPrimitive.content)
            assertTrue(muxValue.at("test").jsonPrimitive.boolean)
            val pushValue = read("push").at("value")
            assertEquals("fixture-apns-secret", pushValue.at("apns", "privateKey").jsonPrimitive.content)
            assertTrue(pushValue.at("fcm", "serviceAccountJson").jsonPrimitive.content.contains("fixture-fcm-secret"))
            assertFalse(pushValue.at("apns", "sandbox").jsonPrimitive.boolean)
            val invalid = invoke(ui, "push", args(true, true, "{invalid", false, true, "team", "key", "app", "typed-draft", false, true), reader)
            assertTrue(invalid.at("feedback", "failed").jsonPrimitive.boolean)
            assertEquals("typed-draft", invalid.at("draft", "privateKey").jsonPrimitive.content)
            assertEquals(pushValue, read("push").at("value"))
            ok(invoke(ui, "mux", args("", "replacement-mux", true, false, "public", "3600", "default", "default", true, "en", false), reader))
            assertEquals("", read("mux").at("value", "tokenId").jsonPrimitive.content)
            assertEquals("replacement-mux", read("mux").at("value", "tokenSecret").jsonPrimitive.content)
            assertFalse("videoQuality" in read("mux").at("value").jsonObject)
            ok(invoke(standalone, "push", args(false, true, "null", true, true, "team", "key", "app", "", true, true), reader))
            assertFalse("serviceAccountJson" in read("push").at("value", "fcm").jsonObject)
            assertFalse("privateKey" in read("push").at("value", "apns").jsonObject)
            ok(invoke(ui, "push", args(false, false, "null", false, false, "", "", "", "", false, true), reader))
            assertFalse("fcm" in read("push").at("value").jsonObject || "apns" in read("push").at("value").jsonObject)
            if (System.getProperty("atlas.providers.browser") == "true") {
                val project = File(requireNotNull(System.getProperty("workspaceRoot")), "admin-support/system-ui")
                val log = File(fixture.directory, "providers-browser.log")
                val browser = ProcessBuilder("node", "src/test/browser/providers.mjs").directory(project).apply {
                    environment().putAll(mapOf("ATLAS_SYSTEM_TEST_URL" to ui, "ATLAS_PROVIDERS_STANDALONE_URL" to standalone, "ATLAS_SYSTEM_TEST_PASSWORD" to "atlas-admin-password"))
                }.redirectErrorStream(true).redirectOutput(log).start()
                try {
                    assertTrue(browser.waitFor(180, TimeUnit.SECONDS), "Provider browser checks timed out: ${log.readText()}")
                    assertEquals(0, browser.exitValue(), log.readText() + "\n" + fixture.logs())
                } finally {
                    if (browser.isAlive) { browser.destroy(); if (!browser.waitFor(5, TimeUnit.SECONDS)) browser.destroyForcibly() }
                }
                assertEquals("browser-mux-secret", read("mux").at("value", "tokenSecret").jsonPrimitive.content)
                assertEquals("browser-private-key", read("push").at("value", "apns", "privateKey").jsonPrimitive.content)
                assertFalse("serviceAccountJson" in read("push").at("value", "fcm").jsonObject)
            }
            for (key in values.keys) {
                val config = read(key)
                assertFalse(config.at("public").jsonPrimitive.boolean)
                assertEquals(setOf("VIEW", "EDIT"), config.at("permissions").jsonArray.map { it.at("action").jsonPrimitive.content }.toSet())
                assertTrue(config.at("permissions").jsonArray.all { it.at("group", "id").jsonPrimitive.content == group })
            }
        }
    }
}
