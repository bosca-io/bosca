package bosca.bible

import kotlinx.serialization.Serializable

/** A chapter recording exposed by a Bible implementation. */
interface IBibleAudioChapter {

    val audio: IBibleAudio
    val usfm: String
    val url: String
    val content: IBibleAudioContent
    val attribution: IBibleAttribution

    /** Captures the chapter recording, including its nested descriptions and timing. */
    fun asSerializable(): BibleAudioChapter = BibleAudioChapter(
        audio.asSerializable(),
        usfm,
        url,
        content.asSerializable(),
        attribution.asSerializable(),
    )
}

/** A chapter recording ready for playback. [url] points to an authorized Bosca audio route. */
@Serializable
data class BibleAudioChapter(
    override val audio: BibleAudio,
    override val usfm: String,
    override val url: String,
    override val content: BibleAudioContent,
    override val attribution: BibleAttribution,
) : IBibleAudioChapter
