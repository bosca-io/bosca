package yks.types

/**
 * Shared XML text node type.
 * Matches yjs Y.XmlText.
 */
class YXmlText : YText() {
    override val typeName: String = "XmlText"
    override fun copy(): YType = YXmlText()
}
