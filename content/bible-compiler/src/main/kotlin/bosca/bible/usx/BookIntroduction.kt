package bosca.bible.usx

import bosca.bible.BookIntroductionStyle
import bosca.bible.Reference

interface BookIntroductionItem : Item

class BookIntroduction(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : ItemContainer<BookIntroductionItem>(reference, position),
    RootItem {

    val style = BookIntroductionStyle.valueOf(attributes["STYLE"] ?: error("missing style"))

    override val htmlClass = "book-introduction $style"
}
