package bosca.bible.usx

import bosca.bible.BookHeaderStyle
import bosca.bible.Reference

class BookHeader(
    attributes: Attributes,
    reference: Reference? = null,
    position: Position
) : ItemContainer<Text>(reference, position),
    RootItem {

    val style: BookHeaderStyle = BookHeaderStyle.valueOf(attributes["STYLE"] ?: error("missing style"))

    override val htmlClass get() = style.name
}
