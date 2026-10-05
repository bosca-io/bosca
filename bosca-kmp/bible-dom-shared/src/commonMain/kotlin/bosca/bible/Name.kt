package bosca.bible

import kotlinx.serialization.Serializable


interface IName {

    val id: String
    val short: String
    val long: String
    val abbreviation: String

    fun asSerializable(): Name = Name(id, long, short, abbreviation)
}

@Serializable
data class Name(
    override val id: String,
    override val long: String,
    override val short: String,
    override val abbreviation: String
) : IName