package bosca.bible

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** An available recording exposed by a Bible implementation. */
interface IBibleAudio {

    val id: Uuid
    val name: String
    val nameLocal: String
    val abbreviation: String
    val attribution: IBibleAttribution

    /** Captures the recording description and its attribution for serialization. */
    fun asSerializable(): BibleAudio = BibleAudio(
        id,
        name,
        nameLocal,
        abbreviation,
        attribution.asSerializable(),
    )
}

/** An available recording identified by [id]. Listing it does not download audio. */
@Serializable
data class BibleAudio(
    override val id: Uuid,
    override val name: String,
    override val nameLocal: String,
    override val abbreviation: String,
    override val attribution: BibleAttribution,
) : IBibleAudio
