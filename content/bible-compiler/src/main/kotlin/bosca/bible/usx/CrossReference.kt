package bosca.bible.usx

import bosca.bible.processor.HtmlContext
import bosca.bible.CrossReferenceStyle
import bosca.bible.Reference

interface CrossReferenceItem : Item

class CrossReference(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) :
    ItemContainer<CrossReferenceItem>(reference, position),
    RootItem,
    ParagraphItem,
    BookIntroductionItem,
    ListItem,
    BookIntroductionEndTitleItem,
    SidebarItem {

    val style = CrossReferenceStyle.valueOf(attributes["STYLE"] ?: error("missing style"))
    val caller = attributes["CALLER"] ?: error("missing caller")

    override val htmlClass = style.toString()
    override val htmlAttributes = mapOf("data-caller" to caller) + super.htmlAttributes

    override fun toHtml(context: HtmlContext): String {
        if (!context.includeCrossReferences) return ""
        return super.toHtml(context)
    }

    override fun toString(context: StringContext): String {
        if (!context.includeCrossReferences) return ""
        return super.toString(context)
    }
}
