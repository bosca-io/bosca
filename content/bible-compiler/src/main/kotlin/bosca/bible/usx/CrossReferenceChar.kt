package bosca.bible.usx

import bosca.bible.processor.HtmlContext
import bosca.bible.CrossReferenceCharStyle
import bosca.bible.Reference

interface CrossReferenceCharItem : Item

class CrossReferenceChar(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) :
    ItemContainer<CrossReferenceCharItem>(reference, position),
    RootItem,
    CrossReferenceItem,
    CrossReferenceCharItem {

    val style = CrossReferenceCharStyle.valueOf(attributes["STYLE"] ?: error("missing style"))

    override val htmlClass = style.toString()

    override fun toHtml(context: HtmlContext): String {
        if (!context.includeCrossReferences) return ""
        return super.toHtml(context)
    }

    override fun toString(context: StringContext): String {
        if (!context.includeCrossReferences) return ""
        return super.toString(context)
    }
}
