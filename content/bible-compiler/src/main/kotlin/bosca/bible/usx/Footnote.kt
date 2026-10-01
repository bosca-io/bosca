package bosca.bible.usx

import bosca.bible.processor.HtmlContext
import bosca.bible.FootnoteStyle
import bosca.bible.Reference

interface FootnoteItem : Item

class Footnote(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : ItemContainer<FootnoteItem>(reference, position),
    RootItem,
    CharItem,
    ParagraphItem,
    BookIntroductionItem,
    BookIntroductionEndTitleItem,
    ListCharItem,
    ListItem,
    SidebarItem,
    TableContentItem {

    val style = FootnoteStyle.valueOf(attributes["STYLE"] ?: error("missing style"))
    val caller = attributes["CALLER"] ?: error("missing caller")
    val category = attributes["CATEGORY"]

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
