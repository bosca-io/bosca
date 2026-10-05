package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class BookInput(
    val abbreviation: String,
    val chapters: List<ChapterInput>,
    val nameLong: String,
    val nameShort: String,
    val reference: ReferenceInput
) {

    fun toBook(id: UUID, version: Int, variant: String, sort: Int) = BibleBook(
        metadataId = id,
        version = version,
        variant = variant,
        usfm = reference.usfm,
        nameShort = nameShort,
        nameLong = nameLong,
        abbreviation = abbreviation,
        sort = sort
    )
}