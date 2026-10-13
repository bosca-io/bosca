package bosca.content.metadata.model

import bosca.bible.BibleAttribution
import bosca.bible.BibleAudioContent
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** One cached recording response; its usage token remains private. */
@Serializable
data class BibleChapterAudio(
    @Contextual val id: UUID,
    @Contextual val sourceId: UUID,
    val sourceVersion: Long,
    val usfm: String,
    val content: BibleAudioContent,
    val attribution: BibleAttribution,
    val fumsToken: String?,
    @Contextual val created: OffsetDateTime,
    @Contextual val expires: OffsetDateTime,
) {
    override fun toString(): String = "BibleChapterAudio(id=$id, sourceId=$sourceId, usfm=$usfm)"
}
