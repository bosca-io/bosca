package bosca.recommendations.ml

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TfServingClientTest {

    // The service owns these; the client just receives them. A single shared pair here mirrors that.
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private fun client(config: TfServingConfiguration) = TfServingClient(config, http, json)

    // buildPredictUrl(modelName, modelVersion?) — the caller passes the personalized model + its pinned
    // version for predict/rank, or the content model (latest, no version) for similar.
    private fun buildPredictUrl(client: TfServingClient, modelName: String, modelVersion: Long?): String {
        val method = TfServingClient::class.java.getDeclaredMethod(
            "buildPredictUrl", String::class.java, Long::class.javaObjectType,
        )
        method.isAccessible = true
        return method.invoke(client, modelName, modelVersion) as String
    }

    @Test
    fun `predict URL without model version omits version path`() {
        val client = client(TfServingConfiguration(url = "http://tf-serving:8501"))
        val url = buildPredictUrl(client, "recommender-personalized", null)
        assertEquals("http://tf-serving:8501/v1/models/recommender-personalized:predict", url)
    }

    @Test
    fun `predict URL with model version includes version path`() {
        val client = client(TfServingConfiguration(url = "http://tf-serving:8501"))
        val url = buildPredictUrl(client, "recommender-personalized", 7L)
        assertEquals("http://tf-serving:8501/v1/models/recommender-personalized/versions/7:predict", url)
    }

    @Test
    fun `predict URL trims trailing slash from base URL`() {
        val client = client(TfServingConfiguration(url = "http://tf-serving:8501/"))
        val url = buildPredictUrl(client, "recommender-content", null)
        assertEquals("http://tf-serving:8501/v1/models/recommender-content:predict", url)
    }

    @Test
    fun `blank content ids from an out-of-vocab user are dropped`() {
        // The personalized model blanks unknown users' rows to empty content ids; those must not survive
        // (downstream parses them as UUIDs, and an all-blank response means "no personalized result").
        val config = TfServingConfiguration()
        val client = client(config)
        val method = TfServingClient::class.java.getDeclaredMethod("parsePredictions", List::class.java, String::class.java)
        method.isAccessible = true
        val responseBody = """{"predictions":[{"content_ids":["","item-b",""],"scores":[0.0,0.8,0.0]}]}"""
        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(client, listOf("cold-user"), responseBody) as Map<String, List<Prediction>>
        val predictions = result["cold-user"]!!
        assertEquals(1, predictions.size)
        assertEquals("item-b", predictions[0].contentId)
    }

    @Test
    fun `request body has correct JSON structure`() {
        val config = TfServingConfiguration()
        val client = client(config)
        val method = TfServingClient::class.java.getDeclaredMethod(
            "buildRequestBody",
            List::class.java,
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        val body = method.invoke(client, listOf("user1", "user2"), "default", "en") as String
        assertTrue(body.contains("\"instances\""))
        assertTrue(body.contains("\"user_id\""))
        assertTrue(body.contains("\"context_type\""))
        assertTrue(body.contains("\"language_tag\""))
        assertTrue(body.contains("\"default\""))
        assertTrue(body.contains("\"user1\""))
        assertTrue(body.contains("\"user2\""))
    }

    @Test
    fun `parsePredictions returns empty map for missing predictions key`() {
        val config = TfServingConfiguration()
        val client = client(config)
        val method = TfServingClient::class.java.getDeclaredMethod("parsePredictions", List::class.java, String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(client, listOf("user1"), "{}") as Map<String, List<Prediction>>
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parsePredictions extracts content IDs and scores`() {
        val config = TfServingConfiguration()
        val client = client(config)
        val method = TfServingClient::class.java.getDeclaredMethod("parsePredictions", List::class.java, String::class.java)
        method.isAccessible = true
        val responseBody = """
            {
                "predictions": [
                    {
                        "content_ids": ["item-a", "item-b"],
                        "scores": [0.95, 0.80]
                    }
                ]
            }
        """.trimIndent()
        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(client, listOf("user1"), responseBody) as Map<String, List<Prediction>>
        assertEquals(1, result.size)
        val predictions = result["user1"]!!
        assertEquals(2, predictions.size)
        assertEquals("item-a", predictions[0].contentId)
        assertEquals(0.95, predictions[0].score)
        assertEquals("item-b", predictions[1].contentId)
        assertEquals(0.80, predictions[1].score)
    }

    @Test
    fun `parsePredictions handles output_0 and output_1 aliases`() {
        val config = TfServingConfiguration()
        val client = client(config)
        val method = TfServingClient::class.java.getDeclaredMethod("parsePredictions", List::class.java, String::class.java)
        method.isAccessible = true
        val responseBody = """
            {
                "predictions": [
                    {
                        "output_0": ["item-x"],
                        "output_1": [0.75]
                    }
                ]
            }
        """.trimIndent()
        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(client, listOf("u1"), responseBody) as Map<String, List<Prediction>>
        val predictions = result["u1"]!!
        assertEquals(1, predictions.size)
        assertEquals("item-x", predictions[0].contentId)
        assertEquals(0.75, predictions[0].score)
    }

    @Test
    fun `Prediction data class stores fields correctly`() {
        val prediction = Prediction(contentId = "abc-123", score = 0.42)
        assertEquals("abc-123", prediction.contentId)
        assertEquals(0.42, prediction.score)
    }
}
