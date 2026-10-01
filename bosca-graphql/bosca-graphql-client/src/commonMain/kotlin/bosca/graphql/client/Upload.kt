package bosca.graphql.client

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A file to upload as the GraphQL `Upload` scalar. Map the schema's `Upload` scalar to this type (with
 * [UploadSerializer]) in the codegen; the typed `Variables` then carry an [Upload], and the upload bridge
 * sends it via the graphql-multipart-request-spec. Input-only — it is never present in a response.
 */
class Upload(val filename: String, val contentType: String, val content: ByteArray)

/**
 * Serializes [Upload] as JSON `null`. Per the multipart request spec the file bytes travel as separate form
 * parts and the variable position is nulled out, referenced from the `map`. So a typed `Variables` holding an
 * [Upload] encodes to `{ "file": null }`, and the bytes are attached by the bridge. Decoding is unsupported —
 * `Upload` is an input-only scalar. Explicit (no reflection) → GraalVM-native-safe.
 */
object UploadSerializer : KSerializer<Upload> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("bosca.graphql.Upload")
    override fun serialize(encoder: Encoder, value: Upload): Unit =
        (encoder as JsonEncoder).encodeJsonElement(JsonNull)
    override fun deserialize(decoder: Decoder): Upload = error("Upload is an input-only GraphQL scalar")
}

/** One file part of a multipart GraphQL request: the [upload] and the JSON [path] of the variable it fills (e.g. `variables.file`). */
data class GraphQLUpload(val path: String, val upload: Upload)

/**
 * A [GraphQLClient] that also performs file uploads via the graphql-multipart-request-spec. Kept separate from
 * the base [GraphQLClient] so a transport opts in only if it can send multipart bodies.
 */
interface GraphQLUploadClient : GraphQLClient {
    /** Send [document] with [variables] (file positions nulled) plus the file [uploads] as a multipart request. */
    suspend fun executeUpload(
        document: String,
        variables: JsonObject?,
        operationName: String?,
        uploads: List<GraphQLUpload>,
    ): GraphQLResponse
}

/**
 * Run a generated upload [operation] with typed [variables] (their `Upload` fields encode to null) plus the
 * file [uploads], returning typed `Data`. Throws [GraphQLClientException] on response errors / no data.
 */
suspend fun <V, D> GraphQLUploadClient.executeUpload(
    operation: BoscaOperation<V, D>,
    variables: V,
    uploads: List<GraphQLUpload>,
): D {
    val response = executeUpload(
        operation.document,
        operation.encodeVariables(variables),
        operation.operationName,
        uploads,
    )
    response.errors?.takeIf { it.isNotEmpty() }?.let { throw GraphQLClientException(it) }
    val data = response.data ?: throw GraphQLClientException(listOf(GraphQLError("response contained no data")))
    return operation.decodeData(data)
}

/** The transport-agnostic pieces of a graphql-multipart-request-spec form. */
class MultipartForm(val operations: String, val map: String, val files: List<Upload>)

/**
 * Build the [MultipartForm] for an upload request: the `operations` envelope (`{ query, operationName?,
 * variables? }`), the `map` linking each ordinal form part to its variable [GraphQLUpload.path], and the file
 * bytes in the same order. The HTTP assembly (boundaries, parts) is left to each transport.
 */
fun buildMultipartForm(
    document: String,
    variables: JsonObject?,
    operationName: String?,
    uploads: List<GraphQLUpload>,
): MultipartForm {
    val operations = buildJsonObject {
        put("query", JsonPrimitive(document))
        if (operationName != null) put("operationName", JsonPrimitive(operationName))
        if (variables != null) put("variables", variables)
    }.toString()
    val map: JsonObject = buildJsonObject {
        uploads.forEachIndexed { index, upload ->
            put(index.toString(), buildJsonArray { add(JsonPrimitive(upload.path)) })
        }
    }
    return MultipartForm(operations, map.toString(), uploads.map { it.upload })
}
