@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.localization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.localization.model.LocalizationProject
import bosca.localization.model.LocalizationProjectDocument
import bosca.localization.model.LocalizationProjectFormat
import bosca.localization.model.LocalizationProjectLanguage
import bosca.localization.model.LocalizationProjectPermission
import bosca.localization.model.LocalizationString
import bosca.localization.model.LocalizationSyncState
import bosca.localization.model.LocalizationTranslation
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationProgress
import bosca.localization.model.TranslationState
import bosca.localization.service.LocalizationService
import bosca.localization.service.LocalizationSyncService
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/** Resolves field-level data on [LocalizationProject]. */
@TypeController
class LocalizationProjectController(
    private val service: LocalizationService,
    private val syncService: LocalizationSyncService,
    private val securityService: SecurityService
) : GraphQLController<LocalizationProject> {

    @Field
    fun id(project: LocalizationProject): UUID = project.id

    @Field
    fun name(project: LocalizationProject): String = project.name

    @Field
    fun description(project: LocalizationProject): String? = project.description

    @Field
    fun sourceLanguage(project: LocalizationProject): String = project.sourceLanguage

    @Field
    fun attributes(project: LocalizationProject): JsonElement? = project.attributes

    @Field
    fun created(project: LocalizationProject): OffsetDateTime? = project.created

    @Field
    fun modified(project: LocalizationProject): OffsetDateTime? = project.modified

    @Field
    suspend fun languages(project: LocalizationProject): List<LocalizationProjectLanguage> =
        service.getProjectLanguages(project.id)

    @Field
    suspend fun formats(project: LocalizationProject): List<LocalizationProjectFormat> =
        service.getProjectFormats(project.id)

    @Field
    suspend fun strings(project: LocalizationProject, offset: Int?, limit: Int?): List<LocalizationString> =
        service.getStrings(project.id, offset ?: 0, limit ?: 100)

    @Field
    suspend fun documents(project: LocalizationProject): List<LocalizationProjectDocument> =
        service.getProjectDocuments(project.id)

    @Field
    suspend fun syncState(project: LocalizationProject): LocalizationSyncState? =
        syncService.getSyncState(project.id)

    @Field
    suspend fun permissions(project: LocalizationProject): List<LocalizationProjectPermission> {
        return service.getPermissions(project).map { it as LocalizationProjectPermission }
    }

    @Field
    suspend fun progress(project: LocalizationProject, languageTag: String): TranslationProgress =
        service.getTranslationProgress(project.id, languageTag)

    @Field
    suspend fun translationsByState(
        project: LocalizationProject,
        languageTag: String,
        state: TranslationState
    ): List<LocalizationTranslation> = service.getTranslationsByLanguageAndState(project.id, languageTag, state)

    @Field
    suspend fun translationsByOrigin(
        project: LocalizationProject,
        languageTag: String,
        origin: TranslationOrigin
    ): List<LocalizationTranslation> = service.getTranslationsByLanguageAndOrigin(project.id, languageTag, origin)
}
