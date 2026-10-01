package bosca.bible.usx

import bosca.bible.ListCharStyle
import bosca.bible.Reference

interface ListCharItem : Item

class ListChar(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : ItemContainer<ListCharItem>(reference, position),
    ListCharItem,
    BookIntroductionItem,
    ListItem {

    val style = ListCharStyle.valueOf(attributes["STYLE"] ?: error("missing style"))
    // char.link?
    // char.closed?

    override val htmlClass = style.toString()
}
