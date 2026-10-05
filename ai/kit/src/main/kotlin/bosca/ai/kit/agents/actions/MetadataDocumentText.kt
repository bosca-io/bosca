package bosca.ai.kit.agents.actions

import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.DocumentService
import bosca.documents.MarkdownConverter

/**
 * Reads the plain text of [metadata]'s document — the input the describe / topics / reading-time
 * capabilities all analyze. Mirrors `MetadataAIController`'s contract: if the live [provided] document
 * is supplied (the editor's current content) use it, otherwise load the stored document via
 * [documentService]. The tiptap `Content` is rendered to markdown text — Kit can't reach the content
 * impl module's `DocumentToTextTransformation`, and markdown is faithful, readable text for a prompt.
 * Returns an empty string when there is no document content.
 */
internal suspend fun metadataDocumentText(
    metadata: Metadata,
    provided: DocumentInput?,
    documentService: DocumentService,
): String {
    val document = provided?.toDocument(metadata) ?: documentService.getDocument(metadata.id, metadata.version)
    return document?.content?.let { MarkdownConverter.toMarkdown(it) }.orEmpty()
}
