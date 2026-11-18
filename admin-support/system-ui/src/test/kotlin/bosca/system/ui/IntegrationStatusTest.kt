package bosca.system.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class IntegrationStatusTest {
    @Test fun `integration cards derive status from saved configuration without serializing credentials`() = runTest {
        for (configured in listOf(true, false)) {
            val model = SystemIntegrationsModel(admin = true)
            val values = if (configured) mapOf(
                "smtp" to """{"host":"smtp.example.test"}""",
                "sendgrid" to """{"apiKey":"private-status-credential"}""",
                "mailgun" to """{"apiKey":"private-status-credential","domain":"example.test"}""",
                "push" to """{"enabled":true,"fcm":{}}""",
                "hubspot" to """{"token":"private-status-credential"}""",
                "mux" to """{"tokenId":"mux-id"}""",
            ) else mapOf(
                "smtp" to """{"host":"smtp.example.test","port":0}""",
                "sendgrid" to "{}", "mailgun" to """{"domain":"example.test"}""",
                "push" to """{"enabled":true,"apns":{"teamId":"team","keyId":"key","bundleId":"app"}}""",
                "hubspot" to "{}", "mux" to "{}",
            )
            val ctx = RenderContext(gql = object : GraphQLClient {
                override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = buildJsonObject {
                    for ((key, value) in values) put(key, buildJsonObject { put("configuration", buildJsonObject { put("value", Json.parseToJsonElement(value)) }) })
                    put("collaboration", Json.parseToJsonElement(if (configured) """{"federation":{"peers":[{"id":"peer"}]}}""" else """{"federation":{"peers":[]}}"""))
                    put("content", Json.parseToJsonElement("""{"bibleProviders":{"configurations":[{"enabled":$configured,"hasApiKey":true}]}}"""))
                }
            })
            model.refresh(ctx)
            assertFalse(model.feedback.failed, model.feedback.message)
            assertEquals(8, model.cards.size)
            assertTrue(model.cards.all { it.configured == configured }, model.cards.toString())
            assertFalse("private-status-credential" in Json.encodeToString(SystemIntegrationsModel.serializer(), model))
        }
    }
}
