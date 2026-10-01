package bosca.bible.usx

import bosca.bible.processor.HtmlContext
import bosca.bible.FootnoteCharStyle
import bosca.bible.Reference

interface FootnoteCharItem : Item

class FootnoteChar(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : ItemContainer<FootnoteCharItem>(reference, position),
    FootnoteItem,
    FootnoteCharItem,
    CrossReferenceCharItem,
    ListCharItem {

    val style = FootnoteCharStyle.valueOf(attributes["STYLE"] ?: error("missing style"))
    // char.link?,
    // char.closed?,

    override val htmlClass = style.toString()

    override fun toHtml(context: HtmlContext): String {
        if (!context.includeFootNotes) return ""
        return super.toHtml(context)
    }

    override fun toString(context: StringContext): String {
        if (!context.includeFootNotes) return ""
        return super.toString(context)
    }
}
