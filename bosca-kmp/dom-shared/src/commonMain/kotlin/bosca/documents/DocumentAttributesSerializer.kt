package bosca.documents

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject

/** Accepts ProseMirror's plain class attrs while retaining typed attributes and the published encoding. */
object DocumentAttributesSerializer : KSerializer<DocumentAttributes> {
    private val typed = DocumentAttributes.serializer()
    override val descriptor = typed.descriptor

    override fun serialize(encoder: Encoder, value: DocumentAttributes) = encoder.encodeSerializableValue(typed, value)

    override fun deserialize(decoder: Decoder): DocumentAttributes {
        if (decoder !is JsonDecoder) return decoder.decodeSerializableValue(typed)
        val element = decoder.decodeJsonElement()
        return if (element is JsonObject && "type" !in element) {
            decoder.json.decodeFromJsonElement(EmptyDocumentAttributes.serializer(), element)
        } else decoder.json.decodeFromJsonElement(typed, element)
    }
}
