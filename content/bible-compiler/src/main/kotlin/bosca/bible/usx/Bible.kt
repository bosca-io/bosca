package bosca.bible.usx

import bosca.bible.IBible
import bosca.bible.IBibleMetadata
import bosca.bible.IBook
import bosca.bible.Reference
import bosca.bible.style.IStyle
import kotlin.collections.List

class Bible(
    override val metadata: IBibleMetadata,
    override val books: List<IBook>,
    override val styles: List<IStyle>
) : IBible {

    private val booksByReference: Map<String, IBook> = books.associateBy { it.reference.usfm }

    override operator fun get(reference: Reference) = booksByReference[reference.bookUsfm]
}