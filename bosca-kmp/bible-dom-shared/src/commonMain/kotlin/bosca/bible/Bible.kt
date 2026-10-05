package bosca.bible

import bosca.bible.style.IStyle
import kotlinx.serialization.Serializable

interface IBible {

    val metadata: IBibleMetadata
    val books: List<IBook>
    val styles: List<IStyle>

    operator fun get(reference: Reference): IBook?

    fun asSerializable(): Bible = Bible(
        metadata.asSerializable(),
        books.map { it.asSerializable() },
        styles
    )
}

@Serializable
class Bible(
    override val metadata: BibleMetadata,
    override val books: List<Book>,
    override val styles: List<IStyle>
) : IBible {

    private val booksByUsfm = books.associateBy { it.reference.bookUsfm }

    override fun get(reference: Reference) = booksByUsfm[reference.bookUsfm]

    override fun asSerializable(): Bible = this
}
