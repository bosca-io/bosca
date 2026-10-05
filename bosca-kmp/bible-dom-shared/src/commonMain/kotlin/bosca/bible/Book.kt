package bosca.bible

import kotlinx.serialization.Serializable

interface IBook {

    val name: IName
    val reference: Reference
    val chapters: List<IChapter>

    operator fun get(reference: Reference): IChapter?

    fun asSerializable() = Book(
        reference,
        name.asSerializable(),
        chapters.map { it.asSerializable() }
    )
}

@Serializable
data class Book(
    override val reference: Reference,
    override val name: Name,
    override val chapters: List<Chapter>
): IBook {

    private val chaptersByUsfm = chapters.associateBy { it.reference.chapterUsfm }

    override fun get(reference: Reference): IChapter? = chaptersByUsfm[reference.chapterUsfm]
}