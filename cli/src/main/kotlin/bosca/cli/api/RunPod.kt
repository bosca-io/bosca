package bosca.cli.api

import kotlinx.coroutines.delay
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration

@Serializable
data class FileRequestHeader(val name: String, val value: String)

@Serializable
data class FileRequest(val action: String, val url: String, val headers: List<FileRequestHeader>)

@Serializable
data class Request(val input: FileRequest)

@Serializable
data class RunConfiguration(
    val token: String,
    val url: String
) {

    companion object {
        const val KEY = "runpod"
    }
}

class RunPod(private val boscaClient: Client) : Api(boscaClient.network) {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .readTimeout(Duration.ofMinutes(30))
        .writeTimeout(Duration.ofMinutes(30))
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    suspend fun <T> execute(strategy: DeserializationStrategy<T>, function: String, fileRequest: FileRequest): T {
        val body = executeRaw(function, fileRequest)
        return Json.decodeFromString(strategy, body)
    }

    suspend fun executeRaw(function: String, fileRequest: FileRequest): String {
        val id = run(function, fileRequest)
        return getResult(function, id)
    }

    private suspend fun run(function: String, fileRequest: FileRequest): String {
        val configuration = boscaClient.configurations.get<RunConfiguration>(RunConfiguration.KEY) ?: error("missing runpod configuration")
        val runUrl = configuration.url + function + "/run"
        val requestBody = Json.encodeToString(Request(fileRequest))
        val request = okhttp3.Request.Builder()
            .url(runUrl)
            .header("Authorization", "Bearer ${configuration.token}")
            .method("POST", requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        val response = client.newCall(request).executeAsync()
        if (!response.isSuccessful) throw Exception("Unexpected code $response")
        if (response.body == null) throw Exception("Missing body")
        val element = Json.Default.parseToJsonElement(response.body!!.string())
        val id = element.jsonObject["id"]?.jsonPrimitive?.content ?: error("missing id")
        println("runpod id: $id")
        return id
    }

    private suspend fun getResult(function: String, id: String): String {
        val configuration = boscaClient.configurations.get<RunConfiguration>(RunConfiguration.KEY) ?: error("missing runpod configuration")
        val runUrl = configuration.url + function + "/status/" + id
        val request = okhttp3.Request.Builder()
            .url(runUrl)
            .header("Authorization", "Bearer ${configuration.token}")
            .post("".toRequestBody())
            .build()
        while (true) {
            val response = client.newCall(request).executeAsync()
            if (!response.isSuccessful) throw Exception("Unexpected code $response")
            if (response.body == null) throw Exception("Missing body")
            val body = response.body!!.string()
            val element = Json.Default.parseToJsonElement(body)
            val status = element.jsonObject["status"]?.jsonPrimitive?.content ?: error("missing status")
            when (status) {
                "IN_PROGRESS", "IN_QUEUE" -> delay(10000)
                "COMPLETED" -> {
                    return element.jsonObject["output"]?.jsonObject?.toString() ?: error("missing output")
                }

                else -> error("runpod failed status: $body")
            }
        }
    }
}