package bosca.bible.usx

import bosca.bible.CharStyle
import bosca.bible.Reference

interface CharItem : Item

class Char(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : ItemContainer<CharItem>(reference, position),
    CharItem,
    ParagraphItem,
    BookIntroductionItem,
    BookIntroductionEndTitleItem,
    FootnoteCharItem,
    CrossReferenceCharItem,
    ListItem,
    ListCharItem,
    IntroCharItem,
    TableContentItem {

    val style = CharStyle.valueOf(attributes["STYLE"] ?: error("missing style"))

    override val htmlClass = style.toString()
}
