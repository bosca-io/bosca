package bosca.content.metadata.service

import bosca.bible.IBibleAudioContent
import java.io.InputStream

/** Playback metadata and access to the same resolved recording, without exposing its storage record. */
interface BibleAudioPlayback {
    val content: IBibleAudioContent

    /** Opens this recording, optionally limited to an inclusive byte range. The caller closes the stream. */
    suspend fun openStream(range: LongRange? = null): InputStream
}
