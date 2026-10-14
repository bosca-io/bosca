package bosca.bible

import kotlinx.serialization.Serializable

/** A verse's playback interval exposed by a recording implementation. */
interface IBibleAudioTimecode {

    val usfm: String
    val start: Double
    val end: Double

    /** Captures the interval in its serializable representation. */
    fun asSerializable(): BibleAudioTimecode = BibleAudioTimecode(usfm, start, end)
}

/** A verse interval in seconds within a chapter recording, identified by [usfm]. */
@Serializable
data class BibleAudioTimecode(
    override val usfm: String,
    override val start: Double,
    override val end: Double,
) : IBibleAudioTimecode
