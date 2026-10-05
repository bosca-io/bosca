package bosca.bml.message

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds a JSON-Schema subset (`type`/`properties`/`required`/`items`/`enum`) from a payload
 * serializer's [SerialDescriptor] — the machine-readable companion of [PayloadSample]. Authoring
 * surfaces (e.g. the pipeline node inspector) render it so an author wiring a payload sees each
 * field's type and whether it must be present. `required` lists the elements with no Kotlin
 * default (a nullable field without a default still needs an explicit `null`).
 */
object PayloadSchema {

    private const val MAX_DEPTH = 6

    fun of(descriptor: SerialDescriptor): JsonElement = value(descriptor, 0)

    @OptIn(ExperimentalSerializationApi::class)
    private fun value(descriptor: SerialDescriptor, depth: Int): JsonElement {
        if (depth > MAX_DEPTH) return schema("object")
        return when (descriptor.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> schema("string")
            PrimitiveKind.BOOLEAN -> schema("boolean")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> schema("integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> schema("number")
            SerialKind.ENUM -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("string"),
                    "enum" to JsonArray((0 until descriptor.elementsCount).map { JsonPrimitive(descriptor.getElementName(it)) }),
                ),
            )
            StructureKind.LIST -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("array"),
                    "items" to value(descriptor.getElementDescriptor(0), depth + 1),
                ),
            )
            StructureKind.MAP -> schema("object")
            StructureKind.CLASS, StructureKind.OBJECT -> JsonObject(
                buildMap {
                    put("type", JsonPrimitive("object"))
                    put(
                        "properties",
                        JsonObject(
                            buildMap {
                                for (i in 0 until descriptor.elementsCount) {
                                    put(descriptor.getElementName(i), value(descriptor.getElementDescriptor(i), depth + 1))
                                }
                            },
                        ),
                    )
                    val required = (0 until descriptor.elementsCount)
                        .filterNot { descriptor.isElementOptional(it) }
                        .map { JsonPrimitive(descriptor.getElementName(it)) }
                    if (required.isNotEmpty()) put("required", JsonArray(required))
                },
            )
            is PolymorphicKind -> schema("object")
            // CONTEXTUAL (UUID, OffsetDateTime, ...) and anything else serializes as a string.
            else -> schema("string")
        }
    }

    private fun schema(type: String): JsonObject = JsonObject(mapOf("type" to JsonPrimitive(type)))
}
