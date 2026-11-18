package bosca.system.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class EmailActionsTest {
    private fun context(handler: suspend (String, JsonObject?) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables)
    })
    private fun configuration(value: JsonElement) = buildJsonObject {
        put("configurations", buildJsonObject { put("configuration", buildJsonObject {
            put("id", "config"); put("description", "Existing description"); put("public", false); put("value", value)
        }) })
    }

    @Test fun `stored credentials stay out of live state and blank edits use a fresh authorized value without changing ACLs`() = runTest {
        var stored = Json.parseToJsonElement("""{"apiKey":"stored-key","webhookVerificationKey":"stored-verification","extra":true}""")
        val model = SystemEmailModel("sendgrid")
        val ctx = context { operation, variables ->
            when (operation) {
                "SystemIntegrationConfiguration" -> configuration(stored)
                "SystemSetConfiguration" -> {
                    val input = requireNotNull(variables).getValue("input").jsonObject
                    assertTrue(input["permissions"] == null || input["permissions"] is JsonNull)
                    assertFalse(input.getValue("public").jsonPrimitive.boolean)
                    stored = input.getValue("value")
                    Json.parseToJsonElement("""{"configurations":{"setConfiguration":{"id":"config"}}}""")
                }
                else -> error("Unexpected $operation")
            }
        }
        model.reload(ctx)
        assertTrue(model.hasApiKey && model.hasVerificationKey)
        assertFalse(Json.encodeToString(model).contains("stored-key"))
        stored = Json.parseToJsonElement("""{"apiKey":"newer-key","webhookVerificationKey":"stored-verification","extra":true}""")
        model.saveSendgrid("", false, "", false, ctx)
        assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals("newer-key", stored.jsonObject.getValue("apiKey").jsonPrimitive.content)
        assertEquals("stored-verification", stored.jsonObject.getValue("webhookVerificationKey").jsonPrimitive.content)
        model.saveSendgrid("replacement", false, "", true, ctx)
        assertEquals("replacement", stored.jsonObject.getValue("apiKey").jsonPrimitive.content)
        assertEquals("", stored.jsonObject.getValue("webhookVerificationKey").jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), stored.jsonObject.getValue("extra"))
        assertEquals("", model.draft.apiKey)
    }

    @Test fun `SMTP rejects invalid ports security and incomplete authentication while retaining failed drafts`() = runTest {
        val model = SystemEmailModel("smtp", loaded = true)
        val noQuery = context { _, _ -> error("Must not query invalid settings") }
        for (port in listOf("0", "65536", "-1", "1.5", "abc")) {
            model.saveSmtp("mail.example.test", port, "STARTTLS", "", "", false, noQuery)
            assertEquals("Port must be between 1 and 65535.", model.feedback.message)
            assertEquals(port, model.draft.port)
        }
        model.saveSmtp("mail.example.test", "587", "UNKNOWN", "", "", false, noQuery)
        assertEquals("Choose a connection security option.", model.feedback.message)
        model.saveSmtp("mail.example.test", "587", "STARTTLS", "", "", false, context { operation, _ ->
            assertEquals("SystemIntegrationConfiguration", operation)
            configuration(Json.parseToJsonElement("""{"password":"stored"}"""))
        })
        assertTrue(model.feedback.failed)
        assertTrue(model.feedback.message.contains("Set both username and password"))
    }

    @Test fun `mailgun validation prevents invalid origins and failed saves retain entered credentials`() = runTest {
        val model = SystemEmailModel("mailgun", loaded = true)
        model.saveMailgun("entered-key", "mg.example.test", "file:///tmp/mail", context { _, _ -> error("Must not query") })
        assertTrue(model.feedback.failed)
        assertEquals("entered-key", model.draft.apiKey)
        model.saveMailgun("entered-key", "mg.example.test", "HTTPS://api.mailgun.net", context { operation, _ ->
            if (operation == "SystemIntegrationConfiguration") configuration(JsonObject(emptyMap())) else error("Denied write")
        })
        assertEquals("Denied write", model.feedback.message)
        assertEquals("entered-key", model.draft.apiKey)
    }

    @Test fun `SMTP preserves password whitespace`() = runTest {
        val model = SystemEmailModel("smtp", loaded = true)
        var written = false
        model.saveSmtp("smtp.example.test", "587", "STARTTLS", "mailer", " ", false, context { operation, variables ->
            if (operation == "SystemSetConfiguration") {
                assertEquals(" ", variables?.getValue("input")?.jsonObject?.getValue("value")?.jsonObject?.getValue("password")?.jsonPrimitive?.content)
                written = true
                Json.parseToJsonElement("""{"configurations":{"setConfiguration":{"id":"config"}}}""")
            } else configuration(JsonObject(emptyMap()))
        })
        assertFalse(model.feedback.failed, model.feedback.message)
        assertTrue(written)
    }

    @Test fun `deployment provider selection stores null and a failed load prevents an uninitialized save`() = runTest {
        val model = SystemEmailModel("mailer")
        model.saveProvider("smtp", context { _, _ -> error("Must not query") })
        assertEquals("Reload the page before saving settings.", model.feedback.message)
        model.loaded = true
        model.saveProvider("deployment", context { operation, variables ->
            if (operation == "SystemSetConfiguration") {
                assertEquals(JsonNull, variables?.getValue("input")?.jsonObject?.getValue("value")?.jsonObject?.getValue("type"))
                Json.parseToJsonElement("""{"configurations":{"setConfiguration":{"id":"config"}}}""")
            } else configuration(JsonObject(emptyMap()))
        })
        assertFalse(model.feedback.failed, model.feedback.message)
    }
}
