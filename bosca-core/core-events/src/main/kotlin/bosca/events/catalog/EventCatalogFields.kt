package bosca.events.catalog

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind

/**
 * Derives the filterable [EventField]s of an event from its [SerialDescriptor].
 *
 * Only the top-level (constructor) properties of the event are surfaced — these are the
 * fields a trigger filter or automation condition can match against. The type label is a
 * coarse, UI-friendly string derived from each element's [SerialKind]; complex, enum, and
 * contextual elements fall back to the short form of their serial name.
 */
object EventCatalogFields {

    /** Maps each top-level element of [descriptor] to an [EventField]. */
    fun of(descriptor: SerialDescriptor): List<EventField> =
        (0 until descriptor.elementsCount).map { index ->
            EventField(
                name = descriptor.getElementName(index),
                type = typeLabel(descriptor.getElementDescriptor(index)),
            )
        }

    private fun typeLabel(element: SerialDescriptor): String {
        val base = when (element.kind) {
            PrimitiveKind.STRING -> "String"
            PrimitiveKind.BOOLEAN -> "Boolean"
            PrimitiveKind.BYTE -> "Byte"
            PrimitiveKind.SHORT -> "Short"
            PrimitiveKind.INT -> "Int"
            PrimitiveKind.LONG -> "Long"
            PrimitiveKind.FLOAT -> "Float"
            PrimitiveKind.DOUBLE -> "Double"
            PrimitiveKind.CHAR -> "Char"
            SerialKind.ENUM -> "Enum"
            StructureKind.LIST -> "List"
            StructureKind.MAP -> "Map"
            else -> element.serialName.removeSuffix("?").substringAfterLast('.')
        }
        return if (element.isNullable) "$base?" else base
    }
}
