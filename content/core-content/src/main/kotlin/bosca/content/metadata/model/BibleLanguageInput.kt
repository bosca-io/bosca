package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class BibleLanguageInput(
    val iso: String,
    val name: String,
    val nameLocal: String,
    val script: String,
    val scriptCode: String,
    val scriptDirection: String,
) {

    fun toLanguage(id: UUID, version: Int, variant: String, sort: Int) = BibleLanguage(
        metadataId = id,
        version = version,
        variant = variant,
        iso = iso,
        name = name,
        nameLocal = nameLocal,
        script = script,
        scriptCode = scriptCode,
        scriptDirection = scriptDirection,
        sort = sort
    )
}