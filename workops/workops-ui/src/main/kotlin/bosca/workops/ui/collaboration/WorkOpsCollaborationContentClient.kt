package bosca.workops.ui.collaboration

import kotlinx.coroutines.future.await
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Uses Content's existing permission check and binary persistence under each presented token. */
internal class WorkOpsCollaborationContentClient(graphqlEndpoint: String) : AutoCloseable {
    private val api = graphqlEndpoint.substringBefore("/graphql").trimEnd('/')
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    suspend fun initialize(room: WorkOpsCollaborationRoom, token: String): ByteArray =
        request(room, token, "POST").also(::verify).body()

    suspend fun authorize(room: WorkOpsCollaborationRoom, token: String) {
        verify(request(room, token, "HEAD"))
    }

    suspend fun load(room: WorkOpsCollaborationRoom, token: String): ByteArray? {
        val response = request(room, token, "GET")
        if (response.statusCode() == 404) return null
        verify(response)
        return response.body()
    }

    suspend fun store(room: WorkOpsCollaborationRoom, token: String, bytes: ByteArray): ByteArray =
        request(room, token, "POST", bytes).also(::verify).body()

    private suspend fun request(room: WorkOpsCollaborationRoom, token: String, method: String, bytes: ByteArray? = null): HttpResponse<ByteArray> {
        if (token.isBlank()) throw WorkOpsCollaborationDeniedException()
        val request = HttpRequest.newBuilder(URI.create(api + room.contentPath))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/octet-stream")
            .method(method, bytes?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody())
            .build()
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).await()
    }

    private fun verify(response: HttpResponse<ByteArray>) {
        val status = response.statusCode()
        if (status in 400..499) throw WorkOpsCollaborationDeniedException()
        if (status !in 200..299) throw IOException("Content collaboration request failed with status $status")
    }

    override fun close() { client.close() }
}
