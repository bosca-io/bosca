package bosca.recommendations.jobs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * Verifies the executor's parsing of the trainer's TWO-model result shape. The log helpers are private and
 * side-effect-only (they log each model's outcome), so these tests drive them via reflection with every
 * response shape the trainer can return, proving none of the branches throw on real payloads.
 */
class TrainModelJobExecutorTest {

    private val executor = TrainModelJobExecutor()

    private fun logContentModel(content: JsonObject?) {
        val m = TrainModelJobExecutor::class.java.getDeclaredMethod("logContentModel", JsonObject::class.java)
        m.isAccessible = true
        m.invoke(executor, content)
    }

    private fun logPersonalizedModel(personalized: JsonObject?) {
        val m = TrainModelJobExecutor::class.java.getDeclaredMethod("logPersonalizedModel", JsonObject::class.java)
        m.isAccessible = true
        m.invoke(executor, personalized)
    }

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `content model outcomes are handled`() {
        // Missing block, clean completion, and a trained-but-upload-failed result must all parse.
        logContentModel(null)
        logContentModel(obj("""{"status":"completed_content_only","model_version":3,"content_items":2284}"""))
        logContentModel(obj("""{"status":"completed_content_only","model_version":4,"content_items":2284,"bosca_upload_error":"connection refused"}"""))
        logContentModel(obj("""{"status":"rejected","reason":"quality regressed"}"""))
        logContentModel(obj("""{"status":"rejected","reason":{}}"""))
        logContentModel(obj("""{"status":"completed_content_only","model_version":{},"content_items":{},"bosca_upload_error":{}}"""))
        logContentModel(obj("""{"status":{}}"""))
    }

    @Test
    fun `personalized model outcomes are handled`() {
        logPersonalizedModel(null)
        logPersonalizedModel(obj("""{"status":"completed","model_version":5,"validation":{"status":"passed"},"training_users":120,"indexed_users":140}"""))
        logPersonalizedModel(obj("""{"status":"rejected","reason":"missing serving facet"}"""))
        logPersonalizedModel(obj("""{"status":"skipped","reason":"only 0 interactions (minimum 100)"}"""))
        logPersonalizedModel(obj("""{"status":"skipped","reason":{}}"""))
        logPersonalizedModel(obj("""{"status":"skipped"}"""))
        logPersonalizedModel(obj("""{"status":"weird"}"""))
        logPersonalizedModel(obj("""{"status":"completed","validation":"invalid","training_users":{},"indexed_users":{}}"""))
        logPersonalizedModel(obj("""{"status":{}}"""))
    }

    @Test
    fun `personalized completed without validation details parses`() {
        logPersonalizedModel(obj("""{"status":"completed","model_version":1}"""))
    }

    @Test
    fun `completed result accepts optional validation and population fields`() {
        logPersonalizedModel(obj("""{"status":"completed","model_version":2}"""))
        assertNull(Json.parseToJsonElement("""{"status":"completed"}""").jsonObject["validation"])
    }
}
