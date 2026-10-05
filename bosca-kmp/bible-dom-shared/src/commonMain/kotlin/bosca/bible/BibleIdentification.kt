package bosca.bible

import kotlinx.serialization.Serializable

interface IBibleIdentification {

    val system: IBibleSystem
    val name: String
    val nameLocal: String
    val description: String
    val abbreviation: String
    val abbreviationLocal: String

    fun asSerializable() = BibleIdentification(
        system.asSerializable(),
        name,
        nameLocal,
        description,
        abbreviation,
        abbreviationLocal
    )
}

@Serializable
class BibleIdentification(
    override val system: BibleSystem,
    override val name: String,
    override val nameLocal: String,
    override val description: String,
    override val abbreviation: String,
    override val abbreviationLocal: String
) : IBibleIdentification
