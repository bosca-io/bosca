package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChapterInput(
    @Contextual
    val component: JsonElement,
    val reference: ReferenceInput
) {

    fun toChapter(id: UUID, version: Int, variant: String, bookUsfm: String, sort: Int) = BibleChapter(
        metadataId = id,
        version = version,
        variant = variant,
        bookUsfm = bookUsfm,
        usfm = reference.usfm,
        components = component,
        sort = sort
    )
}