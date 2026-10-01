package bosca.workops.service

import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.documents.Content
import bosca.documents.MarkdownConverter
import bosca.git.service.CommitFileInput
import bosca.git.service.RepositoryWriteService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.audit.FieldChange
import bosca.workops.model.spec.Spec
import bosca.workops.repository.SpecHistoryRepository
import bosca.workops.repository.SpecRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

@ServiceImplementation
class SpecGitSyncServiceImpl(
    private val specRepository: SpecRepository,
    private val specHistoryRepository: SpecHistoryRepository,
    private val repositoryWriteService: RepositoryWriteService,
    private val metadataService: MetadataService,
    private val documentService: DocumentService,
    private val json: Json,
) : SpecGitSyncService {

    override suspend fun pushToGit(
        spec: Spec,
        content: String,
        actingPrincipalId: UUID,
        authorName: String,
        authorEmail: String,
    ): String? {
        val repositoryId = spec.gitRepositoryId ?: return null
        val gitPath = spec.gitPath ?: return null

        val pushContent = if (gitPath.endsWith(".md")) {
            val documentContent = documentService.getDocument(spec.metadataId, 1)?.content
            if (documentContent != null) {
                MarkdownConverter.toMarkdown(documentContent)
            } else {
                content
            }
        } else {
            content
        }

        val result = repositoryWriteService.commitFile(
            CommitFileInput(
                repositoryId = repositoryId,
                branch = "main",
                path = gitPath,
                content = pushContent,
                message = "Update spec ${spec.key}",
                authorName = authorName,
                authorEmail = authorEmail,
            )
        )

        writeHistory(spec.id, actingPrincipalId, "git_push", result.commitSha)
        return result.commitSha
    }

    override suspend fun pullFromGit(
        specId: UUID,
        commitSha: String,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Spec? {
        val spec = specRepository.getActiveById(specId) ?: return null
        val repositoryId = spec.gitRepositoryId ?: return null
        val gitPath = spec.gitPath ?: return null

        val fileContent = repositoryWriteService.readFile(repositoryId, commitSha, gitPath) ?: return null
        val metadata = metadataService.getById(spec.metadataId) ?: return null

        val tiptapContent = parseContent(gitPath, fileContent) ?: return null

        documentService.setDocument(
            metadata,
            DocumentInput(title = spec.key, content = tiptapContent),
        )

        writeHistory(spec.id, actingPrincipalId, "git_pull", commitSha)
        return spec
    }

    override suspend fun onPushEvent(
        repositoryId: UUID,
        ref: String,
        beforeSha: String,
        afterSha: String,
        changedFiles: Set<String>,
    ) {
        val specs = specRepository.listByGitRepository(repositoryId)
        for (spec in specs) {
            val gitPath = spec.gitPath ?: continue
            if (gitPath in changedFiles) {
                log.info("Spec {} git path {} changed in push to {}, triggering pull", spec.key, gitPath, repositoryId)
                pullFromGit(spec.id, afterSha, UUID.NIL, null)
            }
        }
    }

    private fun parseContent(gitPath: String, fileContent: String): Content? {
        return if (gitPath.endsWith(".md")) {
            MarkdownConverter.fromMarkdown(fileContent)
        } else {
            try {
                json.decodeFromString(Content.serializer(), fileContent)
            } catch (e: SerializationException) {
                log.warn("Failed to parse git content as Tiptap JSON from {}: {}", gitPath, e.message)
                null
            }
        }
    }

    private suspend fun writeHistory(specId: UUID, principalId: UUID, fieldKey: String, value: String) {
        specHistoryRepository.add(
            specId = specId,
            changedAt = OffsetDateTime.now(),
            changedByPrincipalId = principalId,
            changedByProfileId = null,
            changes = json.encodeToJsonElement(
                ListSerializer(FieldChange.serializer()),
                listOf(FieldChange(fieldKey = fieldKey, toValue = JsonPrimitive(value))),
            ),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(SpecGitSyncServiceImpl::class.java)
    }
}
