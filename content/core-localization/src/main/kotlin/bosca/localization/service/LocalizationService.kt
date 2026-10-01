@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.service

import bosca.localization.model.ExportFormat
import bosca.localization.model.ExportRequest
import bosca.localization.model.ExportResult
import bosca.localization.model.LocalizationProjectFormat
import bosca.localization.model.LocalizationProjectLanguage
import bosca.localization.model.LocalizationStringMetadata
import bosca.localization.model.LocalizationDocumentTranslation
import bosca.localization.model.LocalizationDocumentTranslationInput
import bosca.localization.model.LocalizationPluralTranslation
import bosca.localization.model.LocalizationPluralTranslationInput
import bosca.localization.model.LocalizationProject
import bosca.localization.model.LocalizationProjectDocument
import bosca.localization.model.LocalizationProjectDocumentInput
import bosca.localization.model.LocalizationProjectInput
import bosca.localization.model.LocalizationString
import bosca.localization.model.LocalizationStringInput
import bosca.localization.model.LocalizationTranslation
import bosca.localization.model.LocalizationTranslationInput
import bosca.localization.model.TranslationHistory
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationProgress
import bosca.localization.model.TranslationState
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionService
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Top-level service for the localization subsystem: projects, strings, translations
 * (plain and plural), project documents, document translations, workflow state
 * transitions, audit history, translation progress, and exports.
 *
 * Extends [PermissionService] so per-project authorization flows through the same
 * [bosca.security.service.PermissionEvaluator] machinery used elsewhere in Bosca.
 */
interface LocalizationService : PermissionService<LocalizationProject, UUID> {

    // --- Projects ---

    /** Lists every project visible to the caller. */
    suspend fun getProjects(): List<LocalizationProject>

    /** Lists projects with offset-based pagination, ordered by name. */
    suspend fun getProjects(offset: Int, limit: Int): List<LocalizationProject>

    /** Looks up a single project by ID, or `null` when it does not exist. */
    suspend fun getProject(id: UUID): LocalizationProject?

    /**
     * Creates a new project and the three project-scoped groups
     * (`translator-viewer:{id}`, `translator-contributor:{id}`, `translator-manager:{id}`)
     * with the corresponding default permission grants.
     */
    suspend fun addProject(input: LocalizationProjectInput): LocalizationProject

    /** Updates an existing project's mutable fields. */
    suspend fun editProject(id: UUID, input: LocalizationProjectInput): LocalizationProject

    /**
     * Deletes a project and cascades to its strings, translations, documents,
     * document translations, permissions, and the three project-scoped security groups
     * (`translator-viewer`, `translator-contributor`, `translator-manager`).
     */
    suspend fun deleteProject(id: UUID)

    // --- Permissions ---

    /** Grants [action] on a project to a security group. */
    suspend fun addPermission(projectId: UUID, groupId: UUID, action: PermissionAction)

    /** Revokes a specific permission grant from a security group. */
    suspend fun deletePermission(projectId: UUID, groupId: UUID, action: PermissionAction)

    // --- Project languages ---

    /** Every target language declared for the project. */
    suspend fun getProjectLanguages(projectId: UUID): List<LocalizationProjectLanguage>

    /** Declares a target language for the project. Idempotent. */
    suspend fun addProjectLanguage(projectId: UUID, languageTag: String): LocalizationProjectLanguage

    /** Removes a target language declaration from the project. */
    suspend fun removeProjectLanguage(projectId: UUID, languageTag: String)

    // --- Project formats ---

    /** Every export format configured for the project. */
    suspend fun getProjectFormats(projectId: UUID): List<LocalizationProjectFormat>

    /** Adds an export format to the project. Idempotent. */
    suspend fun addProjectFormat(projectId: UUID, format: ExportFormat): LocalizationProjectFormat

    /** Removes an export format from the project. */
    suspend fun removeProjectFormat(projectId: UUID, format: ExportFormat)

    // --- String metadata ---

    /** Every metadata item attached to the string for translator context. */
    suspend fun getStringMetadata(stringId: UUID): List<LocalizationStringMetadata>

    /** Attaches a metadata item to the string. Idempotent. */
    suspend fun addStringMetadata(stringId: UUID, metadataId: UUID): LocalizationStringMetadata

    /** Detaches a metadata item from the string. */
    suspend fun removeStringMetadata(stringId: UUID, metadataId: UUID)

    // --- Strings ---

    /** Lists strings within a project, paginated. */
    suspend fun getStrings(projectId: UUID, offset: Int = 0, limit: Int = 100): List<LocalizationString>

    /** Looks up a string by ID. */
    suspend fun getString(id: UUID): LocalizationString?

    /** Looks up a string by its `(projectId, key)` unique pair. */
    suspend fun getStringByKey(projectId: UUID, key: String): LocalizationString?

    /** Creates a new string within the project identified by [LocalizationStringInput.projectId]. */
    suspend fun addString(input: LocalizationStringInput): LocalizationString

    /** Updates an existing string's metadata (key, context, placeholders, tags, plural flag). */
    suspend fun editString(id: UUID, input: LocalizationStringInput): LocalizationString

    /** Deletes a string and cascades to its translations and plural translations. */
    suspend fun deleteString(id: UUID)

    // --- Plain translations ---

    /** Every language variant of a single string. */
    suspend fun getTranslations(stringId: UUID): List<LocalizationTranslation>

    /** A specific `(stringId, languageTag)` translation, or `null` if none exists. */
    suspend fun getTranslation(stringId: UUID, languageTag: String): LocalizationTranslation?

    /** Looks up a translation by its primary key, or `null` when not found. */
    suspend fun getTranslationById(id: UUID): LocalizationTranslation?

    /** Every translation in a project currently in the given workflow [state]. */
    suspend fun getTranslationsByState(projectId: UUID, state: TranslationState): List<LocalizationTranslation>

    /** Every translation in a project for a specific language and workflow [state]. */
    suspend fun getTranslationsByLanguageAndState(projectId: UUID, languageTag: String, state: TranslationState): List<LocalizationTranslation>

    /** Every translation in a project attributed to the given [origin]. */
    suspend fun getTranslationsByOrigin(projectId: UUID, origin: TranslationOrigin): List<LocalizationTranslation>

    /** Every translation in a project for a specific language and [origin]. */
    suspend fun getTranslationsByLanguageAndOrigin(projectId: UUID, languageTag: String, origin: TranslationOrigin): List<LocalizationTranslation>

    /**
     * Upserts a translation. New translations enter state [TranslationState.AI_GENERATED]
     * when [LocalizationTranslationInput.origin] is [TranslationOrigin.AI], [TranslationState.DRAFT]
     * otherwise. Text changes are recorded in the audit history attributed to [createdBy].
     */
    suspend fun setTranslation(input: LocalizationTranslationInput, createdBy: UUID?): LocalizationTranslation

    /**
     * Generates and persists AI translations for the plain strings identified by [stringIds].
     * Source text comes from each string's translation in the project's source language. Every
     * target must be configured on the project. Results enter the ordinary AI-generated workflow
     * and audit history with [createdBy] attribution; callers never persist provider output directly.
     */
    suspend fun generateAITranslations(
        projectId: UUID,
        stringIds: List<UUID>,
        targetLanguageTags: List<String>,
        createdBy: UUID?,
        originDetail: String? = null,
    ): List<LocalizationTranslation>

    /**
     * Transitions a translation to [toState] if the transition is allowed from the current state;
     * throws otherwise. Writes an audit row recording the actor and origin.
     */
    suspend fun transitionTranslation(id: UUID, toState: TranslationState, reviewedBy: UUID?): LocalizationTranslation

    /** Deletes a translation. */
    suspend fun deleteTranslation(stringId: UUID, languageTag: String)

    // --- Plural translations ---

    /** Every plural category row for a specific `(stringId, languageTag)` pair. */
    suspend fun getPluralTranslations(stringId: UUID, languageTag: String): List<LocalizationPluralTranslation>

    /** Looks up a plural translation row by its primary key, or `null`. */
    suspend fun getPluralTranslationById(id: UUID): LocalizationPluralTranslation?

    /** Upserts a plural translation for a single CLDR category. */
    suspend fun setPluralTranslation(input: LocalizationPluralTranslationInput, createdBy: UUID?): LocalizationPluralTranslation

    /** Transitions a plural translation row; same rules as [transitionTranslation]. */
    suspend fun transitionPluralTranslation(id: UUID, toState: TranslationState, reviewedBy: UUID?): LocalizationPluralTranslation

    /** Removes every plural category for the given `(stringId, languageTag)` pair. */
    suspend fun deletePluralTranslations(stringId: UUID, languageTag: String)

    // --- Bulk state transitions ---

    /**
     * Transitions every translation in a project+language currently in [fromState] to [toState].
     * Returns the number of rows updated.
     */
    suspend fun bulkTransition(
        projectId: UUID,
        languageTag: String,
        fromState: TranslationState,
        toState: TranslationState,
        reviewedBy: UUID?
    ): Int

    // --- Project documents ---

    /** Every metadata item linked to the project for translation. */
    suspend fun getProjectDocuments(projectId: UUID): List<LocalizationProjectDocument>

    /** Links a metadata item to the project. */
    suspend fun addProjectDocument(input: LocalizationProjectDocumentInput): LocalizationProjectDocument

    /** Unlinks a metadata item from the project. */
    suspend fun removeProjectDocument(projectId: UUID, metadataId: UUID)

    // --- Document translations ---

    /** Every language variant of a single project document. */
    suspend fun getDocumentTranslations(documentId: UUID): List<LocalizationDocumentTranslation>

    /** A specific `(documentId, languageTag)` translation, or `null`. */
    suspend fun getDocumentTranslation(documentId: UUID, languageTag: String): LocalizationDocumentTranslation?

    /** Looks up a document translation by its primary key, or `null`. */
    suspend fun getDocumentTranslationById(id: UUID): LocalizationDocumentTranslation?

    /** Looks up a project document by its primary key, or `null`. */
    suspend fun getProjectDocument(id: UUID): LocalizationProjectDocument?

    /** Upserts a document translation; behaves analogously to [setTranslation]. */
    suspend fun setDocumentTranslation(input: LocalizationDocumentTranslationInput, createdBy: UUID?): LocalizationDocumentTranslation

    /** Transitions a document translation; same state machine as string translations. */
    suspend fun transitionDocumentTranslation(id: UUID, toState: TranslationState, reviewedBy: UUID?): LocalizationDocumentTranslation

    /**
     * Serializes the translated content for `(documentId, languageTag)` to HTML suitable
     * for round-tripping through external translation tools.
     */
    suspend fun exportDocumentAsHtml(documentId: UUID, languageTag: String): String

    /**
     * Parses translated HTML back into JSONB and upserts it as the document translation
     * for `(documentId, languageTag)`.
     */
    suspend fun importDocumentFromHtml(
        documentId: UUID,
        languageTag: String,
        html: String,
        origin: TranslationOrigin,
        originDetail: String?,
        createdBy: UUID?
    ): LocalizationDocumentTranslation

    // --- History ---

    /** Audit entries for a single translation, ordered oldest first. */
    suspend fun getTranslationHistory(translationId: UUID): List<TranslationHistory>

    // --- Export ---

    /** Renders translations of a project+language in the requested format. */
    suspend fun export(request: ExportRequest): ExportResult

    // --- Progress ---

    /** Coverage snapshot for a project+language. */
    suspend fun getTranslationProgress(projectId: UUID, languageTag: String): TranslationProgress
}
