package bosca.recommendations.ml

import bosca.server.http.await
import bosca.recommendations.model.RecommendationInference
import bosca.recommendations.model.RecommendationSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Client for calling TensorFlow Serving's REST prediction API to retrieve
 * personalized content recommendations from a trained TFRS model. Each
 * prediction request sends a batch of user IDs and receives back ranked
 * content IDs with scores.
 *
 * The model is expected to accept user IDs as input and return two outputs:
 * - `output_0` or `content_ids`: array of recommended content IDs (strings)
 * - `output_1` or `scores`: array of corresponding relevance scores (floats)
 */
class TfServingClient(
    private val config: TfServingConfiguration,
    // Owned by the calling service (a DI singleton). The lightweight derived client below shares its
    // connection pool and dispatcher while applying the timeout selected for this model configuration.
    sharedHttpClient: OkHttpClient,
    private val json: Json,
) {

    /** Reads one page of the model's final ranking; offset refers to the frozen eligible catalog order. */
    suspend fun predictPage(input: RecommendationInference, offset: Int, coEngagedOnly: Boolean = false): List<Prediction> {
        val fields = linkedMapOf<String, JsonElement>(
            "context_type" to JsonPrimitive(input.contextType),
            "language_tag" to JsonPrimitive(input.languageTag),
            "offset" to JsonPrimitive(offset),
        )
        val signature = if (input.personalized) {
            fields["user_id"] = JsonPrimitive(input.profileId?.toString().orEmpty())
            if (input.sourceId != null) {
                fields["source_id"] = JsonPrimitive(input.sourceId.toString())
                if (coEngagedOnly) "co_engaged" else "related"
            } else "feed_page"
        } else {
            fields["content_id"] = JsonPrimitive(requireNotNull(input.sourceId).toString())
            "similar_page"
        }
        val body = requestModel(input, signature, listOf(JsonObject(fields)))
            ?: throw java.io.IOException("Recommendation model ${input.modelVersion} could not serve $signature")
        return parsePredictions(listOf("request"), body)["request"].orEmpty()
    }

    /** Same-version source ablation for returned items. Positive score changes above 1e-6 are reported. */
    suspend fun explain(input: RecommendationInference, contentIds: List<String>): List<List<RecommendationSource>> {
        if (contentIds.isEmpty()) return emptyList()
        val instances = contentIds.map { id -> JsonObject(mapOf(
            "user_id" to JsonPrimitive(input.profileId?.toString().orEmpty()),
            "source_id" to JsonPrimitive(input.sourceId?.toString().orEmpty()),
            "content_id" to JsonPrimitive(id),
        )) }
        val body = requestModel(input, "explain", instances) ?: return List(contentIds.size) { emptyList() }
        val predictions = json.parseToJsonElement(body).jsonObject["predictions"]?.jsonArray
        val groups = listOf(RecommendationSource.CONTENT_MODEL, RecommendationSource.PERSONALIZED_MODEL,
            RecommendationSource.CO_ENGAGEMENT, RecommendationSource.COHORT_CO_ENGAGEMENT)
        return contentIds.indices.map { index ->
            // TF Serving's row format omits the output name for a single-output signature.
            val values = predictions?.getOrNull(index) as? JsonArray
            groups.filterIndexed { group, _ ->
                val value = (values?.getOrNull(group) as? JsonPrimitive)?.doubleOrNull
                value != null && value.isFinite() && value > 1e-6
            }
        }
    }

    private suspend fun requestModel(input: RecommendationInference, signature: String, instances: List<JsonObject>): String? {
        val body = JsonObject(mapOf("signature_name" to JsonPrimitive(signature), "instances" to JsonArray(instances)))
        val request = Request.Builder()
            .url(buildPredictUrl(if (input.personalized) config.modelName else config.contentModelName, input.modelVersion))
            .header("Content-Type", "application/json")
            .post(json.encodeToString(JsonElement.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return httpClient.newCall(request).await().use { response ->
            if (response.isSuccessful) response.body.string() else {
                log.warn("TF Serving {} failed for version {} ({}): {}", signature, input.modelVersion,
                    response.code, response.body.string())
                null
            }
        }
    }

    private val httpClient = sharedHttpClient.newBuilder()
        .connectTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()

    /**
     * Calls the TF Serving model to get recommendations for a list of user IDs.
     * Returns a map of user ID to list of (content_id, score) pairs.
     *
     * @param userIds the user IDs to get predictions for
     * @param contextType the recommendation context candidate partition
     * @param languageTag the resolved language candidate partition
     * @return map of user ID to list of predicted (contentId, score) pairs
     */
    suspend fun predict(
        userIds: List<String>,
        contextType: String,
        languageTag: String,
    ): Map<String, List<Prediction>> {
        if (userIds.isEmpty()) return emptyMap()

        // User->items retrieval is served by the personalized model; the A/B pins its version.
        val url = buildPredictUrl(config.modelName, config.modelVersion)
        val requestBody = buildRequestBody(userIds, contextType, languageTag)

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = httpClient.newCall(request).await()
        if (!response.isSuccessful) {
            val errorBody = response.body.string()
            log.error("TF Serving prediction failed ({}): {}", response.code, errorBody)
            response.close()
            return emptyMap()
        }

        val body = response.body.string()
        response.close()
        return parsePredictions(userIds, body)
    }

    /**
     * Calls the model for a single user and returns their predictions.
     *
     * @param userId the user ID to get predictions for
     * @param contextType the recommendation context candidate partition
     * @param languageTag the resolved language candidate partition
     * @return list of predicted (contentId, score) pairs, or empty if prediction fails
     */
    suspend fun predictForUser(userId: String, contextType: String, languageTag: String): List<Prediction> {
        return predict(listOf(userId), contextType, languageTag)[userId] ?: emptyList()
    }

    /**
     * Calls the model's `similar` signature (item->similar-items, served from the content tower's
     * learned embeddings) for a batch of content IDs. Returns a map of content ID to its most-similar
     * items with scores. The source item is typically its own nearest neighbor, so callers should drop
     * the self-match.
     *
     * @param contentIds the source content IDs to find similar items for
     * @param contextType the recommendation context candidate partition
     * @param languageTag the resolved language candidate partition
     * @return map of content ID to its list of similar (contentId, score) pairs
     */
    suspend fun predictSimilar(
        contentIds: List<String>,
        contextType: String,
        languageTag: String,
    ): Map<String, List<Prediction>> {
        if (contentIds.isEmpty()) return emptyMap()

        // Context activation selects an exact matching content export; never silently query a newer one.
        val request = Request.Builder()
            .url(buildPredictUrl(config.contentModelName, config.contentModelVersion))
            .header("Content-Type", "application/json")
            .post(buildSimilarRequestBody(contentIds, contextType, languageTag).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = httpClient.newCall(request).await()
        if (!response.isSuccessful) {
            val errorBody = response.body.string()
            log.error("TF Serving similar prediction failed ({}): {}", response.code, errorBody)
            response.close()
            return emptyMap()
        }

        val body = response.body.string()
        response.close()
        return parsePredictions(contentIds, body)
    }

    /**
     * Convenience for a single source item: the model's learned similar items (self-match included;
     * the caller drops it).
     *
     * @param contentId the source content ID
     * @param contextType the recommendation context candidate partition
     * @param languageTag the resolved language candidate partition
     */
    suspend fun predictSimilarForItem(contentId: String, contextType: String, languageTag: String): List<Prediction> {
        return predictSimilar(listOf(contentId), contextType, languageTag)[contentId] ?: emptyList()
    }

    /**
     * Calls the model's `similar_users` signature (user->nearest-users, served from the user tower's learned
     * embeddings) for a batch of user IDs. The neighbor user IDs come back in the `content_ids` field (same
     * envelope as the other signatures), so [parsePredictions] handles them. The viewer is typically their own
     * nearest neighbor, so callers drop the self-match; the personalized model blanks out-of-vocab users to
     * empty ids, which the parser drops. Served by the personalized model (where the user tower lives).
     *
     * @param userIds the viewer user IDs to find nearest users for
     * @return map of user ID to its list of nearest (userId, score) pairs
     */
    suspend fun predictSimilarUsers(userIds: List<String>): Map<String, List<Prediction>> {
        if (userIds.isEmpty()) return emptyMap()

        val request = Request.Builder()
            .url(buildPredictUrl(config.modelName, config.modelVersion))
            .header("Content-Type", "application/json")
            .post(buildSimilarUsersRequestBody(userIds).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = httpClient.newCall(request).await()
        if (!response.isSuccessful) {
            val errorBody = response.body.string()
            log.error("TF Serving similar-users prediction failed ({}): {}", response.code, errorBody)
            response.close()
            return emptyMap()
        }

        val body = response.body.string()
        response.close()
        return parsePredictions(userIds, body)
    }

    /** Convenience for a single viewer: their learned nearest users (self-match included; the caller drops it). */
    suspend fun predictSimilarUsersForUser(userId: String): List<Prediction> {
        return predictSimilarUsers(listOf(userId))[userId] ?: emptyList()
    }

    /**
     * Calls the model's `rank` signature — the learned second-stage ranker — to score how relevant each
     * candidate content item is for a user. Sends one (user_id, content_id) instance per candidate and
     * returns a map of content ID to predicted-engagement score. Used by the two-stage retrieve→rank
     * serve path to re-order a candidate set. Returns empty (caller falls back) on any failure.
     *
     * @param userId the user the candidates are being ranked for
     * @param contentIds the candidate content IDs to score
     * @return map of content ID to its rank score
     */
    suspend fun rank(userId: String, contentIds: List<String>): Map<String, Double> {
        if (contentIds.isEmpty()) return emptyMap()

        val instances = JsonArray(contentIds.map { contentId ->
            JsonObject(mapOf("user_id" to JsonPrimitive(userId), "content_id" to JsonPrimitive(contentId)))
        })
        val bodyJson = json.encodeToString(
            JsonElement.serializer(),
            JsonObject(mapOf("signature_name" to JsonPrimitive("rank"), "instances" to instances)),
        )
        // The learned ranker lives on the personalized model; the A/B pins its version.
        val request = Request.Builder()
            .url(buildPredictUrl(config.modelName, config.modelVersion))
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = httpClient.newCall(request).await()
        if (!response.isSuccessful) {
            val errorBody = response.body.string()
            log.error("TF Serving rank failed ({}): {}", response.code, errorBody)
            response.close()
            return emptyMap()
        }

        val body = response.body.string()
        response.close()
        return parseRankScores(contentIds, body)
    }

    /**
     * Parses the `rank` response — one score per instance, positionally aligned to [contentIds]. TF
     * Serving's row format may surface each prediction as a bare scalar, a single-element array, or a
     * `{score}`/`{scores}` object depending on output naming, so all shapes are handled defensively.
     */
    private fun parseRankScores(contentIds: List<String>, body: String): Map<String, Double> {
        return try {
            val predictions = json.decodeFromString(JsonElement.serializer(), body)
                .jsonObject["predictions"]?.jsonArray ?: return emptyMap()
            contentIds.mapIndexedNotNull { index, contentId ->
                if (index >= predictions.size) return@mapIndexedNotNull null
                val score = scoreOf(predictions[index]) ?: return@mapIndexedNotNull null
                contentId to score
            }.toMap()
        } catch (e: Exception) {
            log.error("Failed to parse rank scores: {}", e.message)
            emptyMap()
        }
    }

    /** Extracts a scalar score from a prediction element regardless of TF Serving's output wrapping. */
    private fun scoreOf(element: JsonElement): Double? = when (element) {
        is JsonPrimitive -> element.doubleOrNull
        is JsonArray -> element.firstOrNull()?.let { (it as? JsonPrimitive)?.doubleOrNull }
        is JsonObject -> (element["scores"] ?: element["score"] ?: element["output_0"])?.let { scoreOf(it) }
        else -> null
    }

    private fun buildPredictUrl(modelName: String, modelVersion: Long? = null): String {
        val base = config.url.trimEnd('/')
        val versionPath = if (modelVersion != null) "/versions/$modelVersion" else ""
        return "$base/v1/models/$modelName$versionPath:predict"
    }

    private fun buildRequestBody(userIds: List<String>, contextType: String, languageTag: String): String {
        val instances = JsonArray(userIds.map { userId ->
            JsonObject(
                mapOf(
                    "user_id" to JsonPrimitive(userId),
                    "context_type" to JsonPrimitive(contextType),
                    "language_tag" to JsonPrimitive(languageTag),
                ),
            )
        })
        return json.encodeToString(JsonElement.serializer(), JsonObject(mapOf("instances" to instances)))
    }

    /** Request body for the `similar` signature: a `signature_name` selector + `content_id` instances. */
    private fun buildSimilarRequestBody(contentIds: List<String>, contextType: String, languageTag: String): String {
        val instances = JsonArray(contentIds.map { contentId ->
            JsonObject(
                mapOf(
                    "content_id" to JsonPrimitive(contentId),
                    "context_type" to JsonPrimitive(contextType),
                    "language_tag" to JsonPrimitive(languageTag),
                ),
            )
        })
        return json.encodeToString(
            JsonElement.serializer(),
            JsonObject(mapOf(
                "signature_name" to JsonPrimitive("similar"),
                "instances" to instances,
            )),
        )
    }

    /** Request body for the `similar_users` signature: a `signature_name` selector + `user_id` instances. */
    private fun buildSimilarUsersRequestBody(userIds: List<String>): String {
        val instances = JsonArray(userIds.map { userId ->
            JsonObject(mapOf("user_id" to JsonPrimitive(userId)))
        })
        return json.encodeToString(
            JsonElement.serializer(),
            JsonObject(mapOf(
                "signature_name" to JsonPrimitive("similar_users"),
                "instances" to instances,
            )),
        )
    }

    /**
     * Parses the TF Serving prediction response. The response format depends
     * on the model signature, but we handle two common patterns:
     *
     * Pattern 1 (per-instance outputs):
     * {"predictions": [{"content_ids": [...], "scores": [...]}, ...]}
     *
     * Pattern 2 (flat arrays):
     * {"predictions": [[id1, id2, ...], [id1, id2, ...]]}
     * with a separate scores array
     */
    private fun parsePredictions(userIds: List<String>, body: String): Map<String, List<Prediction>> {
        return try {
            val responseObj = json.decodeFromString(JsonElement.serializer(), body).jsonObject
            val predictions = responseObj["predictions"]?.jsonArray ?: return emptyMap()

            userIds.mapIndexed { index, userId ->
                if (index >= predictions.size) return@mapIndexed userId to emptyList()
                val prediction = predictions[index]
                userId to parseSinglePrediction(prediction)
            }.toMap()
        } catch (e: Exception) {
            log.error("Failed to parse TF Serving response: {}", e.message)
            emptyMap()
        }
    }

    private fun parseSinglePrediction(prediction: JsonElement): List<Prediction> {
        return try {
            val obj = prediction.jsonObject
            val contentIds = (obj["content_ids"] ?: obj["output_0"])?.jsonArray
                ?: return emptyList()
            val scores = (obj["scores"] ?: obj["output_1"])?.jsonArray
                ?: return emptyList()

            contentIds.zip(scores).mapNotNull { (idElement, scoreElement) ->
                val contentId = idElement.jsonPrimitive.content
                // The personalized model blanks out-of-vocab rows (a user it never trained on) to empty
                // content ids so the caller reads "no personalized result" rather than junk. Drop those —
                // downstream parses these ids as UUIDs, and a warm/cold user is told apart by whether any
                // survive here.
                if (contentId.isEmpty()) return@mapNotNull null
                Prediction(
                    contentId = contentId,
                    score = scoreElement.jsonPrimitive.double,
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to parse single prediction: {}", e.message)
            emptyList()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(TfServingClient::class.java)
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/**
 * A single predicted content recommendation from a TF Serving model,
 * consisting of a content identifier and a relevance score.
 */
data class Prediction(
    /** The predicted content ID (metadata or collection UUID as string). */
    val contentId: String,
    /** The model's predicted relevance score for this content item. */
    val score: Double,
)
