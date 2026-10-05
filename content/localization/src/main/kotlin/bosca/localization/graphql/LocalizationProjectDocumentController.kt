@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.localization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.localization.model.LocalizationDocumentTranslation
import bosca.localization.model.LocalizationProjectDocument
import bosca.localization.service.LocalizationService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/** Resolves field-level data on [LocalizationProjectDocument]. */
@TypeController
class LocalizationProjectDocumentController(
    private val service: LocalizationService
) : GraphQLController<LocalizationProjectDocument> {

    @Field
    fun id(document: LocalizationProjectDocument): UUID = document.id

    @Field
    fun projectId(document: LocalizationProjectDocument): UUID = document.projectId

    @Field
    fun metadataId(document: LocalizationProjectDocument): UUID = document.metadataId

    @Field
    fun attributes(document: LocalizationProjectDocument): JsonElement? = document.attributes

    @Field
    fun created(document: LocalizationProjectDocument): OffsetDateTime? = document.created

    @Field
    fun modified(document: LocalizationProjectDocument): OffsetDateTime? = document.modified

    @Field
    suspend fun translations(document: LocalizationProjectDocument): List<LocalizationDocumentTranslation> =
        service.getDocumentTranslations(document.id)

    @Field
    suspend fun translation(document: LocalizationProjectDocument, languageTag: String): LocalizationDocumentTranslation? =
        service.getDocumentTranslation(document.id, languageTag)
}
