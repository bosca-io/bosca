package bosca.bible.usx

import bosca.bible.BookChapterLabelStyle
import bosca.bible.Reference

class BookChapterLabel(
    attributes: Attributes,
    reference: Reference? = null,
    position: Position
) : ItemContainer<Text>(reference, position),
    RootItem {

    val style = BookChapterLabelStyle.valueOf(attributes["STYLE"] ?: error("missing style"))

    override val htmlClass = style.toString()
}
