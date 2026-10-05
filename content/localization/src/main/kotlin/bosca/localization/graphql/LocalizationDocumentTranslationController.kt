@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.localization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.localization.model.LocalizationDocumentTranslation
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/** Resolves field-level data on [LocalizationDocumentTranslation]. */
@TypeController
class LocalizationDocumentTranslationController : GraphQLController<LocalizationDocumentTranslation> {

    @Field
    fun id(translation: LocalizationDocumentTranslation): UUID = translation.id

    @Field
    fun documentId(translation: LocalizationDocumentTranslation): UUID = translation.documentId

    @Field
    fun languageTag(translation: LocalizationDocumentTranslation): String = translation.languageTag

    @Field
    fun content(translation: LocalizationDocumentTranslation): JsonElement = translation.content

    @Field
    fun state(translation: LocalizationDocumentTranslation): TranslationState = translation.state

    @Field
    fun origin(translation: LocalizationDocumentTranslation): TranslationOrigin = translation.origin

    @Field
    fun originDetail(translation: LocalizationDocumentTranslation): String? = translation.originDetail

    @Field
    fun reviewedBy(translation: LocalizationDocumentTranslation): UUID? = translation.reviewedBy

    @Field
    fun reviewedAt(translation: LocalizationDocumentTranslation): OffsetDateTime? = translation.reviewedAt

    @Field
    fun createdBy(translation: LocalizationDocumentTranslation): UUID? = translation.createdBy

    @Field
    fun created(translation: LocalizationDocumentTranslation): OffsetDateTime? = translation.created

    @Field
    fun modified(translation: LocalizationDocumentTranslation): OffsetDateTime? = translation.modified
}
