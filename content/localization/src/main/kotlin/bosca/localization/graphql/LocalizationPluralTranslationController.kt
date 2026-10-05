@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.localization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.localization.model.LocalizationPluralTranslation
import bosca.localization.model.PluralCategory
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Resolves field-level data on [LocalizationPluralTranslation]. */
@TypeController
class LocalizationPluralTranslationController : GraphQLController<LocalizationPluralTranslation> {

    @Field
    fun id(translation: LocalizationPluralTranslation): UUID = translation.id

    @Field
    fun stringId(translation: LocalizationPluralTranslation): UUID = translation.stringId

    @Field
    fun languageTag(translation: LocalizationPluralTranslation): String = translation.languageTag

    @Field
    // The SDL declares the enum; the model stores CLDR's lowercase varchar — return the typed
    // view or graphql-java rejects the value ("Unknown value 'one'") at serialization.
    fun pluralCategory(translation: LocalizationPluralTranslation): PluralCategory = translation.category

    @Field
    fun text(translation: LocalizationPluralTranslation): String = translation.text

    @Field
    fun state(translation: LocalizationPluralTranslation): TranslationState = translation.state

    @Field
    fun origin(translation: LocalizationPluralTranslation): TranslationOrigin = translation.origin

    @Field
    fun originDetail(translation: LocalizationPluralTranslation): String? = translation.originDetail

    @Field
    fun reviewedBy(translation: LocalizationPluralTranslation): UUID? = translation.reviewedBy

    @Field
    fun reviewedAt(translation: LocalizationPluralTranslation): OffsetDateTime? = translation.reviewedAt

    @Field
    fun createdBy(translation: LocalizationPluralTranslation): UUID? = translation.createdBy

    @Field
    fun created(translation: LocalizationPluralTranslation): OffsetDateTime? = translation.created

    @Field
    fun modified(translation: LocalizationPluralTranslation): OffsetDateTime? = translation.modified
}
