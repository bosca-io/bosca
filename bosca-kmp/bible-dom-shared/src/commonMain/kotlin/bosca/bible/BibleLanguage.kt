package bosca.bible

import kotlinx.serialization.Serializable

interface IBibleLanguage {

    val iso: String
    val name: String
    val nameLocal: String?
    val script: String
    val scriptCode: String
    val scriptDirection: String

    fun asSerializable() = BibleLanguage(
        iso,
        name,
        nameLocal ?: name,
        script,
        scriptCode,
        scriptDirection
    )
}

@Serializable
class BibleLanguage(
    override val iso: String,
    override val name: String,
    override val nameLocal: String,
    override val script: String,
    override val scriptCode: String,
    override val scriptDirection: String
) : IBibleLanguage