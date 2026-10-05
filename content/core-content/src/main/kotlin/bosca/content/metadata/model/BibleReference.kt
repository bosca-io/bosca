package bosca.content.metadata.model

import kotlinx.serialization.Serializable

@Serializable
data class BibleReference(
    val usfm: String,
    val human: String,
    val humanShort: String
)