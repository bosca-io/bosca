package bosca.bible

import kotlinx.serialization.Serializable

interface IBiblePublication {

    val id: String
    val name: String
    val nameLocal: String
    val description: String
    val descriptionLocal: String
    val abbreviation: String
    val abbreviationLocal: String

    fun asSerializable() = BiblePublication(
        id,
        name,
        nameLocal,
        description,
        descriptionLocal,
        abbreviation,
        abbreviationLocal
    )
}

@Serializable
class BiblePublication(
    override val id: String,
    override val name: String,
    override val nameLocal: String,
    override val description: String,
    override val descriptionLocal: String,
    override val abbreviation: String,
    override val abbreviationLocal: String
) : IBiblePublication
