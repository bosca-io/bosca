package bosca.bible.usx

import bosca.bible.processor.HtmlContext
import bosca.bible.Reference

class VerseEnd(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : AbstractItem(reference, position),
    ParagraphItem,
    ListItem,
    RowItem,
    TableContentItem {

    val eid: String = attributes["EID"] ?: error("missing eid")

    override val verse: String? = null
    override val htmlClass: String = ""
    override val htmlAttributes: Map<String, String> = emptyMap()

    override fun toComponent(context: ComponentContext) = bosca.bible.components.VerseEnd()
    override fun toHtml(context: HtmlContext) = ""
    override fun toString(context: StringContext) = ""
}
