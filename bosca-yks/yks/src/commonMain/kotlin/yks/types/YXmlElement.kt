package yks.types

import yks.utils.*

/**
 * Shared XML element type. Has a tag name, attributes (map), and children (list).
 * Matches yjs Y.XmlElement.
 */
class YXmlElement(val tag: String) : YXmlFragment() {
    override val typeName: String = "XmlElement:$tag"

    /** Set an attribute. */
    fun setAttribute(name: String, value: String) {
        val doc = doc ?: throw IllegalStateException("YXmlElement not integrated")
        transact(doc) { transaction ->
            typeMapSet(transaction, this, name, value)
        }
    }

    /** Get an attribute. */
    fun getAttribute(name: String): String? = typeMapGet(this, name) as? String

    /** Remove an attribute. */
    fun removeAttribute(name: String) {
        val doc = doc ?: throw IllegalStateException("YXmlElement not integrated")
        transact(doc) { transaction ->
            typeMapDelete(transaction, this, name)
        }
    }

    /** Get all attributes. */
    fun getAttributes(): Map<String, Any?> = typeMapGetAll(this)

    override fun toJSON(): Any? {
        return mapOf(
            "tag" to tag,
            "attributes" to getAttributes(),
            "children" to toArray().map { it.toJSON() }
        )
    }

    override fun copy(): YType = YXmlElement(tag)
}
