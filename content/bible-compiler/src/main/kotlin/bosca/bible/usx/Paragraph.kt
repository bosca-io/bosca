package bosca.bible.usx

import bosca.bible.processor.HtmlContext
import bosca.bible.ParaStyle
import bosca.bible.Reference
import bosca.bible.components.ComponentContainer
import bosca.bible.components.ContainerType
import bosca.bible.style.StyleReference

interface ParagraphItem : Item

class Paragraph(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : ItemContainer<Item>(reference, position),
    RootItem,
    ChapterItem,
    SidebarItem {

    val style: ParaStyle = ParaStyle.valueOf(attributes["STYLE"] ?: error("missing style"))
    val vid: String? = attributes["VID"]

    override val htmlClass = style.toString()

    override fun toHtml(context: HtmlContext) = context.render("p", this)

    override fun toComponent(context: ComponentContext) =
        ComponentContainer(
            ContainerType.PARAGRAPH,
            items.mapNotNull { it.toComponent(context) },
            StyleReference(htmlClass)
        )
}
