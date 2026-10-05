package bosca.serialization

import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.server.ServerResponseContent
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class JsonContent<T : Any>(
    private val tracer: Tracer,
    private val json: Json,
    private val data: T,
    private val serializer: KSerializer<T>
) : ServerResponseContent {

    val contentType: ContentType = ContentType.Application.Json

    fun asJsonElement(): JsonElement = json.encodeToJsonElement(serializer, data)

    override suspend fun writeTo(call: ServerCall, status: HttpStatusCode) {
        val span = tracer.spanBuilder("JsonContent.writeTo")
            .setParent(Context.current())
            .startSpan()
        try {
            span.makeCurrent().use {
                val bytes = json.encodeToString(serializer, data).toByteArray(Charsets.UTF_8)
                call.respondBytes(bytes, contentType, status)
            }
        } finally {
            span.end()
        }
    }
}
