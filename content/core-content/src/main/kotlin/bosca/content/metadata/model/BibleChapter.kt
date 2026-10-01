package bosca.content.metadata.model

import bosca.bible.bibleJson
import bosca.bible.components.IComponent
import bosca.bible.components.filter
import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
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
) {

    fun getChapterComponents() = components?.let {
        bibleJson.decodeFromJsonElement<IComponent>(it)
    }
}

fun IComponent.toJson() = bibleJson.encodeToJsonElement(this)