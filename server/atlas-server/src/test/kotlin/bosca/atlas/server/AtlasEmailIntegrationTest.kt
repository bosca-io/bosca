package bosca.atlas.server

import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Verifies delegated provider settings through both production hosts without sending email. */
class AtlasEmailIntegrationTest {
    @Test fun `email forms retain secrets and group ACLs and reject unauthorized live state replay`() {
        AtlasProcessFixture().use { fixture ->
            val (api, ui) = fixture.start("email", mapOf("ATLAS_RUNNER_ENABLED" to "false"))
            val standalone = fixture.startUi(api, fixture.newOrigin())
            fun JsonElement.at(vararg keys: String) = keys.fold(this) { value, key -> value.jsonObject.getValue(key) }
            fun login(identifier: String, password: String) = fixture.graphql(api, """mutation { security { login { password(identifier: "$identifier", password: "$password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
            val admin = login("admin", "atlas-admin-password")
            fixture.graphql(api, """mutation { security { signup { passwordV2(identifier: "email-reader@example.test", password: "Reader-test-password", profile: {name: "Email Reader", visibility: PUBLIC, attributes: []}) { principal { id } } } } }""")
            var reader = login("email-reader@example.test", "Reader-test-password")
            val group = fixture.graphql(api, """mutation { security { groups { addGroup(name: "Email settings editors", description: "Delegated provider settings", groupType: PRINCIPAL) { id } } } }""", admin).at("security", "groups", "addGroup", "id").jsonPrimitive.content
            val routes = mapOf("mailer" to "email", "smtp" to "smtp", "mailgun" to "mailgun", "sendgrid" to "sendgrid")
            val values = mapOf(
                "mailer" to "{type: null}",
                "smtp" to """{host: "smtp.example.test", port: 587, security: "STARTTLS", username: "mailer", password: "fixture-smtp-credential"}""",
                "mailgun" to """{domain: "mg.example.test", apiBaseUrl: "https://api.mailgun.net", apiKey: "fixture-mailgun-credential"}""",
                "sendgrid" to """{apiKey: "fixture-sendgrid-credential", webhookVerificationKey: "fixture-webhook-credential"}""",
            )
            fun read(key: String) = fixture.graphql(api, """{ configurations { configuration(key: "$key") { value public permissions { action group { id } } } } }""", admin).at("configurations", "configuration")
            for ((key, value) in values) {
                val id = fixture.graphql(api, """mutation { configurations { setConfiguration(configuration: {key: "$key", description: "Email fixture", value: $value, public: false}) { id } } }""", admin).at("configurations", "setConfiguration", "id").jsonPrimitive.content
                fixture.graphql(api, """mutation { configurations { setConfiguration(configuration: {key: "$key", description: "Email fixture", value: $value, public: false, permissions: [{action: VIEW, entityId: "$id", groupId: "$group"}, {action: EDIT, entityId: "$id", groupId: "$group"}]}) { id } } }""", admin)
            }
            fun page(origin: String, key: String, caller: String = admin): String {
                val response = fixture.request(origin, "/system/integrations/${routes.getValue(key)}", token = caller)
                assertEquals(200, response.statusCode(), response.body() + "\n" + fixture.logs())
                return response.body()
            }
            fun invoke(origin: String, key: String, method: String, args: List<JsonElement>, caller: String = admin): JsonObject {
                val path = "/system/integrations/${routes.getValue(key)}"
                val marker = Regex("<script type=\"application/json\" data-bml-state-key=\"(system-email.model[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(page(origin, key)) ?: error("Missing email state")
                val response = fixture.request(origin, "/_bml/action", buildJsonObject {
                    put("page", path); put("path", path); put("stateKey", marker.groupValues[1]); put("state", marker.groupValues[2]); put("method", method); put("args", JsonArray(args))
                }.toString(), caller)
                assertEquals(200, response.statusCode(), response.body())
                return Json.parseToJsonElement(Json.parseToJsonElement(response.body()).at("state").jsonPrimitive.content).jsonObject
            }
            fun ok(state: JsonObject) = assertFalse(state.at("feedback", "failed").jsonPrimitive.boolean, "$state\n${fixture.logs()}")
            fun strings(vararg values: String) = values.map(::JsonPrimitive)
            val smtpArgs = strings("smtp-edited.example.test", "465", "TLS", "mailer", "") + JsonPrimitive(false)
            val mailgunArgs = listOf(JsonPrimitive(""), JsonPrimitive("mg-edited.example.test"), JsonPrimitive("https://api.eu.mailgun.net"))
            val sendgridArgs = listOf(JsonPrimitive(""), JsonPrimitive(false), JsonPrimitive(""), JsonPrimitive(false))
            for (origin in listOf(ui, standalone)) for ((key, route) in routes) {
                assertEquals(302, fixture.request(origin, "/system/integrations/$route").statusCode())
                val html = page(origin, key)
                assertFalse(listOf("fixture-smtp-credential", "fixture-mailgun-credential", "fixture-sendgrid-credential", "fixture-webhook-credential").any { it in html })
                val (method, args) = when (key) {
                    "mailer" -> "saveProvider" to listOf(JsonPrimitive("smtp"))
                    "smtp" -> "saveSmtp" to smtpArgs
                    "mailgun" -> "saveMailgun" to mailgunArgs
                    else -> "saveSendgrid" to sendgridArgs
                }
                assertTrue(invoke(origin, key, method, args, reader).at("feedback", "failed").jsonPrimitive.boolean)
            }
            val principalId = fixture.graphql(api, "{ security { principals { current { id } } } }", reader).at("security", "principals", "current", "id").jsonPrimitive.content
            fixture.graphql(api, """mutation { security { addPrincipalGroup(groupId: "$group", principalId: "$principalId") } }""", admin)
            reader = login("email-reader@example.test", "Reader-test-password")
            for (origin in listOf(ui, standalone)) {
                for (key in routes.keys) {
                    val marker = Regex("<script type=\"application/json\" data-bml-state-key=\"system-email.model[^\"]*\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(page(origin, key, reader)) ?: error("Missing delegated email state")
                    assertTrue(Json.parseToJsonElement(marker.groupValues[1]).at("loaded").jsonPrimitive.boolean)
                }
                ok(invoke(origin, "mailer", "saveProvider", listOf(JsonPrimitive("smtp")), reader))
                ok(invoke(origin, "smtp", "saveSmtp", smtpArgs, reader))
                ok(invoke(origin, "mailgun", "saveMailgun", mailgunArgs, reader))
                ok(invoke(origin, "sendgrid", "saveSendgrid", sendgridArgs, reader))
            }
            assertEquals("fixture-smtp-credential", read("smtp").at("value", "password").jsonPrimitive.content)
            assertEquals("fixture-mailgun-credential", read("mailgun").at("value", "apiKey").jsonPrimitive.content)
            assertEquals("fixture-sendgrid-credential", read("sendgrid").at("value", "apiKey").jsonPrimitive.content)
            assertEquals("fixture-webhook-credential", read("sendgrid").at("value", "webhookVerificationKey").jsonPrimitive.content)
            ok(invoke(ui, "smtp", "saveSmtp", strings("relay.example.test", "25", "NONE", "", "") + JsonPrimitive(true), reader))
            assertEquals("", read("smtp").at("value", "password").jsonPrimitive.content)
            ok(invoke(standalone, "sendgrid", "saveSendgrid", listOf(JsonPrimitive("replacement-sendgrid"), JsonPrimitive(false), JsonPrimitive(""), JsonPrimitive(true)), reader))
            assertEquals("replacement-sendgrid", read("sendgrid").at("value", "apiKey").jsonPrimitive.content)
            assertEquals("", read("sendgrid").at("value", "webhookVerificationKey").jsonPrimitive.content)
            ok(invoke(ui, "mailer", "saveProvider", listOf(JsonPrimitive("deployment")), reader))
            assertEquals(JsonNull, read("mailer").at("value", "type"))
            for (key in routes.keys) {
                val saved = read(key)
                assertFalse(saved.at("public").jsonPrimitive.boolean)
                assertEquals(setOf("VIEW", "EDIT"), saved.at("permissions").jsonArray.map { it.at("action").jsonPrimitive.content }.toSet())
                assertTrue(saved.at("permissions").jsonArray.all { it.at("group", "id").jsonPrimitive.content == group })
            }
            if (System.getProperty("atlas.email.browser") == "true") {
                val project = File(requireNotNull(System.getProperty("workspaceRoot")), "admin-support/system-ui")
                val log = File(fixture.directory, "email-browser.log")
                val browser = ProcessBuilder("node", "src/test/browser/email.mjs").directory(project).apply {
                    environment().putAll(mapOf("ATLAS_SYSTEM_TEST_URL" to ui, "ATLAS_EMAIL_STANDALONE_URL" to standalone, "ATLAS_SYSTEM_TEST_PASSWORD" to "atlas-admin-password"))
                }.redirectErrorStream(true).redirectOutput(log).start()
                try {
                    assertTrue(browser.waitFor(180, TimeUnit.SECONDS), "Email browser checks timed out: ${log.readText()}")
                    assertEquals(0, browser.exitValue(), log.readText() + "\n" + fixture.logs())
                } finally {
                    if (browser.isAlive) { browser.destroy(); if (!browser.waitFor(5, TimeUnit.SECONDS)) browser.destroyForcibly() }
                }
                assertEquals("browser-smtp-credential", read("smtp").at("value", "password").jsonPrimitive.content)
                assertEquals("browser-mailgun-credential", read("mailgun").at("value", "apiKey").jsonPrimitive.content)
                assertEquals("browser-sendgrid-credential", read("sendgrid").at("value", "apiKey").jsonPrimitive.content)
                assertEquals("", read("sendgrid").at("value", "webhookVerificationKey").jsonPrimitive.content)
            }
        }
    }
}
