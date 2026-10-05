@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.localization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.localization.model.LocalizationPluralTranslation
import bosca.localization.model.LocalizationString
import bosca.localization.model.LocalizationStringMetadata
import bosca.localization.model.LocalizationTranslation
import bosca.localization.service.LocalizationService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/** Resolves field-level data on [LocalizationString]. */
@TypeController
class LocalizationStringController(
    private val service: LocalizationService
) : GraphQLController<LocalizationString> {

    @Field
    fun id(string: LocalizationString): UUID = string.id

    @Field
    fun projectId(string: LocalizationString): UUID = string.projectId

    @Field
    fun key(string: LocalizationString): String = string.key

    @Field
    fun context(string: LocalizationString): String? = string.context

    @Field
    fun metadataId(string: LocalizationString): UUID? = string.metadataId

    @Field
    fun placeholders(string: LocalizationString): JsonElement? = string.placeholders

    @Field
    fun maxLength(string: LocalizationString): Int? = string.maxLength

    @Field
    fun tags(string: LocalizationString): List<String> = string.tags

    @Field
    fun plural(string: LocalizationString): Boolean = string.plural

    @Field
    fun created(string: LocalizationString): OffsetDateTime? = string.created

    @Field
    fun modified(string: LocalizationString): OffsetDateTime? = string.modified

    @Field
    suspend fun translations(string: LocalizationString): List<LocalizationTranslation> =
        service.getTranslations(string.id)

    @Field
    suspend fun pluralTranslations(string: LocalizationString, languageTag: String): List<LocalizationPluralTranslation> =
        if (string.plural) service.getPluralTranslations(string.id, languageTag) else emptyList()

    @Field
    suspend fun contextMetadata(string: LocalizationString): List<LocalizationStringMetadata> =
        service.getStringMetadata(string.id)
}
