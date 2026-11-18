package bosca.kubernetes.controller.models

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServingModelDiscoveryTest {
    @Test
    fun `terminal failure reports use a write credential and preserve rejected or failed reports`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val profile = ServingModelResourcesTest.profile(mapOf("boscaUrl" to server.url("/").toString()))
            val discovery = ServingModelDiscovery(OkHttpClient(), Json)
            for (accepted in listOf(true, false)) {
                server.enqueue(MockResponse(body = """{"data":{"recommendation":{"contexts":{"modelServingFailed":$accepted}}}}"""))
                assertEquals(accepted, discovery.failModel(profile, "write-token", 4, 1, "missing artifact"))
                val request = server.takeRequest()
                assertEquals("Bearer write-token", request.headers["Authorization"])
                kotlin.test.assertTrue(request.body?.utf8().orEmpty().contains("missing artifact"))
            }
            server.enqueue(MockResponse(code = 403))
            assertFailsWith<IllegalStateException> { discovery.failModel(profile, "pull-token", 4, 1, "denied") }
            server.enqueue(MockResponse(body = """{"errors":[{"message":"denied"}]}"""))
            assertFailsWith<IllegalStateException> { discovery.failModel(profile, "pull-token", 4, 1, "denied") }
            Unit
        }
    }

    @Test
    fun `prepared reports carry the claimed revision and endpoint and reject transport failures`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val profile = ServingModelResourcesTest.profile(mapOf("boscaUrl" to server.url("/").toString()))
            val discovery = ServingModelDiscovery(OkHttpClient(), Json)
            val endpoint = "http://serving-4.test.svc:8501"
            for (accepted in listOf(true, false)) {
                server.enqueue(MockResponse(body = """{"data":{"recommendation":{"contexts":{"modelServingPrepared":$accepted}}}}"""))
                assertEquals(accepted, discovery.prepareModel(profile, "control", 4, 7, endpoint))
                val request = server.takeRequest()
                assertEquals("Bearer control", request.headers["Authorization"])
                val variables = Json.parseToJsonElement(requireNotNull(request.body).utf8()).jsonObject.getValue("variables").jsonObject
                assertEquals("7", variables.getValue("selectionRevision").jsonPrimitive.content)
                assertEquals(endpoint, variables.getValue("endpoint").jsonPrimitive.content)
            }
            for (response in listOf(MockResponse(code = 403), MockResponse(body = """{"errors":[{"message":"denied"}]}"""), MockResponse(body = "{}"))) {
                server.enqueue(response)
                assertFailsWith<Exception> { discovery.prepareModel(profile, "control", 4, 7, endpoint) }
            }
            Unit
        }
    }

    @Test
    fun `discovery uses artifact authentication and retains active and pending versions separately`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = """{"data":{"recommendation":{"contexts":{
                "all":[{"id":"context","selectionRevision":1,"activeModelVersion":3,"requestedModelVersion":null}],
                "servingModels":[{"contextId":"context","version":4,"selectionRevision":1,"status":"RUNNING",
                "contentModelName":"content","personalizedModelName":"personalized","personalized":true}]
            }}}}""".trimIndent()))
            val profile = ServingModelResourcesTest.profile(mapOf("boscaUrl" to server.url("/").toString()))
            val (contexts, exports) = ServingModelDiscovery(OkHttpClient(), Json).discover(profile, "pull-token")
            assertEquals(3, contexts.single().activeModelVersion)
            assertEquals(mapOf("content" to 4L, "personalized" to 4L), exports.single().models)
            val request = server.takeRequest()
            assertEquals("/graphql", request.url.encodedPath)
            assertEquals("Bearer pull-token", request.headers["Authorization"])
        }
    }

    @Test
    fun `http graphql and malformed responses are failures rather than empty selections`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val profile = ServingModelResourcesTest.profile(mapOf("boscaUrl" to server.url("/").toString()))
            val discovery = ServingModelDiscovery(OkHttpClient(), Json)
            for (response in listOf(MockResponse(code = 503), MockResponse(body = """{"errors":[{"message":"denied"}]}"""),
                MockResponse(body = "{}"))) {
                server.enqueue(response)
                assertFailsWith<Exception> { discovery.discover(profile, "token") }
            }
        }
    }
}
