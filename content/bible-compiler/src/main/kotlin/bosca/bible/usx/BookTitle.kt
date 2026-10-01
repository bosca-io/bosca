package bosca.bible.usx

import bosca.bible.BookTitleStyle
import bosca.bible.Reference

interface BookTitleItem : Item

class BookTitle(
    reference: Reference? = null,
    position: Position,
    attributes: Attributes
) : ItemContainer<BookTitleItem>(reference, position),
    RootItem {

    val style: BookTitleStyle = BookTitleStyle.valueOf(attributes["STYLE"] ?: error("missing style"))

    override val htmlClass = style.toString()
}
