package bosca.cli.api

import bosca.graphql.gen.ISignedUrlHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.MultipartBody.Companion.FORM
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.nio.file.Files


class Files(network: NetworkClient) : Api(network) {

    /** Download a signed URL (with its required headers) to [file]. Headers are any [ISignedUrlHeader] — the
     *  flattened `download`/`supplementary` selections all satisfy it — so this is decoupled from the operation. */
    suspend fun download(url: String, headers: List<ISignedUrlHeader>, file: File) {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { addHeader(it.name, it.value) } }
            .build()
        execute(request, file)
    }

    /** Downloads a signed URL into memory for protocol responses such as MCP image content. */
    suspend fun downloadBytes(url: String, headers: List<ISignedUrlHeader>): ByteArray {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { addHeader(it.name, it.value) } }
            .build()
        return executeBytes(request) ?: error("File not found: $url")
    }

    suspend fun upload(url: String, headers: List<ISignedUrlHeader>, file: File) {
        val mimeType = withContext(Dispatchers.IO) {
            Files.probeContentType(file.toPath())
        } ?: "application/octet-stream"
        val request = Request.Builder()
            .post(
                MultipartBody.Builder()
                    .setType(FORM)
                    .addFormDataPart("file", file.name, file.asRequestBody(mimeType.toMediaType()))
                    .build()
            )
            .url(url)
            .apply { headers.forEach { addHeader(it.name, it.value) } }
            .build()
        execute(request, file)
    }

    suspend fun download(url: String, file: File, authorization: String? = null) {
        val request = Request.Builder().url(url)
        authorization?.let {
            request.header("Authorization", it)
        }
        execute(request.build(), file)
    }

    private suspend fun execute(request: Request, file: File) {
        network.http.newCall(request).executeAsync().use { response ->
            if (response.code == 404) {
                return
            }
            if (!response.isSuccessful) throw Exception("Unexpected code $response")
            withContext(Dispatchers.IO) {
                file.writeBytes(response.body.bytes())
            }
        }
    }

    private suspend fun executeBytes(request: Request): ByteArray? {
        network.http.newCall(request).executeAsync().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw Exception("Unexpected code $response")
            return response.body.bytes()
        }
    }
}
