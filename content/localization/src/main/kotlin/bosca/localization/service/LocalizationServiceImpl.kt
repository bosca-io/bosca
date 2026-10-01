@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.localization.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.db.transaction
import bosca.graphql.Batch
import bosca.documents.html.DocumentHtmlConverter
import bosca.localization.export.ExportEngine
import bosca.localization.model.ExportFormat
import bosca.localization.model.ExportRequest
import bosca.localization.model.LocalizationPlaceholder
import bosca.localization.model.ExportResult
import bosca.localization.model.LocalizationProjectFormat
import bosca.localization.model.LocalizationProjectLanguage
import bosca.localization.model.LocalizationStringMetadata
import bosca.localization.model.PluralCategory
import bosca.localization.model.LocalizationDocumentTranslation
import bosca.localization.model.LocalizationDocumentTranslationInput
import bosca.localization.model.LocalizationAITranslationRequest
import bosca.localization.model.LocalizationAITranslationSource
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
import bosca.localization.model.TranslationHistoryTables
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationProgress
import bosca.localization.model.TranslationState
import bosca.localization.repository.LocalizationDocumentTranslationRepository
import bosca.localization.repository.LocalizationHistoryRepository
import bosca.localization.repository.LocalizationPluralTranslationRepository
import bosca.localization.repository.LocalizationProjectDocumentRepository
import bosca.localization.repository.LocalizationProjectFormatRepository
import bosca.localization.repository.LocalizationProjectLanguageRepository
import bosca.localization.repository.LocalizationStringMetadataRepository
import bosca.localization.repository.LocalizationProjectPermissionRepository
import bosca.localization.repository.LocalizationProjectRepository
import bosca.localization.repository.LocalizationStringRepository
import bosca.localization.repository.LocalizationTranslationRepository
import bosca.localization.placeholder.PlaceholderValidator
import bosca.localization.security.ProjectGroupNames
import bosca.localization.workflow.TranslationStateMachine
import org.slf4j.LoggerFactory
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

private val logger = LoggerFactory.getLogger(LocalizationServiceImpl::class.java)

/**
 * Primary implementation of [LocalizationService]. Responsibilities:
 *
 * - CRUD on projects, strings, translations, plural translations, project documents,
 *   and document translations
 * - Enforces [TranslationStateMachine] transitions and records every change in
 *   `localization.translation_history`
 * - Creates and tears down the three project-scoped groups
 *   (`translator-viewer`, `translator-contributor`, `translator-manager`) with
 *   default permission grants
 * - Caches per-project permission lists so GraphQL batch resolution avoids N+1 loads
 *
 * Auth-context-dependent fields (`createdBy`, `reviewedBy`) are taken as explicit
 * parameters so the service stays a pure data layer; GraphQL controllers extract
 * the principal from the authentication context and pass it through.
 */
@ServiceImplementation
class LocalizationServiceImpl(
    private val projectRepository: LocalizationProjectRepository,
    private val permissionRepository: LocalizationProjectPermissionRepository,
    private val stringRepository: LocalizationStringRepository,
    private val translationRepository: LocalizationTranslationRepository,
    private val pluralTranslationRepository: LocalizationPluralTranslationRepository,
    private val projectDocumentRepository: LocalizationProjectDocumentRepository,
    private val documentTranslationRepository: LocalizationDocumentTranslationRepository,
    private val historyRepository: LocalizationHistoryRepository,
    private val projectLanguageRepository: LocalizationProjectLanguageRepository,
    private val projectFormatRepository: LocalizationProjectFormatRepository,
    private val stringMetadataRepository: LocalizationStringMetadataRepository,
    private val securityService: SecurityService,
    private val localizationAIService: LocalizationAIService,
    private val exportEngine: ExportEngine,
    private val json: Json
) : LocalizationService {

    private val permissionCache = ServiceCache(
        "localization:project-permissions",
        UUIDKeySerializer,
        batchResolver = { keys, batch ->
            val all = permissionRepository.getByProjectIds(keys)
            val grouped = all.groupBy { it.projectId }
            keys.forEach { key ->
                batch.setData(key, grouped[key]?.map { it as EntityPermission } ?: emptyList())
            }
        }
    ) { projectId ->
        permissionRepository.getByProjectId(projectId).map { it as EntityPermission }
    }

    // ---- Projects -----------------------------------------------------------

    override suspend fun getProjects(): List<LocalizationProject> = projectRepository.getAll()

    override suspend fun getProjects(offset: Int, limit: Int): List<LocalizationProject> =
        projectRepository.getAll(offset, limit)

    override suspend fun getProject(id: UUID): LocalizationProject? = projectRepository.getById(id)

    override suspend fun addProject(input: LocalizationProjectInput): LocalizationProject = transaction {
        val project = projectRepository.add(
            LocalizationProject(
                name = input.name,
                description = input.description,
                sourceLanguage = input.sourceLanguage,
                attributes = input.attributes
            )
        )
        val viewerGroup = securityService.addGroup(Group(name = ProjectGroupNames.viewer(project.id), description = "Localization viewers for ${project.name}", type = GroupType.SYSTEM))
        val contributorGroup = securityService.addGroup(Group(name = ProjectGroupNames.contributor(project.id), description = "Localization contributors for ${project.name}", type = GroupType.SYSTEM))
        val managerGroup = securityService.addGroup(Group(name = ProjectGroupNames.manager(project.id), description = "Localization managers for ${project.name}", type = GroupType.SYSTEM))
        permissionRepository.addPermission(project.id, viewerGroup.id, PermissionAction.VIEW)
        permissionRepository.addPermission(project.id, contributorGroup.id, PermissionAction.EDIT)
        permissionRepository.addPermission(project.id, managerGroup.id, PermissionAction.MANAGE)
        permissionCache.remove(project.id)
        project
    }

    override suspend fun editProject(id: UUID, input: LocalizationProjectInput): LocalizationProject {
        val existing = projectRepository.getById(id) ?: throw NoSuchElementException("Localization project not found: $id")
        return projectRepository.update(
            existing.copy(
                name = input.name,
                description = input.description,
                sourceLanguage = input.sourceLanguage,
                attributes = input.attributes
            )
        ) ?: throw IllegalStateException("Localization project update failed: $id")
    }

    override suspend fun deleteProject(id: UUID) {
        transaction {
            projectRepository.deleteById(id)
            for (groupName in ProjectGroupNames.all(id)) {
                val group = securityService.getGroupByName(groupName, GroupType.SYSTEM)
                if (group != null) securityService.deleteGroup(group.id)
            }
            permissionCache.remove(id)
        }
    }

    // ---- Permissions (PermissionService contract) ---------------------------

    override suspend fun getPermissions(entity: LocalizationProject): List<EntityPermission> =
        permissionCache.get(entity.id) ?: emptyList()

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        permissionCache.addToBatch(batch)
    }

    override suspend fun addPermission(projectId: UUID, groupId: UUID, action: PermissionAction) {
        permissionCache.remove(projectId)
        transaction {
            permissionRepository.addPermission(projectId, groupId, action)
        }
    }

    override suspend fun deletePermission(projectId: UUID, groupId: UUID, action: PermissionAction) {
        permissionCache.remove(projectId)
        transaction {
            permissionRepository.deletePermission(projectId, groupId, action)
        }
    }

    // ---- Project languages ---------------------------------------------------

    override suspend fun getProjectLanguages(projectId: UUID): List<LocalizationProjectLanguage> =
        projectLanguageRepository.getByProjectId(projectId)

    override suspend fun addProjectLanguage(projectId: UUID, languageTag: String): LocalizationProjectLanguage {
        transaction { projectLanguageRepository.add(projectId, languageTag) }
        return LocalizationProjectLanguage(projectId = projectId, languageTag = languageTag)
    }

    override suspend fun removeProjectLanguage(projectId: UUID, languageTag: String) {
        transaction { projectLanguageRepository.delete(projectId, languageTag) }
    }

    // ---- Project formats ----------------------------------------------------

    override suspend fun getProjectFormats(projectId: UUID): List<LocalizationProjectFormat> =
        projectFormatRepository.getByProjectId(projectId)

    override suspend fun addProjectFormat(projectId: UUID, format: ExportFormat): LocalizationProjectFormat {
        transaction { projectFormatRepository.add(projectId, format.name) }
        return LocalizationProjectFormat(projectId = projectId, format = format.name)
    }

    override suspend fun removeProjectFormat(projectId: UUID, format: ExportFormat) {
        transaction { projectFormatRepository.delete(projectId, format.name) }
    }

    // ---- String metadata ----------------------------------------------------

    override suspend fun getStringMetadata(stringId: UUID): List<LocalizationStringMetadata> =
        stringMetadataRepository.getByStringId(stringId)

    override suspend fun addStringMetadata(stringId: UUID, metadataId: UUID): LocalizationStringMetadata {
        transaction { stringMetadataRepository.add(stringId, metadataId) }
        return LocalizationStringMetadata(stringId = stringId, metadataId = metadataId)
    }

    override suspend fun removeStringMetadata(stringId: UUID, metadataId: UUID) {
        transaction { stringMetadataRepository.delete(stringId, metadataId) }
    }

    // ---- Strings ------------------------------------------------------------

    override suspend fun getStrings(projectId: UUID, offset: Int, limit: Int): List<LocalizationString> =
        stringRepository.getByProjectId(projectId, offset, limit)

    override suspend fun getString(id: UUID): LocalizationString? = stringRepository.getById(id)

    override suspend fun getStringByKey(projectId: UUID, key: String): LocalizationString? =
        stringRepository.getByKey(projectId, key)

    override suspend fun addString(input: LocalizationStringInput): LocalizationString {
        val placeholdersJson = input.placeholders?.let { json.encodeToJsonElement(it) }
        return stringRepository.add(
            LocalizationString(
                projectId = input.projectId,
                key = input.key,
                context = input.context,
                metadataId = input.metadataId,
                placeholders = placeholdersJson,
                maxLength = input.maxLength,
                tags = input.tags,
                plural = input.plural
            )
        )
    }

    override suspend fun editString(id: UUID, input: LocalizationStringInput): LocalizationString {
        val existing = stringRepository.getById(id) ?: throw NoSuchElementException("Localization string not found: $id")
        require(input.projectId == existing.projectId) {
            "Cannot move string to a different project via edit"
        }
        val placeholdersJson = input.placeholders?.let { json.encodeToJsonElement(it) } ?: existing.placeholders
        return stringRepository.update(
            existing.copy(
                key = input.key,
                context = input.context,
                metadataId = input.metadataId,
                placeholders = placeholdersJson,
                maxLength = input.maxLength,
                tags = input.tags,
                plural = input.plural
            )
        ) ?: throw IllegalStateException("Localization string update failed: $id")
    }

    override suspend fun deleteString(id: UUID) = stringRepository.deleteById(id)

    // ---- Plain translations -------------------------------------------------

    override suspend fun getTranslations(stringId: UUID): List<LocalizationTranslation> =
        translationRepository.getByStringId(stringId)

    override suspend fun getTranslation(stringId: UUID, languageTag: String): LocalizationTranslation? =
        translationRepository.getByStringIdAndLanguage(stringId, languageTag)

    override suspend fun getTranslationById(id: UUID): LocalizationTranslation? =
        translationRepository.getById(id)

    override suspend fun getTranslationsByState(projectId: UUID, state: TranslationState): List<LocalizationTranslation> =
        translationRepository.getByProjectAndState(projectId, state)

    override suspend fun getTranslationsByLanguageAndState(projectId: UUID, languageTag: String, state: TranslationState): List<LocalizationTranslation> =
        translationRepository.getByProjectLanguageAndState(projectId, languageTag, state)

    override suspend fun getTranslationsByOrigin(projectId: UUID, origin: TranslationOrigin): List<LocalizationTranslation> =
        translationRepository.getByProjectAndOrigin(projectId, origin)

    override suspend fun getTranslationsByLanguageAndOrigin(projectId: UUID, languageTag: String, origin: TranslationOrigin): List<LocalizationTranslation> =
        translationRepository.getByProjectLanguageAndOrigin(projectId, languageTag, origin)

    override suspend fun setTranslation(input: LocalizationTranslationInput, createdBy: UUID?): LocalizationTranslation {
        return transaction {
            validatePlaceholders(input.stringId, input.text)
            val existing = translationRepository.getByStringIdAndLanguage(input.stringId, input.languageTag)
            val state = if (existing != null && existing.text != input.text) {
                TranslationStateMachine.stateAfterTextEdit(existing.state, input.origin)
            } else {
                existing?.state ?: TranslationStateMachine.initialState(input.origin)
            }
            val result = translationRepository.upsert(
                stringId = input.stringId,
                languageTag = input.languageTag,
                text = input.text,
                state = state,
                origin = input.origin,
                originDetail = input.originDetail,
                createdBy = createdBy ?: existing?.createdBy
            )
            if (existing == null || existing.text != input.text || existing.state != state) {
                historyRepository.insert(
                    translationId = result.id,
                    tableName = TranslationHistoryTables.TRANSLATIONS,
                    fromState = existing?.state,
                    toState = state,
                    changedBy = createdBy,
                    origin = input.origin,
                    originDetail = input.originDetail,
                    previousText = existing?.text,
                    newText = input.text
                )
            }
            result
        }
    }

    override suspend fun generateAITranslations(
        projectId: UUID,
        stringIds: List<UUID>,
        targetLanguageTags: List<String>,
        createdBy: UUID?,
        originDetail: String?,
    ): List<LocalizationTranslation> {
        val project = projectRepository.getById(projectId)
            ?: throw NoSuchElementException("Localization project not found: $projectId")
        val distinctStringIds = stringIds.distinct()
        require(distinctStringIds.isNotEmpty()) { "AI translation requires at least one localization string" }
        require(targetLanguageTags.none { it.isBlank() }) { "AI translation target languages must not be blank" }
        val targets = targetLanguageTags.distinct()
        require(targets.isNotEmpty()) { "AI translation requires at least one target language" }
        val configuredTargets = projectLanguageRepository.getByProjectId(projectId).map { it.languageTag }.toSet()
        targets.forEach { languageTag ->
            require(languageTag != project.sourceLanguage) {
                "AI translation target '$languageTag' is the source language for project $projectId"
            }
            require(languageTag in configuredTargets) {
                "AI translation target '$languageTag' is not configured for localization project $projectId"
            }
        }

        val sources = distinctStringIds.map { stringId ->
            val string = stringRepository.getById(stringId)
                ?: throw NoSuchElementException("Localization string not found: $stringId")
            require(string.projectId == projectId) {
                "Localization string $stringId does not belong to project $projectId"
            }
            require(!string.plural) { "AI translation for plural string $stringId is not supported by this operation" }
            val source = translationRepository.getByStringIdAndLanguage(stringId, project.sourceLanguage)
                ?: error("Localization string '${string.key}' is missing source translation '${project.sourceLanguage}'")
            require(source.text.isNotBlank()) {
                "Localization string '${string.key}' has a blank source translation '${project.sourceLanguage}'"
            }
            LocalizationAITranslationSource(
                stringId = string.id,
                key = string.key,
                context = string.context,
                text = source.text,
                placeholders = string.placeholders,
                maxLength = string.maxLength,
            )
        }
        val expected = targets.flatMap { languageTag ->
            sources.map { source -> source.stringId to languageTag }
        }
        val expectedSet = expected.toSet()
        val generated = localizationAIService.translate(
            LocalizationAITranslationRequest(project.sourceLanguage, targets, sources),
        )
        val byKey = generated.associateBy { it.stringId to it.languageTag }
        require(byKey.size == generated.size) { "AI translation provider returned duplicate string/language results" }
        require(byKey.keys == expectedSet) {
            val missing = expectedSet - byKey.keys
            val unexpected = byKey.keys - expectedSet
            "AI translation provider returned an incomplete result set; missing=$missing unexpected=$unexpected"
        }

        return expected.map { (stringId, languageTag) ->
            val result = byKey.getValue(stringId to languageTag)
            require(result.text.isNotBlank()) {
                "AI translation provider returned blank text for string $stringId language '$languageTag'"
            }
            setTranslation(
                LocalizationTranslationInput(
                    stringId = stringId,
                    languageTag = languageTag,
                    text = result.text,
                    origin = TranslationOrigin.AI,
                    originDetail = originDetail,
                ),
                createdBy,
            )
        }
    }

    override suspend fun transitionTranslation(id: UUID, toState: TranslationState, reviewedBy: UUID?): LocalizationTranslation = transaction {
        val existing = translationRepository.getById(id) ?: throw NoSuchElementException("Translation not found: $id")
        TranslationStateMachine.requireTransition(existing.state, toState)
        val updated = if (reviewedBy != null) {
            translationRepository.transitionStateWithReviewer(id, toState, reviewedBy)
        } else {
            translationRepository.transitionStateWithoutReviewer(id, toState)
        } ?: throw IllegalStateException("Translation transition failed: $id")
        historyRepository.insert(
            translationId = id,
            tableName = TranslationHistoryTables.TRANSLATIONS,
            fromState = existing.state,
            toState = toState,
            changedBy = reviewedBy,
            origin = existing.origin,
            originDetail = existing.originDetail,
            previousText = existing.text,
            newText = existing.text
        )
        updated
    }

    override suspend fun deleteTranslation(stringId: UUID, languageTag: String) =
        translationRepository.deleteByStringAndLanguage(stringId, languageTag)

    // ---- Plural translations ------------------------------------------------

    override suspend fun getPluralTranslations(stringId: UUID, languageTag: String): List<LocalizationPluralTranslation> =
        pluralTranslationRepository.getByStringAndLanguage(stringId, languageTag)

    override suspend fun getPluralTranslationById(id: UUID): LocalizationPluralTranslation? =
        pluralTranslationRepository.getById(id)

    override suspend fun setPluralTranslation(input: LocalizationPluralTranslationInput, createdBy: UUID?): LocalizationPluralTranslation {
        return transaction {
            validatePlaceholders(input.stringId, input.text, pluralForm = true)
            val existing = pluralTranslationRepository.getByStringAndLanguage(input.stringId, input.languageTag)
                .firstOrNull { it.pluralCategory == input.pluralCategory.cldrValue() }
            val state = if (existing != null && existing.text != input.text) {
                TranslationStateMachine.stateAfterTextEdit(existing.state, input.origin)
            } else {
                existing?.state ?: TranslationStateMachine.initialState(input.origin)
            }
            val result = pluralTranslationRepository.upsert(
                stringId = input.stringId,
                languageTag = input.languageTag,
                pluralCategory = input.pluralCategory.cldrValue(),
                text = input.text,
                state = state,
                origin = input.origin,
                originDetail = input.originDetail,
                createdBy = createdBy ?: existing?.createdBy
            )
            if (existing == null || existing.text != input.text) {
                historyRepository.insert(
                    translationId = result.id,
                    tableName = TranslationHistoryTables.PLURAL_TRANSLATIONS,
                    fromState = existing?.state,
                    toState = state,
                    changedBy = createdBy,
                    origin = input.origin,
                    originDetail = input.originDetail,
                    previousText = existing?.text,
                    newText = input.text
                )
            }
            result
        }
    }

    override suspend fun transitionPluralTranslation(id: UUID, toState: TranslationState, reviewedBy: UUID?): LocalizationPluralTranslation = transaction {
        val existing = pluralTranslationRepository.getById(id) ?: throw NoSuchElementException("Plural translation not found: $id")
        TranslationStateMachine.requireTransition(existing.state, toState)
        val updated = if (reviewedBy != null) {
            pluralTranslationRepository.transitionStateWithReviewer(id, toState, reviewedBy)
        } else {
            pluralTranslationRepository.transitionStateWithoutReviewer(id, toState)
        } ?: throw IllegalStateException("Plural translation transition failed: $id")
        historyRepository.insert(
            translationId = id,
            tableName = TranslationHistoryTables.PLURAL_TRANSLATIONS,
            fromState = existing.state,
            toState = toState,
            changedBy = reviewedBy,
            origin = existing.origin,
            originDetail = existing.originDetail,
            previousText = existing.text,
            newText = existing.text
        )
        updated
    }

    override suspend fun deletePluralTranslations(stringId: UUID, languageTag: String) =
        pluralTranslationRepository.deleteByStringAndLanguage(stringId, languageTag)

    override suspend fun bulkTransition(
        projectId: UUID,
        languageTag: String,
        fromState: TranslationState,
        toState: TranslationState,
        reviewedBy: UUID?
    ): Int = transaction {
        TranslationStateMachine.requireTransition(fromState, toState)
        val locked = translationRepository.lockForBulkTransition(projectId, languageTag, fromState)
        if (locked.isEmpty()) return@transaction 0
        for (translation in locked) {
            if (reviewedBy != null) {
                translationRepository.transitionStateWithReviewer(translation.id, toState, reviewedBy)
            } else {
                translationRepository.transitionStateWithoutReviewer(translation.id, toState)
            }
            historyRepository.insert(
                translationId = translation.id,
                tableName = TranslationHistoryTables.TRANSLATIONS,
                fromState = fromState,
                toState = toState,
                changedBy = reviewedBy,
                origin = translation.origin,
                originDetail = translation.originDetail,
                previousText = translation.text,
                newText = translation.text
            )
        }
        locked.size
    }

    // ---- Project documents --------------------------------------------------

    override suspend fun getProjectDocuments(projectId: UUID): List<LocalizationProjectDocument> =
        projectDocumentRepository.getByProjectId(projectId)

    override suspend fun addProjectDocument(input: LocalizationProjectDocumentInput): LocalizationProjectDocument =
        projectDocumentRepository.upsert(input.projectId, input.metadataId, input.attributes)

    override suspend fun removeProjectDocument(projectId: UUID, metadataId: UUID) =
        projectDocumentRepository.deleteByProjectAndMetadata(projectId, metadataId)

    // ---- Document translations ----------------------------------------------

    override suspend fun getDocumentTranslations(documentId: UUID): List<LocalizationDocumentTranslation> =
        documentTranslationRepository.getByDocumentId(documentId)

    override suspend fun getDocumentTranslation(documentId: UUID, languageTag: String): LocalizationDocumentTranslation? =
        documentTranslationRepository.getByDocumentAndLanguage(documentId, languageTag)

    override suspend fun getDocumentTranslationById(id: UUID): LocalizationDocumentTranslation? =
        documentTranslationRepository.getById(id)

    override suspend fun getProjectDocument(id: UUID): LocalizationProjectDocument? =
        projectDocumentRepository.getById(id)

    override suspend fun setDocumentTranslation(input: LocalizationDocumentTranslationInput, createdBy: UUID?): LocalizationDocumentTranslation = transaction {
        val contentSize = input.content.toString().length
        require(contentSize <= MAX_DOCUMENT_CONTENT_SIZE) {
            "Document content exceeds maximum size of $MAX_DOCUMENT_CONTENT_SIZE characters (got $contentSize)"
        }
        val existing = documentTranslationRepository.getByDocumentAndLanguage(input.documentId, input.languageTag)
        val state = if (existing != null && existing.content != input.content) {
            TranslationStateMachine.stateAfterTextEdit(existing.state, input.origin)
        } else {
            existing?.state ?: TranslationStateMachine.initialState(input.origin)
        }
        val result = documentTranslationRepository.upsert(
            documentId = input.documentId,
            languageTag = input.languageTag,
            content = input.content,
            state = state,
            origin = input.origin,
            originDetail = input.originDetail,
            createdBy = createdBy ?: existing?.createdBy
        )
        val snapshotChanged = existing == null || existing.content != input.content || existing.state != state
        if (snapshotChanged) {
            historyRepository.insert(
                translationId = result.id,
                tableName = TranslationHistoryTables.DOCUMENT_TRANSLATIONS,
                fromState = existing?.state,
                toState = state,
                changedBy = createdBy,
                origin = input.origin,
                originDetail = input.originDetail,
                previousText = existing?.content?.toString(),
                newText = input.content.toString()
            )
        }
        result
    }

    override suspend fun transitionDocumentTranslation(id: UUID, toState: TranslationState, reviewedBy: UUID?): LocalizationDocumentTranslation = transaction {
        val existing = documentTranslationRepository.getById(id) ?: throw NoSuchElementException("Document translation not found: $id")
        TranslationStateMachine.requireTransition(existing.state, toState)
        val updated = if (reviewedBy != null) {
            documentTranslationRepository.transitionStateWithReviewer(id, toState, reviewedBy)
        } else {
            documentTranslationRepository.transitionStateWithoutReviewer(id, toState)
        } ?: throw IllegalStateException("Document translation transition failed: $id")
        historyRepository.insert(
            translationId = id,
            tableName = TranslationHistoryTables.DOCUMENT_TRANSLATIONS,
            fromState = existing.state,
            toState = toState,
            changedBy = reviewedBy,
            origin = existing.origin,
            originDetail = existing.originDetail,
            previousText = existing.content.toString(),
            newText = existing.content.toString()
        )
        updated
    }

    override suspend fun exportDocumentAsHtml(documentId: UUID, languageTag: String): String {
        val translation = documentTranslationRepository.getByDocumentAndLanguage(documentId, languageTag)
            ?: throw NoSuchElementException("Document translation not found: $documentId / $languageTag")
        return DocumentHtmlConverter.toHtml(translation.content)
    }

    override suspend fun importDocumentFromHtml(
        documentId: UUID,
        languageTag: String,
        html: String,
        origin: TranslationOrigin,
        originDetail: String?,
        createdBy: UUID?
    ): LocalizationDocumentTranslation {
        val content = DocumentHtmlConverter.fromHtml(html)
        return setDocumentTranslation(
            LocalizationDocumentTranslationInput(
                documentId = documentId,
                languageTag = languageTag,
                content = content,
                origin = origin,
                originDetail = originDetail
            ),
            createdBy
        )
    }

    // ---- History ------------------------------------------------------------

    override suspend fun getTranslationHistory(translationId: UUID): List<TranslationHistory> =
        historyRepository.getByTranslationId(translationId)

    // ---- Progress -----------------------------------------------------------

    override suspend fun getTranslationProgress(projectId: UUID, languageTag: String): TranslationProgress {
        val total = stringRepository.countByProjectId(projectId)
        val counts = translationRepository.getProgressCounts(projectId, languageTag)
        val translated = counts?.translatedStrings ?: 0
        val approved = counts?.approvedStrings ?: 0
        val published = counts?.publishedStrings ?: 0
        val aiGenerated = counts?.aiGeneratedStrings ?: 0
        val human = counts?.humanTranslatedStrings ?: 0
        val percentage = if (total == 0) 0.0 else (translated.toDouble() / total.toDouble()) * 100.0
        return TranslationProgress(
            totalStrings = total,
            translatedStrings = translated,
            approvedStrings = approved,
            publishedStrings = published,
            aiGeneratedStrings = aiGenerated,
            humanTranslatedStrings = human,
            percentage = percentage
        )
    }

    // ---- Export -------------------------------------------------------------

    override suspend fun export(request: ExportRequest): ExportResult {
        val project = projectRepository.getById(request.projectId)
            ?: throw NoSuchElementException("Localization project not found: ${request.projectId}")
        val exporter = exportEngine.exporterFor(request.format)
        val states = request.statesFilter ?: listOf(TranslationState.PUBLISHED)
        val allStrings = collectAllStrings(request.projectId)
        val translations = translationRepository.getByProjectAndLanguage(request.projectId, request.languageTag)
            .filter { it.state in states }
            .associateBy { it.stringId }
        val plurals = pluralTranslationRepository.getByProjectAndLanguage(request.projectId, request.languageTag)
            .filter { it.state in states }
            .groupBy { it.stringId }
        val sourceTranslations = if (request.languageTag != project.sourceLanguage) {
            translationRepository.getByProjectAndLanguage(request.projectId, project.sourceLanguage)
                .associateBy { it.stringId }
        } else {
            emptyMap()
        }
        return exporter.export(request.languageTag, allStrings, translations, plurals, project.sourceLanguage, sourceTranslations)
    }

    private suspend fun validatePlaceholders(stringId: UUID, text: String, pluralForm: Boolean = false) {
        val string = stringRepository.getById(stringId) ?: return
        string.maxLength?.let { max ->
            require(text.length <= max) {
                "Translation text exceeds max length of $max characters (got ${text.length})"
            }
        }
        val placeholders = string.placeholders ?: return
        val declared: List<LocalizationPlaceholder> = try {
            json.decodeFromJsonElement(placeholders)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Corrupted placeholder data for string $stringId", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Corrupted placeholder data for string $stringId", e)
        }
        // A plural CATEGORY form may omit only the implicit count placeholder: exact-quantity
        // categories can carry the number in the words ("one item", an Arabic dual). Every other
        // declared placeholder still has to survive in every category.
        val required = if (pluralForm) declared.filterNot { it.name == "count" } else declared
        if (required.isEmpty()) return
        val result = PlaceholderValidator.validate(required, text)
        if (!result.isValid) {
            throw IllegalArgumentException(
                "Translation is missing declared placeholders: ${result.missing.joinToString()}"
            )
        }
    }

    private suspend fun collectAllStrings(projectId: UUID): List<LocalizationString> {
        val page = COLLECT_PAGE_SIZE
        val all = mutableListOf<LocalizationString>()
        var offset = 0
        while (true) {
            val batch = stringRepository.getByProjectId(projectId, offset, page)
            all += batch
            if (batch.size < page) break
            offset += page
        }
        return all
    }

    companion object {
        private const val COLLECT_PAGE_SIZE = 5000
        private const val MAX_DOCUMENT_CONTENT_SIZE = 5_000_000
    }
}
