package bosca.bible

import kotlinx.serialization.Serializable

interface IBibleMetadata {

    val identification: IBibleIdentification
    val publication: IBiblePublication
    val language: IBibleLanguage

    fun asSerializable() = BibleMetadata(
        identification = identification.asSerializable(),
        publication = publication.asSerializable(),
        language = language.asSerializable()
    )
}

@Serializable
class BibleMetadata(
    override val identification: BibleIdentification,
    override val publication: BiblePublication,
    override val language: BibleLanguage
) : IBibleMetadata