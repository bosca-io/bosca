package bosca.system.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class ProviderSettingsActionsTest {
    private fun context(handler: suspend (String, JsonObject?) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables)
    })
    private fun configuration(value: JsonElement) = buildJsonObject {
        put("configurations", buildJsonObject { put("configuration", buildJsonObject {
            put("id", "config"); put("description", "Existing configuration"); put("public", false); put("value", value)
        }) })
    }
    private suspend fun save(model: SystemMuxModel, draft: SystemMuxDraft, ctx: RenderContext) = model.save(draft.tokenId, draft.tokenSecret, draft.clearTokenId, draft.clearTokenSecret, draft.playbackPolicy, draft.maxWaitSeconds, draft.videoQuality, draft.maxResolution, draft.mp4Support, draft.subtitleLanguage, draft.test, ctx)
    private suspend fun save(model: SystemPushModel, draft: SystemPushDraft, ctx: RenderContext) = model.save(draft.enabled, draft.fcmEnabled, draft.serviceAccountJson, draft.useDeploymentCredentials, draft.apnsEnabled, draft.teamId, draft.keyId, draft.bundleId, draft.privateKey, draft.clearPrivateKey, draft.sandbox, ctx)

    @Test fun `Mux preserves fresh credentials and custom fields while clearing default encoding overrides`() = runTest {
        var stored: JsonElement = Json.parseToJsonElement("""{"tokenId":"stored-id","tokenSecret":"stored-secret","videoQuality":"premium","maxResolutionTier":"2160p","streamBaseUrl":"https://video.example.test"}""")
        val ctx = context { operation, variables ->
            if (operation == "SystemSetConfiguration") {
                val input = requireNotNull(variables).getValue("input").jsonObject
                assertTrue(input["permissions"] == null || input["permissions"] is JsonNull)
                assertFalse(input.getValue("public").jsonPrimitive.boolean)
                stored = input.getValue("value")
                Json.parseToJsonElement("""{"configurations":{"setConfiguration":{"id":"config"}}}""")
            } else configuration(stored)
        }
        val model = SystemMuxModel()
        model.reload(ctx)
        assertEquals("premium", model.draft.videoQuality)
        assertTrue(model.hasTokenId && model.hasTokenSecret)
        assertFalse(Json.encodeToString(model).contains("stored-secret"))
        stored = JsonObject(stored.jsonObject + ("tokenSecret" to JsonPrimitive("newer-secret")))
        save(model, SystemMuxDraft(playbackPolicy = "signed", maxWaitSeconds = "7200", mp4Support = false, test = true), ctx)
        assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals("newer-secret", stored.jsonObject.getValue("tokenSecret").jsonPrimitive.content)
        assertEquals("stored-id", stored.jsonObject.getValue("tokenId").jsonPrimitive.content)
        assertEquals("https://video.example.test", stored.jsonObject.getValue("streamBaseUrl").jsonPrimitive.content)
        assertFalse("videoQuality" in stored.jsonObject || "maxResolutionTier" in stored.jsonObject)
        assertEquals(7200L, stored.jsonObject.getValue("maxWaitSeconds").jsonPrimitive.long)
        save(model, model.draft.copy(clearTokenId = true, tokenSecret = "replacement"), ctx)
        assertEquals("", stored.jsonObject.getValue("tokenId").jsonPrimitive.content)
        assertEquals("replacement", stored.jsonObject.getValue("tokenSecret").jsonPrimitive.content)
        assertEquals("", model.draft.tokenSecret)
    }

    @Test fun `Mux validation and denied saves preserve entered settings`() = runTest {
        val model = SystemMuxModel(loaded = true)
        save(model, SystemMuxDraft(tokenSecret = "entered", maxWaitSeconds = "-1"), context { _, _ -> error("Must not query") })
        assertEquals("Max wait must be at least 60 seconds.", model.feedback.message)
        assertEquals("entered", model.draft.tokenSecret)
        save(model, SystemMuxDraft(videoQuality = "unknown"), context { _, _ -> error("Must not query") })
        assertEquals("Choose a video quality.", model.feedback.message)
        save(model, SystemMuxDraft(playbackPolicy = "signed", tokenSecret = "entered"), context { _, _ -> error("Denied configuration read") })
        assertEquals("Denied configuration read", model.feedback.message)
        assertEquals("signed", model.draft.playbackPolicy)
        assertEquals("entered", model.draft.tokenSecret)
    }

    @Test fun `push retains stored credentials and supports ADC explicit clearing and provider removal`() = runTest {
        var stored: JsonElement = Json.parseToJsonElement("""{"enabled":true,"fcm":{"serviceAccountJson":"credential-json"},"apns":{"teamId":"team","keyId":"key","bundleId":"app","privateKey":"stored-private","sandbox":false}}""")
        val ctx = context { operation, variables ->
            if (operation == "SystemSetConfiguration") {
                val input = requireNotNull(variables).getValue("input").jsonObject
                assertTrue(input["permissions"] == null || input["permissions"] is JsonNull)
                stored = input.getValue("value")
                Json.parseToJsonElement("""{"configurations":{"setConfiguration":{"id":"config"}}}""")
            } else configuration(stored)
        }
        val model = SystemPushModel()
        model.reload(ctx)
        assertTrue(model.hasServiceAccount && model.hasPrivateKey)
        assertFalse(model.draft.sandbox)
        assertFalse(Json.encodeToString(model).contains("stored-private"))
        assertFalse(Json.encodeToString(model).contains("credential-json"))
        save(model, model.draft.copy(enabled = false, teamId = "updated-team"), ctx)
        assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals("stored-private", stored.jsonObject.getValue("apns").jsonObject.getValue("privateKey").jsonPrimitive.content)
        assertEquals("credential-json", stored.jsonObject.getValue("fcm").jsonObject.getValue("serviceAccountJson").jsonPrimitive.content)
        save(model, model.draft.copy(useDeploymentCredentials = true, clearPrivateKey = true), ctx)
        assertFalse("serviceAccountJson" in stored.jsonObject.getValue("fcm").jsonObject)
        assertFalse("privateKey" in stored.jsonObject.getValue("apns").jsonObject)
        assertFalse(model.hasServiceAccount || model.hasPrivateKey)
        save(model, model.draft.copy(fcmEnabled = false, apnsEnabled = false), ctx)
        assertFalse("fcm" in stored.jsonObject || "apns" in stored.jsonObject)
    }

    @Test fun `push rejects malformed structured credentials and retains the entire draft for recovery`() = runTest {
        val model = SystemPushModel(loaded = true)
        val draft = SystemPushDraft(enabled = true, fcmEnabled = true, serviceAccountJson = "{invalid", apnsEnabled = true, teamId = "team", privateKey = "entered")
        save(model, draft, context { _, _ -> error("Must not query malformed credentials") })
        assertTrue(model.feedback.failed)
        assertEquals(draft, model.draft)
        save(model, draft.copy(serviceAccountJson = "[]"), context { _, _ -> error("Must not query array credentials") })
        assertTrue(model.feedback.message.contains("must be an object"))
        save(model, draft.copy(fcmEnabled = false), context { _, _ -> error("Denied read") })
        assertEquals("Denied read", model.feedback.message)
    }

    @Test fun `push accepts incomplete APNs settings and replacement service-account objects without exposing them after save`() = runTest {
        val model = SystemPushModel(loaded = true)
        var stored: JsonElement = JsonObject(emptyMap())
        val ctx = context { operation, variables ->
            if (operation == "SystemSetConfiguration") {
                stored = requireNotNull(variables).getValue("input").jsonObject.getValue("value")
                Json.parseToJsonElement("""{"configurations":{"setConfiguration":{"id":"config"}}}""")
            } else configuration(stored)
        }
        save(model, SystemPushDraft(fcmEnabled = true, serviceAccountJson = "{\"project_id\":\"fixture\"}", apnsEnabled = true, teamId = " team "), ctx)
        assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals("team", stored.jsonObject.getValue("apns").jsonObject.getValue("teamId").jsonPrimitive.content)
        assertFalse("privateKey" in stored.jsonObject.getValue("apns").jsonObject)
        assertEquals("{\"project_id\":\"fixture\"}", stored.jsonObject.getValue("fcm").jsonObject.getValue("serviceAccountJson").jsonPrimitive.content)
        assertEquals("null", model.draft.serviceAccountJson)
    }
}
