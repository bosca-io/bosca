package bosca.atlas.server

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises System's BML actions against the production API with isolated infrastructure. */
class AtlasSystemIntegrationTest {
    @Test
    fun `system pages enforce authorization and persist group actions in embedded and standalone hosts`() {
        AtlasProcessFixture().use { fixture ->
            val (api, embeddedUi) = fixture.start("system", mapOf("ATLAS_RUNNER_ENABLED" to "false"))
            val standaloneUi = fixture.startUi(api, fixture.newOrigin())
            fun JsonElement.at(vararg keys: String) = keys.fold(this) { value, key -> value.jsonObject.getValue(key) }
            val token = fixture.graphql(api, """mutation { security { login { password(identifier: "admin", password: "atlas-admin-password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
            fixture.graphql(api, """mutation { security { signup { passwordV2(identifier: "system-reader@example.test", password: "Reader-test-password", profile: {name: "System Reader", visibility: PUBLIC, attributes: []}) { principal { id } } } } }""")
            val reader = fixture.graphql(api, """mutation { security { login { password(identifier: "system-reader@example.test", password: "Reader-test-password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
            val path = "/system/security/groups"
            for (ui in listOf(embeddedUi, standaloneUi)) {
                assertEquals(302, fixture.request(ui, path).statusCode())
                for (route in listOf(path, "/system/jobs", "/system/scheduler", "/system/scheduler/new", "/system/packages", "/system/packages?tab=history", "/system/integrations",
                    "/system/security/principals", "/system/security/personas", "/system/security/tokens", "/system/security/passkeys",
                    "/system/config", "/system/storage", "/system/search", "/system/backups", "/system/utilities", "/system/postgres", "/system/nats", "/system/meilisearch",
                    "/system/integrations/email", "/system/integrations/smtp", "/system/integrations/mailgun", "/system/integrations/sendgrid",
                    "/system/integrations/hubspot", "/system/integrations/mux", "/system/integrations/push", "/system/integrations/bible", "/system/integrations/federation")) {
                    val response = fixture.request(ui, route, token = token)
                    assertEquals(200, response.statusCode(), response.body() + "\n" + fixture.directory.resolve("system.log").readText().takeLast(50_000))
                    assertTrue("Bosca Atlas" in response.body())
                    assertTrue("Unable to load this page" !in response.body(), route)
                    if (route == "/system/integrations") {
                        val state = Regex("<script type=\"application/json\" data-bml-state-key=\"system-integrations.model[^\"]*\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(response.body()) ?: error("Missing integration status")
                        val cards = Json.parseToJsonElement(state.groupValues[1]).at("cards").jsonArray
                        assertEquals(8, cards.size)
                        assertTrue(cards.none { it.at("configured").jsonPrimitive.boolean })
                    }
                }
                val denied = fixture.request(ui, path, token = reader)
                assertEquals(200, denied.statusCode(), denied.body())
                assertTrue("Administrator access is required" in denied.body())
            }
            val html = fixture.request(embeddedUi, path, token = token).body()
            val marker = Regex("<script type=\"application/json\" data-bml-state-key=\"(system-list.model[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html) ?: error("Missing System live state")
            fun create(caller: String, name: String): JsonObject {
                val response = fixture.request(embeddedUi, "/_bml/action", buildJsonObject {
                    put("page", path); put("path", path); put("stateKey", marker.groupValues[1]); put("state", marker.groupValues[2]); put("method", "createGroup")
                    put("args", JsonArray(listOf(JsonPrimitive(name), JsonPrimitive("System integration fixture"), JsonPrimitive("PRINCIPAL"))))
                }.toString(), caller)
                assertEquals(200, response.statusCode(), response.body() + "\n" + fixture.directory.resolve("system.log").readText().takeLast(50_000))
                return Json.parseToJsonElement(Json.parseToJsonElement(response.body()).jsonObject.getValue("state").jsonPrimitive.content).jsonObject
            }
            assertTrue(create(reader, "forbidden-system-group").getValue("feedback").jsonObject.getValue("failed").jsonPrimitive.boolean)
            val created = create(token, "system-fixture-group")
            assertTrue(created["feedback"]?.jsonObject?.get("failed")?.jsonPrimitive?.boolean != true, created.toString())
            val groups = fixture.graphql(api, """{ security { groups { find(nameOrDescription: "system-fixture-group", type: PRINCIPAL, limit: 25, offset: 0) { id name } } } }""", token).at("security", "groups", "find").jsonArray
            assertEquals("system-fixture-group", groups.single().at("name").jsonPrimitive.content)
            assertTrue("system-fixture-group" in fixture.request(standaloneUi, "$path?search=system-fixture-group&groupType=PRINCIPAL", token = token).body())
            val id = groups.single().at("id").jsonPrimitive.content
            fixture.graphql(api, """mutation { security { groups { deleteGroup(id: "$id") } } }""", token)
        }
    }
}
