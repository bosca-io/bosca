package bosca.bible.usx

class Reference(
    attributes: Attributes,
    reference: bosca.bible.Reference?,
    position: Position
) : ItemContainer<Text>(reference, position),
    CharItem,
    ParagraphItem,
    BookIntroductionItem,
    FootnoteCharItem,
    ListCharItem,
    ListItem {

    val loc = attributes["LOC"].toString()

    override val htmlClass: String = ""
}
