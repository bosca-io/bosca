package bosca.ai.kit.tools.content

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import bosca.ai.kit.tools.KitTool
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.service.MetadataService
import bosca.documents.Content
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * The content-management capability — the one place that turns a finished tiptap [Content]
 * into a `bosca/v-document` [bosca.content.metadata.model.Metadata] (via [MetadataService.add],
 * under the caller's identity). It takes the `Content` **object** directly, so the document
 * flows in without a serialize/parse round-trip; the content spoke invokes it.
 */
class CreateDocumentTool(
    private val metadataService: MetadataService,
) : KitTool<CreateDocumentTool.Input, CreateDocumentTool.Output>(
    Input.serializer(),
    Output.serializer(),
    DESCRIPTOR,
) {

    @Serializable
    data class Input(
        val title: String,
        val content: Content,
        val languageTag: String = "en",
    )

    @Serializable
    data class Output(
        @Contextual
        val metadataId: UUID,
        val version: Int,
        val contentType: String,
        val success: Boolean,
        val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        return try {
            val metadata = metadataService.add(
                null,
                null,
                MetadataInput(
                    name = input.title,
                    languageTag = input.languageTag,
                    contentType = CONTENT_TYPE,
                    document = DocumentInput(title = input.title, content = input.content),
                ),
            )
            Output(
                metadataId = metadata.id,
                version = metadata.version,
                contentType = metadata.contentType,
                success = true,
            )
        } catch (e: Exception) {
            Output(metadataId = UUID.NIL, version = 0, contentType = "", success = false, error = "Failed to create document: ${e.message}")
        }
    }

    private companion object {
        const val CONTENT_TYPE = "bosca/v-document"

        /**
         * Hand-written descriptor: the `content` arg is the recursive tiptap [Content] tree, which a
         * generated JSON schema would recurse on forever. The action calls this tool directly (no LLM
         * ever selects it), so an opaque `content` parameter is all the descriptor needs.
         */
        private val DESCRIPTOR = ToolDescriptor(
            name = "create_document",
            description = "Create a Bosca document (content type bosca/v-document) from a finished tiptap Content document. Returns the new metadata id.",
            requiredParameters = listOf(
                ToolParameterDescriptor("title", "The document title; also used as the metadata name", ToolParameterType.String),
                ToolParameterDescriptor("content", "The finished tiptap Content document to save", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("languageTag", "BCP-47 language tag for the document, e.g. 'en'", ToolParameterType.String),
            ),
        )
    }
}
