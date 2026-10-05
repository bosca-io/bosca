package bosca.bible.usx

import bosca.bible.Reference

class Figure(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : ItemContainer<Text>(reference, position),
    ParagraphItem,
    BookIntroductionItem,
    ListItem {

    val style = attributes["STYLE"] ?: error("missing style")
    val alt = attributes["ALT"]
    val file = attributes["FILE"] ?: error("missing file")
    val size = attributes["SIZE"]
    val loc = attributes["LOC"]
    val copy = attributes["COPY"]
    val ref = attributes["REF"]

    override val htmlClass = style
}
