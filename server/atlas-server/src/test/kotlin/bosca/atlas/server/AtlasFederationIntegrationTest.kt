package bosca.atlas.server

import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Exercises federation administration through both production UI hosts and the owning domain service. */
class AtlasFederationIntegrationTest {
    @Test fun `federation peers and private secrets persist and every mutation rejects unauthorized replay`() {
        AtlasProcessFixture().use { fixture ->
            val (api, ui) = fixture.start("federation", mapOf("ATLAS_RUNNER_ENABLED" to "false"))
            val standalone = fixture.startUi(api, fixture.newOrigin())
            fun JsonElement.at(vararg keys: String) = keys.fold(this) { value, key -> value.jsonObject.getValue(key) }
            fun login(identifier: String, password: String) = fixture.graphql(api, """mutation { security { login { password(identifier: "$identifier", password: "$password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
            val admin = login("admin", "atlas-admin-password")
            fixture.graphql(api, """mutation { security { signup { passwordV2(identifier: "federation-reader@example.test", password: "Reader-test-password", profile: {name: "Federation Reader", visibility: PUBLIC, attributes: []}) { principal { id } } } } }""")
            val reader = login("federation-reader@example.test", "Reader-test-password")
            fun page(origin: String, caller: String = admin): String {
                val response = fixture.request(origin, "/system/integrations/federation", token = caller)
                assertEquals(200, response.statusCode(), response.body() + "\n" + fixture.logs())
                return response.body()
            }
            fun invoke(origin: String, method: String, args: List<JsonElement>, caller: String = admin): JsonObject {
                val marker = Regex("<script type=\"application/json\" data-bml-state-key=\"(system-federation.model[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(page(origin)) ?: error("Missing federation state")
                val path = "/system/integrations/federation"
                val response = fixture.request(origin, "/_bml/action", buildJsonObject {
                    put("page", path); put("path", path); put("stateKey", marker.groupValues[1]); put("state", marker.groupValues[2]); put("method", method); put("args", JsonArray(args))
                }.toString(), caller)
                assertEquals(200, response.statusCode(), response.body())
                return Json.parseToJsonElement(Json.parseToJsonElement(response.body()).at("state").jsonPrimitive.content).jsonObject
            }
            fun args(vararg values: String) = values.map(::JsonPrimitive)
            fun ok(state: JsonObject) = assertFalse(state.at("feedback", "failed").jsonPrimitive.boolean, "$state\n${fixture.logs()}")
            fun peers() = fixture.graphql(api, "{ collaboration { federation { peers { id name natsUrl apiUrl active createdAt federatedChannels { peerId } } } } }", admin).at("collaboration", "federation", "peers").jsonArray
            fun configuration(id: String) = fixture.graphql(api, """{ configurations { configuration(key: "federation.peer.$id.secret") { value public } } }""", admin).at("configurations", "configuration")
            for (origin in listOf(ui, standalone)) {
                assertEquals(302, fixture.request(origin, "/system/integrations/federation").statusCode())
                assertTrue("No federation peers registered" in page(origin))
                assertTrue("Administrator access is required" in page(origin, reader))
                val create = args("Unauthorized", "nats://peer.example.test:4222", "https://peer.example.test", "credential")
                assertTrue(invoke(origin, "register", create, reader).at("feedback", "failed").jsonPrimitive.boolean)
            }
            val denied = fixture.request(api, "/graphql", """{"query":"{ collaboration { federation { peers { id } } } }"}""", reader)
            assertTrue("errors" in Json.parseToJsonElement(denied.body()).jsonObject)
            assertTrue(peers().isEmpty())
            for ((index, origin) in listOf(ui, standalone).withIndex()) {
                val secret = "initial-federation-secret-$index"
                ok(invoke(origin, "register", args("Peer $index", "tls://peer-$index.example.test:7422", "https://peer-$index.example.test", secret)))
                val peer = peers().single { it.at("name").jsonPrimitive.content == "Peer $index" }
                val id = peer.at("id").jsonPrimitive.content
                assertEquals("tls://peer-$index.example.test:7422", peer.at("natsUrl").jsonPrimitive.content)
                assertNotEquals(JsonNull, peer.at("createdAt"))
                assertTrue(peer.at("active").jsonPrimitive.boolean)
                assertTrue(peer.at("federatedChannels").jsonArray.isEmpty())
                assertEquals(secret, configuration(id).at("value", "secret").jsonPrimitive.content)
                assertFalse(configuration(id).at("public").jsonPrimitive.boolean)
                assertFalse(secret in page(ui) || secret in page(standalone))
                assertTrue(invoke(origin, "setSecret", args(id, "unauthorized"), reader).at("feedback", "failed").jsonPrimitive.boolean)
                assertTrue(invoke(origin, "deactivate", listOf(JsonPrimitive(id), JsonPrimitive(true)), reader).at("feedback", "failed").jsonPrimitive.boolean)
                assertEquals(secret, configuration(id).at("value", "secret").jsonPrimitive.content)
                ok(invoke(origin, "setSecret", args(id, "rotated-secret")))
                assertEquals("rotated-secret", configuration(id).at("value", "secret").jsonPrimitive.content)
                ok(invoke(origin, "setSecret", args(id, "")))
                assertEquals(JsonNull, configuration(id).at("value"))
                ok(invoke(origin, "setSecret", args(id, "before-deactivation")))
                assertTrue(invoke(origin, "deactivate", listOf(JsonPrimitive(id), JsonPrimitive(false))).at("feedback", "failed").jsonPrimitive.boolean)
                assertTrue(peers().any { it.at("id").jsonPrimitive.content == id })
                ok(invoke(origin, "deactivate", listOf(JsonPrimitive(id), JsonPrimitive(true))))
                assertFalse(peers().any { it.at("id").jsonPrimitive.content == id })
                assertEquals(JsonNull, configuration(id).at("value"))
                val inactive = fixture.graphql(api, """{ collaboration { federation { peer(id: "$id") { active } } } }""", admin).at("collaboration", "federation", "peer", "active").jsonPrimitive.boolean
                assertFalse(inactive)
            }
            if (System.getProperty("atlas.federation.browser") == "true") {
                val project = File(requireNotNull(System.getProperty("workspaceRoot")), "admin-support/system-ui")
                val log = File(fixture.directory, "federation-browser.log")
                val browser = ProcessBuilder("node", "src/test/browser/federation.mjs").directory(project).apply {
                    environment().putAll(mapOf("ATLAS_SYSTEM_TEST_URL" to ui, "ATLAS_FEDERATION_STANDALONE_URL" to standalone, "ATLAS_SYSTEM_TEST_PASSWORD" to "atlas-admin-password"))
                }.redirectErrorStream(true).redirectOutput(log).start()
                try {
                    assertTrue(browser.waitFor(180, TimeUnit.SECONDS), "Federation browser checks timed out: ${log.readText()}")
                    assertEquals(0, browser.exitValue(), log.readText() + "\n" + fixture.logs())
                } finally {
                    if (browser.isAlive) { browser.destroy(); if (!browser.waitFor(5, TimeUnit.SECONDS)) browser.destroyForcibly() }
                }
                assertTrue(peers().isEmpty())
            }
        }
    }
}
