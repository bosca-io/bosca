package yks.structs.content

import yks.structs.Item
import yks.types.*
import yks.utils.Transaction
import yks.utils.UpdateDecoder
import yks.utils.UpdateEncoder

/**
 * Content wrapping a nested YType (YArray, YMap, YText, etc.).
 * Ref = 7. Length = 1. Countable.
 * Matches yjs ContentType.
 */
class ContentType(val type: YType) : AbstractContent {
    override fun getLength(): Int = 1
    override fun getContent(): List<Any?> = listOf(type)
    override fun isCountable(): Boolean = true
    override fun copy(): AbstractContent = ContentType(type.copy())

    override fun splice(offset: Int): AbstractContent {
        throw UnsupportedOperationException("ContentType cannot be split")
    }

    override fun mergeWith(right: AbstractContent): Boolean = false

    override fun integrate(transaction: Transaction, item: Item) {
        type.integrate(transaction.doc, item)
    }

    override fun delete(transaction: Transaction) {
        // When the containing item is deleted, delete all items in the nested type
        var current = type.start
        while (current != null) {
            if (!current.deleted) {
                current.delete(transaction)
            }
            current = current.right
        }
        for ((_, mapItem) in type.map) {
            if (!mapItem.deleted) {
                mapItem.delete(transaction)
            }
        }
    }

    override fun gc(transaction: Transaction) {
        // GC all items in the nested type
        var current = type.start
        while (current != null) {
            current.gc(transaction, true)
            current = current.right
        }
        for ((_, mapItem) in type.map) {
            mapItem.gc(transaction, true)
        }
    }

    override fun write(encoder: UpdateEncoder, offset: Int) {
        encoder.writeTypeRef(typeNameToRef(type.typeName))
        when (type) {
            is YXmlElement -> encoder.writeKey(type.tag)
            is YXmlHook -> encoder.writeKey(type.hookName)
            else -> {}
        }
    }

    override fun getRef(): Int = 7

    companion object {
        fun read(decoder: UpdateDecoder): ContentType {
            val typeRef = decoder.readTypeRef()
            return ContentType(createTypeFromRef(typeRef, decoder))
        }

        private fun typeNameToRef(name: String): Int = when {
            name == "Array" -> 0
            name == "Map" -> 1
            name == "Text" -> 2
            name.startsWith("XmlElement:") -> 3
            name == "XmlFragment" -> 4
            name == "XmlHook" -> 5
            name == "XmlText" -> 6
            else -> throw IllegalStateException("Unknown type name: $name")
        }

        internal fun createTypeFromRef(ref: Int, decoder: UpdateDecoder): YType = when (ref) {
            0 -> YArray()
            1 -> YMap()
            2 -> YText()
            3 -> YXmlElement(decoder.readKey())
            4 -> YXmlFragment()
            5 -> YXmlHook(decoder.readKey())
            6 -> YXmlText()
            else -> throw IllegalStateException("Unknown type ref: $ref")
        }
    }
}
