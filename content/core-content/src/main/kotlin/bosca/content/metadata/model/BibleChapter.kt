package bosca.content.metadata.model

import bosca.bible.bibleJson
import bosca.bible.BibleAttribution
import bosca.bible.BibleChapterContent
import bosca.bible.Chapter
import bosca.bible.Reference
import bosca.bible.components.IComponent
import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class BibleChapter(
    @Contextual
    @ColumnName("metadata_id")
    val metadataId: UUID,
    val version: Int,
    val variant: String,
    @ColumnName("book_usfm")
    val bookUsfm: String,
    val usfm: String,
    @Contextual
    val components: JsonElement?,
    val sort: Int,
    @Contextual @ColumnName("source_id") val sourceId: UUID? = null,
    @property:DbMapper(JsonbMapper::class) val attribution: BibleAttribution? = null,
    @ColumnName("source_version") val sourceVersion: Long? = null,
    @Transient @ColumnName("verse_count") val verseCount: Int? = null,
    @Transient @Contextual val created: OffsetDateTime? = null,
    @Contextual val expires: OffsetDateTime? = null,
    @Transient @property:DbMapper(JsonbMapper::class)
    val audio: List<BibleChapterAudio>? = null,
) {

    fun getChapterComponents() = components?.let {
        if (it == JsonNull) return@let null
        bibleJson.decodeFromJsonElement<IComponent>(it)
    }

    /** Projects the stored chapter into the shared reader model without provider lookups. */
    fun getContent() = BibleChapterContent(Chapter(Reference(usfm), getChapterComponents()), attribution)

    override fun toString(): String = "BibleChapter(metadataId=$metadataId, version=$version, variant=$variant, usfm=$usfm)"
}

fun IComponent.toJson() = bibleJson.encodeToJsonElement(this)
