package bosca.content.metadata.model

import bosca.bible.Reference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class FindBibleResult(
    val book: BibleBook,
    val chapter: BibleChapter?,
    val component: JsonElement?,
    val human: String,
    val humanShort: String,
    val reference: Reference
)