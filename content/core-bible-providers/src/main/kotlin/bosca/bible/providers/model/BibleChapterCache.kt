package bosca.bible.providers.model

import bosca.bible.BibleAttribution
import bosca.bible.Chapter
import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Stores a chapter and the metadata from that exact provider response. Reads do not change
 * [created] or [expires]. This server-side record must not be returned through reader APIs.
 * [provider] identifies the adapter for cache limits shared across configurations.
 */
@Serializable
data class BibleChapterCache(
    @Contextual @ColumnName("source_id") val sourceId: UUID,
    val provider: String,
    val usfm: String,
    @property:DbMapper(BibleChapterMapper::class) override val content: Chapter,
    @property:DbMapper(JsonbMapper::class) override val attribution: BibleAttribution?,
    @ColumnName("verse_count") override val verseCount: Int,
    @ColumnName("fums_token") override val fumsToken: String?,
    @Contextual val created: OffsetDateTime,
    @Contextual val expires: OffsetDateTime,
) : IBibleProviderResponse<Chapter> {
    override fun toString(): String =
        "BibleChapterCache(sourceId=$sourceId, usfm=$usfm, expires=$expires, fumsToken=[redacted])"
}
