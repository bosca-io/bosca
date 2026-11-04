package bosca.system.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class FederationActionsTest {
    private fun context(handler: suspend (String, JsonObject?) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables)
    })

    @Test fun `registration validates URLs and keeps every field after rejected writes`() = runTest {
        val model = SystemFederationModel(admin = true)
        val noQuery = context { _, _ -> error("Must not query") }
        model.register("Peer", "peer.example.test", "https://peer.example.test", "secret", noQuery)
        assertTrue(model.feedback.message.contains("absolute NATS URL"))
        assertEquals("peer.example.test", model.draft.natsUrl)
        model.register("Peer", "tls://peer.example.test:7422", "ftp://peer.example.test", "secret", noQuery)
        assertEquals("API URL must use HTTP or HTTPS.", model.feedback.message)
        for (url in listOf("tls://peer.example.test:7422", "nats://peer_dev:4222", "nats://bøsca.example:4222")) {
            model.register(" Peer ", url, "HTTPS://peer.example.test", " secret ", context { operation, variables ->
                assertEquals("SystemRegisterFederationPeer", operation)
                assertEquals("Peer", variables?.getValue("input")?.jsonObject?.getValue("name")?.jsonPrimitive?.content)
                assertEquals("secret", variables?.getValue("input")?.jsonObject?.getValue("sharedSecret")?.jsonPrimitive?.content)
                error("Denied write")
            })
            assertEquals("Denied write", model.feedback.message, url)
        }
        assertEquals("Denied write", model.feedback.message)
        assertEquals(" secret ", model.draft.secret)
        assertEquals("register", model.mode)
        model.cancel()
        assertEquals("", model.draft.secret)
        assertEquals("", model.mode)
    }

    @Test fun `blank secret clears using null and successful actions discard typed secrets`() = runTest {
        val model = SystemFederationModel(admin = true)
        for (value in listOf("replacement", " ")) {
            model.setSecret("peer", value, context { operation, variables ->
                assertEquals("SystemSetFederationSecret", operation)
                if (value.isBlank()) assertTrue(variables?.get("secret") == null || variables["secret"] is JsonNull)
                else assertEquals(value, variables?.get("secret")?.jsonPrimitive?.content)
                Json.parseToJsonElement("""{"collaboration":{"federation":{"setSharedSecret":true}}}""")
            })
            assertFalse(model.feedback.failed, model.feedback.message)
            assertEquals("", model.nextSecret)
            assertEquals("", model.mode)
        }
        model.setSecret("deleted-peer", "draft", context { _, _ -> Json.parseToJsonElement("""{"collaboration":{"federation":{"setSharedSecret":false}}}""") })
        assertTrue(model.feedback.failed)
        assertEquals("draft", model.nextSecret)
        assertEquals("secret", model.mode)
    }

    @Test fun `deactivation requires confirmation and reloads active peers only after success`() = runTest {
        val model = SystemFederationModel(admin = true)
        model.deactivate("peer", false, context { _, _ -> error("Must not query") })
        assertEquals("Confirm peer deactivation.", model.feedback.message)
        val operations = mutableListOf<String>()
        model.deactivate("peer", true, context { operation, _ ->
            operations += operation
            Json.parseToJsonElement(if (operation == "SystemDeactivateFederationPeer") """{"collaboration":{"federation":{"deactivatePeer":true}}}""" else """{"collaboration":{"federation":{"peers":[]}}}""")
        })
        assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals(listOf("SystemDeactivateFederationPeer", "SystemFederationPeers"), operations)
        assertTrue(model.peers.isEmpty())
    }
}
