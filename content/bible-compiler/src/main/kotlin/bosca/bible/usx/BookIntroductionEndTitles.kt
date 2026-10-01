package bosca.bible.usx

import bosca.bible.BookIntroductionEndTitleStyle
import bosca.bible.Reference

interface BookIntroductionEndTitleItem : Item

class BookIntroductionEndTitles(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : ItemContainer<BookIntroductionEndTitleItem>(reference, position),
    RootItem {

    val style = BookIntroductionEndTitleStyle.valueOf(attributes["STYLE"] ?: error("missing style"))

    override val htmlClass = style.toString()
}
