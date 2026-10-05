package bosca.bible.usx

import bosca.bible.Reference
import bosca.bible.processor.HtmlContext

class ChapterEnd(
    attributes: Attributes,
    reference: Reference?,
    position: Position,
) : AbstractItem(reference, position),
    RootItem,
    ChapterItem {

    val eid: String = attributes["EID"] ?: error("missing eid")

    override val htmlClass: String = ""
    override val htmlAttributes: Map<String, String> = emptyMap()

    override fun toComponent(context: ComponentContext) = null
    override fun toHtml(context: HtmlContext): String = ""
    override fun toString(context: StringContext): String = ""
}
