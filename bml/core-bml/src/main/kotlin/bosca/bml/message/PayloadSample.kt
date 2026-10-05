package bosca.bml.message

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds a sample-payload skeleton from a payload serializer's [SerialDescriptor] — the JSON
 * shape a template's typed decode expects, with type-typical placeholder values. Authoring
 * surfaces (the Studio preview) pre-fill this so authors see the structure instead of
 * decoding "field X is required" errors one at a time.
 */
object PayloadSample {

    private const val MAX_DEPTH = 6

    fun of(descriptor: SerialDescriptor): JsonElement = value(descriptor, 0)

    @OptIn(ExperimentalSerializationApi::class)
    private fun value(descriptor: SerialDescriptor, depth: Int): JsonElement {
        if (depth > MAX_DEPTH) return JsonNull
        return when (descriptor.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> JsonPrimitive("")
            PrimitiveKind.BOOLEAN -> JsonPrimitive(false)
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> JsonPrimitive(0)
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> JsonPrimitive(0.0)
            SerialKind.ENUM -> JsonPrimitive(if (descriptor.elementsCount > 0) descriptor.getElementName(0) else "")
            StructureKind.LIST -> JsonArray(emptyList())
            StructureKind.MAP -> JsonObject(emptyMap())
            StructureKind.CLASS, StructureKind.OBJECT -> JsonObject(
                buildMap {
                    for (i in 0 until descriptor.elementsCount) {
                        val element = descriptor.getElementDescriptor(i)
                        put(
                            descriptor.getElementName(i),
                            if (element.isNullable) JsonNull else value(element, depth + 1),
                        )
                    }
                },
            )
            is PolymorphicKind -> JsonObject(emptyMap())
            // CONTEXTUAL (UUID, OffsetDateTime, ...) and anything else: a string placeholder.
            else -> JsonPrimitive("")
        }
    }
}
