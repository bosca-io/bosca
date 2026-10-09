package bosca.bible

import kotlinx.serialization.Serializable

/** Copyright and citation information exposed by a Bible implementation. */
interface IBibleAttribution {

    val copyright: String
    val citation: String
    val informationUrl: String?

    /** Captures attribution in its serializable representation. */
    fun asSerializable(): BibleAttribution = BibleAttribution(copyright, citation, informationUrl)
}

/** Copyright and citation information displayed alongside Scripture text or recordings. */
@Serializable
data class BibleAttribution(
    override val copyright: String,
    override val citation: String = "",
    override val informationUrl: String? = null,
) : IBibleAttribution
