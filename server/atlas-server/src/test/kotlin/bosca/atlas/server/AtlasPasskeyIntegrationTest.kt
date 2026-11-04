package bosca.atlas.server

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotlin.test.*

/** Exercises the real same-origin registration proxy and optional native browser ceremony in both hosts. */
class AtlasPasskeyIntegrationTest {
    @Test fun `passkey ceremonies retain caller identity and one-time challenges`() {
        AtlasProcessFixture().use { fixture ->
            val standaloneOrigin = fixture.newOrigin()
            val (api, ui) = fixture.start("passkeys", mapOf("ATLAS_RUNNER_ENABLED" to "false", "WEBAUTHN_EXTRA_ORIGIN" to standaloneOrigin))
            val standalone = fixture.startUi(api, standaloneOrigin)
            fun JsonElement.at(vararg keys: String) = keys.fold(this) { value, key -> value.jsonObject.getValue(key) }
            fun login(identifier: String, password: String) = fixture.graphql(api, """mutation { security { login { password(identifier: "$identifier", password: "$password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
            val admin = login("admin", "atlas-admin-password")
            fixture.graphql(api, """mutation { security { signup { passwordV2(identifier: "passkey-reader@example.test", password: "Reader-test-password", profile: {name: "Passkey Reader", visibility: PUBLIC, attributes: []}) { principal { id } } } } }""")
            val reader = login("passkey-reader@example.test", "Reader-test-password")
            val apiToken = fixture.graphql(api, """mutation { security { apiTokens { create(input: {name: "Passkey API rejection"}) { rawToken } } } }""", admin).at("security", "apiTokens", "create", "rawToken").jsonPrimitive.content
            fun post(origin: String, step: String, body: String = "{}", token: String? = admin) = fixture.request(origin, "/api/v1/security/passkeys/register/$step", body, token)
            fun completion(state: String) = buildJsonObject {
                put("stateKey", state); put("name", "Fixture"); put("credentialId", "credential"); put("attestationObject", "not base64!"); put("clientDataJSON", "AA"); put("transports", JsonArray(emptyList()))
            }.toString()
            for (origin in listOf(ui, standalone)) {
                assertEquals(302, fixture.request(origin, "/system/security/passkeys").statusCode())
                val page = fixture.request(origin, "/system/security/passkeys", token = reader)
                assertEquals(200, page.statusCode(), page.body())
                assertTrue("No passkeys registered" in page.body())
                assertEquals(401, post(origin, "begin", token = null).statusCode())
                assertEquals(403, post(origin, "begin", token = apiToken).statusCode())
                val begin = post(origin, "begin")
                assertEquals(200, begin.statusCode(), begin.body() + "\n" + fixture.logs())
                assertEquals("private, no-store", begin.headers().firstValue("Cache-Control").orElse(""))
                val challenge = Json.parseToJsonElement(begin.body())
                assertEquals("localhost", challenge.at("rp", "id").jsonPrimitive.content)
                assertTrue(challenge.at("challenge").jsonPrimitive.content.isNotBlank())
                val state = challenge.at("stateKey").jsonPrimitive.content
                val mismatch = post(origin, "complete", completion(state), reader)
                assertEquals(403, mismatch.statusCode(), mismatch.body())
                assertEquals("principal_mismatch", Json.parseToJsonElement(mismatch.body()).at("error").jsonPrimitive.content)
                val replay = post(origin, "complete", completion(state))
                assertEquals(400, replay.statusCode(), replay.body())
                assertEquals("challenge_expired", Json.parseToJsonElement(replay.body()).at("error").jsonPrimitive.content)
                val own = Json.parseToJsonElement(post(origin, "begin", token = reader).body()).at("stateKey").jsonPrimitive.content
                val invalid = post(origin, "complete", completion(own), reader)
                assertEquals(400, invalid.statusCode(), invalid.body())
                assertEquals("invalid_encoding", Json.parseToJsonElement(invalid.body()).at("error").jsonPrimitive.content)
            }
            if (System.getProperty("atlas.passkeys.browser") == "true") {
                val project = File(requireNotNull(System.getProperty("workspaceRoot")), "admin-support/system-ui")
                val log = File(fixture.directory, "passkeys-browser.log")
                val browser = ProcessBuilder("node", "src/test/browser/passkeys.mjs").directory(project).apply {
                    environment()["ATLAS_SYSTEM_TEST_URL"] = ui
                    environment()["ATLAS_PASSKEY_STANDALONE_URL"] = standalone
                    environment()["ATLAS_SYSTEM_TEST_PASSWORD"] = "atlas-admin-password"
                    environment()["ATLAS_PASSKEY_READER_TOKEN"] = reader
                    redirectErrorStream(true); redirectOutput(log)
                }.start()
                try {
                    assertTrue(browser.waitFor(180, TimeUnit.SECONDS), "Passkey browser check timed out: ${log.readText()}")
                    assertEquals(0, browser.exitValue(), log.readText() + "\n" + fixture.logs())
                } finally { if (browser.isAlive) browser.destroyForcibly() }
                val current = login("admin", "atlas-admin-password")
                assertTrue(fixture.graphql(api, "{ security { passkeys { current { credentialId } } } }", current).at("security", "passkeys", "current").jsonArray.isEmpty())
            }
        }
    }
}
