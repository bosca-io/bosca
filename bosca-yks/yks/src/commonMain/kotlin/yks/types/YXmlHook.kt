package yks.types

/**
 * Hook for custom XML element handling.
 * Matches yjs Y.XmlHook.
 */
class YXmlHook(val hookName: String) : YMap() {
    override val typeName: String = "XmlHook"
    override fun copy(): YType = YXmlHook(hookName)
}
