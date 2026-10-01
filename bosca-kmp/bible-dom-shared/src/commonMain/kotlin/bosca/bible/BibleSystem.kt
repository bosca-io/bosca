package bosca.bible

import kotlinx.serialization.Serializable

interface IBibleSystem {
    val id: String

    fun asSerializable() = BibleSystem(id)
}

@Serializable
class BibleSystem(override val id: String) : IBibleSystem