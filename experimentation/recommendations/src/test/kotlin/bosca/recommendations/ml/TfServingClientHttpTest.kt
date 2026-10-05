package bosca.recommendations.ml

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises [TfServingClient]'s HTTP methods against a mock TF Serving: the empty-input short-circuits, the
 * non-2xx error paths, the retrieve/similar/rank happy paths, and the defensive [TfServingClient] score
 * parsing across TF Serving's various output wrappings (scalar / single-array / {score}/{scores}/{output_0}).
 */
class TfServingClientHttpTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient()

    private fun client() = TfServingClient(
        TfServingConfiguration(url = server.url("/").toString().trimEnd('/'), modelVersion = 7),
        http,
        json,
    )

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun teardown() = server.close()

    private fun enqueue(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `model pages carry captured IDs offsets signatures and exact version`() = runTest {
        val source = bosca.serialization.UUID.random()
        val profile = bosca.serialization.UUID.random()
        val input = bosca.recommendations.model.RecommendationInference(17, true, "reading", "fr", profile, source)
        for ((value, behavioral, signature) in listOf(
            Triple(input, false, "related"), Triple(input.copy(profileId = null), false, "related"), Triple(input, true, "co_engaged"),
            Triple(input.copy(sourceId = null), false, "feed_page"),
            Triple(input.copy(personalized = false), false, "similar_page"),
        )) {
            enqueue(200, """{"predictions":[{"content_ids":["a"],"scores":[0.7]}]}""")
            assertEquals(listOf(Prediction("a", .7)), client().predictPage(value, 20, behavioral))
            val request = server.takeRequest()
            assertTrue(request.url.encodedPath.contains("/versions/17:predict"))
            val body = request.body!!.utf8()
            assertTrue(body.contains("\"signature_name\":\"$signature\""))
            assertTrue(body.contains("\"offset\":20"))
            assertTrue(body.contains("\"context_type\":\"reading\""))
        }
    }

    @Test
    fun `explanations batch candidates and omit zero negative or unavailable effects`() = runTest {
        val input = bosca.recommendations.model.RecommendationInference(17, true, "reading", "en")
        enqueue(200, """{"predictions":[[0.5,-0.1,0,0.2],[0.000001,1,0.2,0]]}""")
        assertEquals(listOf(
            listOf(bosca.recommendations.model.RecommendationSource.CONTENT_MODEL, bosca.recommendations.model.RecommendationSource.COHORT_CO_ENGAGEMENT),
            listOf(bosca.recommendations.model.RecommendationSource.PERSONALIZED_MODEL, bosca.recommendations.model.RecommendationSource.CO_ENGAGEMENT),
        ), client().explain(input, listOf("a", "b")))
        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().body!!.utf8().contains("\"signature_name\":\"explain\""))
        enqueue(503, "unavailable")
        assertEquals(listOf(emptyList()), client().explain(input, listOf("a")))
    }

    @Test
    fun `unavailable explanation entries remain aligned and empty`() = runTest {
        val input = bosca.recommendations.model.RecommendationInference(17, true, "reading", "en")
        assertTrue(client().explain(input, emptyList()).isEmpty())
        assertEquals(0, server.requestCount)
        for (body in listOf(
            "{}", """{"predictions":[null]}""", """{"predictions":[{}]}""",
            """{"predictions":[[]]}""",
            """{"predictions":[{"contributions":{}}]}""",
            """{"predictions":[[{},[],{},[]]]}""",
            """{"predictions":[[null,"invalid","NaN","Infinity"]]}""",
        )) {
            enqueue(200, body)
            assertEquals(listOf(emptyList(), emptyList()), client().explain(input, listOf("a", "b")))
        }
        enqueue(200, """{"predictions":[]}""")
        assertTrue(client().predictPage(input, 0).isEmpty())
        kotlin.test.assertFailsWith<IllegalArgumentException> { client().predictPage(input.copy(personalized = false), 0) }
    }

    // ── predict / predictForUser ─────────────────────────────────────────────────────────────────

    @Test
    fun `predict short-circuits to empty for no user ids without calling the server`() = runTest {
        assertTrue(client().predict(emptyList(), "default", "en").isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `predict returns empty on a non-2xx response`() = runTest {
        enqueue(500, "boom")
        assertTrue(client().predict(listOf("u1"), "default", "en").isEmpty())
    }

    @Test
    fun `predictForUser parses content ids and scores and drops blank oov rows`() = runTest {
        enqueue(200, """{"predictions":[{"content_ids":["a","","b"],"scores":[0.9,0.0,0.5]}]}""")
        val result = client().predictForUser("u1", "images", "fr")
        assertEquals(listOf("a", "b"), result.map { it.contentId })
        assertEquals(0.9, result.first().score)
        val requestBody = server.takeRequest().body!!.utf8()
        assertTrue(requestBody.contains("\"context_type\":\"images\""))
        assertTrue(requestBody.contains("\"language_tag\":\"fr\""))
    }

    @Test
    fun `predict maps a user with no corresponding prediction to an empty list`() = runTest {
        // Two users but only one prediction row → the second is index >= predictions.size.
        enqueue(200, """{"predictions":[{"content_ids":["a"],"scores":[0.9]}]}""")
        val result = client().predict(listOf("u1", "u2"), "default", "en")
        assertEquals(listOf("a"), result["u1"]?.map { it.contentId })
        assertTrue(result["u2"]!!.isEmpty())
    }

    @Test
    fun `predictForUser returns empty when a single prediction is missing scores`() = runTest {
        enqueue(200, """{"predictions":[{"content_ids":["a"]}]}""") // no scores/output_1 → emptyList
        assertTrue(client().predictForUser("u1", "default", "en").isEmpty())
    }

    // ── predictSimilar / predictSimilarForItem ───────────────────────────────────────────────────

    @Test
    fun `context content requests use their exact selected model name and version`() = runTest {
        val contextClient = TfServingClient(
            TfServingConfiguration(url = server.url("/").toString(),
                contentModelName = "recommender-test-content", contentModelVersion = 17), http, json,
        )
        enqueue(200, """{"predictions":[{"content_ids":["a"],"scores":[0.9]}]}""")
        assertEquals(listOf("a"), contextClient.predictSimilarForItem("source", "reading", "en").map { it.contentId })
        assertEquals("/v1/models/recommender-test-content/versions/17:predict", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `predictSimilar short-circuits for no content ids`() = runTest {
        assertTrue(client().predictSimilar(emptyList(), "default", "en").isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `predictSimilar returns empty on a non-2xx response`() = runTest {
        enqueue(503, "down")
        assertTrue(client().predictSimilar(listOf("m1"), "default", "en").isEmpty())
    }

    @Test
    fun `predictSimilarForItem returns the neighbours for a source item`() = runTest {
        enqueue(200, """{"predictions":[{"content_ids":["m1","m2"],"scores":[1.0,0.8]}]}""")
        assertEquals(listOf("m1", "m2"), client().predictSimilarForItem("m1", "videos", "es").map { it.contentId })
        val request = server.takeRequest()
        assertTrue(request.body!!.utf8().contains("\"context_type\":\"videos\""))
        assertTrue(request.body!!.utf8().contains("\"language_tag\":\"es\""))
        assertTrue(request.body!!.utf8().contains("\"signature_name\":\"similar\""))
    }

    // ── predictSimilarUsers / predictSimilarUsersForUser ─────────────────────────────────────────

    @Test
    fun `predictSimilarUsers short-circuits for no user ids`() = runTest {
        assertTrue(client().predictSimilarUsers(emptyList()).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `predictSimilarUsers returns empty on a non-2xx response`() = runTest {
        enqueue(503, "down")
        assertTrue(client().predictSimilarUsers(listOf("u1")).isEmpty())
    }

    @Test
    fun `predictSimilarUsersForUser returns the viewer's nearest users`() = runTest {
        // Neighbor ids ride the content_ids field; the self-match (u1) is included for the caller to drop.
        enqueue(200, """{"predictions":[{"content_ids":["u1","u2","u3"],"scores":[1.0,0.9,0.7]}]}""")
        val result = client().predictSimilarUsersForUser("u1")
        assertEquals(listOf("u1", "u2", "u3"), result.map { it.contentId })
        assertEquals(1.0, result.first().score)
    }

    @Test
    fun `predictSimilarUsersForUser returns empty when the batch request fails`() = runTest {
        enqueue(503, "down")

        assertTrue(client().predictSimilarUsersForUser("u1").isEmpty())
    }

    // ── rank + scoreOf shapes ────────────────────────────────────────────────────────────────────

    @Test
    fun `rank short-circuits for no candidates`() = runTest {
        assertTrue(client().rank("u1", emptyList()).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `rank returns empty on a non-2xx response`() = runTest {
        enqueue(500, "err")
        assertTrue(client().rank("u1", listOf("a")).isEmpty())
    }

    @Test
    fun `rank returns empty when the response has no predictions array`() = runTest {
        enqueue(200, """{"nope":1}""")
        assertTrue(client().rank("u1", listOf("a")).isEmpty())
    }

    @Test
    fun `rank parses every TF Serving score wrapping and drops unparseable ones`() = runTest {
        // Positionally aligned to the candidates: scalar, single-array, empty-array(null), {scores},
        // {score}, {output_0}, non-numeric(null). The two nulls are dropped from the result map.
        enqueue(
            200,
            """{"predictions":[0.1,[0.2],[],{"scores":[0.3]},{"score":0.4},{"output_0":0.5},"nan"]}""",
        )
        val ids = listOf("scalar", "arr", "emptyArr", "objScores", "objScore", "objOut0", "bad")
        val result = client().rank("u1", ids)
        assertEquals(0.1, result["scalar"])
        assertEquals(0.2, result["arr"])
        assertEquals(0.3, result["objScores"])
        assertEquals(0.4, result["objScore"])
        assertEquals(0.5, result["objOut0"])
        assertTrue("emptyArr" !in result)
        assertTrue("bad" !in result)
    }

    @Test
    fun `rank ignores candidates beyond the returned prediction count`() = runTest {
        enqueue(200, """{"predictions":[0.9]}""")
        val result = client().rank("u1", listOf("a", "b"))
        assertEquals(mapOf("a" to 0.9), result)
    }

    @Test
    fun `rank drops null and unrecognized-object score wrappings`() = runTest {
        // null → the scoreOf `else` branch; an object without score/scores/output_0 → all-null → dropped.
        enqueue(200, """{"predictions":[null,{"foo":1}]}""")
        assertTrue(client().rank("u1", listOf("a", "b")).isEmpty())
    }

    @Test
    fun `rank returns empty on a malformed response body`() = runTest {
        enqueue(200, "not json")
        assertTrue(client().rank("u1", listOf("a")).isEmpty())
    }

    @Test
    fun `predict returns empty on a malformed response body`() = runTest {
        enqueue(200, "not json")
        assertTrue(client().predict(listOf("u1"), "default", "en").isEmpty())
    }

    @Test
    fun `predict maps a non-object prediction row to an empty list`() = runTest {
        enqueue(200, """{"predictions":[5]}""") // a bare scalar → parseSinglePrediction catch → empty
        assertTrue(client().predictForUser("u1", "default", "en").isEmpty())
    }

    @Test
    fun `predict maps a prediction object without content ids to an empty list`() = runTest {
        enqueue(200, """{"predictions":[{"scores":[0.9]}]}""") // no content_ids/output_0 → empty
        assertTrue(client().predictForUser("u1", "default", "en").isEmpty())
    }

    @Test
    fun `rank drops an array score whose first element is not a primitive`() = runTest {
        enqueue(200, """{"predictions":[[[0.1]]]}""") // array-of-array → first is an array → not a primitive
        assertTrue(client().rank("u1", listOf("a")).isEmpty())
    }

    @Test
    fun `predictForUser returns empty when the batch call fails`() = runTest {
        enqueue(500, "boom")
        assertTrue(client().predictForUser("u1", "default", "en").isEmpty())
    }

    @Test
    fun `predictSimilarForItem returns empty when the batch call fails`() = runTest {
        enqueue(500, "boom")
        assertTrue(client().predictSimilarForItem("m1", "default", "en").isEmpty())
    }

    @Test
    fun `clients apply independent model timeouts while sharing the transport`() = runTest {
        repeat(2) { enqueue(200, """{"predictions":[]}""") }
        val observedTimeouts = CopyOnWriteArrayList<Triple<Int, Int, Int>>()
        val sharedTransport = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                observedTimeouts += Triple(
                    chain.connectTimeoutMillis(),
                    chain.readTimeoutMillis(),
                    chain.writeTimeoutMillis(),
                )
                chain.proceed(chain.request())
            }
            .build()
        val shortTimeoutClient = TfServingClient(
            TfServingConfiguration(
                url = server.url("/").toString().trimEnd('/'),
                timeoutSeconds = 1,
            ),
            sharedTransport,
            json,
        )
        val longTimeoutClient = TfServingClient(
            TfServingConfiguration(
                url = server.url("/").toString().trimEnd('/'),
                timeoutSeconds = 3,
            ),
            sharedTransport,
            json,
        )

        assertTrue(shortTimeoutClient.predictSimilarForItem("m1", "default", "en").isEmpty())
        assertTrue(longTimeoutClient.predictSimilarForItem("m1", "default", "en").isEmpty())
        assertEquals(
            listOf(Triple(1_000, 1_000, 1_000), Triple(3_000, 3_000, 3_000)),
            observedTimeouts,
        )
        assertEquals(4_000, sharedTransport.connectTimeoutMillis)
        assertEquals(5_000, sharedTransport.readTimeoutMillis)
        assertEquals(6_000, sharedTransport.writeTimeoutMillis)
    }
}
