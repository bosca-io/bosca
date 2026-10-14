package bosca.bible.providers.model

import kotlinx.serialization.Serializable

/** The content supplied by a source. Audio recordings do not supply Scripture text. */
@Serializable
enum class BibleSourceKind {
    TEXT,
    AUDIO,
}
