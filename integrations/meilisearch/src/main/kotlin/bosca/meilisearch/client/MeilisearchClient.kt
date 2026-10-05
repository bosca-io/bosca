package bosca.meilisearch.client

import bosca.meilisearch.client.model.DocumentsResponse
import bosca.meilisearch.client.model.EmbedderConfig
import bosca.meilisearch.client.model.GlobalStats
import bosca.meilisearch.client.model.IndexInfo
import bosca.meilisearch.client.model.IndexResults
import bosca.meilisearch.client.model.IndexStats
import bosca.meilisearch.client.model.KeyResults
import bosca.meilisearch.client.model.MeilisearchError
import bosca.meilisearch.client.model.SearchRequest
import bosca.meilisearch.client.model.SearchResponse
import bosca.meilisearch.client.model.Settings
import bosca.meilisearch.client.model.Task
import bosca.meilisearch.client.model.TaskInfo
import bosca.meilisearch.client.model.TaskResults
import bosca.meilisearch.client.model.Version
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.putJsonArray
import java.io.Closeable
import java.net.URLEncoder
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Duration
import kotlin.coroutines.resumeWithException

/**
 * Suspend-friendly HTTP client for the Meilisearch REST API.
 *
 * Uses OkHttp with async callbacks converted to Kotlin coroutines via
 * [suspendCancellableCoroutine]. All JSON serialization uses kotlinx.serialization.
 * Every public method is a suspend function — no blocking I/O or
 * `withContext(Dispatchers.IO)` required by callers.
 */
class MeilisearchClient(
    url: String,
    private val apiKey: String,
    private val json: Json,
    private val connectTimeout: Duration = Duration.ofSeconds(5),
    private val readTimeout: Duration = Duration.ofSeconds(30),
) : Closeable {

    val url: String = url.trimEnd('/')

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeout)
        .readTimeout(readTimeout)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    // ── Internal HTTP helpers ──

    private suspend fun get(path: String): String {
        val request = Request.Builder()
            .url("$url$path")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        return execute(request)
    }

    private suspend fun post(path: String, body: String = ""): String {
        val request = Request.Builder()
            .url("$url$path")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return execute(request)
    }

    private suspend fun put(path: String, body: String): String {
        val request = Request.Builder()
            .url("$url$path")
            .header("Authorization", "Bearer $apiKey")
            .put(body.toRequestBody(jsonMediaType))
            .build()
        return execute(request)
    }

    private suspend fun patch(path: String, body: String): String {
        val request = Request.Builder()
            .url("$url$path")
            .header("Authorization", "Bearer $apiKey")
            .patch(body.toRequestBody(jsonMediaType))
            .build()
        return execute(request)
    }

    private suspend fun delete(path: String, body: String? = null): String {
        val builder = Request.Builder()
            .url("$url$path")
            .header("Authorization", "Bearer $apiKey")
        if (body != null) {
            builder.delete(body.toRequestBody(jsonMediaType))
        } else {
            builder.delete()
        }
        return execute(builder.build())
    }

    private suspend fun execute(request: Request): String {
        val response = httpClient.newCall(request).await()
        return response.use {
            val responseBody = it.body.string()
            if (!it.isSuccessful) {
                val error = try {
                    json.decodeFromString<MeilisearchError>(responseBody)
                } catch (_: Exception) {
                    MeilisearchError(message = responseBody, code = "unknown", type = "unknown")
                }
                if (it.code == 404 && request.method == "DELETE") {
                    return@use ""
                }
                throw MeilisearchApiException(it.code, error)
            }
            responseBody
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isCancelled) return
                cont.resume(response) { _, _, _ -> response.close() }
            }
        })
    }

    // ── Search ──

    /** Executes a search query against the specified index. */
    suspend fun search(indexUid: String, request: SearchRequest): SearchResponse {
        val body = json.encodeToString(request)
        val response = post("/indexes/$indexUid/search", body)
        return json.decodeFromString(response)
    }

    // ── Indexes ──

    /** Lists all indexes with optional pagination. */
    suspend fun getIndexes(offset: Int? = null, limit: Int? = null): IndexResults {
        val params = buildQueryParams("offset" to offset, "limit" to limit)
        return json.decodeFromString(get("/indexes$params"))
    }

    /** Gets a single index by UID. */
    suspend fun getIndex(uid: String): IndexInfo {
        requireSafePathSegment(uid, "index UID")
        return json.decodeFromString(get("/indexes/$uid"))
    }

    /** Creates a new index with an optional primary key. */
    suspend fun createIndex(uid: String, primaryKey: String? = null): TaskInfo {
        val body = buildJsonObject {
            put("uid", uid)
            if (primaryKey != null) put("primaryKey", primaryKey)
        }
        return json.decodeFromString(post("/indexes", json.encodeToString(body)))
    }

    /** Deletes an index. */
    suspend fun deleteIndex(uid: String): TaskInfo {
        requireSafePathSegment(uid, "index UID")
        return json.decodeFromString(delete("/indexes/$uid"))
    }

    /** Atomically swaps pairs of indexes. Each pair is a list of two index UIDs. */
    suspend fun swapIndexes(pairs: List<Pair<String, String>>): TaskInfo {
        val body = pairs.map { (a, b) ->
            buildJsonObject { putJsonArray("indexes") { add(JsonPrimitive(a)); add(JsonPrimitive(b)) } }
        }
        return json.decodeFromString(post("/swap-indexes", json.encodeToString(body)))
    }

    // ── Documents ──

    /** Gets documents from an index with optional pagination and field selection. */
    suspend fun getDocuments(
        indexUid: String,
        offset: Int? = null,
        limit: Int? = null,
        fields: List<String>? = null,
    ): DocumentsResponse {
        val params = buildQueryParams(
            "offset" to offset,
            "limit" to limit,
            "fields" to fields?.joinToString(","),
        )
        return json.decodeFromString(get("/indexes/$indexUid/documents$params"))
    }

    /**
     * Gets a single document by primary key as a raw JSON body. Returns `null` when
     * Meilisearch responds with HTTP 404 — covers both `document_not_found` (no document
     * with the given ID in an existing index) and `index_not_found` (the index itself
     * is missing). Matching on the HTTP status (rather than the structured error code)
     * keeps this robust against an unparseable error body, where [execute] falls back
     * to `code = "unknown"`. Any other failure propagates as [MeilisearchApiException].
     */
    suspend fun getDocument(indexUid: String, documentId: String): String? {
        requireSafePathSegment(indexUid, "index UID")
        requireSafePathSegment(documentId, "document ID")
        return try {
            get("/indexes/$indexUid/documents/$documentId")
        } catch (e: MeilisearchApiException) {
            if (e.statusCode == 404) null else throw e
        }
    }

    /** Adds or replaces documents in an index. Documents should be a JSON array string. */
    suspend fun addDocuments(indexUid: String, documents: String, primaryKey: String? = null): TaskInfo {
        val params = buildQueryParams("primaryKey" to primaryKey)
        return json.decodeFromString(post("/indexes/$indexUid/documents$params", documents))
    }

    /** Deletes all documents from an index. */
    suspend fun deleteAllDocuments(indexUid: String): TaskInfo {
        return json.decodeFromString(delete("/indexes/$indexUid/documents"))
    }

    /** Deletes a single document by ID. */
    suspend fun deleteDocument(indexUid: String, documentId: String): TaskInfo {
        requireSafePathSegment(indexUid, "index UID")
        requireSafePathSegment(documentId, "document ID")
        return json.decodeFromString(delete("/indexes/$indexUid/documents/$documentId").takeIf { it.isNotBlank() } ?: return TaskInfo(0, indexUid, null, null, null))
    }

    /** Deletes documents matching a filter expression. */
    suspend fun deleteDocumentsByFilter(indexUid: String, filter: String): TaskInfo {
        val body = buildJsonObject { put("filter", filter) }
        return json.decodeFromString(post("/indexes/$indexUid/documents/delete", json.encodeToString(body)))
    }

    // ── Settings ──

    /** Gets all settings for an index. */
    suspend fun getSettings(indexUid: String): Settings {
        return json.decodeFromString(get("/indexes/$indexUid/settings"))
    }

    /** Updates settings for an index (PATCH semantics — only provided fields are changed). */
    suspend fun updateSettings(indexUid: String, settings: Settings): TaskInfo {
        return json.decodeFromString(patch("/indexes/$indexUid/settings", json.encodeToString(settings)))
    }

    /** Resets all settings for an index to their defaults. */
    suspend fun resetSettings(indexUid: String): TaskInfo {
        return json.decodeFromString(delete("/indexes/$indexUid/settings"))
    }

    // ── Settings sub-routes ──

    /** Updates sortable attributes for an index. */
    suspend fun updateSortableAttributes(indexUid: String, attributes: List<String>): TaskInfo {
        return json.decodeFromString(put("/indexes/$indexUid/settings/sortable-attributes", json.encodeToString(attributes)))
    }

    /** Updates searchable attributes for an index. */
    suspend fun updateSearchableAttributes(indexUid: String, attributes: List<String>): TaskInfo {
        return json.decodeFromString(put("/indexes/$indexUid/settings/searchable-attributes", json.encodeToString(attributes)))
    }

    /** Updates filterable attributes for an index. */
    suspend fun updateFilterableAttributes(indexUid: String, attributes: List<String>): TaskInfo {
        return json.decodeFromString(put("/indexes/$indexUid/settings/filterable-attributes", json.encodeToString(attributes)))
    }

    /** Configures embedders for semantic/hybrid search on an index. */
    suspend fun updateEmbedders(indexUid: String, embedders: Map<String, EmbedderConfig>): TaskInfo {
        return json.decodeFromString(patch("/indexes/$indexUid/settings/embedders", json.encodeToString(embedders)))
    }

    /** Gets current embedder settings for an index, or null if none are configured. */
    suspend fun getEmbedders(indexUid: String): Map<String, JsonObject>? {
        val settings = getSettings(indexUid)
        return settings.embedders
    }

    /** Resets all embedder settings for an index. */
    suspend fun resetEmbedders(indexUid: String): TaskInfo {
        return json.decodeFromString(delete("/indexes/$indexUid/settings/embedders"))
    }

    // ── Tasks ──

    /** Gets a single task by UID. */
    suspend fun getTask(uid: Int): Task {
        return json.decodeFromString(get("/tasks/$uid"))
    }

    /** Lists tasks with optional filtering by status, type, and index. */
    suspend fun getTasks(
        limit: Int? = null,
        from: Int? = null,
        statuses: List<String>? = null,
        types: List<String>? = null,
        indexUids: List<String>? = null,
    ): TaskResults {
        val params = buildQueryParams(
            "limit" to limit,
            "from" to from,
            "statuses" to statuses?.joinToString(","),
            "types" to types?.joinToString(","),
            "indexUids" to indexUids?.joinToString(","),
        )
        return json.decodeFromString(get("/tasks$params"))
    }

    /** Cancels tasks matching the specified filters. At least one filter must be provided. */
    suspend fun cancelTasks(
        uids: List<Int>? = null,
        statuses: List<String>? = null,
        types: List<String>? = null,
        indexUids: List<String>? = null,
    ): TaskInfo {
        val params = buildQueryParams(
            "uids" to uids?.joinToString(","),
            "statuses" to statuses?.joinToString(","),
            "types" to types?.joinToString(","),
            "indexUids" to indexUids?.joinToString(","),
        )
        return json.decodeFromString(post("/tasks/cancel$params"))
    }

    // ── Keys ──

    /** Lists all API keys. */
    suspend fun getKeys(): KeyResults {
        return json.decodeFromString(get("/keys"))
    }

    // ── Stats ──

    /** Gets global statistics for the Meilisearch instance. */
    suspend fun getStats(): GlobalStats {
        return json.decodeFromString(get("/stats"))
    }

    /** Gets statistics for a specific index. */
    suspend fun getIndexStats(indexUid: String): IndexStats {
        return json.decodeFromString(get("/indexes/$indexUid/stats"))
    }

    // ── Health ──

    /** Returns true if the Meilisearch instance is healthy, false otherwise. */
    suspend fun health(): Boolean {
        return try {
            get("/health")
            true
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            false
        }
    }

    // ── Version ──

    /** Gets the Meilisearch server version information. */
    suspend fun getVersion(): Version {
        return json.decodeFromString(get("/version"))
    }

    // ── Dumps & Snapshots ──

    /** Creates a database dump. */
    suspend fun createDump(): TaskInfo {
        return json.decodeFromString(post("/dumps"))
    }

    /** Creates a database snapshot. */
    suspend fun createSnapshot(): TaskInfo {
        return json.decodeFromString(post("/snapshots"))
    }

    // ── Experimental Features ──

    /** Enables or disables experimental features. Returns the raw JSON response. */
    suspend fun updateExperimentalFeatures(features: Map<String, Boolean>): String {
        return patch("/experimental-features", json.encodeToString(features))
    }

    // ── Network (federation) ──

    /** Gets the network configuration. Returns raw JSON response. */
    suspend fun getNetwork(): String {
        return get("/network")
    }

    /** Updates the network configuration. Returns raw JSON response. */
    suspend fun updateNetwork(body: String): String {
        return patch("/network", body)
    }

    // ── Export ──

    /** Triggers an export operation and returns the task info. */
    suspend fun export(body: String): TaskInfo {
        return json.decodeFromString(post("/export", body))
    }

    // ── Chat settings ──

    /** Updates chat settings for a named chat index. Returns raw JSON response. */
    suspend fun updateChatSettings(name: String, body: String): String {
        return patch("/chats/$name/settings", body)
    }

    // ── Helpers ──

    override fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun buildQueryParams(vararg params: Pair<String, Any?>): String {
        val filtered = params.mapNotNull { (key, value) ->
            if (value != null) "$key=${URLEncoder.encode(value.toString(), Charsets.UTF_8)}" else null
        }
        return if (filtered.isEmpty()) "" else "?${filtered.joinToString("&")}"
    }

    private fun requireSafePathSegment(value: String, name: String) {
        require(value.none { it == '/' || it == '?' || it == '#' || it == '\\' }) {
            "$name contains invalid URL path characters: $value"
        }
    }
}
