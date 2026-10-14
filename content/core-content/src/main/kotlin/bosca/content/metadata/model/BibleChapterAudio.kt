package bosca.content.metadata.model

import bosca.bible.BibleAttribution
import bosca.bible.BibleAudioContent
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** One recording response retained on a chapter; its usage token is stored separately. */
@Serializable
data class BibleChapterAudio(
    @Contextual val id: UUID,
    @Contextual val sourceId: UUID,
    val sourceVersion: Long,
    val usfm: String,
    val content: BibleAudioContent,
    val attribution: BibleAttribution,
    @Contextual val created: OffsetDateTime,
    @Contextual val expires: OffsetDateTime,
    /**
     * When the recording was replaced or evicted. Its media remains playable by its exact
     * identifier for a short grace period before deletion; null while it is the current recording.
     */
    @Contextual val evicted: OffsetDateTime? = null,
) {
    override fun toString(): String = "BibleChapterAudio(id=$id, sourceId=$sourceId, usfm=$usfm)"
}
