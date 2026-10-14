package bosca.bible

import kotlinx.serialization.Serializable

/** Format, length, and timing exposed by a recording implementation. */
interface IBibleAudioContent {

    val contentType: String
    val contentLength: Long
    val timecodes: List<IBibleAudioTimecode>

    /** Captures recording metadata and its nested timecodes for serialization. */
    fun asSerializable(): BibleAudioContent = BibleAudioContent(
        contentType,
        contentLength,
        timecodes.map { it.asSerializable() },
    )
}

/** Format, length, and verse timing of downloaded chapter recording bytes. */
@Serializable
data class BibleAudioContent(
    override val contentType: String,
    override val contentLength: Long,
    override val timecodes: List<BibleAudioTimecode> = emptyList(),
) : IBibleAudioContent
