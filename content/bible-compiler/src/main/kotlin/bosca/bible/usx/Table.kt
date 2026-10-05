package bosca.bible.usx

import bosca.bible.processor.HtmlContext
import bosca.bible.Reference
import bosca.bible.components.ComponentContainer
import bosca.bible.components.ContainerType
import bosca.bible.style.StyleReference

class Table(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : ItemContainer<Row>(reference, position),
    RootItem,
    ChapterItem,
    SidebarItem {

    val vid: String = attributes["VID"] ?: ""

    override val htmlClass = ""

    override fun toHtml(context: HtmlContext) = context.render("table", this)
    override fun toComponent(context: ComponentContext) =
        ComponentContainer(
            ContainerType.TABLE,
            items.mapNotNull { it.toComponent(context) },
            StyleReference(htmlClass)
        )
}

interface RowItem : Item

class Row(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : ItemContainer<RowItem>(reference, position) {

    val style = attributes["STYLE"] ?: error("missing style")

    override val htmlClass = style
    override fun toHtml(context: HtmlContext) = context.render("tr", this)
    override fun toComponent(context: ComponentContext) =
        ComponentContainer(
            ContainerType.ROW,
            items.mapNotNull { it.toComponent(context) },
            StyleReference(htmlClass)
        )
}

interface TableContentItem : Item

class TableContent(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : ItemContainer<TableContentItem>(reference, position), RowItem {
    val style: String = attributes["STYLE"] ?: error("missing style")
    val align: String = attributes["ALIGN"] ?: error("missing align")
    val colspan: String? = attributes["COLSPAN"]

    override val htmlClass = style

    override fun toHtml(context: HtmlContext) = context.render("td", this)
    override fun toComponent(context: ComponentContext) =
        ComponentContainer(
            ContainerType.COLUMN,
            items.mapNotNull { it.toComponent(context) },
            StyleReference(htmlClass)
        )
}

