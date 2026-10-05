@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.localization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.localization.model.LocalizationDocumentTranslation
import bosca.localization.model.LocalizationDocumentTranslationInput
import bosca.localization.model.LocalizationPluralTranslation
import bosca.localization.model.LocalizationPluralTranslationInput
import bosca.localization.model.ExportFormat
import bosca.localization.model.LocalizationProject
import bosca.localization.model.LocalizationProjectDocument
import bosca.localization.model.LocalizationProjectFormat
import bosca.localization.model.LocalizationProjectLanguage
import bosca.localization.model.LocalizationStringMetadata
import bosca.localization.model.LocalizationProjectDocumentInput
import bosca.localization.model.LocalizationProjectInput
import bosca.localization.model.LocalizationString
import bosca.localization.model.LocalizationStringInput
import bosca.localization.model.LocalizationSyncConfigInput
import bosca.localization.model.LocalizationSyncState
import bosca.localization.model.LocalizationTranslation
import bosca.localization.model.LocalizationTranslationInput
import bosca.localization.model.SyncResult
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState
import bosca.localization.security.LocalizationProjectPermissionEvaluator
import bosca.localization.service.LocalizationService
import bosca.localization.service.LocalizationSyncService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/**
 * Resolves every field under the `LocalizationMutation` GraphQL type.
 *
 * Each mutation verifies project-scoped authorization via
 * [LocalizationProjectPermissionEvaluator]:
 *
 * - VIEW: read-only operations (not mutations; see [LocalizationQueryController])
 * - EDIT: string management, translation set, transitions to IN_REVIEW
 * - MANAGE: approve/reject/publish transitions, sync, project settings/membership
 *
 * Project-creating operations (addProject) and operations without a project context
 * (bulk cross-project admin) fall back to the global `administrators` group check via
 * [GroupEvaluator.verifyHasAdminGroup].
 */
@TypeController
class LocalizationMutationController(
    private val service: LocalizationService,
    private val syncService: LocalizationSyncService,
    private val evaluator: LocalizationProjectPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<LocalizationMutation> {

    // ---- Projects -----------------------------------------------------------

    @Field
    suspend fun addProject(authentication: AuthenticationContext, input: LocalizationProjectInput): LocalizationProject {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.addProject(input)
    }

    @Field
    suspend fun editProject(authentication: AuthenticationContext, id: UUID, input: LocalizationProjectInput): LocalizationProject {
        val project = requireProject(id)
        evaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        return service.editProject(id, input)
    }

    @Field
    suspend fun deleteProject(authentication: AuthenticationContext, id: UUID): Boolean {
        val project = requireProject(id)
        evaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        service.deleteProject(id)
        return true
    }

    @Field
    suspend fun addProjectPermission(
        authentication: AuthenticationContext,
        projectId: UUID,
        groupId: UUID,
        action: PermissionAction
    ): Boolean {
        val project = requireProject(projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        service.addPermission(projectId, groupId, action)
        return true
    }

    @Field
    suspend fun removeProjectPermission(
        authentication: AuthenticationContext,
        projectId: UUID,
        groupId: UUID,
        action: PermissionAction
    ): Boolean {
        val project = requireProject(projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        service.deletePermission(projectId, groupId, action)
        return true
    }

    // ---- Project languages / formats ----------------------------------------

    @Field
    suspend fun addProjectLanguage(authentication: AuthenticationContext, projectId: UUID, languageTag: String): LocalizationProjectLanguage {
        val project = requireProject(projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        return service.addProjectLanguage(projectId, languageTag)
    }

    @Field
    suspend fun removeProjectLanguage(authentication: AuthenticationContext, projectId: UUID, languageTag: String): Boolean {
        val project = requireProject(projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        service.removeProjectLanguage(projectId, languageTag)
        return true
    }

    @Field
    suspend fun addProjectFormat(authentication: AuthenticationContext, projectId: UUID, format: ExportFormat): LocalizationProjectFormat {
        val project = requireProject(projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        return service.addProjectFormat(projectId, format)
    }

    @Field
    suspend fun removeProjectFormat(authentication: AuthenticationContext, projectId: UUID, format: ExportFormat): Boolean {
        val project = requireProject(projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        service.removeProjectFormat(projectId, format)
        return true
    }

    // ---- String metadata ----------------------------------------------------

    @Field
    suspend fun addStringMetadata(authentication: AuthenticationContext, stringId: UUID, metadataId: UUID): LocalizationStringMetadata {
        val string = service.getString(stringId) ?: throw NoSuchElementException("Localization string not found: $stringId")
        val project = requireProject(string.projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.EDIT)
        return service.addStringMetadata(stringId, metadataId)
    }

    @Field
    suspend fun removeStringMetadata(authentication: AuthenticationContext, stringId: UUID, metadataId: UUID): Boolean {
        val string = service.getString(stringId) ?: throw NoSuchElementException("Localization string not found: $stringId")
        val project = requireProject(string.projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.EDIT)
        service.removeStringMetadata(stringId, metadataId)
        return true
    }

    // ---- Strings ------------------------------------------------------------

    @Field
    suspend fun addString(authentication: AuthenticationContext, input: LocalizationStringInput): LocalizationString {
        val project = requireProject(input.projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.EDIT)
        return service.addString(input)
    }

    @Field
    suspend fun editString(authentication: AuthenticationContext, id: UUID, input: LocalizationStringInput): LocalizationString {
        val project = requireProject(input.projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.EDIT)
        return service.editString(id, input)
    }

    @Field
    suspend fun deleteString(authentication: AuthenticationContext, id: UUID): Boolean {
        val string = service.getString(id) ?: throw NoSuchElementException("Localization string not found: $id")
        val project = requireProject(string.projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.EDIT)
        service.deleteString(id)
        return true
    }

    // ---- Translations -------------------------------------------------------

    @Field
    suspend fun setTranslation(authentication: AuthenticationContext, input: LocalizationTranslationInput): LocalizationTranslation {
        val string = service.getString(input.stringId) ?: throw NoSuchElementException("Localization string not found: ${input.stringId}")
        val project = requireProject(string.projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.EDIT)
        return service.setTranslation(input, currentPrincipalId(authentication))
    }

    @Field
    suspend fun transitionTranslation(authentication: AuthenticationContext, id: UUID, toState: TranslationState): LocalizationTranslation {
        val translation = service.getTranslationById(id) ?: throw NoSuchElementException("Translation not found: $id")
        val string = service.getString(translation.stringId) ?: throw NoSuchElementException("Localization string not found for translation: $id")
        val project = requireProject(string.projectId)
        evaluator.verifyAllowed(authentication, project, requiredActionForTransition(translation.state, toState))
        return service.transitionTranslation(id, toState, currentPrincipalId(authentication))
    }

    @Field
    suspend fun deleteTranslation(authentication: AuthenticationContext, stringId: UUID, languageTag: String): Boolean {
        val string = service.getString(stringId) ?: throw NoSuchElementException("Localization string not found: $stringId")
        val project = requireProject(string.projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.EDIT)
        service.deleteTranslation(stringId, languageTag)
        return true
    }

    // ---- Plural translations ------------------------------------------------

    @Field
    suspend fun setPluralTranslation(authentication: AuthenticationContext, input: LocalizationPluralTranslationInput): LocalizationPluralTranslation {
        val string = service.getString(input.stringId) ?: throw NoSuchElementException("Localization string not found: ${input.stringId}")
        val project = requireProject(string.projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.EDIT)
        return service.setPluralTranslation(input, currentPrincipalId(authentication))
    }

    @Field
    suspend fun transitionPluralTranslation(authentication: AuthenticationContext, id: UUID, toState: TranslationState): LocalizationPluralTranslation {
        val plural = service.getPluralTranslationById(id) ?: throw NoSuchElementException("Plural translation not found: $id")
        val string = service.getString(plural.stringId) ?: throw NoSuchElementException("Localization string not found for plural translation: $id")
        val project = requireProject(string.projectId)
        evaluator.verifyAllowed(authentication, project, requiredActionForTransition(plural.state, toState))
        return service.transitionPluralTranslation(id, toState, currentPrincipalId(authentication))
    }

    @Field
    suspend fun deletePluralTranslations(authentication: AuthenticationContext, stringId: UUID, languageTag: String): Boolean {
        val string = service.getString(stringId) ?: throw NoSuchElementException("Localization string not found: $stringId")
        val project = requireProject(string.projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.EDIT)
        service.deletePluralTranslations(stringId, languageTag)
        return true
    }

    // ---- Bulk ---------------------------------------------------------------

    @Field
    suspend fun bulkTransition(
        authentication: AuthenticationContext,
        projectId: UUID,
        languageTag: String,
        fromState: TranslationState,
        toState: TranslationState
    ): Int {
        val project = requireProject(projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        return service.bulkTransition(projectId, languageTag, fromState, toState, currentPrincipalId(authentication))
    }

    // ---- Documents ----------------------------------------------------------

    @Field
    suspend fun addProjectDocument(authentication: AuthenticationContext, input: LocalizationProjectDocumentInput): LocalizationProjectDocument {
        val project = requireProject(input.projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        return service.addProjectDocument(input)
    }

    @Field
    suspend fun removeProjectDocument(authentication: AuthenticationContext, projectId: UUID, metadataId: UUID): Boolean {
        val project = requireProject(projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        service.removeProjectDocument(projectId, metadataId)
        return true
    }

    @Field
    suspend fun setDocumentTranslation(authentication: AuthenticationContext, input: LocalizationDocumentTranslationInput): LocalizationDocumentTranslation {
        val project = projectForDocument(input.documentId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.EDIT)
        return service.setDocumentTranslation(input, currentPrincipalId(authentication))
    }

    @Field
    suspend fun transitionDocumentTranslation(authentication: AuthenticationContext, id: UUID, toState: TranslationState): LocalizationDocumentTranslation {
        val docTranslation = service.getDocumentTranslationById(id) ?: throw NoSuchElementException("Document translation not found: $id")
        val project = projectForDocument(docTranslation.documentId)
        evaluator.verifyAllowed(authentication, project, requiredActionForTransition(docTranslation.state, toState))
        return service.transitionDocumentTranslation(id, toState, currentPrincipalId(authentication))
    }

    @Field
    suspend fun importDocumentFromHtml(
        authentication: AuthenticationContext,
        documentId: UUID,
        languageTag: String,
        html: String,
        origin: TranslationOrigin?,
        originDetail: String?
    ): LocalizationDocumentTranslation {
        val project = projectForDocument(documentId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.EDIT)
        return service.importDocumentFromHtml(
            documentId = documentId,
            languageTag = languageTag,
            html = html,
            origin = origin ?: TranslationOrigin.IMPORT,
            originDetail = originDetail,
            createdBy = currentPrincipalId(authentication)
        )
    }

    // ---- Sync ---------------------------------------------------------------

    @Field
    suspend fun configureSync(authentication: AuthenticationContext, input: LocalizationSyncConfigInput): LocalizationSyncState {
        val project = requireProject(input.projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        return syncService.configureSyncProvider(input)
    }

    @Field
    suspend fun syncToProvider(authentication: AuthenticationContext, projectId: UUID): SyncResult {
        val project = requireProject(projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        return syncService.syncToProvider(projectId)
    }

    @Field
    suspend fun syncFromProvider(authentication: AuthenticationContext, projectId: UUID): SyncResult {
        val project = requireProject(projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        return syncService.syncFromProvider(projectId)
    }

    @Field
    suspend fun removeSync(authentication: AuthenticationContext, projectId: UUID): Boolean {
        val project = requireProject(projectId)
        evaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        syncService.removeSyncProvider(projectId)
        return true
    }

    // ---- Helpers ------------------------------------------------------------

    private suspend fun requireProject(id: UUID): LocalizationProject =
        service.getProject(id) ?: throw NoSuchElementException("Localization project not found: $id")

    private suspend fun projectForDocument(documentId: UUID): LocalizationProject {
        val document = service.getProjectDocument(documentId) ?: throw NoSuchElementException("Localization project document not found: $documentId")
        return requireProject(document.projectId)
    }

    private fun currentPrincipalId(authentication: AuthenticationContext): UUID? =
        authentication.principal()?.id?.takeIf { it != UUID.NIL }

    /**
     * EDIT is sufficient for submitting to review or creating drafts from early states;
     * reverting from APPROVED/PUBLISHED back to DRAFT is a significant action that
     * requires MANAGE, as do all other transitions (approve, publish, archive).
     */
    internal fun requiredActionForTransition(fromState: TranslationState, toState: TranslationState): PermissionAction {
        val revertingFromReviewedState = toState == TranslationState.DRAFT &&
            fromState in setOf(TranslationState.APPROVED, TranslationState.PUBLISHED)
        if (revertingFromReviewedState) return PermissionAction.MANAGE
        return when (toState) {
            TranslationState.IN_REVIEW, TranslationState.DRAFT -> PermissionAction.EDIT
            else -> PermissionAction.MANAGE
        }
    }
}
