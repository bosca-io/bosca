package bosca.bible.usx

import bosca.bible.IntroCharStyle
import bosca.bible.Reference

interface IntroCharItem : Item

class IntroChar(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : ItemContainer<IntroCharItem>(reference, position),
    IntroCharItem,
    BookIntroductionItem {
    val style = IntroCharStyle.valueOf(attributes["STYLE"] ?: error("missing style"))
    // char.closed?

    override val htmlClass: String = style.toString()
}
