package bosca.bible

import kotlinx.serialization.Serializable

/** Uses reviewed by an administrator before provider content is made available. */
@Serializable
enum class BibleContentUse {
    DISPLAY,
    AI_CONTEXT,
    TEXT_TO_SPEECH,
    INDEXING,
    EXPORT,
    PLAYBACK,
}
