@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.localization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.localization.model.LocalizationTranslation
import bosca.localization.model.TranslationHistory
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState
import bosca.localization.service.LocalizationService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Resolves field-level data on [LocalizationTranslation]. */
@TypeController
class LocalizationTranslationController(
    private val service: LocalizationService
) : GraphQLController<LocalizationTranslation> {

    @Field
    fun id(translation: LocalizationTranslation): UUID = translation.id

    @Field
    fun stringId(translation: LocalizationTranslation): UUID = translation.stringId

    @Field
    fun languageTag(translation: LocalizationTranslation): String = translation.languageTag

    @Field
    fun text(translation: LocalizationTranslation): String = translation.text

    @Field
    fun state(translation: LocalizationTranslation): TranslationState = translation.state

    @Field
    fun origin(translation: LocalizationTranslation): TranslationOrigin = translation.origin

    @Field
    fun originDetail(translation: LocalizationTranslation): String? = translation.originDetail

    @Field
    fun reviewedBy(translation: LocalizationTranslation): UUID? = translation.reviewedBy

    @Field
    fun reviewedAt(translation: LocalizationTranslation): OffsetDateTime? = translation.reviewedAt

    @Field
    fun createdBy(translation: LocalizationTranslation): UUID? = translation.createdBy

    @Field
    fun created(translation: LocalizationTranslation): OffsetDateTime? = translation.created

    @Field
    fun modified(translation: LocalizationTranslation): OffsetDateTime? = translation.modified

    @Field
    suspend fun history(translation: LocalizationTranslation): List<TranslationHistory> =
        service.getTranslationHistory(translation.id)
}
