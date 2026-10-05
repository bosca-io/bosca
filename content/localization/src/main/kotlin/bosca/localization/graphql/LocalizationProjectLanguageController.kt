@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.localization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.localization.model.LocalizationProjectLanguage
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Resolves field-level data on [LocalizationProjectLanguage]. */
@TypeController
class LocalizationProjectLanguageController : GraphQLController<LocalizationProjectLanguage> {

    @Field
    fun projectId(language: LocalizationProjectLanguage): UUID = language.projectId

    @Field
    fun languageTag(language: LocalizationProjectLanguage): String = language.languageTag

    @Field
    fun created(language: LocalizationProjectLanguage): OffsetDateTime? = language.created
}
