package bosca.bible.usx

import bosca.bible.processor.HtmlContext
import bosca.bible.ListStyle
import bosca.bible.Reference

interface ListItem : Item

class List(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : ItemContainer<ListItem>(reference, position),
    RootItem,
    ChapterItem,
    SidebarItem {

    val style = ListStyle.valueOf(attributes["STYLE"] ?: error("missing style"))
    val vid = attributes["VID"]

    override val htmlClass = style.toString()

    override fun toHtml(context: HtmlContext) = context.render("li", this) {
        var childItems = ""
        for (item in it) {
            childItems += "<li>"
            childItems += item.toHtml(context)
            childItems += "</li>"
        }
        childItems
    }
}