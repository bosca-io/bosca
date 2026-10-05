package bosca.bible.usx

import bosca.bible.BookIdentificationCode
import bosca.bible.Reference

class BookIdentification(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : ItemContainer<Text>(reference, position),
    RootItem {

    val id: String = attributes["STYLE"] ?: ""
    val code: BookIdentificationCode =
        BookIdentificationCode.valueOf(attributes["CODE"] ?: error("No code provided for book identification."))

    override val htmlClass = "book-identification"
    override val htmlAttributes = mapOf(
        "data-id" to id,
        "data-code" to code.name,
    ) + super.htmlAttributes
}